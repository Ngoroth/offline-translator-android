import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

data class ArchivePin(val archive: File, val url: String, val bytes: Long, val sha256: String) : java.io.Serializable

val sherpaOnnxVersion = libs.versions.sherpa.onnx.get()
val piperPhonemizeVersion = libs.versions.piper.phonemize.get()
val archives = listOf(
    ArchivePin(
        layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile,
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar",
        52_321_252L,
        "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e",
    ),
    ArchivePin(
        layout.projectDirectory.file("libs/piper-phonemize-$piperPhonemizeVersion.aar").asFile,
        "https://github.com/csukuangfj/piper-phonemize/releases/download/v$piperPhonemizeVersion/piper-phonemize-$piperPhonemizeVersion.aar",
        12_672_185L,
        "d0ed1cc8ac8029937a10099539ff4e532e4ec875a4745137283b618d9075daa2",
    ),
)
val prepareSherpaArchive = tasks.register("prepareSherpaArchive") {
    group = "build setup"
    description = "Explicitly downloads and verifies the pinned official Sherpa and Piper AARs."
    val pins = archives
    doLast {
        pins.forEach { (archive, url, bytes, sha256) ->
            SherpaArchivePreparation(URI(url), bytes, sha256).prepare(archive)
            logger.lifecycle("Verified ${archive.name} ($bytes bytes, SHA-256 $sha256)")
        }
    }
}
val verifySherpaArchive = tasks.register("verifySherpaArchive") {
    group = "verification"
    description = "Verifies the pinned Sherpa and Piper AARs offline without repairing them."
    mustRunAfter(prepareSherpaArchive)
    val pins = archives
    doLast {
        pins.forEach { (archive, url, bytes, sha256) ->
            SherpaArchivePreparation(URI(url), bytes, sha256).verify(archive)
        }
    }
}

val verifiedArchives = providers.of(VerifiedSherpaArchives::class) {
    parameters.archives.set(archives.map { it.archive })
    parameters.bytes.set(archives.map { it.bytes })
    parameters.sha256.set(archives.map { it.sha256 })
}

// AGP configures dependency declarations eagerly, even for archive preparation.
// Check only when a configuration is actually resolved.
configurations.configureEach {
    incoming.beforeResolve { verifiedArchives.get() }
}

android {
    namespace = "io.github.ngoroth.offlinetranslator.sherpa"
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
    // Public factory configuration types and isolated JNI tests use Sherpa SDK types.
    api(libs.sherpa.onnx.runtime) {
        artifact { type = "aar" }
    }
    implementation(libs.piper.phonemize.runtime) {
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
