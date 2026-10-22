---
thesaurus-format: "2.0"
skill: ubiquitous-language
---

# Offline Translator Thesaurus

> Existing vocabulary is reconstructed from active code; new approved concepts
> cite their design until implementation supplies source evidence. Definitions
> are interpretations that a domain expert may refine. Maintain this registry
> through the `ubiquitous-language` skill.
>
> Scope: text translation, live conversation, speech resources and user choices.
> Human-readable terms are in English; each Identifier preserves the existing
> code spelling. This is not a catalog of every class, a behavioral specification,
> or a claim about translation or recognition quality.
>
> Lookup: `rg -n -i '<word>' docs/THESAURUS.md`. An Index line gives the canonical
> Identifier; `avoid:` lists forbidden synonyms. Forbidden gives replacements
> after `use:`, Legacy gives replacements after `→`, and Unresolved requires a
> decision rather than a silent choice. Add a new concept to both Index and Terms.
>
> Derive names from Identifiers without synonyms or abbreviations, using normal
> Kotlin casing. Infrastructure roles and established ASR, VAD and TTS acronyms
> are allowed; technology names do not replace domain concepts. No bounded
> contexts are assigned: module boundaries alone do not establish them.

## Index

- **App settings** `AppSettings` kind:value
- **Conversation** `ConversationPipeline` kind:process
- **Conversation failure** `ConversationFailure` kind:state
- **Conversation phase** `ConversationPhase` kind:state
- **Conversation preparation** `ConversationPreparation` kind:state
- **Conversation state** `ConversationState` kind:state
- **Conversation utterance** `ConversationTranscript` kind:entity
- **Direct translation** `Direct` kind:value
- **Install** `Install` kind:command
- **Installation status** `SpeechModelInstallState` kind:state
- **Interface language** `InterfaceLanguage` kind:concept
- **Language identifier** `LanguageId` kind:value
- **Language pair** `LanguagePair` kind:value
- **Pivot translation** `Pivot` kind:value
- **Playback start** `SpeechPlaybackStart` kind:value
- **Prepare** `Prepare` kind:command
- **Recognition backend** `SpeechRecognitionBackend` kind:policy
- **Recognition readiness** `SpeechRecognitionReadiness` kind:state
- **Recognition result** `ConversationRecognitionResult` kind:value
- **Recognized utterance** `RecognizedUtterance` kind:value
- **Speech capture** `SpeechCaptureEngine` kind:service
- **Speech model** `SpeechModelDefinition` kind:entity
- **Speech model role** `SpeechModelRole` kind:role
- **Speech model status** `SpeechModelStatus` kind:value
- **Speech output** `ConversationSpeechOutput` kind:service
- **Speech output backend** `SpeechOutputBackend` kind:policy
- **Speech output readiness** `SpeechOutputReadiness` kind:state
- **Speech resource package** `SpeechModelPackage` kind:entity
- **Speech segment** `SpeechSegment` kind:value
- **Speech transcription** `SpeechTranscriber` kind:service
- **System voice** `TtsVoice` kind:value
- **Text language identification** `TextLanguageIdentifier` kind:service
- **Translate** `Translate` kind:command
- **Translation capability** `TranslationCapability` kind:value
- **Translation engine** `TranslationEngine` kind:service
- **Translation model readiness** `ModelAvailability` kind:state
- **Translation provider** `TranslationProviderDescriptor` kind:value
- **Undetermined language** `UndeterminedLanguage` kind:state
- **Utterance recognition** `ConversationRecognitionEngine` kind:service
- **Utterance status** `ConversationTranscriptStatus` kind:state

## Terms

### App settings
- **Definition**: Persisted user choices for the shared language pair, translation engines, speech output backend and speech-processing parameters.
- **NOT**: Installed models or the current processing phase; recognition models and Piper voices are selected automatically from the languages.
- **Related**: Language pair, Speech output backend.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/AppSettings.kt:8-22`, `RuntimeConfiguration.kt:23-46` in the same directory.

### Conversation
- **Definition**: Sequential processing of utterances in two selected languages with automatic translation direction and half-duplex speech output.
- **NOT**: Text input or simultaneous listening and playback; recording pauses while the translation is played.
- **Related**: Utterance recognition, Speech output, Conversation utterance.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:51-58`.

