# Application Vision

## Purpose and Document Authority

Build an Android application that helps two people understand each other through one shared phone: naturally, accurately, quickly, and entirely offline after language preparation.

This document defines the agreed product direction, not a claim that the current application already meets it. [README](../README.md) describes implemented capabilities and known limitations. [EXTENSIBILITY](EXTENSIBILITY.md) describes current architecture, extension points, and the changes required to support this vision.

This vision governs future product work; implementation mismatches must be reported honestly rather than treated as changes to the requirements.

## Intended Product and Audience

The intended distribution is a public application on Google Play. Monetization is not currently a goal, but is not permanently ruled out.

The initial quality scope is everyday communication: travel, meeting people, shopping, transport, and ordinary practical questions. Users are not prohibited from discussing other topics, but professional, technical, medical, legal, or other specialized conversations are not part of the initial verified quality commitment.

## First Release Strategy and Work Order

The near-term priority is a simple, working first version with **ML Kit as the main translation path**, not replacing the translator before the product is usable. Release is the development goal, not authorization to publish immediately. The first release's language set is not fixed to English/Russian.

- Make preparation, supported pairs, understandable errors, offline operation after restart, and correct stopping/resource release reliable. Intermediate-English translation is allowed; directness is not a product-wide gate.
- Prefer built-in offline Android speech capabilities where their actual availability and behavior satisfy the conversation scenario. Evaluate system `TextToSpeech`, then on-device recognition; neither an API nor a Google service name guarantees local resources for every language/device.
- **Retain Piper and Sherpa as supported options.** Adding system speech is not authorization to delete their working implementations or required resources. Retaining them also retains their model-management and licensing obligations.
- Keep one conversation pipeline with shared preparation, lifecycle, stopping and presentation. Later, an actually integrated and checked alternative translation approach can be selected through Settings; starting a conversation must not require model expertise. Do not add a placeholder provider switch.
- First complete the functional ML Kit path; dedicated speed measurements and engine comparisons follow afterward. Functional offline/lifecycle checks remain required now. The latency target below remains a goal, not a measured claim or a reason to skip later device qualification.

Research into larger ASR models or alternative NMT engines is not a prerequisite for this work. Known meaning failures remain evidence and must not be hidden; making the SDK integration reliable does not mean fixing its closed weights or qualifying an unchecked conversation pair. Privacy, attribution, model rights and public-release requirements remain applicable.

## Primary Conversation Experience

1. Two people choose their languages directly on the conversation screen.
2. The application selects a verified combination of speech recognition, translation, and voices. Users do not need to choose engines or model names.
3. If resources are missing, the application shows preparation progress and readiness, with download size when publicly known. Android-managed speech-pack sizes may be unknown and must be labelled honestly. If the selected provider cannot supply a resource, explain the missing capability rather than promising a working conversation.
4. Users prepare the languages online in advance and start the conversation once.
5. The application listens to successive utterances, determines which selected language was spoken, translates into the other selected language, and speaks the result without a button press before each utterance.
6. Users can stop the conversation using controls that remain accessible.

Language choice is part of the conversation, not a technical setting hidden in a settings sheet. Text translation remains an auxiliary feature and must not determine or complicate the primary conversation experience.

## Conversation Surface

- Focus on the current utterance and the immediately preceding turn, not an accumulating chat feed.
- Make the translation visually prominent while keeping the original text and language direction understandable.
- Keep conversation controls available independently of long-text scrolling.
- Show preparation and actionable errors when relevant. Keep detailed instructions and diagnostics available on demand rather than permanently filling the main screen.
- Do not require model expertise to understand whether a selected pair is ready to use.

## Utterance Timing and Speed

For the initial experience, the translation is presented and spoken after the speaker finishes an utterance. Simultaneous spoken translation while the speaker continues is outside the initial scope and may be reconsidered in later versions.

The target delay is **no more than two seconds from the actual end of the speaker's utterance to the start of audible translated speech**. Lower latency is preferred. End-of-utterance detection and all processing before playback count toward that interval; measuring only translation inference does not prove the target.

