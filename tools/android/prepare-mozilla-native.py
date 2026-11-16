#!/usr/bin/env python3
"""Explicit two-ABI Mozilla build; --verify-only is offline and toolchain-free."""
from __future__ import annotations

import argparse
import gzip
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
ARTIFACT = "mozilla-native-8eada038-r2"
INSTRUCTIONS = "Run pwsh -File tools/android/prepare-mozilla-native.ps1 (Windows/WSL) or python3 tools/android/prepare-mozilla-native.py (Linux)."
PLATFORM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native_inputs(root: Path) -> dict[str, str]:
    # Deliberately excludes the concurrently authored catalog, store and engine.
    paths = list((root / "engine-mozilla/src/main/cpp").rglob("*"))
    paths += list((root / "engine-mozilla/LICENSES").rglob("*"))
    paths += list((root / "engine-mozilla/src/androidTest").rglob("*"))
    paths += [root / name for name in (
        "engine-mozilla/build.gradle.kts", "engine-mozilla/src/main/AndroidManifest.xml",
        "engine-mozilla/consumer-rules.pro", "engine-mozilla/THIRD_PARTY.md",
        "engine-mozilla/src/main/java/io/github/ngoroth/offlinetranslator/mozilla/MozillaNativeRuntime.kt",
        "tools/android/prepare-mozilla-native.py", "tools/android/prepare-mozilla-native.ps1",
        "tools/android/mozilla-native-tools.json", "tools/android/test_mozilla_native_preparation.py")]
    return {path.relative_to(root).as_posix(): sha256(path)
            for path in sorted(paths) if path.is_file()}


def inventory_digest(values: dict[str, str]) -> str:
    return digest("".join(f"{name}\0{value}\n" for name, value in sorted(values.items())).encode())


def tree_digest(root: Path) -> str:
    return inventory_digest({path.relative_to(root).as_posix():
        digest(("symlink:" + os.readlink(path)).encode()) if path.is_symlink() else sha256(path)
        for path in sorted(root.rglob("*")) if path.is_symlink() or path.is_file()})


def elf_inventory(data: bytes, abi: str) -> dict[str, str]:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != {"arm64-v8a": 183, "x86_64": 62}[abi]:
        raise ValueError("Incorrect ELF machine")
    offset = struct.unpack_from("<Q", data, 32)[0]
    width, count = struct.unpack_from("<HH", data, 54)
    if offset < 64 or width < 56 or not count or offset + width * count > len(data):
        raise ValueError("Invalid ELF segment table")
    segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * width) for i in range(count)]
    loads = [segment for segment in segments if segment[0] == 1]
    if not loads or any(s[7] < 16384 or (s[3] - s[2]) % 16384 for s in loads):
        raise ValueError("ELF LOAD lacks 16-KiB alignment")
    dynamic = [s for s in segments if s[0] == 2]
    if len(dynamic) != 1 or dynamic[0][2] + dynamic[0][5] > len(data) or dynamic[0][5] % 16:
        raise ValueError("Invalid ELF dynamic table")
    tags = []
    for position in range(dynamic[0][2], dynamic[0][2] + dynamic[0][5], 16):
        tag, value = struct.unpack_from("<qQ", data, position)
        if tag == 0:
            break
        tags.append((tag, value))
    strings = [value for tag, value in tags if tag == 5]
    sizes = [value for tag, value in tags if tag == 10]
    if len(strings) != 1 or len(sizes) != 1:
        raise ValueError("Missing ELF dynamic strings")
    mapped = [s for s in loads if s[3] <= strings[0] and strings[0] + sizes[0] <= s[3] + s[5]]
    if len(mapped) != 1:
        raise ValueError("Unmapped ELF dynamic strings")
    start = mapped[0][2] + strings[0] - mapped[0][3]
    if start + sizes[0] > len(data):
        raise ValueError("Truncated ELF dynamic strings")
    table = data[start:start + sizes[0]]
    needed = []
    for tag, value in tags:
        if tag != 1:
            continue
        end = table.find(b"\0", value)
        if value >= len(table) or end < 0:
            raise ValueError("Invalid ELF NEEDED string")
        needed.append(table[value:end].decode("ascii"))
    if not set(needed) <= PLATFORM_LIBRARIES:
        raise ValueError("Unapproved dynamic dependency")
    return {"machine": str(machine), "load_alignments": ",".join(str(s[7]) for s in loads),
            "needed": ",".join(sorted(needed))}


