# Third-party notices

Original project materials are licensed under [Apache-2.0](LICENSE). Third-party
code, audio, models, data and trademarks retain their own terms. This inventory
is not a complete APK dependency inventory or redistribution clearance.

## Locally prepared native runtime

The project uses the official **sherpa-onnx 1.13.8** Android AAR, prepared locally
by `:engine-sherpa:prepareSherpaArchive` and verified against its pinned SHA-256.
The current source tree does not vendor the AAR; earlier Git history retains it.
Exact artifact identity, source revisions and component evidence are in the
[module provenance record](engine-sherpa/THIRD_PARTY.md).

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

## Downloaded model weights and voice data

Model packages are downloaded separately, not included in the production AAR/APK.
Their source revisions, licenses and model/voice-specific restrictions are recorded
in the [module notice](engine-sherpa/THIRD_PARTY.md).

## Included audio fixtures

- **48 Common Voice 17.0 recordings:** **CC0-1.0**, as declared by the
  [pinned dataset mirror](https://huggingface.co/api/datasets/fsicoli/common_voice_17_0/revision/8262c16bf297c87a9cd88c51997c4758ed7a8ba2).
- **Two upstream examples**, `ru-official-0.wav` and `en-official-0.wav`:
  **Apache-2.0** under their pinned repository grants. Source links and required
  attribution are in [ATTRIBUTION.md](test-data/corpus/v1/ATTRIBUTION.md).
- **Eight synthetic recordings:** project-authored test sentences generated with
  Windows Microsoft Pavel/David; generation metadata is in
  [smoke-lock.json](test-data/corpus/v1/smoke-lock.json).

Exact file identities and source provenance are in the
[manifest](test-data/corpus/v1/manifest.json) and
[source lock](test-data/corpus/v1/source-lock.tsv). Preserve the applicable
upstream licenses and attribution when redistributing the recordings.