Preliminary processing may be evaluated as an internal optimization, but must not expose an unstable spoken translation or change the agreed utterance-based experience. Streaming is a possible engineering technique, not a product goal by itself.

Interrupting playback with new human speech is deferred. Future work may revisit it, but must prevent the application's own voice from triggering recognition and must preserve the beginning of the new utterance. The initial implementation must not claim reliable interruption before it is demonstrated.

## Translation Quality

Recognize and preserve the speaker's intended meaning across recognition, translation, and speech output. In particular, verify negation, names, numbers, prices, dates, and time relationships using representative everyday phrases, natural speech, and different accents.

A nonempty translation, successful download, or visible language option is not proof of quality. Direct translation is not automatically good translation, and translation through an intermediate language is not automatically unusable.

When processing fails, retain the result already obtained for the current turn when useful and show an understandable error. Do not silently invent a translation or hide a failed turn behind an older successful result.

## Languages and Engine Independence

The long-term goal is offline conversation between arbitrary languages. Reach that goal gradually by adding and checking complete language-pair capabilities rather than advertising all languages understood by an upstream model.

English is not a mandatory participant language. Translation through an intermediate language is allowed; there is no product-wide direct-only rule. Translation method, actual provider support, and verified quality are separate concerns.

No particular recognition, translation, or synthesis model is permanent. ML Kit and Sherpa are current implementation choices, not mandatory product dependencies forever. Use a single composable conversation pipeline with replaceable speech recognition, translation, and speech output stages rather than duplicating conversation management for each engine.

Adding a language on an already supported model family should primarily involve resource registration, localization, and quality verification, not rewriting conversation orchestration or screens. New model families may require adapters. Neither configuration nor an adapter removes the need to verify the resulting conversation.

## Offline Preparation and Privacy

An internet connection is allowed for explicit advance preparation. Once the selected resources are installed, recognition, translation, and synthesis must work offline, including after an application restart. Starting or processing a conversation must not trigger model downloads or remote inference.

Prepare and load only resources needed by the selected pair. Reuse shared model packages rather than downloading or loading a separate copy for every language.

System speech services must also have the selected local language resources ready. Missing voices or recognition models require explicit preparation or an understandable not-ready state, never an undisclosed cloud fallback.

Conversations are ephemeral: do not persist text history or source audio after completion. Temporary bounded audio and text in memory are necessary for processing, but must not become saved conversations. Conversation content must not appear in diagnostic logs. Offline inference alone does not prove privacy.

## Target Devices and Validation Order

The agreed device target is **Android 14 (API 34) or newer**. Devices below Android 14 are outside the product target.

The current source declares `minSdk = 34`, with compile SDK37 and target SDK36 unchanged. The new system-recognition adapter and APK minimum still require the approved host/lint/APK and API37 runtime checks; source migration is not proof of device qualification.

The performance reference is the user's **Nothing Phone (3a) Pro, Snapdragon 7s Gen 3, 12 GB physical RAM**. The target is phones of comparable or greater capability; weaker phones are not the current full voice-conversation target. This is a performance reference, not a RAM-only compatibility rule or proof that every nominally stronger phone will work.

Development is **emulator-first**:

1. Implement and demonstrate the agreed application behavior on the emulator.
2. Once that behavior is ready, proceed to real-phone testing.
3. On the reference phone and other intended devices, qualify end-to-end latency, prolonged-conversation stability, memory use, thermal behavior, audio behavior, and compatibility before making release claims.

Real-phone testing is not a prerequisite for implementing the agreed behavior on the emulator. Emulator results, however, must not be presented as proof of phone performance or real microphone/speaker acoustics.

## Public Release Requirements

Before publication, verify that application dependencies and the exact distributed or downloaded model weights have terms compatible with the intended Google Play distribution. A free application does not by itself establish redistribution permission.

Publish an honest list of supported conversation pairs, preparation requirements, known limitations, and qualified devices. The long-term vision must not be presented as the current release's capability list.

The architecture must remain maintainable, secure, and bounded under load: keep responsibilities clear, resource ownership explicit, and audio, text, queues, and storage limits enforced. Extend existing boundaries where they solve a concrete need rather than building speculative plugin systems or parallel pipelines.
