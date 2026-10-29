# Android verification guide

This is the current entry point for running checks, not a record of a passing device run. Historical execution records are local development materials and are not included in the source distribution.

## Host checks first

Run the existing [host commands in CONTRIBUTING](../../CONTRIBUTING.md#baseline-host-checks), including the separate build-logic tests. Record the source/build identity, complete command output, JUnit results and lint findings. An assembled instrumentation APK has **not** run its tests. Reused outputs are not fresh execution evidence.

Host checks do not require a device or speech-model downloads. They do not prove JNI inference, installed system speech resources, translation quality, real audio, content-log privacy or phone performance.

## Device prerequisites

- Use one verification owner and run instrumentation sequentially. Do not run a competing UI-automation session while instrumentation owns the device.
- Use an explicitly selected emulator/device. The commands below use the existing documented `emulator-5554` setup; change the serial for your own device.
- Have both debug APKs built, `platform-tools` installed and `ANDROID_HOME` set. System recognition/output availability depends on the device, provider and installed local resources.
- Before changing device state, preserve the **current** settings, permissions, connectivity, interface language, font scale and orientation. Keep evidence outside the checkout. Never restore a historical session's snapshot or clear someone else's data/model packs.
- Use only committed or otherwise approved fixed fixtures. Keep the host microphone disabled for injected-audio checks. Do not collect personal conversations or publish raw logcat.
- Prepared FD-injection instrumentation still requires the application's `android.permission.RECORD_AUDIO` runtime grant: the production adapter checks it before each turn. Record its original state, grant it explicitly for the fixture run when needed, keep host audio disabled, and restore the original grant afterward.

These operations install APKs and can change app/device state. Arrange device access and resource preparation before running them; this guide does not authorize changes to another person's device.

The preservation/restoration rules are the default for shared devices. An explicit
owner grant of exclusive, disposable emulator use may waive restoration and permit
RAM/state changes; record the actual configuration and do not extend that grant
to host backups, another worktree, release keys or another device.

## Install and prepare explicitly

Run commands from the repository root in PowerShell:

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

### Fresh translation choice, settings and Reset

Use an explicitly authorized fresh current settings state. Before either route
mounts, verify the compact Fast (ML Kit) / Accurate (Tencent) dialog. Choose each
option in separate fresh-state checks; one committed choice must select both
Text and Conversation, survive restart and dismiss setup only after save succeeds.
Opening or choosing must not load models, download or request microphone permission.
Exercise failed persistence and explicit retry with test-owned fault injection;
engine selection and setup completion must remain atomic.

Settings initially shows only the shared translation choice, interface language
and voice speed. Expand Advanced for Whisper/System recognition, conditional
System service, System/Piper output, silence and Reset. Recovery links must open
Advanced directly. Verify Apply versus Close/Back, immediate locale changes,
and reachable actions in portrait, landscape and font scale2.

Reset immediately restores Tencent/Whisper/System and speech output on while
preserving pair, locale and completed setup; Close must not undo it or reopen setup.
Ordinary Apply and pair changes preserve the latest speaker choice. Schema7 is
the current-only format; documents without `speechOutputEnabled` default to true,
but older schema7 binaries reject the newly written field. Restore their original
raw settings before reinstalling one. Earlier-schema migration and replacement
notifications remain outside this development scope. Shared-device preservation
is required unless the resource owner explicitly grants disposable use.

For an actual default-route check, confirm all selected providers, explicitly
prepare the resources, disable connectivity and restart the process. Exercise
Whisper → Tencent → genuine local System output in both RU/EN directions, Stop
and idle reentry, plus one Tencent Text request. Inspect fixed input/output
meaning and non-silent local synthesis; a completed label alone is insufficient.
Existing System-recognition or ML Kit-specific cases below are separate checks,
not proof of this combined route. Record UI, inference and restoration outcomes
separately. Insufficient model storage is **BLOCKED**, not a passing inference
check or permission to delete another owner's model packs.

### Optional Conversation speech output and actual capture status

For the bounded output-control checks, prepare pinned RU/EN Whisper and VAD,
the selected Tencent model, genuine offline System voices and the existing Piper
RU/EN voices. The unavailable-Piper case also uses prepared Whisper/Tencent
EN↔NL; it must not install or pretend to support a Dutch Piper voice. Verify no
active networks. Install and record the exact current app/test APK hashes, keep
one device operator, and run:

```powershell
pwsh -NoProfile -File tools/android/run-device-gate.ps1 `
  -Adb $adb -Device emulator-5554 `
  -CaseList 'com.example.offlinetranslator.runtime.ConversationSpeechOutputNativeTest,com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest#speechOutputChoiceKeepsTheLiveOwnerAndPersistsSilentTurnsAfterReopen,com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest#unavailablePiperEnableKeepsTheSelectedPairAndCapturesAnotherSilentTurn' `
  -EvidenceDir "$env:TEMP/conversation-output-$([Guid]::NewGuid().ToString('N'))"
```

The direct-owner cases use production Whisper/VAD/Tencent/System/Piper and fixed
paced capture, with only narrow test-local observers. Inspect
`files/conversation-speech-output-proof/{system-offline,piper}.json`: actual
translated silent turns with no output preparation/request, genuine new-turn
speech after enable, mute during in-progress output, retained session/text,
another silent turn, and stopped state. System evidence includes genuine local
non-silent PCM and SDK stop; Piper observes its actual production playback start
and joined continuation. These are not physical microphone/speaker measurements
or translation-quality qualification.

The focused Home/Route cases click the actual toggle, preserve the live capture
owner, reopen with output still off, and continue after unsupported Piper enable
without changing the selected backend/pair. Inspect their new
`files/whisper-recognition-proof/conversation-output-*-ui.{png,txt}` artifacts;
do not confuse them with old proof files or only an isolated composable.

For supported RU/EN Piper with missing/damaged voice data, verify the separate
resource-recovery boundary in `ConversationViewModelTest`, especially
`missingPiperVoiceRecoveryPreparesOnlyAfterStopAndRequiresExplicitEnable`.
Failed enable must retain its real missing-voice reason and silent capture;
preparation is rejected while active, then explicit Prepare after Stop includes
the selected voice resources while effective/saved output stays off. Later
enable must succeed only once local voices are ready. Ordinary silent Prepare
must still omit voices; failed voice recovery and background cancellation must
not prevent a later silent Start when base resources remain usable. The matching
`ConversationScreenTest#missingPiperVoiceShowsActualReasonAndPreparationBecomesEligibleOnlyAfterStop`
checks actual reason and active-to-idle recovery eligibility. These controlled
boundary tests do not claim a network download or missing-voice device run.

Run `ConversationScreenTest` through the same runner separately. Also inspect the
actual full MainActivity/Home window in portrait, landscape and font scale2.
Observe capture during recognition/translation, true waiting during output,
silent capture, both speaker states and failed-enable recovery. Check the pinned
panel, touch targets, Start/Stop, recovery and attribution while content scrolls.
The microphone cue follows fresh active capture, not phase alone; neutral
completion text must not claim audible playback. Keep meaningful assertions and
fixed fixtures unchanged when a model gives an undesirable result.

Pull proof before cleanup; stop all recording/playback and restore the verified
current baseline, including raw settings before an older APK, private/external
state, grants, locale, font, orientation and connectivity. Verify restoration and
declare the exact serial free before another operator uses it.



First exercise **Prepare languages through the actual app UI**. Record missing, unsupported, scheduled, failed and ready states; Start must not become ready merely because a download was scheduled. Open the actual selected engine's voice-data installer when necessary. Do not replace consumer preparation evidence with preinstalled resources from a helper.

For explicit System recognition, select the installed service in advanced Settings and Apply. Admission requires the canonical Google component in `AndroidRecognitionServices` and a singleton CURRENT signer `d22cc500299fb22873a01a010de1c82fbe4d061119b94814dd301dab50cb7678` or `7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053`; there is no version allowlist. Wrong components/unknown current signers must remain incompatible. Verify version-only, update-time-only and trusted-signer changes invalidate stale preparation/inventory and release no PCM before fresh readiness, including before package broadcasts arrive. Readiness requires both selected local languages actually installed, not merely online/supported/pending. Check that Close discards the draft, Reset restores the designated route, restart preserves Apply, and an unavailable saved component remains repairable. Inventory and offline request parameters do not certify future provider/network behavior; the signed Google implementation is trusted to honor its offline contract, not sandboxed by this app.

When a qualified selected language is missing, use **Install recognition language data** from Conversation. Observe the actual provider confirmation, cancel once, then use a fresh explicit action to confirm one missing locale. Return must remain nonrecording and only freshly check inventory; supported/pending/download-success are not both-installed readiness. Reuse existing packs and select an additional supported missing language rather than deleting prepared RU/EN. Generic Prepare must not await Google's known nonresponding SDK download callback. The default designated route keeps its separate native preparation semantics.


After that public preparation check, the existing selected-resource inventory preparation can run:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#prepareSelectedLanguages' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

Preparation can download resources and requires connectivity. It is separate from the offline proof. Preserve the original provider errors; do not retry or swap fixtures to turn a failure into a pass.

## Whisper selected-pair checks

Prepare both native distributions before assembly as documented in
[Build and run](../../README.md#build-and-run). Ordinary Gradle must reject
missing/stale/stock native output, not repair it. Record unique APK filenames
or SHA-256 and confirm the installed artifact before attributing runtime reports.

In the actual app, confirm **Whisper · offline recognition** in Advanced Settings.
Choose the pair and use **Prepare languages**. Whisper uses one shared
375,485,327-byte model, not Android recognition packs or a separate language-ID
model. Prepare genuine local voices and the selected translation provider
separately. The shared Fast choice uses ML Kit resources; Accurate uses Tencent.
Metadata coverage alone cannot enable a usable spoken conversation.

After verifying complete connectivity absence, run the Whisper-specific real
production UI/VAD/recognition/MT/output case:

```powershell
pwsh -NoProfile -File tools/android/run-device-gate.ps1 `
  -Adb $adb -Device emulator-5554 `
  -CaseList 'com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest#whisperUsesSelectedPairAndRealOfflineVoicesInBothDirectionsForDutchEnglishRussian' `
  -EvidenceDir "$env:TEMP/whisper-ui-$([Guid]::NewGuid().ToString('N'))"
```

It preserves fixed-fixture screenshots/semantics under
`files/whisper-recognition-proof/`; inspect the actual default/large-font and
landscape surfaces. Inspect all six source/direction/translation outcomes rather
than treating COMPLETE as a translation-quality or physical-audio assertion.
Use the existing System TTS proof with `systemTtsLanguageIds=en,ru,nl` for genuine
offline voice/non-silent PCM/completion/interruption evidence.

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.SystemSpeechOutputNativeTest#allSelectedLocalesHaveGenuineInstalledOfflineVoiceAndCompletedPcm' -e systemTtsLanguageIds 'en,ru,nl' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The optional argument selects only those fixed fixtures and rejects unknown or
missing fixture IDs. Without it, the existing full-catalog gate is unchanged.
Do not advertise this selected three-language run as full-catalog qualification.

The isolated model-smoke boundary case is
`com.example.offlinetranslator.modelsmoke.WhisperLanguagePairNativeTest#selectedPairProducesItsOwnTranscriptAndRejectsInvalidConfiguration`.
Build **both** APKs with
`./gradlew.bat :engine-sherpa-model-smoke:assembleModelSmoke :engine-sherpa-model-smoke:assembleModelSmokeAndroidTest`;
assembling only the test APK does not refresh the target's catalog/native libraries.
Install both freshly identified APKs and use runner
`com.example.offlinetranslator.modelsmoke.test/com.example.offlinetranslator.modelsmoke.EvaluationInstrumentation`.
Pass instrumentation argument `whisperModelInput` naming an accessible, absolute canonical owned
directory. It must contain the three exact catalog package-ID directories,
each with its declared single file. The existing verified evaluator import
checks/copies only this selected catalog; it neither downloads nor imports
private production audio. Invalid/duplicate/unknown/forced-language requests
must produce no text or direction; the valid case must preserve real speech
meaning and the native-produced completion status.
Use the application's canonical `/data/data/com.example.offlinetranslator.modelsmoke/files/...`
input path; shell `realpath` can instead return the noncanonical `/data/user/0/...` alias.
For binary stdin staging, use `adb exec-in run-as ...`, not `adb shell` stdin;
verify every staged SHA256 before execution. Keep the production import checks intact.

Keep raw no-speech behavior visible: a low-level decoder may hallucinate on
silence, so prove the production VAD creates no accepted turn for that control.
For bounded speed proof, record the exact models/runtime/device, cold preparation,
warm whole recognition and native encoder/SOT/transcription counts. Compare a
matched stock control; stage counters must use actual execution, not source text.
ASR-only timing and System TTS onStart do not pass the2000ms
speech-end-to-first-non-silent-presentation goal. Record missing/failed timing
as such. None of these commands by itself records user acceptance.

### Bounded emulator evidence — 2026-10-22

The evaluated debug candidate is `offline-translator-whisper-883a48c6.apk`
(SHA256 `883a48c6a5f0c137544922033c07eaf956a2a0f7f83c845ee12744fb83d9762d`,
version0.1.1/code2), intended to update the existing debug-signed
`com.example.offlinetranslator` without clearing its data.
These are x86_64/API37, CPU4, 8GiB-emulator observations, not ARM64 phone qualification.

All six selected-pair directions completed through actual VAD/Whisper/ML Kit/System
output, and the real no-speech control created no accepted turn. The semantic
gate nevertheless **fails**: `nl-fleurs-test-1.wav` produces `Valkers` instead of
`varkens`, changing the subject of the sentence. The expected meaning remains in
the test; it was not replaced with the erroneous transcript. Original full PCM
recognizes the word correctly, but both the actual VAD slice and a bounded
restored-prefix comparison retain the error. No speculative production padding
or model change was applied. Existing ML Kit meaning problems remain separate.

Real same-turn JNI-stack witnesses bracketed Stop, Clear, foreground cleanup,
pair disposal and rejected busy-backend save. Joined cancellation invoked neither
real MT nor real output; subsequent explicit starts and existing Piper output
executed. Genuine System voices produced non-silent local PCM and completed or
stopped for English, Russian and Dutch. The unrelated full legacy-ASR control
could not start because its GigaAM/tiny/LID resources were not installed; it is
not reported as a native execution pass.

Warm native language-step observations were34.6–35.8ms with one encoder and one
transcription. A single EN→RU Piper turn reported1997ms from its paced-fixture
end marker to measured AudioTrack presentation. Other recovery fixtures had end
markers later than output and therefore invalid/null intervals; System onStart
remains phase-only. Neither those intervals nor ASR-only timing establish the
overall six-direction2000ms goal.


## Tencent-specific preparation and native checks

Run explicit Tencent native preparation before the host gate; see [CONTRIBUTING](../../CONTRIBUTING.md). No host/CI gate downloads the 1,133,080,448-byte model. In the actual app, confirm Tencent independently in Text and Conversation: fresh installs and Reset select it for both, while older valid saved selections require an explicit change and Apply. Press Prepare over validated Wi-Fi, and record missing/downloading byte progress/verifying/loading/ready. Check available private storage before preparation; the model requires its full declared bytes plus installation headroom. Reopen and explicitly initialize verified installed bytes locally. Readiness must never adopt corrupt bytes or enable Conversation before the selected recognition/output stages are ready. Missing/corrupt/storage/load/memory/limit/Unicode/Wi-Fi/timeout outcomes must stay localized and must not fall back to ML Kit.

After real UI preparation, verify no active networks and restart the app. The new app instrumentation selectors are:

```powershell
pwsh -File tools/android/run-device-gate.ps1 `
  -Adb $adb -Device emulator-5554 `
  -CaseList 'com.example.offlinetranslator.runtime.TencentTranslationNativeTest,com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineTencentRussianEnglishConversation' `
  -EvidenceDir "$env:TEMP/tencent-app-$([Guid]::NewGuid().ToString('N'))"
```

The Text scenarios use the real selected registry/owner/JNI with a runtime restart, cancelled request join, owner replacement and no late delivered text. A runtime reconstruction is not itself OS force-stop/process-restart evidence: additionally force-stop/reopen through the actual app. The Tencent Conversation selector explicitly creates the Tencent provider, real selected System recognition and observed non-silent local System speech for fixed RU/EN synthetic functional input. It is not an ML Kit result, natural-speech claim, full semantic qualification or a substitute for rendered Stop-mid-generation evidence. Retained Sherpa/Piper combinations need their actual installed readiness and separate execution; no speech inventory expansion is implied.

`engine-tencent/src/androidTest/.../TencentNativeRuntimeTest.kt` adds real JNI request isolation, approved newline equivalence, Unicode/NUL/literal-marker input, persistent cancellation/reset and original input-limit coverage. Its `frozenChineseToThaiCompletesWithStrictUnicodeAndRetainsNameTimeAndPrice` regression uses the exact frozen Chinese fixture that failed with InvalidEncoding under unconstrained greedy. It requires a successful complete strictly decoded Thai result and tolerant Anna/08:30/25-euro fact checks, allowing Thai digits, common name transliterations and numeric time formatting rather than a sentence snapshot. Successful native return requires the allowed EOS, not partial output. Script/fact checks do not certify target-language confidence or full semantics, including the platform question, price basis or negation.

Assemble the separate library instrumentation APK with `:engine-tencent:assembleDebugAndroidTest`, install it under the same exclusive owner and pass `-e modelPath <absolute readable pinned GGUF path>` using the [pinned model identity](../../README.md#tencent-model-preparation-and-boundaries). Select `com.example.offlinetranslator.tencent.TencentNativeRuntimeTest#frozenChineseToThaiCompletesWithStrictUnicodeAndRetainsNameTimeAndPrice` for the regression or the whole class for all native checks. This suite never downloads weights; invalid/missing model or incomplete instrumentation fails rather than assuming PASS. It does not alone prove in-decode cancellation; retain actual native callback and app Stop evidence for that claim.

Record exact model/runtime/native-AAR/APK identities, both packaged ABIs and 16-KiB LOAD alignment. Separate all-240 execution/target-language evidence from the fixed 34-direction semantic sample. Freeze source fixtures before generation, retain failed/UNKNOWN outputs without replacement, and use a separately pinned host-only FastText lid.176 evaluator (target top1 confidence at least 0.80 and margin at least 0.20); do not bundle it or equate language identification with meaning. Semantic checks need bilingual reasoning for negation, invented names, numbers, prices, times and role relationships. Chinese target assessment also needs Simplified-script checks beyond a `zh` classifier label.

The pinned runtime uses deterministic strict UTF-8-prefix-constrained greedy decoding, not the earlier unconstrained greedy policy. Preserve eligible greedy maxima/ties; otherwise choose the highest-logit eligible token before commitment without retrying inference. Valid split scalars/NUL remain supported, invalid/impossible prefixes are ineligible, and EOS120020 is allowed only at a complete scalar boundary. No output repair, replacement decoding, byte dropping, model/prompt change or provider fallback is involved. Bounded context and explicit token/output/no-eligible-candidate failures remain; no truncated success is permitted. Original source remains displayed unchanged; only Tencent inference normalizes CRLF/CR to LF.

Corrected revision4's actual-app run completed **all 240 distinct directed pairs with zero execution failures**, using the unchanged 16 source fixtures and pinned Q4_K_M bytes. The unchanged FastText control yields **236 PASS / 4 FAIL**: es/it/th/tr→id all have Indonesian top1, but confidence below 0.80 despite sufficient runner-up margins. These remain failed confidence checks, not proven wrong-language outputs. The frozen 34-direction semantic sample remains **26 PASS / 8 FAIL** with actual source/output bytes and bilingual reasoning retained. Separately, hi→zh changed tomorrow to yesterday. The disclosed model-fidelity and confidence limitations are accepted only for experimental additional-provider use; they are not technical PASS or whole-iteration/phone acceptance.

Retain revision3's **239 completed / one zh→th InvalidEncoding execution failure** as historical evidence, not the current execution count. Corrected revision4's actual x86_64 JNI suite passed all three methods, including the frozen zh→th regression; its Android C++ smoke passed canonical prefix boundaries, real EOS output, reuse, abort/join and reset. The completed current raw corpus has SHA-256 `c4870d289219fc75a2613017aee4f39d5636ec610a613ebc6ca7f6140f5c653b`. Record source/build identities and final qualification in the [continuing review/validation artifact](../workflow/archive/2026-10-20-0230-tencent-local-translation/reviews/tencent-local-translation.md), keeping older failures and scoped acceptance separate.

Keep memory and interruption environments distinct:

| Observed emulator profile | Retained observation | Qualification boundary |
| --- | --- | --- |
| Original 4-GiB, Tencent + Sherpa/Piper | Android LOW_MEMORY during instrumentation and visible foreground startup | That complete speech combination remains unqualified on this profile. |
| Original 4-GiB, system speech + Tencent | Earlier output initialization failure; corrected-build synthetic ASR→MT Stop probe later reached native translation and joined with an INTERRUPTED turn and no delivered translation | The Stop probe does not establish completed system TTS on this profile or erase the earlier failure. |
| Explicit 8-GiB cold boot, corrected-build bounded no-interruptions DND diagnostic | Synthetic RU/EN system recognition/Tencent/local system output completed in both directions; Sherpa/Tencent/Piper completed RU→EN | Not ordinary-notification behavior, 4-GiB compatibility, phone acoustics or a sole-cause diagnosis. Original DND policy/access was captured and restored exactly. |
| Selected 8-GiB cold boot, ordinary DND-off and verified network absence | System RU↔EN ASR→Tencent→local TTS completed with actual non-silent synthesis; installed-ready Sherpa/Tencent/Piper completed RU→EN. After parent-observed OS force-stop/PID termination, one new process completed new Text and full System-speech Conversation in the same real MainActivity. | Fixed synthetic PCM capture only; no notification/focus override, real-microphone, acoustic, all-16-spoken-language, 4-GiB or phone claim. Raw helper failures and earlier diagnostics remain retained. |

Keep the original sampled-entry-only Stop probe separate from corrected revision4's actual native-entry/exit, blank interrupted result and awaited join evidence. Corrected Text HOME/reentry positively observed the Activity STOPPED, retained the original source and delivered no old result; the older unconfirmed-background attempt is not that proof. Finishing during translation and reopening removed the GGUF mapping without delivering the old Text result. The Text footer is disabled Translate while busy, not Stop: only the actual Conversation Stop action supports that claim.

Revision5 also passed actual-playing Conversation Stop, HOME/whole-Activity STOPPED and reentry, and language disposal through the real MainActivity/root. A bounded external SDK debugger observer recorded the non-silent/head-progress-guarded Piper start and public AudioTrack playing/positive head before the actual lifecycle action, then interruption, pause/flush and normal release return. No natural full drain preceded the action and no AudioTrack.play on any owned process track appeared through the bounded quiet interval. Busy provider Apply was refused; joined idle Tencent→prepared ML Kit→Tencent replacement and subsequent real healthy turns passed. The observer detached before healthy-turn checks. This is causal functional evidence, not acoustic, latency or uninstrumented-scheduling qualification. Earlier overflowing/nonmonotonic full-METHOD traces remain diagnostic history and do not establish temporal PASS.

Revision5 exercised real model-file read denial on both routes: cold app-UID hashing produced Storage, while a verified unchanged identity followed by native open denial produced ModelMissing, **not RuntimeLoad or native OOM**. Original mode/bytes were restored, actual Retry/Prepare recovered, and real translation completed; actual Conversation Wi-Fi refusal/download/progress and Help/Terms were inspected separately.

The exceptional consumer checks control only typed TranslationEngine failures while mounting real owners/ViewModels/pipeline/screens. Text accepted 4,096 UTF-16 units, rejected 4,097 without replacing the accepted source/result, disabled the action and recovered through editing. Memory/RuntimeLoad/TokenLimit/OutputLimit retained source, localized errors and no partial success; Conversation produced no SDK speech request or synthesis on failed turns, then actual pinned JNI and non-silent local System speech completed all four recoveries. Memory/RuntimeLoad needed explicit Prepare plus the visible System **Recheck** when real output readiness reported INITIALIZATION_FAILED; do not claim Prepare alone fixed that independent refusal. Limit errors recovered on a new turn in the same session. These are consumer-contract failures, **not induced native allocator/default-cap failures**, and the fixed recognition/model-readiness fixtures do not qualify actual ASR or installed speech inventory.


Qualify actual Android resource use separately from file size or host feasibility RSS. Arm64 packaging/link evidence is not arm64 phone execution, sustained latency, heat, microphone/speaker or memory-pressure qualification. Those phone checks remain pending for the user.


## Prepared offline conversation checks

After saving the original radio state, disconnect networking and **verify actual connectivity absence**, not just the airplane-mode icon. Force-stop and reopen the prepared application before an offline restart check. Restore the original state afterward.

Run the original human fixture cohort for both designated and explicit routes, and keep synthetic functional coverage separate:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineProductionConversation' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineExplicitGoogleProductionConversation' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineSyntheticProductionConversation' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The human cohort checks the pinned RU/EN inputs across the existing orders/directions. The synthetic cohort exercises installed-local-TTS functional coverage and selected translation/output checks. Synthetic completion is not a substitute for a failed human cohort, natural-speech coverage, all-direction semantic quality or microphone acoustics. Record readiness, source resolution, translation meaning and actual non-silent output completion—not merely a phase callback.

The two human methods reuse the same full32 rows, directions, original assets and assertions. Keep complete terminal outcomes and retained per-row failures; do not narrow the explicit cohort, weaken confidence/meaning rules, or substitute synthetic success. A passing prepared-provider probe is not whole-app VAD/ASR/LID/MT/output evidence.

The synthetic method derives its expected turns from the actual catalog-driven pair cohort: currently 34 (each non-English language paired with English, plus French↔Spanish). Emitted expected/unreached totals use that cohort rather than the human32 count, including fatal or unattempted rows. `SystemSpeechOutputNativeTest#allSelectedLocalesHaveGenuineInstalledOfflineVoiceAndCompletedPcm` checks genuine local voices across the catalog, including Dutch; its fixed fixtures do not establish natural human speech quality.

For a controlled missing-ROM-component experiment, use a separate disposable/rootable Android emulator with host audio disabled, the exact qualified Google base/splits, and selected packs prepared through the public installer. Save the effective on-device resource and overlay state, blank only `android:string/config_defaultOnDeviceSpeechRecognitionService` with a scoped fabricated overlay, then verify the actual app's designated route is unavailable while its explicit route still recognizes fixed EN/RU through the production pipeline. Restore the overlay and shut down that emulator afterward. This is an Android resource experiment, not GrapheneOS reproduction, ARM64 qualification or a guarantee of version-atomic service binding.


Additional existing boundaries can be checked independently:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.SystemSpeechOutputNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.RuntimeSettingsNativeTest,com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.AndroidSpeechPcmWriterNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The whole `SharedLanguagePairNativeTest` class includes the Whisper checks above;
its full-class invocation requires the actual prepared resources. Select
individual methods when qualifying only an existing System/Sherpa boundary.

These focused commands are not an exhaustive or release-qualifying device suite.

## Focused Dutch verification

Dutch source integration adds `nl` to the 17-language catalog and maps system recognition/output to `nl-NL`. Catalog integration alone is not a passing device result; this section describes the System/text procedure, while the Whisper gates above exercise a separate selected backend. Keep functional, semantic, memory and physical-device qualification distinct.

- After all source/localization edits settle and host-build access is granted, run the relevant existing host gate once and check Dutch translatable-key/format-signature parity with a throwaway script. Do not add permanent resource-copy tests or duplicate old per-language matrices.
- After explicit device handoff, preserve current APK identities, private settings/packs, grants, connectivity, app locale, font scale and rotation. Through the actual UI, select Dutch in both ordered pair positions on Conversation and Text, swap, and relaunch. Select the Dutch interface (`Nederlands`) and inspect Conversation, Text, Settings, Help, dialogs, preparation and errors in portrait, landscape and increased font scale. Existing shared-pair/interface tests do not automatically exercise Dutch.
- Prepare selected English↔Dutch and Russian↔Dutch resources online through the actual app UI, then disconnect, verify connectivity absence and force-stop/reopen. Execute fixed invented text in all four directions with negation, `Anna`, two tickets, tomorrow/yesterday and 15:00 versus 16:00. Retain exact unchanged inputs/outputs and independently assess meaning; do not retry modified inputs or promote method metadata based on fluent text. English↔Dutch is executable `Unknown`; Russian↔Dutch is SDK-managed `Pivot(en)`.
- Check actual installed local `nl-NL` recognition and voice readiness through the selected System backends, using the provider's public installer explicitly when missing. Unsupported/missing System resources must stay non-ready; Dutch does not gain Sherpa/Piper coverage. Selecting Whisper is an explicit separate action, not a way to relabel a failed System gate. With genuine selected-backend readiness, run fixed Dutch↔English and Dutch↔Russian audio through production VAD/ASR/source resolution/ML Kit/output. Retain detected source, transcript, translation, non-silent output evidence and unchanged errors.
- Keep temporary production-runtime smoke code and captured outputs outside the permanent source changes; remove temporary source afterward. Narrow Dutch evidence does not require running all-language preparation/cohorts. Synthetic functional success is not human-audio, GrapheneOS, ARM64 or phone-acoustic qualification; finish with the state-restoration procedure below.

## UI, lifecycle and evidence

Inspect the actual rendered Conversation, Text, Settings and preparation/error states. Cover language/pair changes, Start/Stop, background/re-entry, late completion, portrait/landscape, larger fonts, long content and reachable controls. Source inspection or semantic assertions alone do not establish visible layout or hit-area behavior.

For retained complete instrumentation output, use the PowerShell 7 [device runner](../../tools/android/run-device-gate.ps1) after installing the APKs:

```powershell
pwsh -File tools/android/run-device-gate.ps1 `
  -Adb $adb `
  -Device emulator-5554 `
  -CaseList 'com.example.offlinetranslator.speech.SystemSpeechOutputNativeTest' `
  -EvidenceDir "$env:TEMP/translator-device-$([Guid]::NewGuid().ToString('N'))"
```

The runner returns exit 0 only for a completed successful test selection; native errors, failed or incomplete instrumentation return nonzero. The evidence directory must not already contain runner output. Installation, device exclusivity, fixture selection and state restoration are caller-owned.

A valid result identifies the exact APKs, device/provider versions, installed resources, selected test methods, terminal runner outcome and any remaining limitations. Keep FAIL, UNKNOWN, incomplete and BLOCKED distinct. Local temporary evidence paths in the archive are historical references, not prerequisites available to every contributor.

## Finish without losing the original state

Await capture, recognizer, writer, player and SDK/native cleanup. Stop the application/instrumentation, restore the saved **current** settings and device state, verify an idle restart without automatic microphone capture, and leave reusable prepared packs installed. Do not restore another session's settings bytes.

Known translation-meaning errors and ML Kit native content logging remain unresolved. Offline tests do not clear them. Real-phone latency/acoustics/thermal behavior and distribution rights are separate qualification work.
