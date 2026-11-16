"""Consumer-facing offline rejection cases; no compiler, network, model or device."""
from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("mozilla_preparation", Path(__file__).with_name("prepare-mozilla-native.py"))
preparation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preparation)


def elf(machine: int, needed: str = "libc.so", alignment: int = 16384) -> bytes:
    data = bytearray(768)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 2, 6, 384, 384, 384, 64, 64, 8)
    strings = b"\0" + needed.encode("ascii") + b"\0"
    data[512:512 + len(strings)] = strings
    for index, pair in enumerate(((5, 512), (10, len(strings)), (1, 1), (0, 0))):
        struct.pack_into("<qQ", data, 384 + index * 16, *pair)
    return bytes(data)


class MozillaNativePreparationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for name in preparation.native_inputs(preparation.ROOT):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(preparation.ROOT / name, target)
        self.output = self.root / "engine-mozilla/libs"
        self.output.mkdir()
        self.archive = self.output / f"{preparation.ARTIFACT}.aar"
        self.sources = self.output / f"{preparation.ARTIFACT}.sources.tar.gz"
        self.sources.write_bytes(b"explicit synthetic corresponding-source fixture")
        self.members = {
            "jni/arm64-v8a/libmozilla_jni.so": elf(183),
            "jni/x86_64/libmozilla_jni.so": elf(62),
            "AndroidManifest.xml": b"fixture manifest", "classes.jar": b"fixture classes", "proguard.txt": b"fixture rules",
            "assets/mozilla-native/licenses/mozilla-LICENSE": b"fixture notice",
            "assets/mozilla-native/licenses/LGPL-2.1": b"fixture notice",
            "assets/mozilla-native/THIRD_PARTY.md": b"fixture notice",
        }
        for code in ("en", "es", "it", "zh", "de", "pl", "pt", "ru", "fr", "nl"):
            self.members[f"assets/mozilla/prefixes/{code}.txt"] = b"fixture data"
        self.publish()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def publish(self, extra: tuple[str, bytes] | None = None) -> None:
        with zipfile.ZipFile(self.archive, "w") as packed:
            for name, value in self.members.items():
                preparation.zip_member(packed, name, value)
            if extra:
                preparation.zip_member(packed, *extra)
        inputs = preparation.native_inputs(self.root)
        receipt = {"schema": "1", "mozilla.commit": "8eada038fd45bbf6fa58a412b22f80c79887fabc",
                   "abis": "arm64-v8a,x86_64", "inputs.sha256": preparation.inventory_digest(inputs),
                   "aar.sha256": preparation.sha256(self.archive), "aar.bytes": str(self.archive.stat().st_size),
                   "sources.sha256": preparation.sha256(self.sources), "sources.bytes": str(self.sources.stat().st_size)}
        receipt.update({"input." + name: value for name, value in inputs.items()})
        receipt.update({"member." + name: preparation.digest(value) for name, value in self.members.items()})
        # Structural negative fixtures also get updated outer hashes: the consumer
        # must inspect ELF/ABI/member contracts rather than trust a checksum alone.
        for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
            for key, value in preparation.elf_inventory(elf(machine), abi).items():
                receipt[f"elf.{abi}.{key}"] = value
        (self.output / f"{preparation.ARTIFACT}.receipt.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in sorted(receipt.items())), encoding="ascii")

    def reject(self, message: str) -> None:
        with self.assertRaisesRegex(RuntimeError, message):
            preparation.verify(self.root)

    def test_valid_prepared_distribution_is_offline_verifiable(self) -> None:
        saved = preparation.verify(self.root)
        self.assertEqual("libc.so", saved["elf.arm64-v8a.needed"])

    def test_modified_native_input_invalidates_receipt(self) -> None:
        path = self.root / "engine-mozilla/consumer-rules.pro"
        path.write_bytes(path.read_bytes() + b"\n")
        self.reject("stale")

    def test_concurrent_catalog_is_not_a_native_receipt_input(self) -> None:
        catalog = self.root / "engine-mozilla/src/main/resources/mozilla-translation-catalog.json"
        catalog.parent.mkdir(parents=True, exist_ok=True)
        catalog.write_text("not a native build input")
        preparation.verify(self.root)

    def test_modified_aar_fails_checksum(self) -> None:
        with self.archive.open("ab") as stream:
            stream.write(b"modified")
        self.reject("checksum/length")

    def test_modified_corresponding_source_fails_checksum(self) -> None:
        self.sources.write_bytes(b"modified source")
        self.reject("Corresponding-source checksum/length")

    def test_missing_abi_is_rejected_even_with_updated_receipt(self) -> None:
        del self.members["jni/arm64-v8a/libmozilla_jni.so"]
        self.publish()
        self.reject("native library inventory")

    def test_wrong_machine_is_rejected_even_with_updated_member_hash(self) -> None:
        self.members["jni/arm64-v8a/libmozilla_jni.so"] = elf(62)
        self.publish()
        self.reject("ELF machine")

    def test_unaligned_load_is_rejected_even_with_updated_member_hash(self) -> None:
        self.members["jni/x86_64/libmozilla_jni.so"] = elf(62, alignment=4096)
        self.publish()
        self.reject("16-KiB")

    def test_nonplatform_needed_is_rejected_without_readelf(self) -> None:
        self.members["jni/x86_64/libmozilla_jni.so"] = elf(62, needed="libc++_shared.so")
        self.publish()
        self.reject("dynamic dependency")

    def test_truncated_dynamic_table_is_rejected(self) -> None:
        self.members["jni/x86_64/libmozilla_jni.so"] = elf(62)[:400]
        self.publish()
        self.reject("dynamic table")

    def test_missing_prefix_notice_and_wrong_member_are_rejected(self) -> None:
        del self.members["assets/mozilla/prefixes/zh.txt"]
        self.publish()
        self.reject("Missing notices or source splitter")

    def test_unreceipted_member_is_rejected(self) -> None:
        self.publish(extra=("jni/x86_64/libunexpected.so", b"unapproved"))
        self.reject("member inventory")

    def test_duplicate_members_are_rejected(self) -> None:
        with self.assertWarns(UserWarning):
            self.publish(extra=("classes.jar", b"duplicate"))
        self.reject("Duplicate AAR")


if __name__ == "__main__":
    unittest.main()
