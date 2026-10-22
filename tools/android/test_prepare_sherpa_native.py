"""Consumer-visible failure boundaries; no NDK/build/network/device required."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("prepare_sherpa_native", Path(__file__).with_name("prepare-sherpa-native.py"))
native = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(native)


def elf(name, abi, *, alignment=16384, dependency="libc.so", exports=(), marker=b""):
    """Small real ELF table fixture, not a native inference substitute."""
    strings = bytearray(b"\0")
    def add(text):
        offset = len(strings)
        strings.extend(text.encode() + b"\0")
        return offset
    soname, needed = add(name), add(dependency)
    symbols = b"".join(struct.pack("<IBBHQQ", add(symbol), 0x12, 0, 1, 0, 0) for symbol in exports)
    data = bytearray(64 + 56)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183 if abi == "arm64-v8a" else 62, 1,
                     0, 64, 0, 0, 64, 56, 1, 64, 4, 0)
    string_offset = len(data); data.extend(strings)
    dynamic = struct.pack("<qQqQqQ", 14, soname, 1, needed, 0, 0)
    dynamic_offset = len(data); data.extend(dynamic)
    symbol_offset = len(data); data.extend(symbols)
    data.extend(marker)
    section_offset = len(data)
    sections = [(0,) * 10,
                (0, 3, 0, 0, string_offset, len(strings), 0, 0, 1, 0),
                (0, 6, 0, 0, dynamic_offset, len(dynamic), 1, 0, 8, 16),
                (0, 11, 0, 0, symbol_offset, len(symbols), 1, 0, 8, 24)]
    for section in sections:
        data.extend(struct.pack("<IIQQQQIIQQ", *section))
    struct.pack_into("<Q", data, 40, section_offset)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 4, 0, 0, 0, len(data), len(data), alignment)
    return bytes(data)


def write_aar(path, entries):
    with zipfile.ZipFile(path, "w") as packed:
        for name, data in entries.items():
            packed.writestr(name, data)


class NativeDistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "android"; self.output.mkdir()
        self.spec = copy.deepcopy(native.manifest())
        self.stock = {"classes.jar": b"unchanged classes", "AndroidManifest.xml": b"unchanged manifest"}
        self.entries = dict(self.stock)
        for abi in native.ABIS:
            for name in self.spec["settings"]["libraries"]:
                exports = ["Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_decode"] if name.endswith("jni.so") else (
                    ["_ZN11sherpa_onnx9WhateverEv"] if "cxx-api" in name else ["SherpaOnnxCreateOfflineRecognizer"])
                dependency = "libsherpa-onnx-c-api.so" if "cxx-api" in name else (
                    "libc.so" if name == "libonnxruntime.so" else "libonnxruntime.so")
                original = elf(name, abi, exports=exports, dependency=dependency)
                key = f"jni/{abi}/{name}"
                self.stock[key] = original
                self.entries[key] = original if name == "libonnxruntime.so" else elf(
                    name, abi, exports=exports, dependency=dependency,
                    marker=b"patched:whisper-language-pair-v1\0")
        self.entries["assets/sherpa-native/licenses/LICENSE"] = b"preserved license"
        self.official = self.root / "stock.aar"; write_aar(self.official, self.stock)
        self.archive = self.output / (self.spec["artifact"] + ".aar")
        (self.output / ".sherpa-native-owner").write_text(self.spec["artifact"])
        self.inputs = {"recipe.py": "current-recipe-hash"}
        for target, replacement in (("manifest", lambda: self.spec), ("native_inputs", lambda: self.inputs),
                                    ("official_archive", lambda spec: self.official)):
            context = patch.object(native, target, replacement); context.start(); self.addCleanup(context.stop)
        self.save()

    def save(self):
        write_aar(self.archive, self.entries)
        receipt = {"schema": 1, "artifact": self.spec["artifact"], "feature": self.spec["feature"],
                   "abis": native.ABIS, "inputs": self.inputs, "pins": self.spec,
                   "archive": {"bytes": self.archive.stat().st_size, "sha256": native.sha256(self.archive)},
                   "distribution": native.distribution_inventory(self.archive, self.official, self.spec)}
        (self.output / "receipt.json").write_text(json.dumps(receipt))

    def test_read_only_verification_requires_no_network_tools_or_source_cache(self):
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        with patch.object(native.subprocess, "run", side_effect=AssertionError("subprocess forbidden")), \
             patch.object(native.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
            self.assertEqual(self.spec["feature"], native.verify(self.output, native.ABIS)["feature"])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})

    def test_missing_corrupt_and_stale_publication_fail_closed(self):
        for fault in ("missing-receipt", "invalid-json", "missing-archive", "corrupt-archive", "stale-inputs", "wrong-feature", "receipt-inventory"):
            with self.subTest(fault=fault):
                self.save()
                receipt = self.output / "receipt.json"
                if fault == "missing-receipt": receipt.unlink()
                elif fault == "invalid-json": receipt.write_text("{")
                elif fault == "missing-archive": self.archive.unlink()
                elif fault == "corrupt-archive": self.archive.write_bytes(b"corrupt")
                else:
                    saved = json.loads(receipt.read_text())
                    if fault == "stale-inputs": saved["inputs"] = {}
                    elif fault == "wrong-feature": saved["feature"] = "stock"
                    else: saved["distribution"]["elf"] = {}
                    receipt.write_text(json.dumps(saved))
                with self.assertRaisesRegex(RuntimeError, "Ordinary Gradle builds never"):
                    native.verify(self.output, native.ABIS)

    def test_mixed_stock_ort_changed_non_native_and_unsupported_abis_rejected(self):
        original = dict(self.entries)
        faults = {
            "mixed": lambda: self.entries.update({"jni/x86_64/libsherpa-onnx-cxx-api.so": self.stock["jni/x86_64/libsherpa-onnx-cxx-api.so"]}),
            "ort": lambda: self.entries.update({"jni/arm64-v8a/libonnxruntime.so": self.entries["jni/arm64-v8a/libonnxruntime.so"] + b"changed"}),
            "classes": lambda: self.entries.update({"classes.jar": b"different wrapper"}),
            "missing": lambda: self.entries.pop("jni/arm64-v8a/libsherpa-onnx-jni.so"),
            "32bit": lambda: self.entries.update({"jni/x86/libonnxruntime.so": b"not allowed"}),
            "missing-feature": lambda: self.entries.update({"jni/x86_64/libsherpa-onnx-jni.so": elf(
                "libsherpa-onnx-jni.so", "x86_64", dependency="libonnxruntime.so",
                exports=["Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_decode"], marker=b"unidentified custom build")}),
        }
        for name, corrupt in faults.items():
            with self.subTest(name=name):
                self.entries = dict(original); corrupt(); write_aar(self.archive, self.entries)
                with self.assertRaises(ValueError):
                    native.distribution_inventory(self.archive, self.official, self.spec)
        self.entries = original

    def test_wrong_abi_alignment_dependency_and_missing_public_api_rejected(self):
        name = "libsherpa-onnx-jni.so"
        for data in (elf(name, "arm64-v8a"), elf(name, "x86_64", alignment=4096),
                     elf(name, "x86_64", dependency="libunpackaged.so")):
            with self.assertRaises(ValueError):
                native.elf_inventory(data, name, "x86_64", set(self.spec["settings"]["libraries"]))
        self.entries["jni/x86_64/" + name] = elf(name, "x86_64", exports=["differentExport"], dependency="libonnxruntime.so")
        write_aar(self.archive, self.entries)
        with self.assertRaisesRegex(ValueError, "public API"):
            native.distribution_inventory(self.archive, self.official, self.spec)

    def test_duplicate_aar_entries_are_not_accepted_by_last_entry_wins(self):
        with zipfile.ZipFile(self.archive, "w") as packed:
            packed.writestr("classes.jar", b"one")
            with self.assertWarns(UserWarning): packed.writestr("classes.jar", b"two")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            native.aar_entries(self.archive)

    def test_failed_preparation_preserves_previously_valid_published_output(self):
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        with self.assertRaisesRegex(RuntimeError, "build failure"):
            with native.publication(self.output, self.spec["artifact"]) as stage:
                (stage / self.archive.name).write_bytes(b"partial build")
                raise RuntimeError("build failure")
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})
        native.verify(self.output, native.ABIS)
        self.assertFalse(self.output.with_name("android.prepare.lock").exists())

    def test_publication_rename_failure_restores_old_generation(self):
        before = self.archive.read_bytes()
        replace = native.os.replace
        def fail_stage(source, target):
            if ".stage-" in str(source):
                raise OSError("publication interrupted")
            return replace(source, target)
        with patch.object(native.os, "replace", side_effect=fail_stage):
            with self.assertRaisesRegex(OSError, "interrupted"):
                with native.publication(self.output, self.spec["artifact"]) as stage:
                    (stage / self.archive.name).write_bytes(b"new")
        self.assertEqual(before, self.archive.read_bytes())
        native.verify(self.output, native.ABIS)


class ArchiveAndPatchTest(unittest.TestCase):
    def test_flat_header_zip_materializes_without_requiring_an_invented_archive_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); archive = root / "headers.zip"
            write_aar(archive, {"headers/onnxruntime_cxx_api.h": b"header", "jni/x86_64/libonnxruntime.so": b"input"})
            extracted = native.extract_archive(archive, root / "materialized", ".")
            self.assertEqual(b"header", (extracted / "headers/onnxruntime_cxx_api.h").read_bytes())
            self.assertEqual(b"input", (extracted / "jni/x86_64/libonnxruntime.so").read_bytes())

    def test_unsafe_zip_paths_fail_without_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, member in enumerate(("../escape", "/absolute", "root/../../escape", "root\\escape", "C:/escape")):
                archive = root / f"unsafe-{index}.zip"
                with zipfile.ZipFile(archive, "w") as packed:
                    entry = zipfile.ZipInfo(member)
                    # ZipInfo normalizes backslashes on Windows; retain the actual unsafe archive bytes.
                    entry.filename = member
                    packed.writestr(entry, b"target")
                with self.assertRaises(ValueError):
                    native.extract_archive(archive, root / f"out-{index}", "root")
            self.assertFalse((root / "escape").exists())

    def test_zip_source_links_are_omitted_without_following_their_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); archive = root / "source.zip"
            outside = root / "escape"; outside.write_bytes(b"unchanged")
            with zipfile.ZipFile(archive, "w") as packed:
                packed.writestr("root/native.cc", b"source")
                for name, target in (("relative", "../../escape"), ("absolute", "/developer/source")):
                    entry = zipfile.ZipInfo(f"root/{name}"); entry.external_attr = 0o120777 << 16
                    packed.writestr(entry, target)
            extracted = native.extract_archive(archive, root / "out", "root")
            self.assertEqual(["native.cc"], sorted(path.name for path in extracted.iterdir()))
            self.assertEqual(b"source", (extracted / "native.cc").read_bytes())
            self.assertEqual(b"unchanged", outside.read_bytes())

    def test_source_link_cannot_hide_a_duplicate_regular_zip_member(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); archive = root / "source.zip"
            with zipfile.ZipFile(archive, "w") as packed:
                entry = zipfile.ZipInfo("root/native.cc"); entry.external_attr = 0o120777 << 16
                packed.writestr(entry, "../../escape")
                with self.assertWarns(UserWarning): packed.writestr("root/native.cc", b"replacement")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                native.extract_archive(archive, root / "out", "root")

    def test_tar_source_links_are_omitted_but_devices_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as packed:
                source = tarfile.TarInfo("root/native.cc"); source.size = len(b"source")
                packed.addfile(source, io.BytesIO(b"source"))
                for name, kind in (("symbolic", tarfile.SYMTYPE), ("hard", tarfile.LNKTYPE)):
                    member = tarfile.TarInfo(f"root/{name}"); member.type = kind; member.linkname = "/developer/source"
                    packed.addfile(member)
            extracted = native.extract_archive(archive, root / "out", "root")
            self.assertEqual(["native.cc"], sorted(path.name for path in extracted.iterdir()))
            self.assertEqual(b"source", (extracted / "native.cc").read_bytes())
            special = root / "device.tar.gz"
            with tarfile.open(special, "w:gz") as packed:
                member = tarfile.TarInfo("root/device"); member.type = tarfile.CHRTYPE
                packed.addfile(member)
            with self.assertRaisesRegex(ValueError, "Special"):
                native.extract_archive(special, root / "special", "root")

    def test_source_corruption_is_not_repaired_even_when_download_authorized(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory); archive = cache / "input.zip"; archive.write_bytes(b"user-corruption")
            pin = {"archive": archive.name, "url": "https://example.invalid/input", "sha256": native.digest(b"correct"), "bytes": 7}
            with patch.object(native.urllib.request, "urlopen", side_effect=AssertionError("must not repair")):
                with self.assertRaises(ValueError): native.source_archive(cache, pin, True)
            self.assertEqual(b"user-corruption", archive.read_bytes())

    def test_exact_patch_rejects_offset_or_preimage_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "source"; source.mkdir()
            target = source / "native.cc"; target.write_text("first\nold\nlast\n")
            patch_file = root / "change.patch"
            patch_file.write_text("--- a/native.cc\n+++ b/native.cc\n@@ -2 +2 @@\n-old\n+new\n")
            preimage = {"native.cc": native.sha256(target)}
            native.apply_patch(source, patch_file, preimage)
            self.assertEqual("first\nnew\nlast\n", target.read_text())
            target.write_text("prefix\nfirst\nold\nlast\n")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                native.apply_patch(source, patch_file, preimage)
            with self.assertRaisesRegex(ValueError, "preimage"):
                native.apply_patch(source, patch_file, {"native.cc": native.sha256(target)})


if __name__ == "__main__":
    unittest.main()