### Conversation failure
- **Definition**: A typed cause of failure during conversation preparation, capture, language identification, recognition, translation or playback.
- **NOT**: Utterance status: the cause of an error and the processing state of a record are separate concepts.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:24-39`.

### Conversation phase
- **Definition**: The conversation pipeline's current activity: ready, preparing, listening, understanding, translating, speaking or error.
- **NOT**: Utterance status or Conversation preparation; the phase belongs to the running pipeline.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:23,41-44`.

### Conversation preparation
- **Definition**: The displayed state of explicit preparation for the selected languages, distinguishing checks, required user action, speech resources, translation preparation and errors.
- **NOT**: Conversation phase: this preparation state precedes pipeline startup, rather than describing the current utterance or microphone recording.
- **Evidence**: `app/src/main/java/com/example/offlinetranslator/conversation/ConversationUiState.kt:3-4`, `app/src/main/res/values-ru/strings.xml:44-52`.

### Conversation state
- **Definition**: The current snapshot of the conversation phase, session and capture activity, utterance records, general failure and bounded segment queue.
- **NOT**: Conversation phase or Utterance status alone; an active session does not necessarily mean that the microphone is running.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:41-49`.

### Conversation utterance
- **Definition**: An identifiable processing record for one utterance, containing the original text, optional direction and translation, status, failure and latency data.
- **NOT**: Only the source transcription or the entire conversation; the record exists before recognition completes.
- **Related**: Utterance status, Conversation failure.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:30-39`, `app/src/main/res/values-ru/strings.xml:32-35`.

### Direct translation
- **Definition**: A translation path for which the application has confirmed SDK direct-translation evidence for the selected pair.
- **NOT**: A guarantee of executability, quality or the absence of internal intermediate SDK representations.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationCapabilities.kt:3-4,18-19`, `engine-mlkit/src/main/java/com/example/offlinetranslator/mlkit/MlKitTranslationEngine.kt:146-166`.

### Install
- **Definition**: Explicitly obtain and verify speech resources owned by the model repository.
- **NOT**: Prepare or installation of an Android system voice; the selected system engine installs its local data through a separate action.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/SpeechModelRepository.kt:27-33`, `ConversationEngines.kt:5-9` in the same directory.

### Installation status
- **Definition**: The installation stage of a speech resource: missing, downloading, verifying, ready or failed.
- **NOT**: Speech output readiness or readiness of the complete translation path; READY applies to a particular resource.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/SpeechModelRepository.kt:6-11`.

### Interface language
- **Definition**: The Android-persisted choice of language for the app-owned interface from the supported catalog; applied immediately and defaulting to English.
- **NOT**: The translation language pair, the detected utterance language or the language of Android system screens.
- **Related**: Language identifier, Language pair.
- **Evidence**: `app/src/main/java/com/example/offlinetranslator/ui/InterfaceLanguage.kt:10-30,41-52`.

### Language identifier
- **Definition**: A normalized string language code with validated format and length.
- **NOT**: The result of identifying the language of an utterance; a valid code alone does not establish speech or translation support.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/LanguageId.kt:5-15`.

### Language pair
- **Definition**: An ordered pair of distinct languages with source and target positions, shared by text and conversation modes.
- **NOT**: An unordered set: order determines text translation direction, while each recognized conversation language selects the forward or reverse pair without changing the persisted order.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/LanguagePair.kt:3-6`, `RuntimeConfiguration.kt:44-49` in the same directory; `app/src/main/res/values-ru/strings.xml:118-125,138`.

### Pivot translation
- **Definition**: A translation path for which the engine reports an intermediate language, `via`.
- **NOT**: Direct translation; this classification alone does not require the application to perform two translations manually.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationCapabilities.kt:3-7`.

### Playback start
- **Definition**: A speech-output notification with an optional timestamp for the first non-silent audio presentation and a flag indicating measurement provenance.
- **NOT**: A guaranteed physical measurement: a notification without a timestamp reports only a phase and provides no numerical latency.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:55-59` (`SpeechPlaybackStart`).

### Prepare
- **Definition**: Explicitly establish a component's resources and readiness before local processing, including model downloads or initialization where that component requires them.
- **NOT**: Install: preparation does not always download files, and installation does not guarantee readiness of every conversation component.
- **Related**: Conversation preparation.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationEngine.kt` (`prepare`), `ConversationEngines.kt:49,66` in the same directory.

