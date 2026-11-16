"""Read-only inventory and schema verification for mozilla-v1; no inference."""

from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import unicodedata


LANGUAGES = "en vi es id it zh ko de pl pt ru th tr fr hi ja nl".split()
CATEGORIES = ("request", "negation", "price-time")
TINY = {"vi-en", "en-id", "id-en", "en-tr", "tr-en", "en-hi", "hi-en"}
JUDGMENT = "host-only AI-authored guidance; no human or native-speaker certification"
V1_PINS = {
    "cases.jsonl": "bb060ef99b129f2ebb791656ad1286c0a0de8232ebdd334f729b21d1ace8a135",
    "meaning-expectations.jsonl": "3afd1eeafcae083b22d440b102105788e4acb57a07f7438d6f8a009d0d666477",
    "warmup.jsonl": "5edb88309e80dc382729f093a73f5d9d7467e038ae5b6cba531e6fa25fae3c01",
}
ROOT = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse(text):
    return json.loads(text, object_pairs_hook=object_pairs)


def read_text(path):
    require(path.is_file() and not path.is_symlink(), f"Not a regular input: {path.name}")
    raw = path.read_bytes()
    require(raw.endswith(b"\n") and b"\r" not in raw and b"\x00" not in raw,
            f"Expected LF/final newline/no NUL: {path.name}")
    require(not raw.startswith(b"\xef\xbb\xbf"), f"Unexpected BOM: {path.name}")
    return raw, raw.decode("utf-8", errors="strict")


def rows(text):
    return [parse(line) for line in text.splitlines()]


def strings(values):
    return isinstance(values, list) and all(isinstance(v, str) and v.strip() for v in values)


