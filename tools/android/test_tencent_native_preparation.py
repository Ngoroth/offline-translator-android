#!/usr/bin/env python3
"""Isolated build-contract checks; no tools, model, network or Android device."""
import importlib.util
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("tencent_preparation", Path(__file__).with_name("prepare-tencent-native.py"))
PREP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREP)


def elf(machine, alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)


class PreparedArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in PREP.native_inputs(PREP.ROOT):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PREP.ROOT / name, target)
        self.output = self.root / "engine-tencent/libs"
        self.output.mkdir(parents=True)
        self.archive = self.output / f"{PREP.ARTIFACT}.aar"
        self.receipt = self.output / f"{PREP.ARTIFACT}.receipt.properties"
        self.members = {"jni/arm64-v8a/libtencent_jni.so": elf(183),
                        "jni/x86_64/libtencent_jni.so": elf(62),
                        "assets/tencent-native/LICENSE": b"Fixture license"}
        self.publish()

    def publish(self):
        inputs = PREP.native_inputs(self.root)
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, data in self.members.items():
                archive.writestr(name, data)
        receipt = {"schema": "1", "llama.commit": "d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654",
                   "abis": "arm64-v8a,x86_64", "inputs.sha256": PREP.inventory_digest(inputs),
                   "aar.sha256": PREP.sha256(self.archive), "aar.bytes": str(self.archive.stat().st_size)}
        receipt.update({"input." + name: value for name, value in inputs.items()})
        receipt.update({"member." + name: PREP.digest(data) for name, data in self.members.items()})
        self.receipt.write_text("".join(f"{key}={value}\n" for key, value in receipt.items()), encoding="ascii")

    def test_current_distribution_is_accepted_without_source_cache(self):
        result = PREP.verify(self.root)
        self.assertEqual(result["aar.sha256"], PREP.sha256(self.archive))

    def test_missing_artifact_reports_explicit_preparation(self):
        self.archive.unlink()
        with self.assertRaisesRegex(RuntimeError, "prepare-tencent-native.ps1"):
            PREP.verify(self.root)

    def test_source_recipe_and_settings_changes_reject_old_receipt(self):
        for name in ("engine-tencent/src/main/cpp/tencent_runtime.cpp",
                     "tools/android/prepare-tencent-native.py", "tools/android/tencent-native-tools.json"):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                path.write_bytes(original + b"\n")
                with self.assertRaisesRegex(RuntimeError, "receipt is stale"):
                    PREP.verify(self.root)
                path.write_bytes(original)

    def test_added_native_source_rejects_old_receipt(self):
        (self.root / "engine-tencent/src/main/cpp/new_boundary.h").write_text("// new input\n")
        with self.assertRaisesRegex(RuntimeError, "receipt is stale"):
            PREP.verify(self.root)

    def test_mutated_archive_rejected_independently_of_current_inputs(self):
        with self.archive.open("ab") as archive:
            archive.write(b"altered")
        with self.assertRaisesRegex(RuntimeError, "checksum/size mismatch"):
            PREP.verify(self.root)

    def test_self_consistent_wrong_abi_is_rejected(self):
        del self.members["jni/arm64-v8a/libtencent_jni.so"]
        self.publish()
        with self.assertRaisesRegex(RuntimeError, "native library inventory"):
            PREP.verify(self.root)

    def test_self_consistent_unaligned_elf_is_rejected(self):
        self.members["jni/x86_64/libtencent_jni.so"] = elf(62, 4096)
        self.publish()
        with self.assertRaisesRegex(RuntimeError, "16-KiB"):
            PREP.verify(self.root)

    def test_wrong_machine_is_rejected(self):
        self.members["jni/x86_64/libtencent_jni.so"] = elf(183)
        self.publish()
        with self.assertRaisesRegex(RuntimeError, "ELF machine"):
            PREP.verify(self.root)

    def test_license_is_required_even_with_fresh_checksums(self):
        del self.members["assets/tencent-native/LICENSE"]
        self.publish()
        with self.assertRaisesRegex(RuntimeError, "runtime license"):
            PREP.verify(self.root)


if __name__ == "__main__":
    unittest.main()