def properties(path: Path) -> dict[str, str]:
    lines = [line for line in path.read_text(encoding="ascii").splitlines() if line and not line.startswith("#")]
    values = dict(line.split("=", 1) for line in lines)
    if len(values) != len(lines):
        raise ValueError("Duplicate receipt properties")
    return values


def verify(root: Path = ROOT) -> dict[str, str]:
    try:
        directory = root / "engine-mozilla/libs"
        archive = directory / f"{ARTIFACT}.aar"
        saved = properties(directory / f"{ARTIFACT}.receipt.properties")
        manifest = json.loads((root / "tools/android/mozilla-native-tools.json").read_text())
        current = native_inputs(root)
        inputs = {k.removeprefix("input."): v for k, v in saved.items() if k.startswith("input.")}
        if inputs != current or saved["inputs.sha256"] != inventory_digest(current):
            raise ValueError("Native source/binding/tool/notice/module receipt is stale")
        if saved["schema"] != "1" or saved["mozilla.commit"] != manifest["mozilla_commit"]:
            raise ValueError("Incorrect source pin/schema")
        if saved["abis"] != "arm64-v8a,x86_64":
            raise ValueError("Incorrect ABI receipt")
        if sha256(archive) != saved["aar.sha256"] or str(archive.stat().st_size) != saved["aar.bytes"]:
            raise ValueError("AAR checksum/length mismatch")
        source = directory / f"{ARTIFACT}.sources.tar.gz"
        if sha256(source) != saved["sources.sha256"] or str(source.stat().st_size) != saved["sources.bytes"]:
            raise ValueError("Corresponding-source checksum/length mismatch")
        with zipfile.ZipFile(archive) as zipped:
            members = {info.filename: digest(zipped.read(info)) for info in zipped.infolist()}
            if len(members) != len(zipped.infolist()):
                raise ValueError("Duplicate AAR members")
            expected = {k.removeprefix("member."): v for k, v in saved.items() if k.startswith("member.")}
            if members != expected:
                raise ValueError("Incorrect AAR member inventory")
            if sorted(n for n in members if n.startswith("jni/")) != [
                    f"jni/{abi}/libmozilla_jni.so" for abi in manifest["settings"]["abis"]]:
                raise ValueError("Incorrect native library inventory")
            required = {"assets/mozilla-native/licenses/mozilla-LICENSE", "assets/mozilla-native/licenses/LGPL-2.1",
                        "assets/mozilla-native/THIRD_PARTY.md", "proguard.txt", "AndroidManifest.xml", "classes.jar"}
            required |= {f"assets/mozilla/prefixes/{code}.txt" for code in manifest["prefixes"]}
            if not required <= members.keys():
                raise ValueError("Missing notices or source splitter data")
            for abi in manifest["settings"]["abis"]:
                observed = elf_inventory(zipped.read(f"jni/{abi}/libmozilla_jni.so"), abi)
                if any(saved[f"elf.{abi}.{key}"] != value for key, value in observed.items()):
                    raise ValueError("ELF receipt mismatch")
        return saved
    except (OSError, KeyError, ValueError, struct.error, zipfile.BadZipFile) as error:
        raise RuntimeError(f"Mozilla native verification failed: {error}. {INSTRUCTIONS}") from error


def run(command: list[str], log: Path | None = None) -> str:
    print("+ " + " ".join(map(str, command)), flush=True)
    if log:
        with log.open("w") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text(errors="replace")[-16000:], flush=True)
            raise RuntimeError(f"Command failed ({result.returncode}); full log: {log}")
        return ""
    return subprocess.check_output(command, text=True).strip()


def download(spec: dict, cache: Path) -> Path:
    target = cache / "archives" / spec["url"].rsplit("/", 1)[1]
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        partial = target.with_suffix(target.suffix + ".partial")
        try:
            with urllib.request.urlopen(spec["url"], timeout=60) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
            if sha256(partial) != spec["sha256"] or ("bytes" in spec and partial.stat().st_size != spec["bytes"]):
                raise RuntimeError("Official archive checksum/length mismatch")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    if sha256(target) != spec["sha256"] or ("bytes" in spec and target.stat().st_size != spec["bytes"]):
        raise RuntimeError(f"Cached archive checksum/length mismatch: {target}")
    return target


