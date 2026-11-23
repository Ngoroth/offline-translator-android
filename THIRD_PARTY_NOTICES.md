# Third-party notices

Original project materials are licensed under [Apache-2.0](LICENSE). Third-party
code, audio, models, data and trademarks retain their own terms. This inventory
is not a complete APK dependency inventory or redistribution clearance.

## Sherpa and Piper Phonemize Android runtimes

`:engine-sherpa:prepareSherpaArchive` acquires the unmodified official
sherpa-onnx 2.0.2 and Piper Phonemize 1.5.9 Android AARs. Ordinary builds verify
the pinned local bytes; neither AAR is vendored in the current source tree.
Sherpa no longer embeds eSpeak NG or Piper Phonemize. The separate Piper
Phonemize AAR contains native eSpeak NG code and eSpeak language data, but no
voice weights. See the [module provenance record](engine-sherpa/THIRD_PARTY.md)
for exact binaries, recipes and evidence limits.

| Component | Terms and preserved texts |
| --- | --- |
| sherpa-onnx 2.0.2 | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.2/LICENSE); full text in [LICENSE](LICENSE) |
| ONNX Runtime 1.28.2 | [MIT](LICENSES/onnxruntime-MIT.txt) and [upstream third-party notices](LICENSES/onnxruntime-ThirdPartyNotices.txt) |
| Piper Phonemize 1.5.9 and embedded eSpeak NG / ucd-tools | GPL-3.0-or-later; full [GPLv3 text](LICENSES/espeak-ng-COPYING.txt) |
| Piper Phonemize's uni-algo | [Public Domain or MIT text](LICENSES/piper-phonemize-uni-algo.txt) |
| eSpeak / ucd-tools Unicode data | Copyright Unicode, Inc. (1991–2018); [permission notice](LICENSES/espeak-ng-Unicode.txt) |

The [historical Piper phonemize MIT notice](LICENSES/piper-phonemize-MIT.txt)
applies to the earlier source used with Sherpa 1.13.8, not as a license for the
current combined phonemizer. Redistributing either AAR or an APK containing
them requires the applicable notices and terms. For GPL-covered object code,
Corresponding Source and compatible combined-work terms still apply; see
[native distribution conditions](engine-sherpa/THIRD_PARTY.md#conditions-before-redistributing-this-aar-or-an-apk-containing-it).

## Tencent local translation

The registered Tencent translation provider embeds CPU **llama.cpp v1.0.0** at commit
[`b1b770d90d94cad9e9ea5b993bdbc383a522fb28`](https://github.com/ggml-org/llama.cpp/tree/b1b770d90d94cad9e9ea5b993bdbc383a522fb28)
under MIT. It is explicitly built into a verified local JNI AAR, not downloaded
as executable application content. Exact source/toolchain/ABI and license
inventory is in [Tencent native provenance](engine-tencent/THIRD_PARTY.md); its
notice is packaged in APK license assets and the native distribution.

**Hy-MT2-1.8B-GGUF**, copyright 2026 Tencent, is separately downloaded model
data under the exact repository Apache-2.0 grant. Pinned revision:
`a0c709d9fac510f2c807aa3af52872340dc37a4a`; file:
`Hy-MT2-1.8B-Q4_K_M.gguf`, 1,133,080,448 bytes, SHA-256
`dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`.
The [full exact license](LICENSES/Tencent-Hy-MT2-Apache-2.0.txt) is preserved
and packaged; source [license](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/a0c709d9fac510f2c807aa3af52872340dc37a4a/LICENSE.txt)
SHA-256 is `a1d52d448f81c584a47c583e19dfab2d3851c7c84431b07baee093c1113ed114`.
No NOTICE file was present in the captured pinned upstream inventory. Weights
are not bundled in Git, the JNI AAR or the APK. Tencent and Hy-MT2 names remain
their owners’ marks; attribution is not endorsement or quality certification.

## Mozilla local translation

The optional Mozilla provider uses maintained Mozilla Translations inference
at `8eada038fd45bbf6fa58a412b22f80c79887fabc`, with source-supplied Marian,
SentencePiece, intgemm/Ruy and the verified native dependency closure.
It is prepared as a two-ABI JNI AAR, not downloaded as executable content.
Exact source, patches, toolchain, notices and corresponding-source preparation
are described in [Mozilla provenance](engine-mozilla/THIRD_PARTY.md).

Mozilla translation weights, vocabularies and shortlists are separately
downloaded under the upstream MPL-2.0 distribution terms, using the pinned
provider catalog. They are not included in Git, the native AAR or the APK.
Source-language splitter prefix files retain their LGPL-2.1 terms as data;
they are not compiled into the native library. Preserve their source and
notices along with the actual native dependency licenses. Original project
licensing does not relicense these materials; Mozilla branding is not endorsement.

## Libraries resolved at build time

Gradle resolves Maven libraries and transitive dependencies; versions and
coordinates are in [the version catalog](gradle/libs.versions.toml).

- **Apache Commons Compress 1.28.0**, `org.apache.commons:commons-compress`:
  Apache-2.0; the full upstream [NOTICE](LICENSES/commons-compress-NOTICE.txt)
  is preserved from the [release source](https://github.com/apache/commons-compress/blob/rel/commons-compress-1.28.0/NOTICE.txt).
- AndroidX/Compose/DataStore, Kotlin coroutines, `org.json:json`, JUnit and Android
  test libraries retain their artifact licenses and applicable transitive notices.

## Downloaded model weights and voice data

Model weights and voice packages are downloaded separately, not included in
the production AARs or APK. This is distinct from the eSpeak language data
bundled in Piper Phonemize. Model source revisions, licenses and voice-specific
restrictions are recorded in the [module notice](engine-sherpa/THIRD_PARTY.md).

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
