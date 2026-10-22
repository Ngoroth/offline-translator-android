# Third-party components

The [root third-party inventory](../THIRD_PARTY_NOTICES.md) defines the scope of the
project's Apache-2.0 grant, preserves upstream native license texts and records
remaining native, model and audio rights questions. It does not replace these
module-specific notices or establish redistribution clearance.

## sherpa-onnx Android runtime

- Version: `1.13.8`
- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8>
- Official upstream preparation input (not the packaged selected-pair distribution): `libs/sherpa-onnx-1.13.8.aar`
- SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- sherpa-onnx source license: Apache License 2.0,
  <https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE>.
  This does not describe every component linked into the AAR.
- ONNX Runtime: MIT; preserved version-specific license and notices are linked in
  the root inventory. Native eSpeak NG and Piper evidence is recorded below.

The official input AAR is obtained explicitly with
`./gradlew.bat :engine-sherpa:prepareSherpaArchive --console=plain` (Windows) or
`bash ./gradlew :engine-sherpa:prepareSherpaArchive --console=plain` (POSIX).
Acquisition verifies size/SHA-256 before atomic placement and reuses valid local
bytes. Corrupt input fails unchanged. The app instead resolves the locally
rebuilt selected-pair distribution described below. SDK types remain inside
`:engine-sherpa`; neither `:core` nor `:app` receives them through public APIs.
Historical official-AAR Git blobs are not removed by this change.

### Locally modified selected-pair distribution

`tools/android/prepare-sherpa-native.py` builds
`.native-cache/android/sherpa-onnx-1.13.8-pair-v1.aar` explicitly on Linux/WSL.
`native-build.json` pins source, the complete selected dependency closure,
ORT headers and the exact NDK/CMake/Ninja identities. The six-file
`native/whisper-language-pair.patch` adds a metadata-backed two-language SOT
comparison while preserving one encoder, one transcription and old no-option
behavior. It does not change public C/JNI/Kotlin layouts.

Only ARM64/x86_64 are packaged. All three Sherpa libraries are rebuilt for each;
official non-native AAR entries and per-ABI ORT1.28.2 bytes are retained exactly.
The generated receipt binds source/patch/recipe/tool identities, actual archive
and ELF hashes, public exports, dependency closure,16KiB alignment and an
explicit retained `whisper-language-pair-v1` feature marker. Source-supplied
license/notice material is bundled under `assets/sherpa-native/licenses/`.
Ordinary Gradle verification is read-only: no stock fallback, repair, download
or implicit compilation. The upstream-input composition below remains provenance
for that input, not a claim that its unmodified native libraries are shipped.

### Exact artifact and native composition

The official [release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar)
is 50,129,134 bytes; [release metadata](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8)
identifies asset `555224925` with the same pinned SHA-256 above.
The inspected release tag resolves to source commit
`11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.

The AAR contains `libonnxruntime.so`, `libsherpa-onnx-c-api.so`,
`libsherpa-onnx-cxx-api.so` and `libsherpa-onnx-jni.so` for each of
`arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Both C API and JNI binaries
contain eSpeak NG and Piper phonemizer code; this is not just downloaded voice data.
The native dependencies are statically linked rather than shipped as separate
`libespeak-ng.so` or `libpiper_phonemize.so` files.

The embedded diagnostics include eSpeak's
[`src/libespeak-ng/error.c`](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/libespeak-ng/error.c),
copyright Reece H. Dunn (2016), GPL-3.0-or-later, and Piper's
[`src/phonemize.cpp`](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/src/phonemize.cpp).
Binary inspection establishes their presence, not the exact source revision used
to build this release asset.

### Available source recipe and preserved notices

The v1.13.8 [Android release workflow](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/.github/workflows/android.yaml)
builds all four ABIs, enables the C API, uses `ANDROID_NDK_LATEST_HOME`, then copies
the native libraries into the AAR. Its per-ABI scripts
[`build-android-arm64-v8a.sh`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/build-android-arm64-v8a.sh),
[`build-android-armv7-eabi.sh`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/build-android-armv7-eabi.sh),
[`build-android-x86.sh`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/build-android-x86.sh)
and [`build-android-x86-64.sh`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/build-android-x86-64.sh)
default TTS to `ON`. The arm64 script defaults to shared ONNX Runtime 1.28.2 from
<https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip>.
This describes the published recipe, not an attestation of the release runner's
actual environment or overrides.

The [root CMake file](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/CMakeLists.txt)
loads eSpeak first, sets `ESPEAK_NG_DIR` to that source tree and then loads Piper
when TTS is enabled. Thus Piper's standalone fallback eSpeak revision is **not**
the eSpeak revision selected by this sherpa recipe.
[`cmake/espeak-ng-for-piper.cmake`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/espeak-ng-for-piper.cmake)
and [`cmake/piper-phonemize.cmake`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/piper-phonemize.cmake)
temporarily force static builds. Piper links `espeak-ng` and `ucd`;
[`sherpa-onnx/csrc/CMakeLists.txt`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/CMakeLists.txt)
links Piper into the static core, and the
[`JNI target`](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/jni/CMakeLists.txt)
links that core.

