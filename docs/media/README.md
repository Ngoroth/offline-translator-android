# Showcase media

These are captures of the real Android application, not mockups or rewritten translation results. They show one prepared English/Russian setup on `emulator-5554`; they do not qualify every language, device or phrase.

## Images

- [Conversation](conversation.png): the settled, ready conversation surface.
- [Text translation](text-translation.png): the actual offline result for the invented input “Where is the train station?” → “Где железнодорожная станция?”.
- [Speech preparation](preparing-speech.png): the actual “Starting speech resources…” phase after Start. Languages were already installed; this is runtime startup, **not a model-download progress screen**.
- [Language preparation help](language-preparation-help.png): the app's real Help instructions about advance preparation. No resource was deleted to manufacture a missing-pack or download screenshot.

Images are direct 1080×2424 device screenshots, captured at font scale 1.0 and display density 420. No interface text, state or branding was composited into them.

## Recorded offline conversation

[MP4 with captured output audio](offline-conversation.mp4) · [silent animated preview](offline-conversation.gif)

The main application received the unchanged committed Common Voice fixture
`test-data/corpus/v1/quality/en/common_voice_en_15903562.wav` through the emulator's public microphone-injection API. The actual path was emulator microphone → production AudioRecord/VAD → Android on-device recognition → ML Kit → selected local speech output. No oracle text was injected into the recognizer or the UI.

Observed result: **“Turn to the right” → “Поверни на право” → Spoken**, followed by listening again. The displayed Russian spelling is preserved; no subtitle correction or result replacement was made. The unrelated Text screenshot uses a different, explicitly invented sentence.

Airplane mode was enabled, Wi-Fi/mobile data were off and the saved connectivity dump reported no active default network. Resources were prepared before capture. This demonstrates one offline functional turn, not cold language installation, natural microphone acoustics or the product's latency target.

The MP4 is approximately12.2 seconds at540×1212 with H.264/AAC. It retains video intervals0–4 and18–26.2 seconds from the original device recording, removing idle operator waiting before fixture injection. There is **no speed-up** of recognition, translation or output and no change of frame order. The GIF is a smaller silent preview of the same edited clip.

Output audio is from the emulator's real `streamAudio` receiver, aligned using host packet-receive times; gaps have silence rather than invented speech. This is not sample-accurate audio/video presentation measurement. The input clip itself is not mixed into the output track.

## Input and rights

- The complete committed input is mono PCM16/16kHz, 43,776 samples, injected without trimming, padding or oracle substitution. The host microphone was disabled during fixed-fixture capture.
- Common Voice17.0 provenance and the pinned mirror's CC0-1.0 declaration are recorded in [third-party notices](../../THIRD_PARTY_NOTICES.md#included-audio-fixtures). No private conversation was recorded, and no personal transcript/log is included here.
- Google branding and third-party component rights are not transferred by the original project's Apache license. These captures are not endorsement or a general redistribution clearance for the AAR/models/test corpus.

