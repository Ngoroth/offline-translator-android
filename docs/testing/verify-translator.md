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

## Tencent-specific preparation and native checks

Run explicit Tencent native preparation before the host gate; see [CONTRIBUTING](../../CONTRIBUTING.md). No host/CI gate downloads the 1,133,080,448-byte model. On the actual app, select Tencent independently in Text and Conversation, press Prepare over validated Wi-Fi, and record missing/downloading byte progress/verifying/loading/ready. Reopen and explicitly initialize verified installed bytes locally. Readiness must never adopt corrupt bytes or enable Conversation before the selected recognition/output stages are ready. Missing/corrupt/storage/load/memory/limit/Unicode/Wi-Fi/timeout outcomes must stay localized and must not fall back to ML Kit.

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

For a controlled missing-ROM-component experiment, use a separate disposable/rootable Android emulator with host audio disabled, the exact qualified Google base/splits, and selected packs prepared through the public installer. Save the effective on-device resource and overlay state, blank only `android:string/config_defaultOnDeviceSpeechRecognitionService` with a scoped fabricated overlay, then verify the actual app's designated route is unavailable while its explicit route still recognizes fixed EN/RU through the production pipeline. Restore the overlay and shut down that emulator afterward. This is an Android resource experiment, not GrapheneOS reproduction, ARM64 qualification or a guarantee of version-atomic service binding.


Additional existing boundaries can be checked independently:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.SystemSpeechOutputNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.RuntimeSettingsNativeTest,com.example.offlinetranslator.runtime.SharedLanguagePairNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.speech.AndroidSpeechPcmWriterNativeTest' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

These focused commands are not an exhaustive or release-qualifying device suite.

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