def tool(spec: dict, cache: Path, installed: Path | None) -> Path:
    # Reuse only a fully attested existing extracted tree; never mutate it.
    base = (installed if installed else cache / "tools").resolve()
    target = base / spec["directory"]
    stamp = base / (spec["directory"] + ".sha256")
    if target.exists():
        if not stamp.is_file() or stamp.read_text().strip() != tree_digest(target):
            raise RuntimeError(f"Pinned extracted tool changed: {target}")
        return target
    if installed:
        raise RuntimeError(f"Read-only installed pinned tool missing: {target}")
    archive = download(spec, cache)
    staging = cache / "tools.partial" / spec["directory"]
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as packed:
            for info in packed.infolist():
                destination = staging / info.filename
                if not destination.resolve().is_relative_to(staging.resolve()):
                    raise RuntimeError("Unsafe tool archive path")
                mode = info.external_attr >> 16
                if info.is_dir():
                    destination.mkdir(parents=True, exist_ok=True)
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                if stat.S_ISLNK(mode):
                    link = packed.read(info).decode()
                    if not (destination.parent / link).resolve().is_relative_to(staging.resolve()):
                        raise RuntimeError("Unsafe tool symlink")
                    destination.symlink_to(link)
                else:
                    destination.write_bytes(packed.read(info))
                    destination.chmod(mode & 0o777 or 0o755)
    else:
        with tarfile.open(archive) as packed:
            packed.extractall(staging, filter="data")
    target.parent.mkdir(parents=True, exist_ok=True)
    nested = staging / spec["directory"]
    if nested.is_dir():
        nested.replace(target)
        staging.rmdir()
    else:
        staging.replace(target)
    stamp.write_text(tree_digest(target) + "\n")
    return target


def export(repository: Path, commit: str, destination: Path, paths: list[str] = ()) -> None:
    if run(["git", "-C", str(repository), "rev-parse", commit + "^{commit}"]) != commit:
        raise RuntimeError("Pinned source commit missing")
    # A partial-clone seed must never lazily fetch into its read-only object store.
    # Missing objects fail closed; omit the seed to fetch into the independent cache.
    packed = subprocess.check_output(
        ["git", "-C", str(repository), "archive", "--format=tar", commit, *paths],
        env={**os.environ, "GIT_NO_LAZY_FETCH": "1"})
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(packed)) as source:
        source.extractall(destination, filter="data")


def source_snapshot(manifest: dict, cache: Path, repository: Path | None) -> Path:
    if repository is None:
        repository = cache / "upstream"
        if not repository.exists():
            run(["git", "clone", "--no-checkout", manifest["mozilla_repository"], str(repository)])
        run(["git", "-C", str(repository), "fetch", "origin", manifest["mozilla_commit"]])
        run(["git", "-C", str(repository), "checkout", "--detach", manifest["mozilla_commit"]])
        for name in manifest["submodules"]:
            parent = max((p for p in manifest["submodules"] if name.startswith(p + "/")), key=len, default="")
            run(["git", "-C", str(repository / parent), "submodule", "update", "--init", "--checkout", "--", name[len(parent):].lstrip("/")])
    destination = cache / "source"
    if destination.exists():
        shutil.rmtree(destination)
    export(repository, manifest["mozilla_commit"], destination, ["inference", "LICENSE", "README.md", ".gitmodules"])
    for name, commit in sorted(manifest["submodules"].items(), key=lambda item: item[0].count("/")):
        parent = max((p for p in manifest["submodules"] if name.startswith(p + "/")), key=len, default="")
        parent_commit = manifest["submodules"].get(parent, manifest["mozilla_commit"])
        relative = name[len(parent):].lstrip("/")
        tree = run(["git", "-C", str(repository / parent), "ls-tree", parent_commit, "--", relative])
        if tree.split()[:3] != ["160000", "commit", commit]:
            raise RuntimeError(f"Submodule pin does not match parent tree: {name}")
        export(repository / name, commit, destination / name)
    return destination


def zip_member(packed: zipfile.ZipFile, name: str, value: bytes) -> None:
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    packed.writestr(info, value)


def corresponding_source(output: Path, source: Path, pcre: Path, inputs: dict[str, str]) -> None:
    with output.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as packed:
            for directory, prefix in ((source, "source"), (pcre, "pcre2-10.49")):
                for path in sorted(directory.rglob("*")):
                    info = packed.gettarinfo(str(path), f"{prefix}/{path.relative_to(directory).as_posix()}")
                    info.uid = info.gid = info.mtime = 0
                    info.uname = info.gname = ""
                    if path.is_file():
                        with path.open("rb") as stream:
                            packed.addfile(info, stream)
                    else:
                        packed.addfile(info)
            for name in sorted(inputs):
                path = ROOT / name
                info = packed.gettarinfo(str(path), "build/" + name)
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                with path.open("rb") as stream:
                    packed.addfile(info, stream)