### Recognition backend
- **Definition**: A choice between Android system offline, joint Whisper, legacy Sherpa and NeMo recognition, independent of the Speech output backend.
- **NOT**: Speech output backend or Translation engine; this choice neither replaces speech output nor enables automatic switching between backends.
- **Related**: Utterance recognition, Speech output backend, Recognition readiness.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/AppSettings.kt` (`SpeechRecognitionBackend`).

### Recognition readiness
- **Definition**: The state of the selected recognition backend and its selected resources for the current language pair: ready, missing local model, unsupported, engine unavailable, scheduled, downloading, download failed or check failed.
- **NOT**: Language identification quality, Speech output readiness or Translation model readiness.
- **Related**: Recognition backend, Conversation preparation.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:38-42`.

### Recognition result
- **Definition**: The outcome of recognizing a speech segment, carrying original text and either an assigned translation direction or an undetermined language.
- **NOT**: Recognized utterance alone: an outcome may retain text without authorizing translation or playback.
- **Related**: Recognized utterance, Undetermined language.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:13-22`.

### Recognized utterance
- **Definition**: A recognition result containing the original text and translation direction determined from the utterance language.
- **NOT**: Conversation utterance: the result does not yet contain a translation, playback status or record failure.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:17-20`.

### Speech capture
- **Definition**: Obtaining microphone audio while the application is active and producing bounded speech segments for processing.
- **NOT**: Utterance recognition: a segment contains audio, not text.
- **Related**: Speech segment.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/SpeechCaptureEngine.kt:3-28`.

### Speech model
- **Definition**: An identifiable description of an executable speech model, including its role, languages, adapter parameters and references to required files.
- **NOT**: Speech resource package or an ML Kit translation model; a Piper voice has the TTS role, but a System voice is not a model in this catalog.
- **Related**: Speech model role.
- **Evidence**: `speech-models/src/main/java/com/example/offlinetranslator/speechmodels/SpeechModelDefinition.kt:9-17`.

### Speech model role
- **Definition**: The purpose of a speech resource: voice activity detection (VAD), speech recognition (ASR), language identification (LANGUAGE_ID) or speech synthesis (TTS).
- **NOT**: The model's language, family or installation status; a language identification model is not a Language identifier.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/RuntimeConfiguration.kt:3`, `SpeechModelRepository.kt:14-24` in the same directory.

### Speech model status
- **Definition**: Information about an installable speech resource: identifier, source, license, size, progress, failure, role and languages.
- **NOT**: Installation status alone or evidence that the entire conversation is ready.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/SpeechModelRepository.kt:14-25`.

### Speech output
- **Definition**: Local synthesis and playback of translated text in the target language, including the ability to interrupt current speech.
- **NOT**: Speech capture or Translation engine; synthesis and audio presentation are parts of speech output, not synonyms for translation.
- **Related**: Speech output backend, Speech output readiness, Playback start.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:63-71`.

### Speech output backend
- **Definition**: The explicit choice between Android system offline speech output and local Piper voices.
- **NOT**: Translation provider or Recognition backend; system speech output does not move the entire speech path to system components, and Piper is not its automatic fallback.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/AppSettings.kt:3-6`, `app/src/main/res/values-ru/strings.xml:136,140-144`.

### Speech output readiness
- **Definition**: Speech-output availability, distinguishing ready, missing local voice, engine unavailable and initialization failed states.
- **NOT**: Translation model readiness or the presence of recognition models.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:61`.

### Speech resource package
- **Definition**: An identifiable downloadable archive or file with a verifiable set of speech-resource files, size, source and license.
- **NOT**: Speech model: the package is an installation unit, while one model can reference multiple packages and share their files with other models.
- **Evidence**: `speech-models/src/main/java/com/example/offlinetranslator/speechmodels/SpeechModelCatalog.kt:14-27`.

### Speech segment
- **Definition**: A bounded fragment of one utterance in mono 16 kHz PCM, extracted by voice activity detection and lasting no more than 15 seconds.
- **NOT**: Recognized utterance: this is audio with optional end-of-speech timing data, not text.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/SpeechSegment.kt:3-7,19-34`.

### Speech transcription
- **Definition**: Obtaining original text from a speech segment without requiring a selected source language or assigning a translation direction.
- **NOT**: Utterance recognition, which also resolves or explicitly declines a selected-pair direction, or language-conditioned recognition that requires a pair.
- **Related**: Speech segment, Text language identification.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:24-28`, `engine-nemo/src/main/java/com/example/offlinetranslator/nemo/NemoSpeechTranscriber.kt`.

