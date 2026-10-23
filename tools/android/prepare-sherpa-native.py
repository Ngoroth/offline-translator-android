#!/usr/bin/env python3
"""Explicit Linux/WSL Sherpa preparation; --verify reads local bytes only."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "engine-sherpa"
DEFAULT_OUTPUT = MODULE / ".native-cache/android"
ABIS = ["arm64-v8a", "x86_64"]
PLATFORM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
INSTRUCTIONS = ("Explicitly run python3 tools/android/prepare-sherpa-native.py "
                "--tools-root <verified-linux-tools> --source-cache <owned-source-cache> "
                "--output engine-sherpa/.native-cache/android --abis arm64-v8a x86_64 "
                "on Linux/WSL; add --download only to acquire missing pinned inputs. "
                "Ordinary Gradle builds never download, repair or compile native artifacts.")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inventory_digest(inventory: dict) -> str:
    return digest(json.dumps(inventory, sort_keys=True, separators=(",", ":")).encode())


def tree_digest(directory: Path) -> str:
    # Same tool-tree identity convention as prepare-nemo-native.py.
    rows = {}
    for path in sorted(directory.rglob("*")):
        name = path.relative_to(directory).as_posix()
        if path.is_symlink():
            rows[name] = digest(("symlink:" + os.readlink(path)).encode())
        elif path.is_file():
            rows[name] = sha256(path)
    return digest("".join(f"{name}\0{value}\n" for name, value in sorted(rows.items())).encode())


def manifest() -> dict:
    return json.loads((MODULE / "native-build.json").read_text(encoding="utf-8"))


def native_inputs() -> dict:
    # Documentation/UI edits must not force expensive native recompilation.
    paths = [MODULE / "native-build.json", MODULE / "native/whisper-language-pair.patch",
             ROOT / "tools/android/prepare-sherpa-native.py",
             ROOT / "LICENSES/onnxruntime-MIT.txt", ROOT / "LICENSES/onnxruntime-ThirdPartyNotices.txt"]
    return {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths}


def checked_file(path: Path, pin: dict) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Missing or unsafe pinned input: {path}")
    if "bytes" in pin and path.stat().st_size != pin["bytes"]:
        raise ValueError(f"Pinned input size mismatch: {path}")
    if sha256(path) != pin["sha256"]:
        raise ValueError(f"Pinned input SHA-256 mismatch: {path}")


def source_archive(cache: Path, pin: dict, download: bool) -> Path:
    path = cache / pin["archive"]
    if path.is_symlink():
        raise ValueError(f"Unsafe pinned archive symlink: {path}")
    if not path.exists():
        if not download:
            raise ValueError(f"Missing {path}; supply pinned bytes or explicitly add --download")
        if not pin["url"].startswith("https://"):
            raise ValueError("Only HTTPS source acquisition is permitted")
        cache.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=cache)
        partial = Path(temporary)
        try:
            with os.fdopen(fd, "wb") as target, urllib.request.urlopen(pin["url"], timeout=60) as response:
                if not response.url.startswith("https://"):
                    raise ValueError("Download redirected away from HTTPS")
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > pin.get("bytes", 256 * 1024 * 1024):
                        raise ValueError("Download exceeds pinned/safety size limit")
                    target.write(chunk)
            checked_file(partial, pin)
            os.replace(partial, path)
        finally:
            partial.unlink(missing_ok=True)
    checked_file(path, pin)
    return path


def safe_name(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if not path.parts or not name or "\\" in name or path.is_absolute() or any(p in ("..", "") or ":" in p for p in path.parts):
        raise ValueError(f"Unsafe archive path: {name}")
    return path


def extract_archive(archive: Path, destination: Path, directory: str) -> Path:
    """Copy regular source files/directories; omit links without resolving their targets."""
    destination.mkdir(parents=True, exist_ok=False)
    seen = set()
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as packed:
            for entry in packed.infolist():
                name = safe_name(entry.orig_filename)
                mode = entry.external_attr >> 16
                if (directory != "." and name.parts[0] != directory) or str(name) in seen:
                    raise ValueError(f"Unsafe/duplicate ZIP member: {entry.filename}")
                seen.add(str(name))
                if stat.S_ISLNK(mode):
                    continue
                if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                    raise ValueError(f"Special ZIP member: {entry.filename}")
                target = destination.joinpath(*name.parts)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with packed.open(entry) as source, target.open("xb") as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(0o755 if mode & 0o111 else 0o644)
    else:
        with tarfile.open(archive, "r:*") as packed:
            for entry in packed:
                name = safe_name(entry.name)
                if (directory != "." and name.parts[0] != directory) or str(name) in seen:
                    raise ValueError(f"Unsafe/duplicate TAR member: {entry.name}")
                seen.add(str(name))
                if entry.issym() or entry.islnk():
                    continue
                if not (entry.isdir() or entry.isfile()):
                    raise ValueError(f"Special TAR member: {entry.name}")
                target = destination.joinpath(*name.parts)
                if entry.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with packed.extractfile(entry) as source, target.open("xb") as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(0o755 if entry.mode & 0o111 else 0o644)
    result = destination / directory
    if not result.is_dir():
        raise ValueError(f"Missing archive root {directory}")
    return result


def apply_patch(source: Path, patch: Path, preimages: dict) -> None:
    """Apply the checked-in unified diff at exact line positions, with no offset/fuzz."""
    rows = patch.read_text(encoding="utf-8").splitlines(keepends=True)
    index, touched = 0, set()
    while index < len(rows):
        if not rows[index].startswith("--- a/") or index + 1 >= len(rows):
            raise ValueError("Malformed patch file header")
        name = rows[index][6:].rstrip("\n")
        safe_name(name)
        if rows[index + 1] != "+++ b/" + name + "\n" or name not in preimages or name in touched:
            raise ValueError("Unexpected/duplicate native patch target")
        target = source / name
        checked_file(target, {"sha256": preimages[name]})
        original = target.read_text(encoding="utf-8").splitlines(keepends=True)
        output, cursor = [], 0
        index += 2
        while index < len(rows) and rows[index].startswith("@@ "):
            match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n", rows[index])
            if not match:
                raise ValueError("Malformed patch hunk")
            start, old_count, new_start, new_count = (int(match[1]), int(match[2] or 1), int(match[3]), int(match[4] or 1))
            position = start - 1 if old_count else start
            if position < cursor or position > len(original):
                raise ValueError("Overlapping/out-of-range patch hunk")
            output.extend(original[cursor:position]); cursor = position
            if len(output) != (new_start - 1 if new_count else new_start):
                raise ValueError("Native patch output line mismatch")
            index += 1
            consumed = produced = 0
            while index < len(rows) and rows[index][:1] in (" ", "+", "-") and not rows[index].startswith("--- a/"):
                row = rows[index]; index += 1
                if row[0] in " -":
                    if cursor >= len(original) or original[cursor] != row[1:]:
                        raise ValueError(f"Native patch preimage mismatch: {name}")
                    cursor += 1; consumed += 1
                if row[0] in " +":
                    output.append(row[1:]); produced += 1
            if (consumed, produced) != (old_count, new_count):
                raise ValueError("Native patch hunk length mismatch")
        output.extend(original[cursor:])
        target.write_text("".join(output), encoding="utf-8", newline="\n")
        touched.add(name)
    if touched != set(preimages):
        raise ValueError("Native patch does not cover exactly six pinned files")


def elf_inventory(data: bytes, name: str, abi: str, libraries: set[str]) -> dict:
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"Not little-endian ELF64: {name}")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3 or machine != {"arm64-v8a": 183, "x86_64": 62}[abi]:
        raise ValueError(f"Wrong shared-object ABI: {name}")
    phoff, shoff = struct.unpack_from("<QQ", data, 32)
    phsize, phcount, shsize, shcount = struct.unpack_from("<HHHH", data, 54)
    if phsize != 56 or shsize != 64 or not phcount or not shcount or phoff + phsize * phcount > len(data) or shoff + shsize * shcount > len(data):
        raise ValueError("Truncated/unexpected ELF tables")
    alignments = []
    for index in range(phcount):
        ptype, flags, offset, address, _, filesz, _, alignment = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
        if offset + filesz > len(data):
            raise ValueError("Truncated ELF segment")
        if ptype == 1:
            if alignment < 16384 or alignment & (alignment - 1) or (address - offset) % 16384:
                raise ValueError("ELF LOAD lacks 16 KiB compatibility")
            alignments.append(alignment)
        if ptype == 0x6474E551 and flags & 1:
            raise ValueError("Executable ELF stack")
    if not alignments:
        raise ValueError("ELF has no LOAD segments")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shsize) for index in range(shcount)]

    def section_bytes(index):
        offset, size = sections[index][4:6]
        if offset + size > len(data):
            raise ValueError("Truncated ELF section")
        return data[offset:offset + size]

    def string(table, offset):
        end = table.find(b"\0", offset)
        if offset >= len(table) or end < 0:
            raise ValueError("Invalid ELF string")
        return table[offset:end].decode("utf-8")

    needed, exports, sonames = set(), set(), []
    for index, section in enumerate(sections):
        if section[1] not in (6, 11):
            continue
        table, entries = section_bytes(section[6]), section_bytes(index)
        width = 16 if section[1] == 6 else 24
        if section[9] != width or len(entries) % width:
            raise ValueError("Unexpected ELF dynamic entry width")
        for offset in range(0, len(entries), width):
            if section[1] == 6:
                tag, value = struct.unpack_from("<qQ", entries, offset)
                if tag == 1:
                    needed.add(string(table, value))
                elif tag == 14:
                    sonames.append(string(table, value))
            else:
                symbol, info, visibility, defined, _, _ = struct.unpack_from("<IBBHQQ", entries, offset)
                if defined and info >> 4 in (1, 2) and visibility & 3 == 0:
                    exports.add(string(table, symbol))
    if sonames != [name] or not needed or not needed <= libraries | PLATFORM_LIBS:
        raise ValueError(f"Unexpected ELF SONAME/dependency closure: {name}, {sonames}, {sorted(needed)}")
    return {"machine": machine, "loadAlignments": alignments, "soname": sonames[0],
            "needed": sorted(needed), "exports": sorted(exports)}


def aar_entries(path: Path) -> dict[str, bytes]:
    result = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            safe_name(entry.filename)
            if entry.filename in result or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Duplicate/unsafe AAR entry")
            if not entry.is_dir():
                result[entry.filename] = archive.read(entry)
    return result


def official_archive(spec: dict) -> Path:
    path = MODULE / "libs" / spec["officialAar"]["archive"]
    checked_file(path, spec["officialAar"])
    return path


def distribution_inventory(archive: Path, official: Path, spec: dict) -> dict:
    actual, stock = aar_entries(archive), aar_entries(official)
    libraries = set(spec["settings"]["libraries"])
    native = {f"jni/{abi}/{name}" for abi in ABIS for name in libraries}
    if {name for name in actual if name.startswith("jni/")} != native:
        raise ValueError("Missing, unexpected or 32-bit native AAR members")
    non_native = {name: data for name, data in stock.items() if not name.startswith("jni/")}
    if any(actual.get(name) != data for name, data in non_native.items()):
        raise ValueError("Official non-native AAR bytes changed")
    extra = set(actual) - native - set(non_native)
    if not extra or any(not name.startswith("assets/sherpa-native/licenses/") for name in extra):
        raise ValueError("Missing notices or unexpected non-native AAR additions")
    elf = {}
    for abi in ABIS:
        for name in sorted(libraries):
            key = f"jni/{abi}/{name}"
            data, original = actual[key], stock[key]
            inventory = elf_inventory(data, name, abi, libraries)
            baseline = elf_inventory(original, name, abi, libraries)
            if name == "libonnxruntime.so":
                if data != original:
                    raise ValueError("ORT differs from the exact official per-ABI library")
            else:
                if data == original:
                    raise ValueError("Mixed stock/patched native closure")
                required = ("libsherpa-onnx-c-api.so" if name == "libsherpa-onnx-cxx-api.so"
                            else "libonnxruntime.so")
                if required not in inventory["needed"]:
                    raise ValueError(f"Missing required native dependency {required}: {key}")
                public = {s for s in baseline["exports"] if s.startswith(("SherpaOnnx", "Java_")) or "sherpa_onnx" in s}
                if not public or not public <= set(inventory["exports"]):
                    raise ValueError(f"Missing existing public API exports: {key}")
                # Deliberate retained capability metadata, not optimizer-dependent error literals.
                if name != "libsherpa-onnx-cxx-api.so" and spec["feature"].encode() + b"\0" not in data:
                    raise ValueError(f"Missing paired native implementation marker: {key}")
            elf[key] = inventory
    return {"files": {name: {"bytes": len(data), "sha256": digest(data)} for name, data in sorted(actual.items())}, "elf": elf}


def verify(output: Path, abis: list[str]) -> dict:
    """Read-only, portable: no subprocess/network/source cache or installed tool required."""
    try:
        spec = manifest()
        if sorted(abis) != ABIS:
            raise ValueError("Both supported ABIs are required")
        if output.is_symlink() or not output.is_dir():
            raise ValueError("Missing/unsafe prepared native directory")
        for name in ("receipt.json", ".sherpa-native-owner"):
            if (output / name).is_symlink():
                raise ValueError("Unsafe native receipt/ownership symlink")
        saved = json.loads((output / "receipt.json").read_text(encoding="utf-8"))
        if (output / ".sherpa-native-owner").read_text(encoding="ascii") != spec["artifact"]:
            raise ValueError("Native output ownership marker mismatch")
        expected = {"schema": 1, "artifact": spec["artifact"], "feature": spec["feature"],
                    "abis": ABIS, "inputs": native_inputs(), "pins": spec}
        if any(saved.get(key) != value for key, value in expected.items()):
            raise ValueError("Stale native recipe/source/patch/tool/input identity")
        archive = output / (spec["artifact"] + ".aar")
        checked_file(archive, saved["archive"])
        actual = distribution_inventory(archive, official_archive(spec), spec)
        if actual != saved["distribution"]:
            raise ValueError("Actual native/AAR bytes or ELF inventories differ from receipt")
        return saved
    except (OSError, ValueError, KeyError, IndexError, TypeError, struct.error, zipfile.BadZipFile) as error:
        raise RuntimeError(f"Sherpa native verification failed: {error}. {INSTRUCTIONS}") from error


@contextmanager
def publication(output: Path, artifact: str):
    """Build off-path; preserve the last good publication on any preparation failure."""
    output.parent.mkdir(parents=True, exist_ok=True)
    lock = output.with_name(output.name + ".prepare.lock")
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    stage = None
    try:
        os.close(descriptor)
        if output.is_symlink():
            raise ValueError("Unsafe output symlink")
        if output.exists() and any(output.iterdir()):
            marker = output / ".sherpa-native-owner"
            if not marker.is_file() or marker.is_symlink() or marker.read_text(encoding="ascii") != artifact:
                raise ValueError("Refusing to replace unowned native output")
        stage = Path(tempfile.mkdtemp(prefix=output.name + ".stage-", dir=output.parent))
        (stage / ".sherpa-native-owner").write_text(artifact, encoding="ascii")
        yield stage
        backup = output.with_name(output.name + ".previous")
        if backup.exists() or backup.is_symlink():
            raise ValueError(f"Prior interrupted publication requires manual recovery: {backup}")
        if output.exists():
            os.replace(output, backup)
        try:
            os.replace(stage, output)
        except BaseException:
            if backup.exists():
                os.replace(backup, output)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    finally:
        if stage is not None and stage.exists():
            shutil.rmtree(stage)
        lock.unlink(missing_ok=True)


def run(command: list[str], work: Path, environment: dict, log: Path) -> str:
    print("+ " + " ".join(command), flush=True)
    with log.open("w", encoding="utf-8") as stream:
        stream.write(json.dumps(command) + "\n"); stream.flush()
        result = subprocess.run(command, cwd=work, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Native command failed ({result.returncode}); see {log}")
    return log.read_text(encoding="utf-8")


def notice_files(sources: dict[str, Path], llvm: Path) -> dict[str, bytes]:
    notices = {}
    # Keep source-supplied license/notice texts, including nested bundled components.
    for component, source in sources.items():
        if component == "onnxruntime":
            # This exact Android header ZIP supplies no license entries.
            continue
        found = False
        for path in sorted(source.rglob("*")):
            if path.is_file() and not path.is_symlink() and any(
                    part.upper().startswith(("LICENSE", "COPYING", "NOTICE", "COPYRIGHT"))
                    for part in path.relative_to(source).parts):
                notices[f"assets/sherpa-native/licenses/{component}/{path.relative_to(source).as_posix()}"] = path.read_bytes()
                found = True
        if not found:
            raise ValueError(f"No source license/notice material found for {component}")
    for name in ("onnxruntime-MIT.txt", "onnxruntime-ThirdPartyNotices.txt"):
        notices["assets/sherpa-native/licenses/onnxruntime/" + name] = (ROOT / "LICENSES" / name).read_bytes()
    notices["assets/sherpa-native/licenses/libcxx-NOTICE"] = (llvm.parent / "NOTICE").read_bytes()
    return notices


def package_distribution(stage: Path, spec: dict, official: Path, stock: dict,
                         replacements: dict, notices: dict) -> None:
    """Publish only the complete rebuilt closure through the same post-build verification."""
    archive = stage / (spec["artifact"] + ".aar")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as packed:
        for name, data in sorted(stock.items()):
            if name.startswith("jni/") and name.split("/")[1] not in ABIS:
                continue
            packed.writestr(name, replacements.get(name, data))
        for name, data in sorted(notices.items()):
            packed.writestr(name, data)
    distribution = distribution_inventory(archive, official, spec)
    saved = {"schema": 1, "artifact": spec["artifact"], "feature": spec["feature"], "abis": ABIS,
             "inputs": native_inputs(), "pins": spec,
             "archive": {"bytes": archive.stat().st_size, "sha256": sha256(archive)},
             "distribution": distribution}
    (stage / "receipt.json").write_text(json.dumps(saved, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    verify(stage, ABIS)


def prepare(args: argparse.Namespace, abis: list[str]) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64" or sys.version_info < (3, 12):
        raise RuntimeError("Preparation requires Linux x86_64/WSL and Python >=3.12")
    if abis != ABIS:
        raise ValueError("Preparation must rebuild BOTH arm64-v8a and x86_64")
    spec = manifest()
    if args.output.is_symlink():
        raise ValueError("Unsafe output symlink")
    output, tools, cache = args.output.resolve(), args.tools_root.resolve(), args.source_cache.resolve()
    if cache == ROOT or ROOT in cache.parents or cache in ROOT.parents:
        raise ValueError("Source cache must be outside the checkout")
    if any(output == path or output in path.parents or path in output.parents for path in (tools, cache)) or output == MODULE or output == ROOT or output in ROOT.parents:
        raise ValueError("Output must not overlap source/tools or repository roots")
    paths = {}
    for name, pin in spec["tools"].items():
        path = tools / pin["directory"]
        print(f"Verifying read-only {name}: {path}", flush=True)
        if path.is_symlink() or not path.is_dir() or tree_digest(path) != pin["treeSha256"]:
            raise ValueError(f"Pinned tool tree mismatch: {path}")
        paths[name] = path
    ndk = paths["ndk"]
    if "Pkg.Revision = " + spec["tools"]["ndk"]["version"] not in (ndk / "source.properties").read_text():
        raise ValueError("NDK version mismatch")
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    cmake, ninja = str(paths["cmake"] / "bin/cmake"), str(paths["ninja"] / "ninja")
    # The official AAR remains explicit input, never an ordinary build fallback.
    official = official_archive(spec)
    archives = {name: source_archive(cache, pin, args.download) for name, pin in {
        "sherpa": spec["source"], "onnxruntime": spec["ortHeaders"], **spec["dependencies"]}.items()}
    pins = {"sherpa": spec["source"], "onnxruntime": spec["ortHeaders"], **spec["dependencies"]}
    work = Path(tempfile.mkdtemp(prefix="sherpa-pair-build-", dir=cache))
    compiler = subprocess.run([str(llvm / "clang"), "--version"], check=True, capture_output=True, text=True)
    if not re.search(r"\bclang version " + str(spec["settings"]["clangMajor"]) + r"\.", compiler.stdout):
        raise ValueError("Pinned NDK Clang major mismatch")
    print(f"Owned work and persistent failure logs: {work}", flush=True)
    sources = {name: extract_archive(archive, work / name, pins[name]["directory"]) for name, archive in archives.items()}
    source, ort = sources["sherpa"], sources["onnxruntime"]
    apply_patch(source, MODULE / "native/whisper-language-pair.patch", spec["patchPreimages"])
    stock = aar_entries(official)
    for abi in ABIS:
        # Only headers come from the ORT release ZIP; link the exact stock AAR library.
        target = ort / f"jni/{abi}/libonnxruntime.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(stock[f"jni/{abi}/libonnxruntime.so"])
    environment = {key: value for key, value in os.environ.items() if key not in (
        "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CMAKE_PREFIX_PATH", "CMAKE_TOOLCHAIN_FILE")}
    environment["CMAKE_BUILD_PARALLEL_LEVEL"] = "1"
    environment["GIT_CEILING_DIRECTORIES"] = str(work)
    logs = work / "logs"; logs.mkdir()
    with publication(output, spec["artifact"]) as stage:
        replacements = {}
        for abi in ABIS:
            build = work / ("build-" + abi)
            flags = ["-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=" + ninja, "-DCMAKE_BUILD_TYPE=Release",
                     "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                     "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                     "-DBUILD_SHARED_LIBS=ON", "-DBUILD_TESTING=OFF", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
                     "-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF",
                     "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                     "-DCMAKE_POLICY_VERSION_MINIMUM=3.10"]
            flags += ["-DSHERPA_ONNX_ENABLE_" + name + "=OFF" for name in (
                "PYTHON", "TESTS", "CHECK", "PORTAUDIO", "WEBSOCKET", "BINARY", "GPU", "DIRECTML",
                "RKNN", "AXERA", "AXCL", "ASCEND_NPU", "QNN", "SPACEMIT")]
            flags += ["-DSHERPA_ONNX_ENABLE_" + name + "=ON" for name in ("JNI", "C_API", "TTS", "SPEAKER_DIARIZATION")]
            flags += ["-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF", "-DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF",
                      "-DBUILD_PIPER_PHONMIZE_EXE=OFF", "-DBUILD_PIPER_PHONMIZE_TESTS=OFF",
                      "-DBUILD_ESPEAK_NG_EXE=OFF", "-DBUILD_ESPEAK_NG_TESTS=OFF"]
            flags += [f"-DFETCHCONTENT_SOURCE_DIR_{name.upper()}={path}" for name, path in sources.items() if name != "sherpa"]
            run([cmake, "-S", str(source), "-B", str(build)] + flags, work, environment, logs / (abi + "-configure.log"))
            run([cmake, "--build", str(build), "--target", "sherpa-onnx-c-api", "sherpa-onnx-cxx-api", "sherpa-onnx-jni", "-j", "1"],
                work, environment, logs / (abi + "-build.log"))
            for name in spec["settings"]["rebuiltLibraries"]:
                path = build / "lib" / name
                run([str(llvm / "llvm-strip"), "--strip-debug", str(path)], work, environment, logs / (abi + "-" + name + "-strip.log"))
                replacements[f"jni/{abi}/{name}"] = path.read_bytes()
        package_distribution(stage, spec, official, stock, replacements, notice_files(sources, llvm))
    verify(output, ABIS)
    print(f"Verified both-ABI native artifact: {output}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Read-only; no subprocess, downloads or installed tools")
    parser.add_argument("--tools-root", type=Path)
    parser.add_argument("--source-cache", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--abis", nargs="+", choices=ABIS, default=ABIS)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    abis = sorted(args.abis)
    if abis != ABIS:
        parser.error("Supply each supported ABI exactly once: arm64-v8a x86_64")
    if args.verify:
        if args.download or args.tools_root or args.source_cache:
            parser.error("--verify cannot download, access source cache or use tools")
        verify(args.output, abis)
        print(f"Verified Sherpa native artifact: {args.output}")
    else:
        if args.tools_root is None or args.source_cache is None:
            parser.error("Explicit --tools-root and --source-cache are required")
        prepare(args, abis)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, tarfile.TarError, zipfile.BadZipFile,
            struct.error, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
