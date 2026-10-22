# Third-party notices

Original project materials are licensed under [Apache-2.0](LICENSE). Third-party
code, audio, models, data and trademarks retain their own terms. This inventory
is not a complete APK dependency inventory or redistribution clearance.

## Locally prepared native runtimes

The project locally prepares a **modified sherpa-onnx 1.13.8** Android AAR using
the pinned official AAR as wrapper/ORT input and a six-file selected-pair source
patch. `:engine-sherpa:prepareSherpaArchive` acquires only that verified input;
`tools/android/prepare-sherpa-native.py` explicitly rebuilds and verifies the
ARM64/x86_64 native closure. The source tree does not vendor either AAR.
Source/dependency/tool pins, the patch, actual-byte receipt and applicable
redistribution conditions are in the [module provenance record](engine-sherpa/THIRD_PARTY.md).
Prepared source-supplied license/notice material is retained under
`assets/sherpa-native/licenses/`; original project licensing does not relicense
linked third-party components.

| Component | Terms and preserved texts |
| --- | --- |
| sherpa-onnx | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE); full text in [LICENSE](LICENSE) |
| ONNX Runtime 1.28.2 | [MIT](LICENSES/onnxruntime-MIT.txt) and [upstream third-party notices](LICENSES/onnxruntime-ThirdPartyNotices.txt) |
| Embedded eSpeak NG / ucd-tools | GPL-3.0-or-later; full [GPL-3.0 text](LICENSES/espeak-ng-COPYING.txt) |
| Piper phonemize | MIT, copyright Michael Hansen (2023); full [license](LICENSES/piper-phonemize-MIT.txt) |
| Piper's uni-algo | [Public Domain or MIT text](LICENSES/piper-phonemize-uni-algo.txt) |
| eSpeak / ucd-tools Unicode data | Copyright Unicode, Inc. (1991–2018); [permission notice](LICENSES/espeak-ng-Unicode.txt) |

Redistributing the AAR or an APK containing it requires the applicable notices,
Corresponding Source and compatible combined-work terms; see
[native distribution conditions](engine-sherpa/THIRD_PARTY.md#conditions-before-redistributing-this-aar-or-an-apk-containing-it).

The selectable NeMo backend adds **NVIDIA NeMo-Speech.cpp 0.2.0** (Apache-2.0),
patched **llama.cpp / ggml** (MIT and Apache-2.0 additions), and static
**SentencePiece 0.2.1** with its bundled dependencies. Exact source pins, the
Android-only modification, model attribution and redistribution terms are in the
[NeMo provenance record](engine-nemo/THIRD_PARTY.md). Explicit native preparation
copies the original upstream license and notice texts into the APK under
`assets/nemo-native/licenses/`; this does not supersede the Sherpa conditions above.

## Libraries resolved at build time

Gradle resolves Maven libraries and transitive dependencies; versions and
coordinates are in [the version catalog](gradle/libs.versions.toml).

- **Apache Commons Compress 1.28.0**, `org.apache.commons:commons-compress`:
  Apache-2.0; the full upstream [NOTICE](LICENSES/commons-compress-NOTICE.txt)
  is preserved from the [release source](https://github.com/apache/commons-compress/blob/rel/commons-compress-1.28.0/NOTICE.txt).
- AndroidX/Compose/DataStore, Kotlin coroutines, Google ML Kit Translate,
  `org.json:json`, JUnit and Android test libraries retain their artifact licenses
  and applicable transitive notices.
- Google ML Kit and Google Translate names, logos and attribution artwork are
  subject to their own SDK/service and branding terms, not the project license.
- Bundled **ML Kit Language Identification 17.0.6**,
  `com.google.mlkit:language-id`, is independent of the Translate client. It retains
  the applicable [ML Kit SDK terms](https://developers.google.com/ml-kit/terms).

## Downloaded model weights and voice data

Model packages are downloaded separately, not included in the production AAR/APK.
Their source revisions, licenses and model/voice-specific restrictions are recorded
in the [module notice](engine-sherpa/THIRD_PARTY.md).
The NeMo catalog separately downloads NVIDIA's official Parakeet TDT 0.6B v3
Q8_0 GGUF. Its **CC BY 4.0** attribution, unchanged conversion, pinned revision
and digest are recorded in the [NeMo model notice](engine-nemo/THIRD_PARTY.md#nvidia-parakeet-tdt-06b-v3-model).

Whisper recognition separately downloads the pinned multilingual **OpenAI Whisper
Small INT8 ONNX** export and token table. OpenAI publishes its code and model
weights under MIT; Sherpa's export documentation identifies the selected
`csukuangfj/sherpa-onnx-whisper-small` repository. The exact HF revision itself
contains no README/license file: attribution relies on those upstream records,
not an invented repository license field. See the module notice and catalog for
the immutable revision and all three SHA-256 identities.

## Included audio fixtures

- **48 Common Voice 17.0 recordings:** **CC0-1.0**, as declared by the
  [pinned dataset mirror](https://huggingface.co/api/datasets/fsicoli/common_voice_17_0/revision/8262c16bf297c87a9cd88c51997c4758ed7a8ba2).
- **Two upstream examples**, `ru-official-0.wav` and `en-official-0.wav`:
  **Apache-2.0** under their pinned repository grants. Source links and required
  attribution are in [ATTRIBUTION.md](test-data/corpus/v1/ATTRIBUTION.md).
- **Eight synthetic recordings:** project-authored test sentences generated with
  Windows Microsoft Pavel/David; generation metadata is in
  [smoke-lock.json](test-data/corpus/v1/smoke-lock.json).
- **Two Dutch Google FLEURS recordings:** **CC BY 4.0**, attribution to Google
  FLEURS contributors. The [pinned source and fixture manifest](test-data/conversation/manifest.json)
  records original/converted hashes and the conversion to mono 16 kHz PCM16.
  Preserve attribution and the [license link](https://creativecommons.org/licenses/by/4.0/)
  when redistributing these test recordings.

Exact file identities and source provenance are in the
[manifest](test-data/corpus/v1/manifest.json) and
[source lock](test-data/corpus/v1/source-lock.tsv). Preserve the applicable
upstream licenses and attribution when redistributing the recordings.
