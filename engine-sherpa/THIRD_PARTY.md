# Third-party components

The [root third-party inventory](../THIRD_PARTY_NOTICES.md) defines the scope of the
project's Apache-2.0 grant, preserves upstream native license texts and records
remaining native, model and audio rights questions. It does not replace these
module-specific notices or establish redistribution clearance.

## Official Android runtimes

Both unmodified AARs are obtained explicitly with
`.\gradlew.bat :engine-sherpa:prepareSherpaArchive --console=plain` (Windows) or
`bash ./gradlew :engine-sherpa:prepareSherpaArchive --console=plain` (POSIX).
Acquisition verifies the pinned size and SHA-256 before atomic placement and
reuses valid local copies. Ordinary Gradle builds verify both files locally,
without compilation, repair or downloads. The files are not vendored in the
current source tree. `:engine-sherpa` exposes sherpa-onnx configuration types
through its public factories. The isolated `:engine-sherpa-model-smoke`
instrumentation tests use those factories and call the Sherpa SDK directly.
Piper Phonemize remains an implementation dependency of `:engine-sherpa`.

| Prepared file | Release asset | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `libs/sherpa-onnx-2.0.2.aar` | [sherpa-onnx v2.0.2](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar) | 52,321,252 | `6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e` |
| `libs/piper-phonemize-1.5.9.aar` | [Piper Phonemize v1.5.9](https://github.com/csukuangfj/piper-phonemize/releases/download/v1.5.9/piper-phonemize-1.5.9.aar) | 12,672,185 | `d0ed1cc8ac8029937a10099539ff4e532e4ec875a4745137283b618d9075daa2` |

### sherpa-onnx 2.0.2

[Release metadata](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v2.0.2)
identifies asset `766997436`; the release tag resolves to commit
[`5d2bd37de78b223a2e8e00e25483d12c5b164299`](https://github.com/k2-fsa/sherpa-onnx/tree/5d2bd37de78b223a2e8e00e25483d12c5b164299).
Sherpa's [Apache-2.0 license](https://github.com/k2-fsa/sherpa-onnx/blob/5d2bd37de78b223a2e8e00e25483d12c5b164299/LICENSE)
matches the root [license text](../LICENSE). It does not cover every linked component.

The tagged [Android release recipe](https://github.com/k2-fsa/sherpa-onnx/blob/5d2bd37de78b223a2e8e00e25483d12c5b164299/.github/workflows/android.yaml)
uses `ANDROID_NDK_LATEST_HOME`, enables the C API, builds four ABIs and packages
the native outputs in the AAR. The [successful release run](https://github.com/k2-fsa/sherpa-onnx/actions/runs/44486230886)
matches the tag commit and asset upload window. The verified AAR contains
`libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so` and
`libsherpa-onnx-jni.so` for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.
The app packages only `arm64-v8a` and `x86_64`.

The Android scripts use shared ONNX Runtime 1.28.2 by default; see the
[ARM64 script](https://github.com/k2-fsa/sherpa-onnx/blob/5d2bd37de78b223a2e8e00e25483d12c5b164299/build-android-arm64-v8a.sh)
and [x86_64 script](https://github.com/k2-fsa/sherpa-onnx/blob/5d2bd37de78b223a2e8e00e25483d12c5b164299/build-android-x86-64.sh).
All four `libonnxruntime.so` files byte-match the previous 1.13.8 dependency
and the corresponding entries in
[`onnxruntime-android-1.28.2.zip`](https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip)
(33,765,715 bytes, SHA-256 `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`).
That ZIP carries no license inventory. The existing 1.28.2
[MIT text](../LICENSES/onnxruntime-MIT.txt) and
[third-party notices](../LICENSES/onnxruntime-ThirdPartyNotices.txt) are retained
for these unchanged binaries.

The release includes upstream [PR #4093](https://github.com/k2-fsa/sherpa-onnx/pull/4093):
CPU ONNX Whisper accepts `language_candidates` per stream, returns the selected
language in the existing result, and has no separate option status. Upstream
[PR #4055](https://github.com/k2-fsa/sherpa-onnx/pull/4055) removed linked
eSpeak NG and Piper Phonemize from Sherpa before this release. The 2.0.2 native
recipe no longer links them, and inspection of the shipped-ABI C API and JNI
libraries did not find the corresponding old eSpeak/Piper symbols or source
diagnostics. Piper models now need a lexicon or external phonemization.

### Piper Phonemize 1.5.9

[Release metadata](https://api.github.com/repos/csukuangfj/piper-phonemize/releases/tags/v1.5.9)
identifies asset `658561689` and tag source
[`dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d`](https://github.com/csukuangfj/piper-phonemize/tree/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d).
Its [README license declaration](https://github.com/csukuangfj/piper-phonemize/blob/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d/README.md#license)
is GPL-3.0-or-later; its [GPLv3 text](https://github.com/csukuangfj/piper-phonemize/blob/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d/LICENSE.md)
byte-matches the [preserved text](../LICENSES/espeak-ng-COPYING.txt). The
app feeds phoneme codepoints from this separate runtime to Sherpa's VITS
synthesis, following the [upstream Android example](https://github.com/k2-fsa/sherpa-onnx/blob/5d2bd37de78b223a2e8e00e25483d12c5b164299/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt).

The tagged [Android recipe](https://github.com/csukuangfj/piper-phonemize/blob/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d/.github/workflows/android.yaml)
and [successful release run](https://github.com/csukuangfj/piper-phonemize/actions/runs/39384537865)
show four ABIs and an API 26 native target (the AAR manifest declares minSdk 21).
Its [CMake recipe](https://github.com/csukuangfj/piper-phonemize/blob/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d/CMakeLists.txt)
statically links eSpeak NG, selecting
[`ed530aa113046142eb5115cf2fc9157854d0ffe1`](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1)
(archive SHA-256 `e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd`).
The recipe copies the repository's `swift-api-examples/espeak-ng-data` into the
AAR. The verified asset contains `libpiper-phonemize-jni.so` for all four ABIs
with eSpeak and Piper code, and bundles `assets/espeak-ng-data/`, including
Russian and US English data. Both packaged 64-bit libraries have 16 KiB ELF
load-segment alignment. The bundled data is not a voice model. The downloaded
Irina and Lessac model packages retain their distinct terms below.

Neither inspected AAR contains a license/notice inventory. Archive inspection,
byte matches and public recipe/run metadata identify the checked inputs and
outputs; they do not attest to every runner input or constitute an exhaustive
SBOM, a complete Corresponding Source package or release qualification.

Relevant full texts preserved in this repository:

- eSpeak NG: [GPL-3.0](../LICENSES/espeak-ng-COPYING.txt).
  The pinned [README](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/README.md#license-information)
  specifies GPL-3.0-or-later. Its `ucd-tools` sources also specify GPL-3.0-or-later,
  for example [case.c](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/ucd-tools/src/case.c),
  copyright Reece H. Dunn (2012–2018, 2021).
- Piper Phonemize includes a [uni-algo header](https://github.com/csukuangfj/piper-phonemize/blob/dae4d2d831f9f0817355f7c0e8abd98c2fb0a49d/src/uni_algo.h)
  under [Public Domain or MIT terms](../LICENSES/piper-phonemize-uni-algo.txt).
- The historical [Piper phonemize MIT notice](../LICENSES/piper-phonemize-MIT.txt)
  is retained from [`f3ff95afc03640bc1399e113e83361192a2fafb4`](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/LICENSE.md),
  used by the earlier Sherpa 1.13.8 runtime. It does not replace v1.5.9's GPL terms.
- eSpeak/ucd-tools Unicode tables: [Unicode permission notice](../LICENSES/espeak-ng-Unicode.txt),
  copyright Unicode, Inc. (1991–2018), from pinned
  [COPYING.UCD](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/COPYING.UCD)
  (also present in `src/ucd-tools/COPYING.UCD`).

### Conditions before redistributing this AAR or an APK containing it

Publishing a repository containing either AAR also distributes object code;
calling it a source release does not avoid native obligations. Preserve
applicable copyright, permission, attribution and warranty notices,
including the Apache, MIT, Unicode and GPL texts above. The root Apache grant
licenses original project materials, not all linked binaries or the full APK.

For GPL-covered object code, [GPLv3 sections 1, 4–6](../LICENSES/espeak-ng-COPYING.txt)
require the applicable notices and machine-readable Corresponding Source, including
the source and scripts needed to generate, install, run and modify the covered
work, with the section 1 exclusions for System Libraries and general-purpose tools.
An eSpeak source link alone is not necessarily the Corresponding Source of the
statically linked phonemizer or any covered combination. Establish the covered
combined-work scope and compatible terms; neither putting GPL text beside an
Apache-licensed application nor moving the GPL code into a separate AAR
establishes redistribution clearance.

For a public network download, section 6(d) permits equivalent no-charge source
access with clear directions next to the object-code download. A third-party source
server is permitted, but the distributor remains responsible for availability.
Do not substitute a generic three-year written offer: section 6(b)'s offer is a
different route tied to physical-product distribution. Installation Information
is required only when section 6's User Product/transfer conditions apply, not
automatically for every APK download.

For the current Piper Phonemize AAR or an APK containing it, account for the
actual linked eSpeak/ucd-tools code, packaged language data and the preferred
form for changing that data, as well as the build/install scripts and the
covered work as a whole. No complete source package or redistribution clearance
is provided. The pinned Sherpa recipe and inspected binaries do not establish
rights to distribute other native components, audio, models or voice data.

Historical [sherpa-onnx 1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)
binaries (tag commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`) included
embedded eSpeak and Piper code. The former local `sherpa-onnx-1.13.8-pair-v1.aar`
used a native source patch and an exact-byte receipt; its
[archived plan](../docs/workflow/archive/2026-10-21-2346-whisper-conversation/plans/2026-10-21-2346-whisper-conversation.md)
records the old inputs and obligations. Historical Git blobs were not removed
by this change. The new release does not retroactively change the terms of those
binaries or attest that Corresponding Source was provided for them.

## Apache Commons Compress

- Version: `1.28.0`
- Source: <https://commons.apache.org/proper/commons-compress/>
- License: Apache License 2.0
- Used only to stream and validate the pinned `.tar.bz2` model packages in private staging.

## Production conversation speech models

Model weights are separate downloads and are not included in the AAR or APK.
Production packages pin archive/file hashes, byte counts, redirect hosts and
installation quotas in `src/main/assets/speech-models.json`. The following
notices also cover retained catalog resources from legacy recognition paths:

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
Its original attributions and `docs/workflow/archive/legacy/archived/testing/speech-model-selection.md` decisions
remain applicable; neither original ASR is accepted for A059P.

- Russian T-one candidate: Apache-2.0 license in the upstream model package,
  <https://huggingface.co/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08>.
- English Zipformer 2023-06-26 emulator candidate: Apache-2.0 model card at pinned
  revision `672fbf1b30579d6585301139bb363f42a0ad4a24`,
  <https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26>.

## Downloaded voice packages and retained language-ID resources

The catalog pins Piper Irina/Lessac voices and retains Whisper tiny resources from
the former standalone language-ID path. These packages are separate downloads.
Voice weights and their package-specific eSpeak data are not included in the
production APK; the Piper Phonemize AAR's bundled eSpeak data is a separate input.
Exact byte counts, SHA-256 digests, redirect hosts and complete voice inventories
are in `src/main/assets/speech-models.json`.

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
