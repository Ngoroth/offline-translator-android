# Changelog

Notable capability, behavior and developer-facing compatibility changes. Entries describe development changes, not qualified releases.

## Unreleased

### Joint selected-pair Whisper recognition — 2026-10-22

- Added an explicit Whisper recognition choice using one shared Small INT8 model for the current17 language codes. Fresh-install defaults, Reset, schemas1–6 and saved System/Sherpa/NeMo/output/advanced choices remain unchanged.
- Restricted Whisper's source selection to the two participants within the same encoder/transcription path. This backend adds no independent classifier, confidence-based repeat request, second transcript or hidden provider fallback.
- Added source/patch/tool-pinned ARM64/x86_64 native preparation with explicit capability metadata, actual-byte receipts, unchanged stock wrapper/ORT input and existing public API/16KiB checks. Ordinary Gradle verifies only; no native download/rebuild fallback.
- Kept one serialized resident owner with actual-return cancellation/release barriers and direct normalized PCM input. Added the recognition option and shared-model name translations without adding model selectors; irrelevant System-service/LID controls hide without clearing saved values.
- Preserved independent translation/output readiness and old quality evidence. Raw Whisper silence can hallucinate text; the production VAD boundary remains required. These development changes do not establish universal language quality, physical-phone performance or the full two-second output goal.
- Bounded emulator verification completed all six NL/EN/RU directions and actual JNI-overlap lifecycle/Piper checks, but the semantic gate remains failed: one Dutch fixture recognizes `varkens` as `Valkers`. System output timing is still phase-only, not full two-second qualification; see the Whisper checks in `docs/testing/verify-translator.md`.

### Text-first multilingual conversation recognition — 2026-10-21

- Added selectable, persisted NeMo/Parakeet recognition without changing the System default or removing Sherpa/Piper. One pinned 714 MB multilingual GGUF covers the app's current English, Spanish, French, German, Italian, Russian, Dutch, Polish and Portuguese subset; unsupported speech pairs remain explicit rather than triggering fallback.
- Added optional unconditioned transcription and bundled ML Kit text-language identification behind the existing pair-level recognition contract. Insufficient/foreign text-language evidence retains the transcript, asks for repetition and skips translation/output while listening continues. Native/SDK errors are not converted into abstention.
- Extracted shared catalog/download/installation/storage into `speech-models`, preserving installed paths, strict verification and Sherpa evaluator contracts. Only the per-model metadata language ceiling changed from 16 to 32. Recognition owns its model installation; the capture/output projection receives explicit recognition model IDs.
- Added pinned Linux/WSL CPU native preparation for ARM64 and x86_64 with fail-closed ordinary-build verification, generated receipts, 16 KiB ELF/ZIP alignment checks and bundled upstream licenses. Builds do not implicitly fetch tools, sources or weights.
- Added backend-specific settings/preparation/coverage and repeat feedback across all 17 interface packs. Valid text translation remains available when the selected speech backend does not cover the pair.
- The final scoped host gate has 407 zero-failure/error/skipped test results: the 174-test app suite executed after the Reset correction, while five unchanged modules' results were reused. Lint completed with 21 warnings and no errors; both app APKs and the isolated model-smoke APK assembled. Final packaged JNI processed the unchanged six-human-plus-silence controls offline; the frozen 68-text policy gate accepted 67 correctly, abstained once and accepted no wrong/foreign source. These bounded checks do not certify general ASR or translation quality.
- Actual offline UI conversations completed in both directions for NL/EN, NL/RU and RU/EN on an 8 GiB-configured emulator, including unknown-language repeat feedback, subsequent recovery and idle restart. Separate genuine System TTS checks proved non-silent local PCM, completion and interruption for English, Russian and Dutch. Their existing full-catalog gate keeps its default fixtures/assertions; optional `systemTtsLanguageIds` explicitly selects a bounded run and rejects missing fixture IDs.
- Preserve the first 4 GiB failure: Android killed Google TTS for low memory before output. The later capacity-specific pass is not a minimum-RAM or phone guarantee. Dutch `varkens` → `vakers` recognition loss, a nonsensical Dutch→Russian translation and Russian `Иди сюда` → English `Go here` remain real observed meaning problems. Previous latency failures and ML Kit content-log privacy also remain unresolved.
- Independent review caught and the implementation corrected a settings regression: Reset from NeMo/Sherpa now refreshes installed System recognition-service choices in the still-open sheet. The focused state-transition test failed before the correction and passed afterward; both saved-backend transitions and real provider selection were exercised on the emulator without reopening the sheet.


