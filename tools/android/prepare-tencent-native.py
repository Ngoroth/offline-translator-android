#!/usr/bin/env python3
"""Explicit pinned Linux native build; ordinary builds only verify the receipt."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "tencent-native-d8d92973-r2"
INSTRUCTIONS = ("Run python3 tools/android/prepare-tencent-native.py on Linux, or "
                "pwsh -File tools/android/prepare-tencent-native.ps1 on Windows/WSL.")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def native_inputs(root: Path) -> dict[str, str]:
    paths = list((root / "engine-tencent/src/main/cpp").rglob("*"))
    paths += [root / name for name in (
        "engine-tencent/build.gradle.kts", "engine-tencent/src/main/AndroidManifest.xml",
        "engine-tencent/src/main/java/com/example/offlinetranslator/tencent/TencentNativeRuntime.kt",
        "engine-tencent/THIRD_PARTY.md", "tools/android/prepare-tencent-native.py",
        "tools/android/prepare-tencent-native.ps1", "tools/android/tencent-native-tools.json")]
    return {path.relative_to(root).as_posix(): sha256(path)
            for path in sorted(paths) if path.is_file()}


def inventory_digest(inventory: dict[str, str]) -> str:
    return digest("".join(f"{name}\0{value}\n" for name, value in sorted(inventory.items())).encode())


def properties(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="ascii").splitlines()
                if line and not line.startswith("#"))


def elf_inventory(data: bytes, abi: str) -> dict[str, str]:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != {"x86_64": 62, "arm64-v8a": 183}[abi]:
        raise ValueError("Unexpected ELF machine")
    offset = struct.unpack_from("<Q", data, 32)[0]
    width, count = struct.unpack_from("<HH", data, 54)
    alignments = []
    for index in range(count):
        segment = offset + index * width
        kind = struct.unpack_from("<I", data, segment)[0]
        if kind == 1:
            alignment = struct.unpack_from("<Q", data, segment + 48)[0]
            file_offset, address = struct.unpack_from("<QQ", data, segment + 8)
            if alignment < 16384 or (address - file_offset) % 16384:
                raise ValueError("ELF LOAD segment lacks 16-KiB compatibility")
            alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no LOAD segments")
    return {"machine": str(machine), "load_alignments": ",".join(map(str, alignments))}


def verify(root: Path = ROOT) -> dict[str, str]:
    """No network, tools or source cache is needed to verify a prepared distribution."""
    directory = root / "engine-tencent/libs"
    archive = directory / f"{ARTIFACT}.aar"
    receipt = directory / f"{ARTIFACT}.receipt.properties"
    try:
        saved = properties(receipt)
        manifest = json.loads((root / "tools/android/tencent-native-tools.json").read_text())
        current = native_inputs(root)
        expected_inputs = {key.removeprefix("input."): value for key, value in saved.items()
                           if key.startswith("input.")}
        if current != expected_inputs or saved["inputs.sha256"] != inventory_digest(current):
            raise ValueError("Native source/recipe/settings receipt is stale")
        if saved["schema"] != "1" or saved["llama.commit"] != manifest["llama_commit"]:
            raise ValueError("Unexpected receipt schema or runtime pin")
        if saved["abis"] != ",".join(manifest["settings"]["abis"]):
            raise ValueError("Unexpected ABI inventory")
        if sha256(archive) != saved["aar.sha256"] or str(archive.stat().st_size) != saved["aar.bytes"]:
            raise ValueError("Prepared AAR checksum/size mismatch")
        with zipfile.ZipFile(archive) as zipped:
            members = {info.filename: digest(zipped.read(info)) for info in zipped.infolist()}
            if len(members) != len(zipped.infolist()):
                raise ValueError("Duplicate AAR members")
            expected = {key.removeprefix("member."): value for key, value in saved.items()
                        if key.startswith("member.")}
            if members != expected:
                raise ValueError("AAR member inventory mismatch")
            libraries = sorted(name for name in members if name.startswith("jni/"))
            if libraries != [f"jni/{abi}/libtencent_jni.so" for abi in manifest["settings"]["abis"]]:
                raise ValueError("Unexpected native library inventory")
            if "assets/tencent-native/LICENSE" not in members:
                raise ValueError("Missing runtime license")
            for abi in manifest["settings"]["abis"]:
                elf_inventory(zipped.read(f"jni/{abi}/libtencent_jni.so"), abi)
        return saved
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, struct.error) as error:
        raise RuntimeError(f"Tencent native artifact verification failed: {error}. {INSTRUCTIONS}") from error


def run(command: list[str], log: Path | None = None) -> str:
    print("+ " + " ".join(map(str, command)), flush=True)
    if log:
        with log.open("w") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}); see {log}")
        return ""
    return subprocess.check_output(command, text=True).strip()


def download(spec: dict, cache: Path, seed: Path | None = None) -> Path:
    path = cache / "archives" / spec["url"].rsplit("/", 1)[1]
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        partial = path.with_suffix(path.suffix + ".partial")
        try:
            if seed:
                shutil.copyfile(seed, partial)
            else:
                print(f"Downloading {spec['url']}", flush=True)
                with urllib.request.urlopen(spec["url"], timeout=60) as response, partial.open("wb") as output:
                    shutil.copyfileobj(response, output, 1024 * 1024)
            if sha256(partial) != spec["sha256"]:
                raise RuntimeError(f"Official archive checksum mismatch: {spec['url']}")
            partial.replace(path)
        finally:
            partial.unlink(missing_ok=True)
    if sha256(path) != spec["sha256"]:
        raise RuntimeError(f"Cached archive checksum mismatch; remove and prepare again: {path}")
    return path


def tree_digest(root: Path) -> str:
    inventory = {}
    for path in sorted(root.rglob("*")):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            inventory[name] = digest(("symlink:" + os.readlink(path)).encode())
        elif path.is_file():
            inventory[name] = sha256(path)
    return inventory_digest(inventory)


def extract_zip(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as zipped:
        for info in zipped.infolist():
            target = destination / info.filename
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError("Unsafe archive path")
            mode = info.external_attr >> 16
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if stat.S_ISLNK(mode):
                link = zipped.read(info).decode()
                if not (target.parent / link).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Unsafe archive symlink")
                target.symlink_to(link)
            else:
                with zipped.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                if mode:
                    target.chmod(mode & 0o777)


def tool(spec: dict, cache: Path, seed: Path | None = None) -> Path:
    archive = download(spec, cache, seed)
    destination = cache / "tools" / spec["directory"]
    stamp = cache / "tools" / (spec["directory"] + ".sha256")
    if destination.exists():
        if not stamp.exists() or stamp.read_text().strip() != tree_digest(destination):
            raise RuntimeError(f"Extracted tool changed; remove {destination} and prepare again")
        return destination
    staging = cache / "tools" / (spec["directory"] + ".partial")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    if archive.name.endswith(".zip"):
        extract_zip(archive, staging)
    else:
        with tarfile.open(archive) as packed:
            packed.extractall(staging, filter="data")
    nested = staging / spec["directory"]
    if nested.is_dir():
        nested.replace(destination)
        staging.rmdir()
    else:
        staging.replace(destination)
    stamp.write_text(tree_digest(destination) + "\n")
    return destination


def source_snapshot(manifest: dict, cache: Path, seed: Path | None) -> Path:
    repository = seed or cache / "upstream.git"
    commit = manifest["llama_commit"]
    if not repository.exists():
        run(["git", "init", "--bare", str(repository)])
    try:
        resolved = run(["git", "-C", str(repository), "rev-parse", "--verify", commit + "^{commit}"])
    except subprocess.CalledProcessError:
        if seed:
            raise RuntimeError("Seed source repository lacks the pinned commit")
        run(["git", "-C", str(repository), "fetch", "--depth=1", manifest["llama_repository"], commit])
        resolved = run(["git", "-C", str(repository), "rev-parse", "--verify", commit + "^{commit}"])
    if resolved != commit:
        raise RuntimeError("Resolved upstream commit mismatch")
    # Export Git objects, never unchecked working-tree bytes. No build uses the seed path.
    destination = cache / "source" / commit
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    packed = subprocess.check_output(["git", "-C", str(repository), "archive", "--format=tar", commit])
    with tarfile.open(fileobj=io.BytesIO(packed)) as archive:
        archive.extractall(destination, filter="data")
    return destination


def zip_member(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def prepare(args: argparse.Namespace) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("The pinned tool archives require Linux x86_64; use the Windows WSL wrapper")
    manifest = json.loads((ROOT / "tools/android/tencent-native-tools.json").read_text())
    initial_inputs = native_inputs(ROOT)
    cache = ROOT / "engine-tencent/.native-cache"
    cache.mkdir(parents=True, exist_ok=True)
    # Concurrent preparation must not replace tool/source/build/output state.
    import fcntl
    with (cache / "prepare.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        source = source_snapshot(manifest, cache, args.source_repository)
        ndk = tool(manifest["ndk"], cache, args.ndk_archive)
        cmake = tool(manifest["cmake"], cache) / "bin/cmake"
        ninja = tool(manifest["ninja"], cache) / "ninja"
        ninja.chmod(ninja.stat().st_mode | 0o111)
        for executable, expected in ((cmake, "cmake version 4.2.3"), (ninja, "1.13.2")):
            if run([str(executable), "--version"]).splitlines()[0] != expected:
                raise RuntimeError("Pinned tool version mismatch")
        if "Pkg.Revision = 29.0.13113456-beta1" not in (ndk / "source.properties").read_text():
            raise RuntimeError("Pinned NDK version mismatch")
        libraries = {}
        receipt = {"schema": "1", "llama.commit": manifest["llama_commit"],
                   "source.tree.sha256": tree_digest(source), "abis": ",".join(manifest["settings"]["abis"])}
        for name in ("ndk", "cmake", "ninja"):
            receipt[f"tool.{name}.version"] = manifest[name]["version"]
            receipt[f"tool.{name}.archive.sha256"] = manifest[name]["sha256"]
        for abi in manifest["settings"]["abis"]:
            build = cache / f"build-{abi}"
            command = [str(cmake), "-S", str(ROOT / "engine-tencent/src/main/cpp"), "-B", str(build),
                       "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release",
                       f"-DTENCENT_LLAMA_SOURCE={source}",
                       f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                       f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static"]
            run(command, cache / f"{abi}-configure.log")
            run([str(cmake), "--build", str(build), "--target", "tencent_jni", "-j", "2"],
                cache / f"{abi}-build.log")
            library = build / "jni/libtencent_jni.so"
            run([str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"), "--strip-unneeded", str(library)])
            data = library.read_bytes()
            for key, value in elf_inventory(data, abi).items():
                receipt[f"elf.{abi}.{key}"] = value
            libraries[f"jni/{abi}/libtencent_jni.so"] = data
            dependencies = run([str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"), "-d", str(library)])
            needed = sorted(line.split("[", 1)[1].split("]", 1)[0] for line in dependencies.splitlines()
                            if "(NEEDED)" in line)
            if not set(needed) <= {"libm.so", "libdl.so", "libc.so", "liblog.so", "libandroid.so"}:
                raise RuntimeError(f"Unexpected non-platform dependency: {needed}")
            receipt[f"elf.{abi}.needed"] = ",".join(needed)
        members = dict(libraries)
        members["AndroidManifest.xml"] = (b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            b'package="com.example.offlinetranslator.tencent.nativecpu"><uses-sdk android:minSdkVersion="26"/></manifest>\n')
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w"):
            pass
        members["classes.jar"] = jar.getvalue()
        members["R.txt"] = b""
        members["proguard.txt"] = (b'-keep class com.example.offlinetranslator.tencent.TencentNativeRuntime { *; }\n'
                                   b'-keep class com.example.offlinetranslator.tencent.TencentNativeException { *; }\n')
        license_inputs = [source / "LICENSE"] + sorted((source / "licenses").glob("*"))
        license_inputs += sorted((source / "vendor/hash").rglob("LICENSE*"))
        for path in license_inputs:
            if path.is_file():
                members["assets/tencent-native/" + path.relative_to(source).as_posix()] = path.read_bytes()
        members["assets/tencent-native/THIRD_PARTY.md"] = (ROOT / "engine-tencent/THIRD_PARTY.md").read_bytes()
        if native_inputs(ROOT) != initial_inputs:
            raise RuntimeError("Native inputs changed during preparation; rerun after the edit wave")
        output = ROOT / "engine-tencent/libs"
        output.mkdir(parents=True, exist_ok=True)
        archive = output / f"{ARTIFACT}.aar"
        partial = archive.with_suffix(".aar.partial")
        with zipfile.ZipFile(partial, "w") as packed:
            for name, data in sorted(members.items()):
                zip_member(packed, name, data)
        receipt.update({"aar.sha256": sha256(partial), "aar.bytes": str(partial.stat().st_size),
                        "inputs.sha256": inventory_digest(initial_inputs)})
        receipt.update({"input." + name: value for name, value in initial_inputs.items()})
        receipt.update({"member." + name: digest(data) for name, data in members.items()})
        partial_receipt = output / f"{ARTIFACT}.receipt.properties.partial"
        partial_receipt.write_text("".join(f"{key}={value}\n" for key, value in sorted(receipt.items())), encoding="ascii")
        partial.replace(archive)
        partial_receipt.replace(output / f"{ARTIFACT}.receipt.properties")
        verify()
        print(json.dumps({"aar": str(archive), **receipt}, indent=2), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--source-repository", type=Path)
    parser.add_argument("--ndk-archive", type=Path)
    args = parser.parse_args()
    if args.verify_only:
        print(json.dumps(verify(), indent=2))
    else:
        prepare(args)


if __name__ == "__main__":
    main()
