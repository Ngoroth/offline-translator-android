import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

val sherpaOnnxVersion = libs.versions.sherpa.onnx.asProvider().get()
val sherpaArchive = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
val expectedSherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val expectedSherpaBytes = 50_129_134L
val sherpaArchiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
tasks.register("prepareSherpaArchive") {
    group = "build setup"
    description = "Explicitly downloads and verifies the pinned official Sherpa AAR, or reuses verified local bytes."
    inputs.property("archivePath", sherpaArchive.asFile.absolutePath)
    inputs.property("archiveUrl", sherpaArchiveUrl)
    inputs.property("expectedBytes", expectedSherpaBytes)
    inputs.property("expectedSha256", expectedSherpaSha256)
    doLast {
        val archive = File(inputs.properties.getValue("archivePath") as String)
        val url = inputs.properties.getValue("archiveUrl") as String
        val bytes = inputs.properties.getValue("expectedBytes") as Long
        val hash = inputs.properties.getValue("expectedSha256") as String
        logger.lifecycle("Preparing Sherpa archive from $url")
        SherpaArchivePreparation(URI(url), bytes, hash).prepare(archive)
        logger.lifecycle("Verified Sherpa archive: $archive ($bytes bytes, SHA-256 $hash)")
    }
}
val nativeOutput = layout.projectDirectory.dir(".native-cache/android")
val nativePython = providers.gradleProperty("sherpaPython").orElse(
    if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
)
val nativeVerification = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine(nativePython.get(), "tools/android/prepare-sherpa-native.py", "--verify",
        "--output", nativeOutput.asFile.absolutePath, "--abis", "arm64-v8a", "x86_64")
    isIgnoreExitValue = true
}
val verifiedNative = nativeVerification.result.map { result ->
    if (result.exitValue != 0) {
        throw GradleException(nativeVerification.standardError.asText.get())
    }
    true
}
// Dependency resolution may precede preBuild; never resolve an unverified/missing AAR.
configurations.configureEach {
    incoming.beforeResolve { verifiedNative.get() }
}
val verifySherpaArchive = tasks.register<Exec>("verifySherpaArchive") {
    group = "verification"
    description = "Read-only verification of the prepared paired Sherpa AAR, provenance and both ABI closures."
    workingDir(rootProject.projectDir)
    commandLine(nativePython.get(), "tools/android/prepare-sherpa-native.py", "--verify",
        "--output", nativeOutput.asFile.absolutePath, "--abis", "arm64-v8a", "x86_64")
    outputs.upToDateWhen { false }
}

android {
    namespace = "com.example.offlinetranslator.sherpa"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":speech-models"))
    // Resolve only from the root's local flat directory; explicit preparation supplies verified bytes.
    implementation(libs.sherpa.onnx.runtime) {
        artifact { type = "aar" }
    }

    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("preBuild").configure {
    dependsOn(verifySherpaArchive)
}

tasks.register("assembleModelSmokeAndroidTest") {
    group = "verification"
    description = "Assembles the isolated real-weight smoke test package."
    dependsOn(":engine-sherpa-model-smoke:assembleModelSmokeAndroidTest")
}

tasks.register("connectedModelSmokeAndroidTest") {
    group = "verification"
    description = "Runs real-weight VAD and RU/EN ASR in a separate test application."
    dependsOn(":engine-sherpa-model-smoke:connectedModelSmokeAndroidTest")
}
