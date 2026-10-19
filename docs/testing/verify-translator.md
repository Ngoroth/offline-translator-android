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

These operations install APKs and can change app/device state. Arrange device access and resource preparation before running them; this guide does not authorize changes to another person's device.

## Install and prepare explicitly

Run commands from the repository root in PowerShell:

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

First exercise **Prepare languages through the actual app UI**. Record missing, unsupported, scheduled, failed and ready states; Start must not become ready merely because a download was scheduled. Open the actual selected engine's voice-data installer when necessary. Do not replace consumer preparation evidence with preinstalled resources from a helper.

After that public preparation check, the existing selected-resource inventory preparation can run:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#prepareSelectedLanguages' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

Preparation can download resources and requires connectivity. It is separate from the offline proof. Preserve the original provider errors; do not retry or swap fixtures to turn a failure into a pass.

## Prepared offline conversation checks

After saving the original radio state, disconnect networking and **verify actual connectivity absence**, not just the airplane-mode icon. Force-stop and reopen the prepared application before an offline restart check. Restore the original state afterward.

Run the original human fixture cohort and the synthetic functional cohort separately:

```powershell
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineProductionConversation' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 shell am instrument -w -r -e class 'com.example.offlinetranslator.runtime.AndroidConversationRecognitionNativeTest#preparedOfflineSyntheticProductionConversation' com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner
```

The human cohort checks the pinned RU/EN inputs across the existing orders/directions. The synthetic cohort exercises installed-local-TTS functional coverage and selected translation/output checks. Synthetic completion is not a substitute for a failed human cohort, natural-speech coverage, all-direction semantic quality or microphone acoustics. Record readiness, source resolution, translation meaning and actual non-silent output completion—not merely a phase callback.

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