def input_rows(items, warmup):
    ids, texts = set(), set()
    counts = Counter()
    fields = {"id", "source", "target", "text"} | ({"warmup"} if warmup else set())
    for item in items:
        require(isinstance(item, dict) and set(item) == fields, "Invalid source schema")
        require(all(isinstance(item[k], str) and item[k].strip()
                    for k in ("id", "source", "target", "text")), "Blank/non-string source field")
        pair = f"{item['source']}-{item['target']}"
        require(pair in DIRECTIONS, f"Unexpected direction: {pair}")
        require(item["id"] not in ids and item["text"] not in texts, "Duplicate source ID/text")
        require(item["text"] == item["text"].strip(), "Untrimmed source")
        require(not any(unicodedata.category(c).startswith("C") for c in item["text"]),
                "Control/format/surrogate in source")
        require(len(item["text"].encode("utf-16-le")) // 2 <= 200, "Source is not a short turn")
        if warmup:
            require(item["id"] == f"moz1-warmup-{pair}" and item["warmup"] is True,
                    "Invalid warm-up identity")
        else:
            require(item["id"] in {f"moz1-{pair}-{category}" for category in CATEGORIES},
                    "Invalid measured identity/category")
        ids.add(item["id"])
        texts.add(item["text"])
        counts[pair] += 1
    return ids, texts, counts


DIRECTIONS = {f"en-{language}" for language in LANGUAGES if language != "en"}
DIRECTIONS |= {f"{language}-en" for language in LANGUAGES if language != "en"}


def main():
    manifest_raw, manifest_text = read_text(ROOT / "manifest.json")
    manifest = parse(manifest_text)
    require(manifest["schemaVersion"] == 1 and manifest["id"] == "translation-mozilla-tourist-v1"
            and manifest["version"] == 1, "Unexpected fixture identity/version")
    require(manifest["encoding"] == "UTF-8" and manifest["lineEndings"] == "LF",
            "Unexpected encoding declaration")
    require(manifest["counts"] == {"measured": 96, "hostExpectations": 96, "warmup": 32,
                                  "directions": 32, "sourceLanguages": 17,
                                  "baseMemoryDirections": 25, "tinyDirections": 7}, "Wrong counts")
    require(set(manifest["sourceLanguages"]) == set(LANGUAGES)
            and len(manifest["sourceLanguages"]) == 17, "Wrong language catalog")
    require(manifest["languageNotes"]["zh"] == "Simplified Chinese; upstream zh-Hans",
            "Wrong zh mapping")
    require(manifest["categories"] == list(CATEGORIES), "Wrong category declaration")
    directions = manifest["directions"]
    require(set(directions) == DIRECTIONS, "Wrong manifest direction coverage")
    for pair, details in directions.items():
        require(details == {"measured": 3, "warmup": 1,
                            "architecture": "tiny" if pair in TINY else "base-memory"},
                f"Wrong route selection/counts: {pair}")
    expected_files = {"cases.jsonl", "meaning-expectations.jsonl", "warmup.jsonl",
                      "README.md", "validate.py"}
    inventory = manifest["files"]
    require(len(inventory) == 5 and {f["path"] for f in inventory} == expected_files,
            "Wrong inventory")
    require({p.name for p in ROOT.iterdir()} == expected_files | {"manifest.json"},
            "Unexpected corpus file/directory")
    texts = {}
    for entry in inventory:
        raw, text = read_text(ROOT / entry["path"])
        require(len(raw) == entry["bytes"], f"Byte mismatch: {entry['path']}")
        require(hashlib.sha256(raw).hexdigest() == entry["sha256"],
                f"Hash mismatch: {entry['path']}")
        require(len(text.splitlines()) == entry["rows"], f"Row mismatch: {entry['path']}")
        texts[entry["path"]] = text
    measured = rows(texts["cases.jsonl"])
    warmups = rows(texts["warmup.jsonl"])
    expectations = rows(texts["meaning-expectations.jsonl"])
    require(len(measured) == len(expectations) == 96 and len(warmups) == 32, "Wrong row count")
    ids, source_texts, counts = input_rows(measured, False)
    warm_ids, warm_texts, warm_counts = input_rows(warmups, True)
    require(counts == Counter({pair: 3 for pair in DIRECTIONS}), "Missing measured leg")
    require(warm_counts == Counter({pair: 1 for pair in DIRECTIONS}), "Missing warm-up leg")
    require(ids == {f"moz1-{pair}-{category}" for pair in DIRECTIONS for category in CATEGORIES},
            "Missing category")
    require({item["source"] for item in measured} == set(LANGUAGES), "Missing source language")
    require(ids.isdisjoint(warm_ids) and source_texts.isdisjoint(warm_texts),
            "Measured/warm-up overlap")
    expectation_ids = set()
    for item in expectations:
        require(isinstance(item, dict) and set(item) ==
                {"id", "essential_propositions", "uncertainty", "judgment"}, "Invalid expectation schema")
        require(isinstance(item["id"], str) and item["id"] in ids
                and item["id"] not in expectation_ids, "Duplicate/unmatched expectation ID")
        require(strings(item["essential_propositions"]) and item["essential_propositions"],
                "Missing English essential propositions")
        require(strings(item["uncertainty"]) and item["judgment"] == JUDGMENT,
                "Missing uncertainty/judgment declaration")
        expectation_ids.add(item["id"])
    require(expectation_ids == ids, "Incomplete host expectation coverage")
    old = {}
    for name, digest in V1_PINS.items():
        raw, text = read_text(ROOT.parent / "v1" / name)
        require(hashlib.sha256(raw).hexdigest() == digest, f"Preserved v1 changed: {name}")
        old[name] = rows(text)
    old_inputs = old["cases.jsonl"] + old["warmup.jsonl"]
    require((ids | warm_ids).isdisjoint({item["id"] for item in old_inputs}), "v1 ID reused")
    require((source_texts | warm_texts).isdisjoint({item["text"] for item in old_inputs}),
            "v1 exact source reused")
    in_catalog = [item for item in old["cases.jsonl"]
                  if item["source"] in LANGUAGES and item["target"] in LANGUAGES]
    outside = [item for item in old["cases.jsonl"] if item not in in_catalog]
    require(len(in_catalog) == 48 and len(outside) == 8
            and all("ar" in (item["source"], item["target"]) for item in outside),
            "Wrong v1 regression boundary")
    print("PASS measured=96 host_expectations=96 directions=32 source_languages=17")
    print("PASS categories=request:32,negation:32,price-time:32 base-memory=25/75 tiny=7/21")
    print("PASS warmup=32 excluded=true unique_ids=true exact_source_disjoint=true UTF-8/LF=true")
    print("PASS preserved_v1_payload_hashes=3 in_catalog_regression=48 out_of_catalog_Arabic=8")
    for entry in inventory:
        print(f"{entry['path']} rows={entry['rows']} bytes={entry['bytes']} sha256={entry['sha256']}")
    print(f"manifest.json bytes={len(manifest_raw)} sha256={hashlib.sha256(manifest_raw).hexdigest()}")
    print("LIMIT linguistic correctness and human/native-speaker acceptance are not verified")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
