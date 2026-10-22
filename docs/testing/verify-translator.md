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

In the actual app, select **Whisper · offline recognition**, Apply, choose the
pair and use **Prepare languages**. Defaults and Reset must still select System.
Whisper uses one shared375485327byte model, not Android recognition packs or
the legacy independent LID. Prepare genuine local voices and ML Kit data
separately; metadata coverage alone cannot enable a usable spoken conversation.

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


## NeMo/Parakeet production checks

Prepare both native distributions before assembly, following
[the explicit Linux/WSL build instructions](../../README.md#build-and-run).
Ordinary Gradle builds only verify local NeMo artifacts; they never acquire tools,
SDK sources or model weights. Device inference requires the separately installed
pinned 714 MB GGUF, real Silero VAD, selected ML Kit translation data and local
System TTS voices. Record guest RAM/CPU/API, APK identities and provider versions;
an ASR-only footprint is not a budget for their coexistence.

First select **Parakeet · multilingual offline recognition** in actual Settings,
apply, choose the pair and exercise **Prepare languages** in Conversation.
Observe missing → downloading → ready without a helper import or a substituted
provider. Parakeet does not require Android recognition-language packs; System
output still requires genuinely installed offline voices in the default engine.
The following production installer check is additional evidence, not a replacement
for consumer UI preparation:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.NemoRecognitionNativeTest#prepareSelectedLanguagesThroughProductionInstaller' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

After disconnecting and verifying complete connectivity absence, run the real
packaged JNI controls and the unchanged frozen text-policy cohort:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.NemoRecognitionNativeTest#packagedJniPreservesHumanControlMeaningAndRejectsForeignPairAndSilence,com.example.offlinetranslator.runtime.NemoRecognitionNativeTest#bundledIdentifierMaintainsFrozenHeldOutCoverageWithoutWrongAcceptedSources' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The six human inputs combine the unchanged approved EN/RU controls and two
attributed Dutch FLEURS recordings in `test-data/conversation/`. They establish
bounded integration evidence, not accent/conversational representativeness. Keep
known Dutch transcript discrepancies; do not correct references or treat successful
source routing as preserved meaning. The 68-text gate independently requires zero
wrong accepted sources, at least 90% correct acceptance per source and rejected
foreign-pair controls, without threshold tuning.

Exercise the actual Conversation surface, all three pairs in both directions,
foreign-language uncertainty, subsequent recovery, Stop and idle restart:

```powershell
pwsh -File tools/android/run-device-gate.ps1 `
  -Adb $adb -Device emulator-5554 `
  -CaseList 'com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest#parakeetUsesRealOfflineVoicesAndBothDirectionsForDutchEnglishRussian,com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest#parakeetUndeterminedHumanTurnRequestsRepeatThenResumesSelectedDirection' `
  -EvidenceDir "$env:TEMP/nemo-ui-$([Guid]::NewGuid().ToString('N'))"
```

These methods use paced public PCM through real production VAD/ASR/text-ID/MT and
System output, not a microphone or fabricated transcript. They retain actual
screenshots/semantics under app-private `files/nemo-recognition-proof/`; inspect
rendered default and increased-font states. UI completion and a phase-only TTS
callback are not themselves non-silent PCM evidence. Run the existing genuine
System-output mechanism for the selected languages as a separate proof:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.SystemSpeechOutputNativeTest#allSelectedLocalesHaveGenuineInstalledOfflineVoiceAndCompletedPcm' -e systemTtsLanguageIds 'en,ru,nl' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The optional argument selects only those fixed fixtures and rejects unknown or
missing fixture IDs. Without it, the existing full-catalog gate is unchanged.
Keep its voice identity, non-silent PCM, completion and interruption records;
do not advertise a selected three-language pass as full-catalog qualification.

Report model creation plus real warmup, ASR, first/warmed text-ID and output
timing boundaries separately. System phase-only output has no measured first
non-silent presentation timestamp and cannot prove the two-second physical target.
If Android kills a provider for low memory, preserve process-exit evidence and the
failed gate. A later pass on a larger-RAM emulator does not erase that failure,
establish minimum RAM, repair model meaning or qualify a physical phone.

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

The whole `SharedLanguagePairNativeTest` class now includes the NeMo cases above;
its full-class invocation requires those actual prepared resources too. Select
individual methods when qualifying only an existing System/Sherpa boundary.

These focused commands are not an exhaustive or release-qualifying device suite.

## Focused Dutch verification

Dutch source integration adds `nl` to the 17-language catalog and maps system recognition/output to `nl-NL`. Catalog integration alone is not a passing device result; this section describes the System/text procedure, while the NeMo gates above exercise a separate selected backend. Keep functional, semantic, memory and physical-device qualification distinct.

- After all source/localization edits settle and host-build access is granted, run the relevant existing host gate once and check Dutch translatable-key/format-signature parity with a throwaway script. Do not add permanent resource-copy tests or duplicate old per-language matrices.
- After explicit device handoff, preserve current APK identities, private settings/packs, grants, connectivity, app locale, font scale and rotation. Through the actual UI, select Dutch in both ordered pair positions on Conversation and Text, swap, and relaunch. Select the Dutch interface (`Nederlands`) and inspect Conversation, Text, Settings, Help, dialogs, preparation and errors in portrait, landscape and increased font scale. Existing shared-pair/interface tests do not automatically exercise Dutch.
- Prepare selected English↔Dutch and Russian↔Dutch resources online through the actual app UI, then disconnect, verify connectivity absence and force-stop/reopen. Execute fixed invented text in all four directions with negation, `Anna`, two tickets, tomorrow/yesterday and 15:00 versus 16:00. Retain exact unchanged inputs/outputs and independently assess meaning; do not retry modified inputs or promote method metadata based on fluent text. English↔Dutch is executable `Unknown`; Russian↔Dutch is SDK-managed `Pivot(en)`.
- Check actual installed local `nl-NL` recognition and voice readiness through the selected System backends, using the provider's public installer explicitly when missing. Unsupported/missing System resources must stay non-ready; Dutch does not gain Sherpa/Piper coverage. Selecting NeMo is an explicit separate action, not a way to relabel a failed System gate. With genuine selected-backend readiness, run fixed Dutch↔English and Dutch↔Russian audio through production VAD/ASR/source resolution/ML Kit/output. Retain detected source, transcript, translation, non-silent output evidence and unchanged errors.
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
