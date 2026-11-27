# Tencent native distribution

The provider uses CPU-only llama.cpp [v1.0.0](https://github.com/ggml-org/llama.cpp/releases/tag/v1.0.0)
at immutable commit `b1b770d90d94cad9e9ea5b993bdbc383a522fb28` from
<https://github.com/ggml-org/llama.cpp/tree/b1b770d90d94cad9e9ea5b993bdbc383a522fb28>.
The prepared AAR statically links llama/ggml into `libtencent_jni.so` for
`arm64-v8a` and `x86_64`. ARM64 also includes `libtencent_jni_simd.so`, built with
`armv8-a+dotprod+fp16+i8mm`. A separate baseline-compiled probe uses Android's
hardware capability bits before loading exactly one runtime; if any required
ARM64 feature is absent, the ARMv8-A runtime is loaded instead.
The AAR contains no model weights. Its `assets/tencent-native/` directory includes
the upstream MIT license and additional upstream license inputs.

Explicit Prepare or Update downloads Tencent Hy-MT3-1.8B Q4_K_M, repository revision
`4c4370b31f13ccac4ac71133e3a0967c88cc792c`, 1,348,432,160 bytes,
SHA-256 `bae6e591103654ff43ef0de7d1492f6e6df1f955505f6bb2cc52e86fc399a942`.
The exact Apache-2.0 license is at
<https://huggingface.co/tencent/Hy-MT3-1.8B-GGUF/blob/4c4370b31f13ccac4ac71133e3a0967c88cc792c/LICENSE.txt>.

Previously installed Tencent Hy-MT2-1.8B Q4_K_M remains usable until Hy-MT3
passes verification and initialization. This verify-only model uses repository
revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`, 1,133,080,448 bytes,
SHA-256 `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`,
and its own Apache-2.0 license at
<https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/a0c709d9fac510f2c807aa3af52872340dc37a4a/LICENSE.txt>.
The application packages both exact model licenses and presents the loaded
model's attribution and Terms. Neither model is included in the JNI AAR or APK.

The provider bridge uses deterministic strict UTF-8-prefix-constrained greedy
selection rather than unconstrained greedy. It preserves eligible greedy winners
and tie ordering, otherwise selects the highest-logit eligible token before
commitment, and admits EOS120020 only at a complete scalar boundary. Exact
length-delimited pieces support split scalars and NUL; invalid/impossible prefixes
are excluded, not repaired or decoded with replacement. This bridge policy does
not modify the pinned upstream sources, model, quantization or prompt and does not
certify translation meaning. Native changes require fresh two-ABI preparation
and actual JNI regression evidence; host feasibility is not Android validation.

## Explicit native preparation

On Linux x86_64, run `python3 tools/android/prepare-tencent-native.py`.
On Windows, run `pwsh -File tools/android/prepare-tencent-native.ps1`; this uses
an existing WSL Ubuntu installation, without changing the Android SDK.
Python 3.12+, Git, HTTPS access and standard archive support are required.
The recipe obtains SHA-256-pinned official NDK r29-beta1 (29.0.13113456-beta1),
CMake 4.2.3 and Ninja 1.13.2 and resolves the exact upstream commit. Archives,
source snapshots, tools and sequential two-job builds for baseline `arm64-v8a`,
baseline `x86_64` and optional ARM64 SIMD live in ignored
`engine-tencent/.native-cache`. Optional `--source-repository PATH` and
`--ndk-archive PATH` seed preparation with verified local inputs, not runtime dependencies.
All effective native compiler/backend settings are pinned by the tool manifest,
CMake and preparation script, included in the current-input receipt digest.

Outputs are `engine-tencent/libs/tencent-native-b1b770d9-r2.aar` and
`engine-tencent/libs/tencent-native-b1b770d9-r2.receipt.properties`.
Ordinary Gradle builds verify current source/recipe/settings inventory, upstream pin,
AAR SHA-256, complete JNI ABI inventory, member hashes and ELF 16-KiB LOAD alignment
without fetching or building anything. Changes to native sources, adapter, manifest,
this notice, module build file, tool manifest or preparation scripts require explicit
re-preparation. The receipt is local integrity/provenance, not a signed supply-chain
attestation or a claim of bit-identical rebuilds.

Prepared-artifact regression coverage can be run with
`python3 tools/android/test_tencent_native_preparation.py`.
The optional real host runtime smoke is built from `src/main/cpp/smoke.cpp` using the
same runtime implementation; it does not establish Android JNI execution or phone
performance. Android JNI and application proof remain integration checks.

## llama.cpp MIT license

MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
