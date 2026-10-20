# Contributing

Start with [README](README.md), [the application vision](docs/APP_VISION.md) and [the architecture guide](docs/EXTENSIBILITY.md).

Small focused pull requests are welcome. Explain the problem, behavior changed, tests exercised and known limitations. Discuss changes to product scope, new engines, model downloads or third-party distribution before implementing them. Outside contributors do not need to follow the repository's historical multi-session planning/review process.

## Development environment

- JDK **17**, configured through `JAVA_HOME`.
- Android SDK, configured through `ANDROID_HOME`, with `platform-tools`, **`platforms;android-37.0`**, and **`build-tools;36.0.0`**.
- Use current Android SDK command-line tools **23.0** or newer with repository schema 3 support for this platform package. The package name is `platforms;android-37.0`, not `platforms;android-37`; old schema 1/2 repository listings do not include it.
- The checked-in Gradle wrapper supplies Gradle. Run commands from the repository root. Windows, Linux and macOS host commands are below.

The [host workflow](.github/workflows/host-checks.yml) provisions official Android command-line tools archive revision **16111833** (CLI 23.0), JDK 17, platform 37.0 and Build Tools 36.0.0 on Ubuntu 24.04. Locally, install the named packages with the official SDK Manager and review/accept its licenses; the project's Apache license does not grant SDK or third-party rights.

Host checks run for branch pushes, pull requests and manual dispatch. Tag-only pushes do not start this workflow; publish release tags only after checking their branch commit.

The app compiles against API 37, targets API 36, requires API 34 (Android 14), and packages `arm64-v8a` and `x86_64`. Build/dependency downloads require network access; host checks need neither speech models nor an emulator.

## Baseline host checks

Before changing behavior, record the relevant baseline and distinguish existing failures from regressions. Use the same commands afterward and report the actual result, including any failure or blocked check. Do not suppress checks or replace a failed fixture to manufacture a pass.

### Windows (PowerShell)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "C:\Users\<user>\AppData\Local\Android\Sdk"

.\gradlew.bat :engine-sherpa:prepareSherpaArchive --console=plain

.\gradlew.bat :core:testDebugUnitTest :engine-mlkit:testDebugUnitTest :engine-sherpa:testDebugUnitTest :app:testDebugUnitTest :translator-test-kit:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

# The included build has a separate JVM test gate.
.\gradlew.bat -p build-logic test --console=plain
```

### Linux / macOS (POSIX)

```bash
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"

bash ./gradlew :engine-sherpa:prepareSherpaArchive --console=plain

bash ./gradlew :core:testDebugUnitTest :engine-mlkit:testDebugUnitTest :engine-sherpa:testDebugUnitTest :app:testDebugUnitTest :translator-test-kit:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

# The included build has a separate JVM test gate.
bash ./gradlew -p build-logic test --console=plain
```

Using `bash ./gradlew` works even when the wrapper is not executable. Run the separate build-logic tests from a real repository checkout with valid Git metadata: existing recovery fixtures use the repository's `.git`. An export without Git metadata is not an equivalent test environment.

Run explicit preparation before either host gate: build-logic recovery tests copy the prepared AAR. Preparation downloads the pinned official Sherpa 1.13.8 runtime to ignored `engine-sherpa/libs/sherpa-onnx-1.13.8.aar` and verifies its size and SHA-256. Valid local bytes are reusable offline; corrupt existing files fail unchanged and must be inspected/removed explicitly. Ordinary builds only verify the file, never download it. See [native provenance](engine-sherpa/THIRD_PARTY.md) for artifact identity and distribution conditions.

Inspect module `build/test-results/` and `build/reports/`, plus `app/build/reports/lint-results-debug.*`. APK outputs are under `app/build/outputs/apk/`. Assembling the instrumentation APK does not run its tests.

## Behavioral tests and device scope

Add deterministic behavioral tests at the existing contract boundary for the behavior changed. Cover errors, cancellation and awaited cleanup when relevant, not just a success return or implementation detail. Reuse the current fake SDK/native boundaries and bounded fixtures; unit tests should not rely on network access, installed speech services, live microphones, timing sleeps or downloaded production weights. Preserve the real Android dependency/runtime path when testing host-specific behavior.

Changes touching JNI, model inference, Android speech services, microphone/speaker behavior, translation quality or latency also need the relevant opt-in device checks. Record the APK identity, resources, device/service versions and actual result. An offline check requires prepared resources and verified connectivity absence. Arrange device use and downloads with its owner.

Use [the verification guide](docs/testing/verify-translator.md) for device prerequisites, instrumentation selectors and the PowerShell device-runner invocation. The runner requires PowerShell 7, installed APKs and caller-managed device exclusivity.

## Safe reports, fixtures and submissions

For a non-sensitive bug, provide a minimal synthetic reproduction, expected/actual behavior, relevant device/API/backend versions and the exact checks you ran. State whether resources were prepared and whether the observation was online or offline. Use invented text/audio and remove identifying information from screenshots and artifacts.

**Do not submit secrets or real conversation data.** Exclude raw logcat, full bugreports, private recordings/transcripts, signing keys, credentials, local SDK configuration and model caches. Unresolved ML Kit native logging can expose content even offline; review/redact necessary excerpts locally. Follow the [security policy](SECURITY.md) for suspected vulnerabilities.

Test fixtures must be minimal, deterministic and safe to publish. Record source, license and any modifications for third-party audio or data; do not assume upstream availability grants redistribution rights. Never use production credentials as fixtures. The existing self-signed localhost `engine-sherpa/src/test/resources/model-fixture.p12` and its disclosed `fixture-only` password are downloader-test inputs, not a pattern for submitting real private keys.

Keep generated reports, recordings, model downloads and machine-local configuration out of submissions. Keep pull requests focused and report failures as well as passes.

## Signed release APK (Windows)

Use PowerShell 7, JDK 17 and `ANDROID_HOME` pointing to an Android SDK with Build Tools 36.0.0. Keep the signing keystore and its password file outside the repository; do not pass the password itself on the command line.

```powershell
./tools/android/build-release.ps1 -Keystore C:/private/release.p12 -PasswordFile C:/private/release-password.txt
```

The script builds and lints the release, aligns it for 16 KB pages, signs it, verifies its signature and alignment, and writes the APK and SHA-256 checksum under `app/build/outputs/apk/release/`. The default key alias is `release`; use `-KeyAlias` for another alias. Back up the key and store its password securely: updates require the same signing identity.

The APK includes license texts and third-party notices in `assets/licenses/`. These files do not replace other obligations, including applicable GPL Corresponding Source requirements; see [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).

## Contribution licensing

By submitting an original contribution for inclusion, you agree to license it under the project's [Apache License 2.0](LICENSE), unless the contribution is explicitly identified as third-party material with its own applicable terms. Do not claim ownership or grant rights you do not have. Preserve required upstream notices and identify third-party additions and redistribution obligations in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md) and the relevant provenance documentation. Models, voices, datasets, native binaries and trademarks are not automatically relicensed by the root license.
