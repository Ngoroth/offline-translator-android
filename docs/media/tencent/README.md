# Tencent Store Listing Media

Captured on 10 November 2026 from the actual Android application on `emulator-5554` (API 37, x86_64, 8 GiB emulator profile). These are not mockups or composited translation results.

## Capture identity

- Application ID: `io.github.ngoroth.offlinetranslator`.
- Installed artifact: `app/build/outputs/apk/debug/app-debug.apk`, version `0.1.6-debug`, version code 7.
- Installed APK SHA-256: `5d88b0444dc0635b5fb154a06735417539cb02b25c960f35ff985d422f7a348e`.
- Saved translation engine: `tencent`; recognition: `whisper`; speech output: `system-offline`, enabled. Tencent is used for both Text and Conversation.
- Selecting Tencent does not remove the unused ML Kit dependency from this development APK. These captures are not evidence that the planned ML-Kit-free release has been built.

## Screenshots

All four images are 1080 × 1920 (9:16), RGB PNG without transparency. The only image conversion removes the screenshot alpha channel; no interface text, content, branding or results were replaced.

Recommended ordering:

1. `conversation-translation.png`: an actual translated turn, with listening resumed and Stop still available.
2. `text-translation.png`: invented input “Where is the train station?” and actual Tencent output “Где находится железнодорожная станция?”.
3. `conversation.png`: the prepared, idle Conversation surface.
4. `prepare-models.png`: genuine Tencent model-download progress over Wi-Fi. This image is intentionally online; it is not an offline-inference screenshot.

The other three screenshots were captured with airplane mode enabled and Wi-Fi/mobile data disabled, after explicit preparation. English/Russian captures do not qualify every catalog language or phone.

## Video

`offline-conversation.mp4` is a 24-second, 1080 × 1920 H.264/AAC clip. It shows startup, recognition, automatic translation, spoken output and resumed listening without a button press for the utterance. It does not demonstrate alternating human speakers or the future continuous text-translation mode.

The unchanged committed mono PCM16/16 kHz fixture `test-data/corpus/v1/quality/en/common_voice_en_15903562.wav` (43,776 samples) entered through the emulator's public `injectAudio` microphone API. The host microphone was disabled. Silence was added around, not substituted for, the unchanged fixture. No expected transcript or translation was injected into the app.

The actual path was emulator microphone → production capture/VAD → Whisper → Tencent Hy-MT2 → local System speech output. The observed text was “Turn to the right.” → “Поверните направо.”. Capture occurred with airplane mode enabled and Wi-Fi/mobile data disabled. Recorded translated playback contained non-silent audio.

Only the first 24 seconds of the approximately 89-second raw screen recording are used. There is no speed-up or removal of processing intervals. Encoding at 30 fps duplicates frames while preserving the original elapsed time. The audio is genuine emulator `streamAudio` output, placed on a host receive-time axis and delayed by 2,535 ms to align with the recorded playback state. This is editorial audiovisual alignment, not sample-accurate timing or latency evidence. The input fixture was not mixed into the output soundtrack; no speech was synthesized for editing.

Common Voice 17.0 fixture provenance and the pinned mirror's CC0-1.0 declaration are recorded in [third-party notices](../../../THIRD_PARTY_NOTICES.md#included-audio-fixtures). Model, runtime and voice licenses remain separate from the application's original-code license.

These materials demonstrate a prepared emulator setup, not real-phone acoustics, sustained use, all-language quality or release qualification. After capture, the display size, network settings and host microphone were restored, and Conversation was left stopped. Prepared models remain installed.

The video must be uploaded to an appropriate YouTube URL before it can be used in the Play Console preview-video field; an MP4 file itself is not accepted there.