### Dutch catalog integration — 2026-10-20

- Added Dutch (`nl`) to the shared 17-language catalog, with system recognition/output locale `nl-NL`, localized language names and interface-locale registration.
- Kept ML Kit method evidence unchanged: English↔Dutch remains executable `Unknown`, Russian↔Dutch uses SDK-managed `Pivot(en)`. No Dutch Sherpa/Piper models or automatic backend substitution were added.
- Extended existing synthetic speech/voice inventories with Dutch fixtures and cohort-derived totals (34 synthetic turns); the original human32 cohort and assertions remain unchanged.
- Added the complete Dutch interface pack (177 translatable strings); independent source/localization review found no blocking issues. Actual Conversation, Text, Settings and Help surfaces were inspected in portrait and landscape at 130% font scale; Conversation and Text were also inspected at 100%.
- The bounded host gate passed: 373 tests executed, 19 test-kit results reused, lint completed with 20 warnings and no errors, and both debug APKs assembled. Actual app-UI preparation and network-disconnected English↔Dutch and Russian↔Dutch text checks preserved the fixed name, negation, yesterday/tomorrow, ticket-count and departure-time controls.
- Dutch conversation remains device-dependent: the API 37 x86_64 verification setup reported unsupported selected-pair recognition and a missing local voice, with Start disabled. Provider language installation was not authorized in that shared-device window, so Dutch ASR/source-routing/audio completion remains unqualified; no general translation-quality, GrapheneOS or real-phone claim is made.

### 0.1.1 development prerelease — 2026-10-20

- Published explicit Google recognition-service selection and signer-based admission for the GrapheneOS compatibility fix. VersionCode is now2 so release updates are distinguishable from0.1.0.
- Limited host-check push triggers to branches; tag-only pushes no longer start a duplicate check run. Pull-request and manual checks remain available.

- Added a local Windows release APK builder with external signing credentials, signature and 16 KB alignment verification, SHA-256 output, and packaged license texts and third-party notices.
- Local development tools and workflow history (`.agents/`, `.omp/`, `docs/archived/`, plans, reviews, brainstorms and iteration notes) are excluded from the source distribution; current setup and verification instructions remain versioned.
- Translated the domain thesaurus into English and restored it to version control, preserving all 36 canonical identifiers and their concept relationships; naming guidance continues to use the `ubiquitous-language` skill.

### Explicit Android recognition service — 2026-10-20

- Added a persisted nullable recognition-service preference in settings schema6, with lazy schemas1–5 migration to the unchanged designated route, installed-service discovery and visible missing/incompatible selections.
- Qualified the explicit Google recognition component only for versionCode210683483 and its pinned current signer; changed identities/releases fail closed without cloud or provider fallback. This does not qualify GrapheneOS, ARM64 or future provider releases.
- Added bounded Google error14-to-real-inventory compatibility while retaining both-installed language readiness and the original support deadline; generic14 remains a failed check.
- Gate explicit PCM delivery on actual ready-for-speech plus fresh identity/generation checks, bind inventory readiness to that installed identity, and revoke pending/active work on observed package changes with FD closure and awaited session/writer cleanup.
- Added a localized app-originated, selected-language public installer action that releases runtime ownership before provider consent. Cancellation/return only rechecks readiness and never starts capture or a second installer automatically; the default route keeps its native preparation semantics.
- Added the explicit-route production check using the unchanged full human32 cohort and regressions for identity changes, callback/deadline races, installer confinement, owner cutover and large-font recovery. Build/device results remain separate verification evidence, not release qualification.

### Google Speech signer-based admission — 2026-10-20

