# Privacy Policy — Offline Translator

Last updated: 10 November 2026

## About this policy

Offline Translator (`io.github.ngoroth.offlinetranslator`) is developed by Daniil Sterkhov (Ngoroth). For privacy questions, contact [daniil.dm.sterkhov@gmail.com](mailto:daniil.dm.sterkhov@gmail.com).

This policy describes the release of Offline Translator that uses Tencent Hy-MT2 for local translation and does not include Google ML Kit. It does not describe earlier development builds containing ML Kit.

## Voice and text processing

The app uses the text you enter and, when you start a conversation and grant microphone permission, the speech captured by the microphone to provide recognition, translation and optional speech output.

Translation runs on your device using downloaded models. The default Whisper speech recognizer also runs on your device. Using these local models does not send your audio, recognized speech, entered text or translations to the developer, Tencent or OpenAI. We do not use your conversations to train models.

The app does not create a saved conversation history or save microphone recordings as audio files. Conversation text and translation results are held in app memory to display and process the current interaction; they are not stored as a persistent transcript. The developer cannot access this local content remotely.

Microphone access is not needed for text translation. You can revoke microphone permission in Android Settings at any time.

## Optional Android speech services

If you select Android speech recognition instead of Whisper, the app supplies audio to your selected Android recognition service and requests offline recognition. When system speech output is enabled, the app supplies the text to be spoken to the installed Android text-to-speech engine. Only installed voices reported by that engine as not requiring a network connection are eligible. Piper speech output, where available and selected, runs inside the app using local voice models.

Android speech services are installed and managed separately from this app. Their operation and privacy settings are governed by their providers. If you use Google's speech services, see [Google's Privacy Policy](https://policies.google.com/privacy). For another provider, consult that provider's policy.

## Internet access and model downloads

Internet access is used to download the models needed for offline operation. Once the required models and voices are installed, local recognition and translation can work without an internet connection.

Model files are downloaded over HTTPS from Hugging Face, GitHub and their file-delivery providers. These services receive the network information needed to handle a download, including your IP address, the requested file and standard connection information. The app does not include conversation text or microphone audio in model-download requests.

These services handle their own request records under their respective policies:

- [Hugging Face Privacy Policy](https://huggingface.co/privacy)
- [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement)

Opening an external link from the app also subjects your visit to the destination site's privacy policy.

## Analytics, advertising and sharing

The release covered by this policy does not include advertising SDKs, analytics SDKs or automatic crash-report uploading. It does not require an account and does not operate a developer-run server for receiving conversations or usage analytics.

We do not sell or rent your personal data, use your conversations for advertising, or upload conversations to a translation service. The limited interactions with model hosts and optional Android speech services are described above.

## Local storage, retention and deletion

App preferences, selected languages and downloaded models are stored on your device. Preferences remain until changed or the app's storage is cleared. Downloaded model files remain until removed with the app's storage. Temporary download files may remain after an interrupted download.

To remove all app-managed settings, downloaded models and temporary files, use Android Settings to clear the app's storage or uninstall the app. Clearing storage means models must be downloaded again before offline use. Closing the app's process removes its in-memory conversation state; this is not a guarantee of secure erasure from device memory.

The app disables Android cloud backup and device-transfer backup for its own data. Speech resources installed by a separate Android speech service are managed by that service and are not deleted by clearing Offline Translator's storage.

## Contacting the developer

If you email us, we receive your email address and any information you choose to include. We use that information to respond to your request and keep it only as long as needed to handle the correspondence or meet applicable legal obligations. You can request deletion by contacting the same address. Please do not send private conversations or unredacted diagnostic logs unless you intentionally wish to share them.

## Security and your choices

The app uses Android's app-private storage for its settings and model files, requests microphone permission before recording, and uses HTTPS for model downloads. No device or transmission method is completely secure.

You control microphone permission, the selected speech services and whether to prepare models online. You can delete local app data through Android Settings. Depending on applicable law, you may also have rights to access, correct or delete personal information provided in support correspondence, object to its processing, or complain to a data protection authority. Contact us to exercise those rights. We cannot retrieve or delete content held only on your device remotely.

## Intended audience

Offline Translator is intended for adults aged 18 and over and is not directed at children.

## Policy hosting and updates

When this policy is hosted on GitHub Pages, GitHub processes website request information under its [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement). That website traffic is separate from your locally processed conversations.

We may update this policy when the app or its data practices change. The latest policy will show its revision date at the top.
