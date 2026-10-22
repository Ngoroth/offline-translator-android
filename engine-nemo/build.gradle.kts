plugins {
    alias(libs.plugins.android.library)
}

val nativeOutput = layout.projectDirectory.dir(".native-cache/android")
val verifyNemoNative = tasks.register<Exec>("verifyNemoNative") {
    group = "verification"
    description = "Verifies prepared NeMo sources, tool pins and both real JNI/SDK ABI closures without network."
    workingDir(rootProject.projectDir)
    val python = providers.gradleProperty("nemoPython").orElse(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    )
    commandLine(python.get(), "tools/android/prepare-nemo-native.py", "--verify",
        "--output", nativeOutput.asFile.absolutePath, "--abis", "arm64-v8a", "x86_64")
    // Always verify actual bytes, including a missing/corrupted receipt or native file.
    outputs.upToDateWhen { false }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.example.offlinetranslator.nemo"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    sourceSets.getByName("main") {
        jniLibs.directories.add(nativeOutput.dir("jniLibs").asFile.absolutePath)
        assets.directories.add(nativeOutput.dir("assets").asFile.absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":speech-models"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
}

tasks.named("preBuild").configure { dependsOn(verifyNemoNative) }