- Removed the version allowlist after the ARM64 variant210683481 exposed false incompatibility with the emulator-only210683483 policy. Admit the canonical enabled/exported Google recognition service with either inspected current Google signing certificate, regardless of version; unknown/current-multiple signers and other components remain rejected.
- Preserve full version/update-time/signer identity for update invalidation, stale-inventory refusal and ready-before-PCM barriers. Extended consumer regressions for non-allowlisted trusted versions, stale eligible identities and explicit runtime errors before/after ready, with no same-turn retry/download or translation/output on failure.
- This explicitly trusts the signed Google provider to honor offline requests; installed-language checks and `PREFER_OFFLINE` are not network-isolation attestations. Historical ARM binary inspection and x86 offline cohorts do not prove actual GrapheneOS/ARM or universal future-release behavior.

### Runtime bounds and source preparation — 2026-10-17

- Fixed Conversation Stop/Clear waiting on a replacement session instead of the stopped session, and made committed SpeechToText Stop complete its terminal bookkeeping even when its caller is cancelled.
- Limited actual ML Kit inference to one completion-owned slot per engine and shared pending model-presence checks per language. Closing still waits for underlying SDK work, not just coroutine cancellation.
- Added a 4,096-UTF-16-unit text limit with localized field errors and rejection before provider effects. Fixed accepted-text replay after rejecting an oversized edit, including ownerless Text state.
- Replaced the vendored Sherpa AAR with explicit `:engine-sherpa:prepareSherpaArchive` preparation and exact official 1.13.8 size/SHA verification. Ordinary builds require the prepared local artifact and never download it; runtime bytes are unchanged. This does not clear native redistribution obligations or remove historical Git blobs.

### Conversation recognition and Android compatibility — 2026-10-12

- Added default Android on-device recognition through the existing conversation pipeline, with fresh FD/segmented requests, selected-pair language detection, bounded audio/text/deadlines and awaited cancellation/release. There is no cloud or hidden recognizer fallback.
- Expanded the shared catalog and platform mappings to 16 languages, including Mainland Simplified Mandarin. Catalog coverage remains distinct from installed service resources and translation quality; Sherpa/Piper retain their actual RU/EN coverage.
- Added independent advanced recognition/output choices and settings schema 5. Fresh and legacy settings select System recognition; migration preserves pair order, preferences and historical output. Unchanged legacy settings retain their original bytes.
- Raised minimum Android API to 34; compile API remains 37 and target API remains 36.
- Added typed selected-recognizer readiness and explicit selected-only installation. Scheduled downloads are not ready, and Android-managed resource sizes are reported as unknown rather than invented.
- Separated interactive model installation from the recognition deadline so user consent and SDK completion can finish without a recognition timeout. Readiness checks and recognition retain bounded deadlines; cancellation is preserved.
- Made the recognizer's owned timeout an ordinary turn failure, joined background readiness/download work before reuse, and drained queued local SDK cancellation/destruction dispatch before request handoff.
- Resolve source language only from a unique selected confident result, or a strict weak-evidence leader matching the final consecutive run. Conflicting, malformed or insufficient evidence fails rather than guessing; successful switching does not upgrade confidence.
- Fixed ordered recognition assembly to ignore null/empty/whitespace segment contributions without discarding valid text or weakening final nonblank/overflow bounds.
- Added fixed human-speech and separate synthetic functional checks through the production pipeline. Functional completion is not semantic qualification: upstream translation defects and some synthetic reference failures remain documented.

### Interface and language selection — 2026-10-12

- Simplified Conversation to current/previous turns, fixed controls and a compact starting illustration; removed repeated headings and diagnostic-heavy main-screen content.
- Unified Text around a prominent selectable result and fixed localized Translate action, preserving keyboard-open and compact-window reachability. Enforced 48 dp Help targets and corrected readiness guidance for Android-managed speech resources.
- Consolidated usage/preparation/privacy guidance into shared Help, with explicit Translation terms and stable Back/Close navigation.
- Show one unchanged Google badge above the primary action only for the actual ML Kit provider, including Conversation Stop. Removed duplicate card branding; other or unavailable providers do not inherit Google claims. Required disclaimer and provider links remain.
- Added immediately persisted interface-language selection via Android LocaleManager and app-owned resources for all 16 catalog languages. Locale changes are independent of the main pair and advanced drafts/reset, with in-place locale/layout-direction updates.
- Removed duplicate single-provider Settings controls while retaining real alternatives, invalid-choice recovery and provider information.

