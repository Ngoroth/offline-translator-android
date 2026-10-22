#!/usr/bin/env python3
"""Explicit Linux CPU SDK/JNI preparation; --verify only reads local distribution bytes."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "engine-nemo"
DEFAULT_OUTPUT = MODULE / ".native-cache/android"
INSTRUCTIONS = ("Explicitly run python3 tools/android/prepare-nemo-native.py "
                "--tools-root <verified-linux-tools> --source-cache <pinned-archives> "
                "--output engine-nemo/.native-cache/android --abis arm64-v8a x86_64 "
                "on Linux/WSL; add --download only to fetch missing source archives. "
                "Ordinary Gradle builds never prepare or download native artifacts.")
JNI_PREFIX = "Java_com_example_offlinetranslator_nemo_NemoNative_"
JNI_EXPORTS = {JNI_PREFIX + name for name in ("create", "warm", "recognize", "destroy")}
C_EXPORTS = {"nemo_speech_asr_" + name for name in (
    "create", "destroy", "recognize_f32", "recognition_options_default", "last_error",
    "result_alternative_count", "result_transcript", "result_destroy")}
PLATFORM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so"}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory_digest(inventory: dict[str, str]) -> str:
    data = "".join(f"{name}\0{value}\n" for name, value in sorted(inventory.items()))
    return hashlib.sha256(data.encode()).hexdigest()


def tree_digest(directory: Path) -> str:
    inventory = {}
    for path in sorted(directory.rglob("*")):
        name = path.relative_to(directory).as_posix()
        if path.is_symlink():
            inventory[name] = hashlib.sha256(("symlink:" + os.readlink(path)).encode()).hexdigest()
        elif path.is_file():
            inventory[name] = sha256(path)
    return inventory_digest(inventory)


def native_inputs() -> dict[str, str]:
    # Bind the native recipe, JNI declarations and packaged attribution, not unrelated Kotlin/DSL edits.
    paths = [MODULE / name for name in (
        "consumer-rules.pro", "native-build.json", "THIRD_PARTY.md",
        "src/main/java/com/example/offlinetranslator/nemo/NemoNative.kt")]
    paths += [ROOT / "tools/android/prepare-nemo-native.py"]
    paths += [path for path in (MODULE / "src/main/cpp").rglob("*") if path.is_file()]
    return {path.relative_to(ROOT).as_posix(): sha256(path) for path in sorted(paths)}


def manifest() -> dict:
    return json.loads((MODULE / "native-build.json").read_text(encoding="utf-8"))


def elf_inventory(path: Path, abi: str, libraries: set[str]) -> dict:
    data = path.read_bytes()
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"Not little-endian ELF64: {path}")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3 or machine != {"arm64-v8a": 183, "x86_64": 62}[abi]:
        raise ValueError(f"Wrong shared-object ABI: {path}")
    phoff, shoff = struct.unpack_from("<QQ", data, 32)
    phsize, phcount, shsize, shcount = struct.unpack_from("<HHHH", data, 54)
    if phsize != 56 or shsize != 64 or not phcount or not shcount:
        raise ValueError(f"Unexpected ELF tables: {path}")
    if phoff + phsize * phcount > len(data) or shoff + shsize * shcount > len(data):
        raise ValueError(f"Truncated ELF tables: {path}")
    alignments = []
    for index in range(phcount):
        entry = phoff + index * phsize
        ptype, flags, offset, address, _, filesz, _, alignment = struct.unpack_from("<IIQQQQQQ", data, entry)
        if offset + filesz > len(data):
            raise ValueError(f"Truncated ELF segment: {path}")
        if ptype == 1:
            if alignment < 16384 or alignment & (alignment - 1) or (address - offset) % 16384:
                raise ValueError(f"ELF LOAD lacks 16 KiB compatibility: {path}")
            alignments.append(alignment)
        if ptype == 0x6474E551 and flags & 1:
            raise ValueError(f"Executable ELF stack: {path}")
    if not alignments:
        raise ValueError(f"ELF has no LOAD segments: {path}")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shsize)
                for index in range(shcount)]

    def section_bytes(index: int) -> bytes:
        section = sections[index]
        offset, size = section[4:6]
        if offset + size > len(data):
            raise ValueError(f"Truncated ELF section: {path}")
        return data[offset:offset + size]

    def string(table: bytes, offset: int) -> str:
        if offset >= len(table):
            raise ValueError("ELF string offset out of bounds")
        end = table.find(b"\0", offset)
        if end < 0:
            raise ValueError("Unterminated ELF string")
        return table[offset:end].decode("utf-8", errors="strict")

    needed, symbols, sonames = set(), set(), []
    for index, section in enumerate(sections):
        if section[1] not in (6, 11):
            continue
        table = section_bytes(section[6])
        entries = section_bytes(index)
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
                name, info, visibility, defined, _, _ = struct.unpack_from("<IBBHQQ", entries, offset)
                if defined and info >> 4 in (1, 2) and visibility & 3 == 0:
                    symbols.add(string(table, name))
    if sonames != [path.name] or not needed or not needed <= libraries | PLATFORM_LIBS:
        raise ValueError(f"Unexpected ELF SONAME/dependency closure for {path}: {sonames}, {sorted(needed)}")
    if path.name == "libnemo_speech_jni.so":
        if {name for name in symbols if name.startswith("Java_")} != JNI_EXPORTS:
            raise ValueError("JNI method export inventory mismatch")
        if "libnemo_speech_asr_c.so" not in needed:
            raise ValueError("JNI does not link the real NeMo C SDK")
    elif path.name == "libnemo_speech_asr_c.so":
        if not C_EXPORTS <= symbols or "libnemo_speech_asr.so" not in needed:
            raise ValueError("Missing real NeMo C ABI exports/dependency")
    return {"machine": machine, "loadAlignments": alignments,
            "needed": sorted(needed), "soname": sonames[0]}


def distribution_files(output: Path) -> dict[str, dict]:
    result = {}
    for directory in ("jniLibs", "assets"):
        root = output / directory
        if not root.is_dir() or root.is_symlink():
            raise ValueError(f"Missing or unsafe distribution directory: {root}")
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Symlink in prepared distribution: {path}")
            if path.is_file():
                result[path.relative_to(output).as_posix()] = {
                    "bytes": path.stat().st_size, "sha256": sha256(path)}
    return result


def verify(output: Path, abis: list[str]) -> dict:
    """No subprocess, network, tool installation or source-cache access on this path."""
    try:
        spec = manifest()
        saved = json.loads((output / "receipt.json").read_text(encoding="utf-8"))
        current = native_inputs()
        if saved["schema"] != 1 or saved["artifact"] != spec["artifact"]:
            raise ValueError("Unexpected native receipt identity")
        if saved["inputs"] != current or saved["inputsSha256"] != inventory_digest(current):
            raise ValueError("Native source/bridge/recipe fingerprint is stale")
        if saved["sources"] != spec["sources"] or saved["tools"] != spec["tools"]:
            raise ValueError("Native source/tool pins differ from approved manifest")
        if saved["settings"] != spec["settings"] or saved["abis"] != sorted(abis):
            raise ValueError("Native ABI/build-settings inventory mismatch")
        files = distribution_files(output)
        if files != saved["files"]:
            raise ValueError("Prepared file inventory/size/SHA-256 mismatch")
        libraries = set(spec["settings"]["libraries"])
        expected = {f"jniLibs/{abi}/{name}" for abi in abis for name in libraries}
        if {name for name in files if name.startswith("jniLibs/")} != expected:
            raise ValueError("Missing or unexpected native libraries/ABIs")
        if not any(name.startswith("assets/nemo-native/licenses/") for name in files):
            raise ValueError("Missing packaged native licenses")
        for abi in abis:
            for name in libraries:
                relative = f"jniLibs/{abi}/{name}"
                if elf_inventory(output / relative, abi, libraries) != saved["elf"][relative]:
                    raise ValueError(f"ELF receipt mismatch: {relative}")
        return saved
    except (OSError, ValueError, KeyError, IndexError, TypeError, struct.error) as error:
        raise RuntimeError(f"NeMo native verification failed: {error}. {INSTRUCTIONS}") from error


def run(command: list[str], work: Path, environment: dict[str, str], log: Path) -> str:
    print("+ " + " ".join(command), flush=True)
    with log.open("w", encoding="utf-8") as stream:
        stream.write(json.dumps(command) + "\n")
        stream.flush()
        result = subprocess.run(command, cwd=work, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}")
    return log.read_text(encoding="utf-8")


def source_archive(cache: Path, spec: dict, download: bool) -> Path:
    path = cache / spec["archive"]
    if not path.exists():
        if not download:
            raise RuntimeError(f"Missing source archive {path}; supply verified bytes or explicitly add --download")
        cache.mkdir(parents=True, exist_ok=True)
        partial = path.with_name(path.name + ".partial")
        try:
            with urllib.request.urlopen(spec["url"], timeout=60) as response, partial.open("wb") as output:
                if response.url.split(":", 1)[0] != "https":
                    raise RuntimeError("Source download redirected away from HTTPS")
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > spec["bytes"]:
                        raise RuntimeError("Source download exceeds pinned byte count")
                    output.write(chunk)
            if partial.stat().st_size != spec["bytes"] or sha256(partial) != spec["sha256"]:
                raise RuntimeError(f"Official source checksum/size mismatch: {path.name}")
            partial.replace(path)
        finally:
            partial.unlink(missing_ok=True)
    if path.is_symlink() or path.stat().st_size != spec["bytes"] or sha256(path) != spec["sha256"]:
        raise RuntimeError(f"Cached source checksum/size mismatch: {path}")
    return path


def prepare(args: argparse.Namespace, abis: list[str]) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64" or sys.version_info < (3, 12):
        raise RuntimeError("Preparation requires Linux x86_64 (WSL supported), Python >=3.12 and supplied pinned tools")
    spec = manifest()
    output, tools, sources = args.output.resolve(), args.tools_root.resolve(), args.source_cache.resolve()
    if (output == ROOT or output == MODULE or output in ROOT.parents or
            output == tools or output == sources or output in tools.parents or output in sources.parents or
            tools in output.parents or sources in output.parents):
        raise RuntimeError("Output must not overlap source/tool locations or a repository root")
    marker = output / ".nemo-native-owner"
    if output.exists() and any(output.iterdir()) and not marker.is_file():
        raise RuntimeError(f"Refusing to modify unowned nonempty output: {output}")
    output.mkdir(parents=True, exist_ok=True)
    if marker.exists() and marker.read_text(encoding="ascii") != spec["artifact"]:
        raise RuntimeError("Unexpected native output ownership marker")
    marker.write_text(spec["artifact"], encoding="ascii")
    # Invalidate publication before any build mutation; partial/failed preparation fails closed.
    (output / "receipt.json").unlink(missing_ok=True)
    paths = {}
    for name, tool in spec["tools"].items():
        directory = tools / tool["directory"]
        print(f"Verifying read-only {name} tree: {directory}", flush=True)
        if not directory.is_dir() or directory.is_symlink() or tree_digest(directory) != tool["treeSha256"]:
            raise RuntimeError(f"Pinned read-only tool tree mismatch: {directory}")
        paths[name] = directory
    ndk = paths["ndk"]
    version = (ndk / "source.properties").read_text(encoding="ascii")
    if f"Pkg.Revision = {spec['tools']['ndk']['version']}" not in version:
        raise RuntimeError("NDK version mismatch")
    cmake = str(paths["cmake"] / "bin/cmake")
    ninja = str(paths["ninja"] / "ninja")
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    archives = {name: source_archive(sources, entry, args.download) for name, entry in spec["sources"].items()}
    work = output / "work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir()
    for name, archive in archives.items():
        with tarfile.open(archive, "r:gz") as packed:
            packed.extractall(work, filter="data")
        if not (work / spec["sources"][name]["directory"]).is_dir():
            raise RuntimeError(f"Archive root mismatch: {name}")
    src = work / spec["sources"]["nemo"]["directory"]
    sp = work / spec["sources"]["sentencepiece"]["directory"]
    (src / "llama.cpp").rmdir()
    (work / spec["sources"]["llama"]["directory"]).rename(src / "llama.cpp")
    cmake_asr = src / "src/asr/CMakeLists.txt"
    content = cmake_asr.read_text(encoding="utf-8")
    anchor = 'set(_NEMO_SPEECH_SENTENCEPIECE_LICENSE_DIR\n'
    if content.count(anchor) != 1 or "target_link_libraries(nemo_speech_asr PRIVATE log)" in content:
        raise RuntimeError("Pinned upstream liblog patch context mismatch")
    cmake_asr.write_text(content.replace(anchor,
        "if(ANDROID)\n    target_link_libraries(nemo_speech_asr PRIVATE log)\nendif()\n" + anchor), encoding="utf-8")
    # Preserve upstream materialization/series, not a separate patch/decoder implementation.
    patches = {path.name: sha256(path) for path in sorted((src / "patches").iterdir()) if path.is_file()}
    environment = os.environ.copy()
    for name in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CMAKE_PREFIX_PATH", "CMAKE_TOOLCHAIN_FILE"):
        environment.pop(name, None)
    environment["GIT_CEILING_DIRECTORIES"] = str(work)
    environment["CMAKE_BUILD_PARALLEL_LEVEL"] = "1"
    logs = output / "logs"
    logs.mkdir(exist_ok=True)
    libraries = set(spec["settings"]["libraries"])
    elf = {}
    for abi in abis:
        common = ["-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=" + ninja, "-DCMAKE_BUILD_TYPE=Release",
                  "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                  "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                  "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
                  "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--exclude-libs,libc++_static.a:libc++abi.a",
                  "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"]
        spbuild = work / ("sp-" + abi)
        run([cmake, "-S", str(sp), "-B", str(spbuild)] + common + [
            "-DSPM_BUILD_TEST=OFF", "-DSPM_ENABLE_SHARED=OFF", "-DSPM_ENABLE_TCMALLOC=OFF"],
            work, environment, logs / ("sp-config-" + abi + ".log"))
        run([cmake, "--build", str(spbuild), "--target", "sentencepiece-static", "-j", "1"],
            work, environment, logs / ("sp-build-" + abi + ".log"))
        sdkbuild = work / ("sdk-" + abi)
        flags = ["-DNEMO_SPEECH_BUILD_ASR=ON", "-DNEMO_SPEECH_GGML_PATCHED=ON", "-DBUILD_SHARED_LIBS=ON",
                 "-DSENTENCEPIECE_STATIC_LIB=" + str(spbuild / "src/libsentencepiece.a"),
                 "-DSENTENCEPIECE_INCLUDE_DIR=" + str(sp / "src"), "-DBUILD_TESTING=OFF"]
        flags += ["-DNEMO_SPEECH_BUILD_" + name + "=OFF" for name in (
            "DIAR", "TTS", "NMT", "S2S", "CLI", "MIC_CAPTURE", "HTTP", "GRPC", "EXAMPLES", "TESTS", "TOOLS")]
        flags += ["-DGGML_" + name + "=OFF" for name in (
            "CUDA", "VULKAN", "METAL", "BLAS", "OPENMP", "NATIVE", "AVX", "AVX2", "AVX_VNNI",
            "AVX512", "AVX512_VBMI", "AVX512_VNNI", "FMA", "F16C", "CPU_KLEIDIAI")]
        if abi == "arm64-v8a":
            flags += ["-DGGML_CPU_ARM_ARCH=armv8-a"]
        run([cmake, "-S", str(src), "-B", str(sdkbuild)] + common + flags,
            work, environment, logs / ("sdk-config-" + abi + ".log"))
        run([cmake, "--build", str(sdkbuild), "--target", "nemo_speech_asr_c", "-j", "1"],
            work, environment, logs / ("sdk-build-" + abi + ".log"))
        bridge = work / ("jni-" + abi)
        run([cmake, "-S", str(MODULE / "src/main/cpp"), "-B", str(bridge)] + common + [
            "-DNEMO_SDK_LIB_DIR=" + str(sdkbuild / "bin"), "-DNEMO_SDK_INCLUDE_DIR=" + str(src / "include")],
            work, environment, logs / ("jni-config-" + abi + ".log"))
        run([cmake, "--build", str(bridge), "--target", "nemo_speech_jni", "-j", "1"],
            work, environment, logs / ("jni-build-" + abi + ".log"))
        destination = output / "jniLibs" / abi
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for name in sorted(libraries):
            source = bridge / name if name == "libnemo_speech_jni.so" else sdkbuild / "bin" / name
            target = destination / name
            shutil.copyfile(source, target)
            run([str(llvm / "llvm-strip"), "--strip-debug", str(target)],
                work, environment, logs / ("strip-" + abi + "-" + name + ".log"))
            elf[f"jniLibs/{abi}/{name}"] = elf_inventory(target, abi, libraries)
    licenses = output / "assets/nemo-native/licenses"
    if (output / "assets").exists():
        shutil.rmtree(output / "assets")
    licenses.mkdir(parents=True)
    notice_sources = {
        "NeMo-LICENSE": src / "LICENSE", "NeMo-NOTICE": src / "NOTICE",
        "NeMo-THIRD_PARTY_NOTICES.md": src / "THIRD_PARTY_NOTICES.md",
        "llama-LICENSE": src / "llama.cpp/LICENSE", "SentencePiece-LICENSE": sp / "LICENSE",
        "Abseil-LICENSE": sp / "third_party/absl/LICENSE",
        "Darts-clone-LICENSE": sp / "third_party/darts_clone/LICENSE",
        "Protobuf-lite-LICENSE": sp / "third_party/protobuf-lite/LICENSE",
        "esaxx-LICENSE": sp / "third_party/esaxx/LICENSE",
        "libcxx-NOTICE": llvm.parent / "NOTICE",
    }
    for name, source in notice_sources.items():
        shutil.copyfile(source, licenses / name)
    shutil.copyfile(MODULE / "THIRD_PARTY.md", licenses / "APPLICATION-NOTICE.md")
    inputs = native_inputs()
    saved = {"schema": 1, "artifact": spec["artifact"], "abis": sorted(abis),
             "sources": spec["sources"], "tools": spec["tools"], "settings": spec["settings"],
             "inputs": inputs, "inputsSha256": inventory_digest(inputs), "upstreamPatches": patches,
             "androidPatch": "Exactly three Android-only liblog linkage lines in src/asr/CMakeLists.txt",
             "elf": elf, "files": distribution_files(output)}
    partial = output / "receipt.json.partial"
    partial.write_text(json.dumps(saved, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    partial.replace(output / "receipt.json")
    verify(output, abis)
    print(f"Verified native distribution: {output}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Read-only; never invokes a tool or uses network")
    parser.add_argument("--tools-root", type=Path, help="Read-only directory containing the pinned Linux tools")
    parser.add_argument("--source-cache", type=Path, help="Directory containing pinned source .tar.gz archives")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--abis", nargs="+", choices=("arm64-v8a", "x86_64"), default=["arm64-v8a", "x86_64"])
    parser.add_argument("--download", action="store_true", help="Explicitly fetch missing sources only; never installs tools")
    args = parser.parse_args()
    abis = sorted(set(args.abis))
    if len(abis) != len(args.abis):
        parser.error("Duplicate ABI")
    if args.verify:
        if args.download:
            parser.error("--verify cannot download")
        verify(args.output, abis)
        print(f"Verified NeMo native artifacts: {args.output}")
    else:
        if args.tools_root is None or args.source_cache is None:
            parser.error("Explicit --tools-root and --source-cache are required for preparation")
        prepare(args, abis)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, tarfile.TarError, struct.error) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
