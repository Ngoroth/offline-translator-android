import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

val sherpaOnnxVersion = libs.versions.sherpa.onnx.get()
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
val verifySherpaArchive = tasks.register("verifySherpaArchive") {
    val receipt = layout.buildDirectory.file("verified/sherpa-onnx-$sherpaOnnxVersion.sha256")
    val archiveFile = sherpaArchive.asFile
    // Missing input must reach our actionable failure, not Gradle's generic missing-file validation.
    inputs.files(provider { listOfNotNull(archiveFile.takeIf { it.exists() }) })
    inputs.property("archivePath", archiveFile.absolutePath)
    inputs.property("expectedBytes", expectedSherpaBytes)
    inputs.property("expectedSha256", expectedSherpaSha256)
    inputs.property("runtimeVersion", sherpaOnnxVersion)
    inputs.property("archiveUrl", sherpaArchiveUrl)
    outputs.file(receipt)
    doLast {
        val receiptFile = outputs.files.singleFile
        val archive = File(inputs.properties.getValue("archivePath") as String)
        val url = inputs.properties.getValue("archiveUrl") as String
        val bytes = inputs.properties.getValue("expectedBytes") as Long
        val hash = inputs.properties.getValue("expectedSha256") as String
        val actual = SherpaArchivePreparation(URI(url), bytes, hash).verify(archive)
        receiptFile.apply {
            parentFile.mkdirs()
            writeText(actual)
        }
    }
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
    implementation(libs.commons.compress)
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