### System voice
- **Definition**: A named Android TTS voice with a language locale and flags for network requirements and missing installed data.
- **NOT**: A guarantee that every installed voice supports offline output; Piper uses a Speech model rather than this system object.
- **Related**: Speech output readiness.
- **Evidence**: `app/src/main/java/com/example/offlinetranslator/speech/AndroidTtsClient.kt:38-43`, `app/src/main/res/values-ru/strings.xml:131-135`.

### Text language identification
- **Definition**: Assigning one of the selected languages to transcribed original text, or declining when the available evidence does not satisfy the provider's acceptance policy.
- **NOT**: Language identifier, acoustic language identification, or correction of mistranscribed words.
- **Related**: Language pair, Speech transcription, Undetermined language.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:30-34`, `engine-mlkit/src/main/java/com/example/offlinetranslator/mlkit/MlKitTextLanguageIdentifier.kt:14-30`.

### Translate
- **Definition**: Request translation of text from the source language to the target language of the selected pair.
- **NOT**: Prepare: translation uses local resources and does not download missing models.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationEngine.kt:3-13` (`translate`).

### Translation capability
- **Definition**: The engine's classification of the selected pair's translation path: direct, pivot, unsupported or unknown.
- **NOT**: Translation model readiness or confirmation of output quality; an unknown classification is not an unsupported pair.
- **Related**: Direct translation, Pivot translation.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationCapabilities.kt:3-22`.

### Translation engine
- **Definition**: A component that translates nonblank text in a selected direction using prepared local models.
- **NOT**: Utterance recognition or Speech output; invoking translation does not download missing models.
- **Related**: Translation provider, Translate.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationEngine.kt:3-13`.

### Translation model readiness
- **Definition**: Local availability of the models needed for the selected translation, with MISSING and READY states.
- **NOT**: Translation capability: a supported path can still require resource preparation.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ModelAvailability.kt:3-6`, `TranslationEngine.kt:8-11` in the same directory.

### Translation provider
- **Definition**: An identifiable translation implementation with a name, provider and SDK information, attribution and terms of use.
- **NOT**: Speech output backend: choosing a text or conversation translation provider does not choose recognition or a voice.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/TranslationCapabilities.kt:25-32`. SDK information stays in the provider description, not ordinary Help.

### Undetermined language
- **Definition**: A recognition outcome retaining any original text without a sufficiently supported language assignment within the selected pair.
- **NOT**: An unsupported selected pair or a recognized direction that may be guessed for translation.
- **Related**: Recognition result, Text language identification.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:22`, `TextFirstConversationRecognitionEngine.kt:82-96` in the same directory.

### Utterance recognition
- **Definition**: Obtaining a speech segment's original text and resolving its translation direction within the selected pair, or explicitly leaving its source undetermined.
- **NOT**: Translation engine: recognition returns original text with a direction or an undetermined outcome, not translated text.
- **Related**: Recognition result, Recognized utterance, Undetermined language.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationEngines.kt:44-53`.

### Utterance status
- **Definition**: The processing stage of an individual conversation record: understanding, translating, speaking, completed, failed or interrupted.
- **NOT**: Conversation phase: a completed or interrupted record retains its own status independently of the next utterance.
- **Evidence**: `core/src/main/java/com/example/offlinetranslator/core/ConversationPipeline.kt:28-39`.

## Forbidden

No confirmed words currently require a separate global ban. Do not ban a name
solely because of a technical suffix: Model can denote an actual ML model here,
and Settings can denote real user choices. Clarify vague responsibilities by
meaning rather than mechanically substituting an arbitrary Index term.

## Legacy

No confirmed legacy domain names were found in the inspected active paths.
Historical settings-format migration does not make active concepts obsolete.
The evaluation `SpeechToTextPipeline` is not the user-facing Text mode:
the application uses `ConversationPipeline` and `TranslationRoute`, while the
separate speech pipeline is used by `engine-sherpa-model-smoke`. Do not declare
it legacy without a decision about its purpose.

## Unresolved

No open conflicts requiring a choice between competing canonical names were
identified in the inspected active paths. Language pair versus utterance direction,
transcription versus utterance record, preparation versus installation, and
translation provider versus speech output backend are distinguished in their
definitions rather than declared synonyms. For new conflicts, record the source,
question, number of affected files and possible resolutions here; do not assign
bounded contexts automatically.
