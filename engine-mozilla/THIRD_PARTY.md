# Mozilla native translation

This module adds an optional translation provider. It does not change the application's license. No model weights are bundled in the native AAR.

## Source and compiled closure

The maintained Mozilla translations source is pinned to `8eada038fd45bbf6fa58a412b22f80c79887fabc` at <https://github.com/mozilla/translations>. The inference wrapper is MPL-2.0; its Marian fork retains its MIT notice. The exact source submodule revisions, official tool archive digests, PCRE2 archive identity, original/patched source trees, application-owned input hashes, commands and AAR member hashes are in `libs/mozilla-native-8eada038-r2.receipt.properties`.

The static closure contains Mozilla inference, Marian, ssplit-cpp (Apache-2.0 code), SentencePiece (Apache-2.0 and its bundled Abseil/Protobuf/Darts/ESAXX notices), intgemm (MIT, x86_64), ONNX.js SGEMM (MIT), Eigen with `EIGEN_MPL2_ONLY` (MPL-2.0 and permissive notices), yaml-cpp (MIT), spdlog/fmt (MIT/BSD notices), Pathie (BSD-2-Clause), CLI11 (BSD-3-Clause), PCRE2 10.49 (BSD-3-Clause), and the NDK static libc++/libc++abi/unwind runtime (Apache-2.0 WITH LLVM-exception and retained runtime notices). ARM additionally compiles Ruy (Apache-2.0), cpuinfo and clog (BSD-2-Clause), and SIMD_Utils (BSD-2-Clause, including its bundled SIMD helper copyright notices). It does not use an ONNX Runtime Android package, CUDA, MKL, OpenMP, FBGEMM, a native AsyncService, or model downloads during ordinary Gradle builds.

Original full license/notice files from the exact exported sources and NDK are bundled as `assets/mozilla-native/licenses/` in the prepared AAR. Redistribution must retain them, this notice, the native receipt, and access to the corresponding-source distribution.

## Source splitter data

The unmodified upstream `nonbreaking_prefix.{en,es,it,zh,de,pl,pt,ru,fr,nl}` data is bundled as `assets/mozilla/prefixes/<appcode>.txt`. This data was copied upstream from Moses and is licensed LGPL-2.1; it is read as replaceable data, not compiled into the native library. The AAR includes the ssplit attribution, prefix README, full LGPL-2.1 text and exact member hashes. Chinese app code `zh` uses the upstream `zh` prefix file. For `vi,id,ko,th,tr,hi,ja`, upstream ships no corresponding prefix file; the provider explicitly supplies an empty file, preserving upstream regex-only segmentation. No invented prefix lists are substituted.

## Corresponding source and reproducibility

`libs/mozilla-native-8eada038-r2.sources.tar.gz` contains the exact modified MPL-covered source, all exported compiled dependency sources (including ARM), PCRE2, replaceable prefix data, production patches and this module's native/binding/tool/notice/build inputs. The receipt records its SHA-256 and length. Make this archive available alongside any redistributed binaries; a GitHub source link alone is not the corresponding-source handoff.

Explicit preparation on Linux x86_64:

```sh
python3 tools/android/prepare-mozilla-native.py \
  --source-repository /path/to/pinned/translations-checkout \
  --installed-tools /path/to/attested/pinned/tools
python3 tools/android/prepare-mozilla-native.py --verify-only
```

The optional seed checkout and installed tools are read-only: source is exported from pinned Git objects into the independent Mozilla cache, never from patched experiment working-tree bytes. Without a seed, the explicit preparer obtains the fixed source submodules and tool archives. The independently pinned PCRE2 10.49 tar.bz2 is exactly 2,185,271 bytes, SHA-256 `53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384`.

To rebuild from the corresponding-source archive, extract it, enter `build/`, use the pinned NDK/CMake/Ninja archives recorded in the manifest, and configure `engine-mozilla/src/main/cpp` with `MOZILLA_SOURCE` pointing to the extracted `source/`, `MOZILLA_PCRE_SOURCE` to `pcre2-10.49/`, NDK toolchain, `ANDROID_PLATFORM=android-28`, `ANDROID_STL=c++_static`, `CMAKE_BUILD_TYPE=Release`, and each of `ANDROID_ABI=arm64-v8a,x86_64`; build target `mozilla_jni` with `-j 2`. The source is already patched: do not apply patches twice. The recipe records exact configure arguments for the original build. Environment-specific absolute paths may differ.

## Production patches and ownership

`src/main/cpp/patches/` enables eager single-replica initialization, actual per-sentence EOS and bounded strict output evidence, checked model-buffer reads, Android exclusion of upstream process-global terminate/SIGSEGV/SIGFPE installation, typed aligned-allocation errors, exception-safe Ruy allocation/disposal, and a pinned Clang compatibility fix. `JNI_OnLoad` enables Marian throwing mode before fallible Marian work. Android loggers are retained within this hidden closure, avoiding duplicate-name/partial-logger failures across successive owners; no external provider logger or process handlers are replaced.

Partial graph destruction tolerates a device/workspace that was not initialized, so a real upstream setup exception survives disposal instead of dereferencing a null workspace. The preparer retains the unstripped linker output in each independent ABI build directory and strips only a packaging copy; the receipt binds the retained symbol-bearing library's path, SHA-256 and length. This does not install fault handlers or recover native signals as exceptions.

Shortlist validation treats offsets as half-open range boundaries: a valid empty trailing row may start at the list end. It still verifies the binary checksum, end sentinel and every target vocabulary index, and rejects out-of-range or reversed row boundaries. Model files and their attested hashes are unchanged.

A blocking owner uses cache size zero, disposes the entire service and selected models after any failure, and cannot be reused. A fresh owner is required. There is no cancellation API: the caller stops admission/publication and joins accepted calls. JNI symbols and the exception constructor are kept by `consumer-rules.pro`; all non-JNI exports are hidden. Native instrumentation-only entry points exercise the same validators, snapshots and allocation classification without substituting a translation backend. ARM cross-compilation does not establish ARM runtime performance or success.