The following exact upstream archives are among the pinned inputs of the local
build; the manifest records the remaining dependencies and tool/header pins:

| Candidate source input | SHA-256 |
| --- | --- |
| [sherpa-onnx v1.13.8 source](https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.zip) | `b63b7613812346f2d1396a3a7f94accd47539e3dadc3ea385ba6474c70ab9897` |
| [eSpeak NG `ed530aa113046142eb5115cf2fc9157854d0ffe1`](https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip) | `e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd` |
| [Piper phonemize `f3ff95afc03640bc1399e113e83361192a2fafb4`](https://github.com/csukuangfj/piper-phonemize/archive/f3ff95afc03640bc1399e113e83361192a2fafb4.zip) | `d9cca4e2bdc7d6dd8dffb96a4668283dbd3f77a9c194a3e530c1e8eba9406a5d` |

Relevant full texts preserved in this repository:

- eSpeak NG: [GPL-3.0](../LICENSES/espeak-ng-COPYING.txt).
  The pinned [README](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/README.md#license-information)
  specifies GPL-3.0-or-later. Its `ucd-tools` sources also specify GPL-3.0-or-later,
  for example [case.c](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/ucd-tools/src/case.c),
  copyright Reece H. Dunn (2012–2018, 2021).
- Piper phonemize: [MIT](../LICENSES/piper-phonemize-MIT.txt), copyright Michael
  Hansen (2023), from the pinned
  [LICENSE.md](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/LICENSE.md).
- Piper's included uni-algo header: [Public Domain or MIT text](../LICENSES/piper-phonemize-uni-algo.txt),
  from [licenses/uni-algo/LICENSE.md](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/licenses/uni-algo/LICENSE.md).
  The same alternatives appear in the embedded `src/uni_algo.h` used by the phonemizer.
- eSpeak/ucd-tools Unicode tables: [Unicode permission notice](../LICENSES/espeak-ng-Unicode.txt),
  copyright Unicode, Inc. (1991–2018), from pinned
  [COPYING.UCD](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/COPYING.UCD)
  (also present in `src/ucd-tools/COPYING.UCD`).

### Conditions before redistributing this AAR or an APK containing it

Publishing a repository containing this AAR also distributes object code; calling
it a source release does not avoid the native obligations. Preserve applicable
copyright, permission, attribution and warranty notices, including the Apache,
MIT, Unicode and GPL texts above. The root Apache grant remains a grant for
original project materials, not an Apache-only license for this linked binary.

For GPL-covered object code, [GPLv3 sections 1, 4–6](../LICENSES/espeak-ng-COPYING.txt)
require the applicable notices and machine-readable Corresponding Source, including
the source and scripts needed to generate, install, run and modify the covered
work, with the section 1 exclusions for System Libraries and general-purpose tools.
An eSpeak source link alone is not necessarily the Corresponding Source of the
statically linked combination. Establish the covered combined-work scope and
compatible distribution terms; do not assume that placing a GPL text beside an
Apache-licensed application resolves that question.

For a public network download, section 6(d) permits equivalent no-charge source
access with clear directions next to the object-code download. A third-party source
server is permitted, but the distributor remains responsible for availability.
Do not substitute a generic three-year written offer: section 6(b)'s offer is a
different route tied to physical-product distribution. Installation Information
is required only when section 6's User Product/transfer conditions apply, not
automatically for every APK download.

For the official upstream input, its recipe and candidate archives alone do not
attest a complete Corresponding Source package for that historical binary.
The local modified distribution records its actual source/dependency/tool pins,
modification patch, build helper and byte receipt. Supply that source material
and the scripts needed for the shipped combined work with any binary handoff;
public hosting/redistribution still requires the applicable compatible terms
and source availability. No formal exhaustive SBOM or byte-for-byte identical
rebuild guarantee is asserted, and no third-party rights can be granted by the
project owner.

## Apache Commons Compress

- Version: `1.28.0`
- Source: <https://commons.apache.org/proper/commons-compress/>
- License: Apache License 2.0
- Used only to stream and validate the pinned `.tar.bz2` model packages in private staging.

## Production conversation speech models

Model weights are separate downloads and are not included in the AAR or APK.
Production packages pin archive/file hashes, byte counts, redirect hosts and
installation quotas in `src/main/assets/speech-models.json`. Legacy Sherpa
recognition uses one Whisper tiny identifier and two offline recognizers:

- Russian GigaAM v1 NeMo CTC int8:
  <https://github.com/salute-developers/GigaAM>.
  Official converted October 2024 archive:
  <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-giga-am-russian-2024-10-24.tar.bz2>.
  Its original **GigaAM Attribution-NonCommercial Public License** notice is
  `GigaAM%20License_NC.pdf`, installed literally alongside `model.int8.onnx`
  and `tokens.txt`; all three files have pinned digests and byte counts.
  Current upstream <https://github.com/salute-developers/GigaAM/blob/main/LICENSE>
  is MIT; the upstream README announces relicensing in December 2024.
  That later source-license statement does not relabel the earlier archive
  or replace its original notice. Development license acceptance is not
  publication/redistribution clearance.
- English Whisper tiny.en: Whisper upstream MIT,
  <https://github.com/openai/whisper/blob/main/LICENSE>. Converted encoder, decoder
  and tokens are pinned to revision `d026532c022fa99fd789d6b32446a1df7b6bfc43`:
  <https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/tree/d026532c022fa99fd789d6b32446a1df7b6bfc43>.
  Official release archive:
  <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2>.
  Consumed `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx` and
  `tiny.en-tokens.txt` were verified byte-identical to that immutable source.
- Silero VAD: MIT,
  <https://github.com/snakers4/silero-vad/blob/v6.2.2/LICENSE>.

These model licenses are distinct from the Apache-2.0 sherpa-onnx conversion/runtime
license. Host native controls informed the model selection; they do not establish
Android latency acceptance or a pass of the historical corpus quality gates.

### Shared multilingual Whisper Small

The explicit Whisper recognition choice uses one Small INT8 definition across
the current17 app codes and three raw packages, not one model per language.
Source: [Sherpa's OpenAI Whisper ONNX export documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html),
which identifies `csukuangfj/sherpa-onnx-whisper-small`.
Immutable revision: `8f3c18b358db4d1f2fc1eae49d75cd20989e4309`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `small-encoder.int8.onnx` | 112442483 | `4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9` |
| `small-decoder.int8.onnx` | 262226114 | `acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee` |
| `small-tokens.txt` | 816730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

[OpenAI states that Whisper code and model weights are MIT](https://github.com/openai/whisper#license);
the complete [copyright/license text](../LICENSES/whisper-MIT.txt) is preserved
and packaged with app notices. The selected HF revision contains no README or
license sibling; this record does not invent one or claim additional training
or model conversion by this project. The app downloads these exact exported
bytes. Vocabulary coverage is not all-language quality or voice qualification.


## Retained fixed-direction evaluator candidates

The original three-package evaluator remains on the baseline pins in
`test-data/model-sets/baseline.json`, not the production conversation ASR catalog.
Its original attributions and `docs/archived/testing/speech-model-selection.md` decisions
remain applicable; neither original ASR is accepted for A059P.

- Russian T-one candidate: Apache-2.0 license in the upstream model package,
  <https://huggingface.co/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08>.
- English Zipformer 2023-06-26 emulator candidate: Apache-2.0 model card at pinned
  revision `672fbf1b30579d6585301139bb363f42a0ad4a24`,
  <https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26>.

## Conversation language identification and voices

The production catalog additionally pins Whisper tiny encoder/decoder files and the
Piper Irina/Lessac voices. All seven packages are downloaded separately; weights and
eSpeak data are excluded from the production APK. Exact byte counts, SHA-256 digests,
redirect hosts and complete voice file inventories are in `src/main/assets/speech-models.json`.

- Whisper upstream: MIT, <https://github.com/openai/whisper/blob/main/LICENSE>.
  Converted tiny files are pinned to revision `65176e2deb88badc814a94058666cadccc29b61c`:
  <https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/tree/65176e2deb88badc814a94058666cadccc29b61c>.
- Piper Irina Russian model card:
  <https://huggingface.co/rhasspy/piper-voices/raw/v1.0.0/ru/ru_RU/irina/medium/MODEL_CARD>.
  The card does not establish complete voice/data redistribution rights.
- Piper Lessac English model card:
  <https://huggingface.co/rhasspy/piper-voices/raw/v1.0.0/en/en_US/lessac/medium/MODEL_CARD>.
  Its dataset is Blizzard 2013, with research-use terms; do not label these weights Apache-2.0 or MIT.
- Both voice packages include eSpeak NG data: GPL-3.0-or-later,
  <https://github.com/espeak-ng/espeak-ng/blob/master/COPYING>.

The user accepts any license for this development stage. These notices describe provenance,
not a publication or redistribution clearance.


## Instrumentation audio fixtures

The canonical official recordings are in `test-data/corpus/v1/smoke` and are packaged only
in instrumentation APKs: the isolated runner uses their original names; the app test APK
retains the existing generated official aliases and artifact guards. Production APKs exclude
all fixture audio. Conversation instrumentation stages its committed controls externally
instead of extending the packaged fixtures. None of these recordings contains user audio.

- `ru-official-0.wav`: T-one archive example; SHA-256
  `980B4327E684725859E437F05D15DFA4EC8AFB994EF2E8FB356365AF76547C41`.
- `en-official-0.wav`: prior Zipformer 20M archive example retained as an independent
  production-boundary fixture; SHA-256
  `6BC58A4EFDF20DAAC252B6B1502632601A71EFE0308F6757DC1EDA34891A7E4F`.

For these two examples, the pinned upstream repositories' Apache-2.0 grants supply
the documented distribution basis; no audio exclusion was found in the inspected
contents. Preserve [fixture attribution](../test-data/corpus/v1/ATTRIBUTION.md)
and the full Apache license in source distributions. The eight Windows TTS
outputs are retained by the owner's 2026-10-18 source-publication decision,
without independent output-rights clearance. No permission request is planned.
Separate test-APK notice packaging is not established by this source notice.