def notices(source: Path, pcre: Path, ndk: Path) -> dict[str, bytes]:
    third = source / "inference/marian-fork/src/3rd_party"
    files = {"mozilla-LICENSE": source / "LICENSE", "marian-LICENSE": source / "inference/marian-fork/LICENSE.md",
             "ssplit-LICENSE.md": source / "inference/3rd_party/ssplit-cpp/LICENSE.md",
             "prefixes-README.txt": source / "inference/3rd_party/ssplit-cpp/nonbreaking_prefixes/README.txt",
             "PCRE2-LICENCE": pcre / "LICENCE.md", "PCRE2-AUTHORS": pcre / "AUTHORS.md",
             "LGPL-2.1": third / "onnxjs/deps/eigen/COPYING.LGPL"}
    for name in ("NOTICE", "NOTICE.toolchain"):
        files["NDK-" + name] = ndk / name
    # Some SIMD helpers keep the permission notice in the header itself.
    for path in sorted((third / "simd_utils").glob("*.h")):
        files["simd_utils__" + path.name] = path
    for name in ("sse_mathfun.h", "avx_mathfun.h", "threadpool.h", "any_type.h", "reduce_all.h"):
        files["marian-helpers__" + name] = third / name
    dependencies = ["intgemm", "onnxjs", "simd_utils", "ruy", "ruy/third_party/cpuinfo",
                    "ruy/third_party/cpuinfo/deps/clog", "sentencepiece", "spdlog", "yaml-cpp", "pathie-cpp", "CLI"]
    for dependency in dependencies:
        directory = third / dependency
        candidates = sorted({p for p in directory.rglob("*") if p.is_file() and
                             (p.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE")))})
        # Avoid unrelated recursively checked-in test dependencies; exported tree is already bounded.
        if not candidates:
            raise RuntimeError(f"Missing dependency notice: {dependency}")
        for path in candidates:
            files[(dependency + "/" + path.relative_to(directory).as_posix()).replace("/", "__")] = path
    return {"assets/mozilla-native/licenses/" + name: path.read_bytes() for name, path in files.items()}


def prepare(args: argparse.Namespace) -> None:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("Pinned tools require Linux x86_64; use the WSL wrapper")
    manifest = json.loads((ROOT / "tools/android/mozilla-native-tools.json").read_text())
    initial = native_inputs(ROOT)
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    import fcntl
    with (cache / "prepare.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        source = source_snapshot(manifest, cache, args.source_repository)
        receipt = {"schema": "1", "mozilla.commit": manifest["mozilla_commit"],
                   "abis": "arm64-v8a,x86_64", "source.original.sha256": tree_digest(source)}
        receipt.update({"source.submodule." + name: pin for name, pin in manifest["submodules"].items()})
        patches = ROOT / "engine-mozilla/src/main/cpp/patches"
        for patch, cwd in (("production-contract.patch", source), ("model-validation.patch", source),
                           ("exported-source-version.patch", source),
                           ("partial-graph-disposal.patch", source),
                           ("shortlist-empty-row.patch", source),
                           ("sentencepiece-clang20.patch", source / "inference/marian-fork/src/3rd_party/sentencepiece"),
                           ("ruy-allocation.patch", source / "inference/marian-fork/src/3rd_party/ruy")):
            run(["git", "-C", str(cwd), "apply", "--recount", str(patches / patch)])
        receipt["source.patched.sha256"] = tree_digest(source)
        pcre_archive = download(manifest["pcre2"], cache)
        pcre = cache / "pcre2-10.49"
        if pcre.exists():
            shutil.rmtree(pcre)
        with tarfile.open(pcre_archive) as packed:
            packed.extractall(cache, filter="data")
        receipt["source.pcre2.archive.sha256"] = sha256(pcre_archive)
        receipt["source.pcre2.tree.sha256"] = tree_digest(pcre)
        tools = {name: tool(manifest[name], cache, args.installed_tools) for name in ("ndk", "cmake", "ninja")}
        ndk = tools["ndk"]
        cmake = tools["cmake"] / "bin/cmake"
        ninja = tools["ninja"] / "ninja"
        if run([str(cmake), "--version"]).splitlines()[0] != "cmake version 4.2.3" or run([str(ninja), "--version"]) != "1.13.2":
            raise RuntimeError("Pinned tool version mismatch")
        if "Pkg.Revision = 29.0.13113456-beta1" not in (ndk / "source.properties").read_text():
            raise RuntimeError("Pinned NDK revision mismatch")
        for name in tools:
            receipt[f"tool.{name}.archive.sha256"] = manifest[name]["sha256"]
            receipt[f"tool.{name}.tree.sha256"] = tree_digest(tools[name])
            receipt[f"tool.{name}.version"] = manifest[name]["version"]
        members = notices(source, pcre, ndk)
        members["assets/mozilla-native/THIRD_PARTY.md"] = (ROOT / "engine-mozilla/THIRD_PARTY.md").read_bytes()
        for code in manifest["prefixes"]:
            path = source / f"inference/3rd_party/ssplit-cpp/nonbreaking_prefixes/nonbreaking_prefix.{code}"
            members[f"assets/mozilla/prefixes/{code}.txt"] = path.read_bytes()
        for abi in manifest["settings"]["abis"]:
            build = cache / f"build-{abi}"
            command = [str(cmake), "-S", str(ROOT / "engine-mozilla/src/main/cpp"), "-B", str(build), "-G", "Ninja",
                       f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release", f"-DMOZILLA_SOURCE={source}",
                       f"-DMOZILLA_PCRE_SOURCE={pcre}", f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                       f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static"]
            receipt[f"command.{abi}.configure"] = json.dumps(command)
            run(command, cache / f"{abi}-configure.log")
            receipt.update({f"backend.{abi}.{key}": value for key, value in
                            properties(build / "abi-settings.txt").items()})
            for filename in ("CMakeCache.txt", "compile_commands.json"):
                receipt[f"build.{abi}.{filename}.sha256"] = sha256(build / filename)
            run([str(cmake), "--build", str(build), "--target", "mozilla_jni", "-j", "2"], cache / f"{abi}-build.log")
            library = build / "jni/libmozilla_jni.so"
            # Retain the exact linker output for crash symbolication. Strip only
            # a packaging copy, never the sole cached symbol-bearing library.
            receipt[f"symbols.{abi}.unstripped.path"] = str(library)
            receipt[f"symbols.{abi}.unstripped.sha256"] = sha256(library)
            receipt[f"symbols.{abi}.unstripped.bytes"] = str(library.stat().st_size)
            stripped = cache / f"libmozilla_jni.{abi}.stripped.so"
            shutil.copyfile(library, stripped)
            run([str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"), "--strip-unneeded", str(stripped)])
            data = stripped.read_bytes()
            receipt.update({f"elf.{abi}.{key}": value for key, value in elf_inventory(data, abi).items()})
            members[f"jni/{abi}/libmozilla_jni.so"] = data
        members["AndroidManifest.xml"] = (b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            b'package="io.github.ngoroth.offlinetranslator.mozilla.nativecpu"><uses-sdk android:minSdkVersion="28"/></manifest>\n')
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w"):
            pass
        members["classes.jar"] = jar.getvalue()
        members["R.txt"] = b""
        members["proguard.txt"] = (ROOT / "engine-mozilla/consumer-rules.pro").read_bytes()
        if native_inputs(ROOT) != initial:
            raise RuntimeError("Owned native inputs changed during preparation")
        output = ROOT / "engine-mozilla/libs"
        output.mkdir(parents=True, exist_ok=True)
        sources = output / f"{ARTIFACT}.sources.tar.gz.partial"
        corresponding_source(sources, source, pcre, initial)
        receipt.update({"sources.sha256": sha256(sources), "sources.bytes": str(sources.stat().st_size)})
        archive = output / f"{ARTIFACT}.aar.partial"
        with zipfile.ZipFile(archive, "w") as packed:
            for name, value in sorted(members.items()):
                zip_member(packed, name, value)
        receipt.update({"aar.sha256": sha256(archive), "aar.bytes": str(archive.stat().st_size),
                        "inputs.sha256": inventory_digest(initial)})
        receipt.update({"input." + name: value for name, value in initial.items()})
        receipt.update({"member." + name: digest(value) for name, value in members.items()})
        saved = output / f"{ARTIFACT}.receipt.properties.partial"
        saved.write_text("".join(f"{key}={value}\n" for key, value in sorted(receipt.items())), encoding="ascii")
        sources.replace(output / f"{ARTIFACT}.sources.tar.gz")
        archive.replace(output / f"{ARTIFACT}.aar")
        saved.replace(output / f"{ARTIFACT}.receipt.properties")
        verify()
        print(json.dumps({"aar": str(output / f"{ARTIFACT}.aar"), **receipt}, indent=2), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--source-repository", type=Path)
    parser.add_argument("--installed-tools", type=Path)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/offline-translator-mozilla-native-8eada038-r2")
    args = parser.parse_args()
    if args.verify_only:
        print(json.dumps(verify(), indent=2))
    else:
        prepare(args)


if __name__ == "__main__":
    main()