### Shared pair, output adapters and lifecycle — 2026-10-08

- Replaced independent Text/Conversation choices with one ordered main-screen language pair, accessible swap and bounded localized chooser. Valid text-only pairs persist and remain editable without creating a conversation owner.
- Added schema 4 migration from schemas 1–3 using the former conversation pair and existing output. Advanced Apply patches the latest pair; advanced Reset preserves it. Schema 5 later supersedes changed writes.
- Enabled SDK-supported ML Kit Direct/Pivot/Unknown pairs consistently across validation, registry, preparation and inference; genuine Unsupported pairs remain rejected. Pivot uses the original SDK pair API, not manual double translation, and preparation downloads only selected resources.
- Integrated Android local TextToSpeech as a selected output adapter alongside Piper. System output requires eligible installed local voices, exact voice activation and actual-engine identity checks; it never silently falls back to another engine. System selection does not prepare Piper weights.
- Distinguished phase-only System playback callbacks from physical audio presentation timing. They no longer produce numeric latency or stale timing warnings; Piper retains AudioTrack-backed timing.
- Serialized settings and real route/ViewModel work through runtime ownership, retained compatible owners on no-op/cross-mode changes, and required awaited teardown before replacement or save.
- Made cleanup barriers retain typed failures while awaiting remaining resource releases. Native stop errors cannot escape synchronous UI lifecycle callbacks or be mistaken for successful teardown.
- Fixed stale owner-open requests incorrectly classifying a newly selected text-only pair as a failed conversation open. Teardown, save and current-configuration open failures remain distinct.
- Fixed settings stream I/O failures being treated as corrupt configuration and wiping saved preferences.

### Extensible language and model configuration — 2026-10-04–07

- Introduced `LanguageId`, `LanguagePair` and centralized `LanguageCatalog` in place of closed language/direction enums; migrated pipelines, evaluators and instrumentation consumers.
- Added honest Direct/Pivot/Unsupported/Unknown capability metadata. Initial direct-only execution restrictions were superseded by SDK-supported pivot/unknown execution on October 8.
- Added English↔German text translation, then English↔French/Spanish/Italian/Portuguese. These additions do not supply speech models or certify semantic quality; upstream meaning failures remain known.
- Added schema-2 speech model metadata with typed compiled adapter families and derived package requirements. Replaced component/global installation APIs with selected-package installation and availability.
- Added lazy per-selected-package migration that verifies and reuses existing model directories without copying or re-downloading; unselected packages are not scanned.
- Added typed DataStore settings, bounded accessible advanced editing and application-scoped serialized runtime ownership.
- Derived RU/EN ASR and Piper voice choices from the language pair instead of manual model selectors. Schema 2 removed voice choices; schema 3 removed ASR maps while preserving unrelated legacy preferences. Alternate model catalog/evaluator support remains.
- Added a pinned Whisper tiny token package so alternate English recognition can reuse multilingual language-identification weights.

### Developer tooling and project materials

- Added `tools/android/run-device-gate.ps1` for existing instrumentation with continuous logs and JSON outcomes; incomplete runs, empty selections and native failures cannot produce a successful exit solely because adb exited successfully.
- Added pinned, read-only-permission GitHub Actions host checks and contributor/security guidance. CI configuration alone is not evidence of a completed remote run.
- Added Apache-2.0 licensing for original project materials, Daniil Sterkhov (`Ngoroth`) attribution, preserved upstream license texts and separate native/model/audio notices. Third-party rights are not relicensed by the project grant.
- Added the architecture/extension guide and code-backed Russian domain thesaurus. Agent skills are optional ignored local development tools, not contributor or build prerequisites.
- Added real app screenshots and an offline conversation video/GIF using the committed fixture through microphone/VAD/recognition/translation/output. Media disclose prepared resources, idle-time trimming, unchanged translation wording and no speed-up; they do not qualify natural speech or latency.
