import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val generateLicenseAssets = tasks.register<Sync>("generateLicenseAssets") {
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.layout.projectDirectory.dir("LICENSES")) { into("LICENSES") }
    from(rootProject.layout.projectDirectory.file("engine-sherpa/THIRD_PARTY.md")) { into("engine-sherpa") }
    from(rootProject.layout.projectDirectory.file("engine-nemo/THIRD_PARTY.md")) { into("engine-nemo") }
    into(licenseAssets.map { it.dir("licenses") })
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateLicenseAssets) }

val canonicalSmoke = rootProject.layout.projectDirectory.dir("test-data/corpus/v1/smoke")
val fixtureAliases = layout.buildDirectory.dir("generated/androidTestOfficialFixtureAliases/debug")
val generateDebugAndroidTestOfficialFixtureAliases = tasks.register<Sync>("generateDebugAndroidTestOfficialFixtureAliases") {
    from(canonicalSmoke) {
        include("ru-official-0.wav", "en-official-0.wav")
        rename("ru-official-0.wav", "speech-ru-fixture.wav")
        rename("en-official-0.wav", "speech-en-fixture.wav")
    }
    from(rootProject.layout.projectDirectory.dir("test-data/corpus/v1/quality/ru")) {
        include("common_voice_ru_18878647.wav", "common_voice_ru_18889191.wav")
        rename("common_voice_ru_18878647.wav", "short-human-ru-timer.wav")
        rename("common_voice_ru_18889191.wav", "short-human-ru-come.wav")
    }
    from(rootProject.layout.projectDirectory.dir("test-data/corpus/v1/quality/en")) {
        include("common_voice_en_1282109.wav", "common_voice_en_15903562.wav", "common_voice_en_18361303.wav")
        rename("common_voice_en_15903562.wav", "short-human-en-right.wav")
        rename("common_voice_en_18361303.wav", "short-human-en-aisle.wav")
        rename("common_voice_en_1282109.wav", "short-human-en-wait.wav")
    }
    from(rootProject.layout.projectDirectory.dir("test-data/conversation")) {
        include("nl-fleurs-test-0.wav", "nl-fleurs-test-1.wav", "manifest.json", "parakeet-heldout-texts.json")
        into("conversation")
    }
    into(fixtureAliases)
    doFirst {
        mapOf(
            "ru-official-0.wav" to (101804L to "980b4327e684725859e437f05d15dfa4ec8afb994ef2e8fb356365af76547c41"),
            "en-official-0.wav" to (212044L to "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"),
            "common_voice_ru_18878647.wav" to (96812L to "c56d2b49c92ff19fb3779b9644b4e9da21aff5919ef4efed20b1054c78a5aac8"),
            "common_voice_en_15903562.wav" to (87596L to "1f1d013f7217228a03c293749a98de6197efdf16d81ee66ec82f2cf691bc1309"),
            "common_voice_ru_18889191.wav" to (84524L to "b0575a2591783d64d325d47f7127b3daf3d73810f703ac8e793a6dd5743269c4"),
            "common_voice_en_18361303.wav" to (100652L to "418cf7345bddc6aea09007564ac19d703c31c55c4a45a76ffe6ff0972a64c518"),
            "common_voice_en_1282109.wav" to (206636L to "5643c304164391cdb29c046c51433b82679d6001ef5689d22db7b6029a6acfc0"),
            "nl-fleurs-test-0.wav" to (207438L to "53cb19844ff097b3a603ba73e817173e9dbedf5e8522cec387e8c4f5dff56790"),
            "nl-fleurs-test-1.wav" to (140238L to "99ff8d08d87654dcefa2c52b8e2d5b3b6337624b606aa66874b747a9be88b0c1"),
        ).forEach { (name, pin) ->
            val file = inputs.files.files.single { it.name == name }
            require(file.length() == pin.first && MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == pin.second) { "Official fixture mismatch: $name" }
        }
    }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" || it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(generateDebugAndroidTestOfficialFixtureAliases)
}

android {
    namespace = "com.example.offlinetranslator"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.offlinetranslator"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf(fixtureAliases))
    sourceSets.getByName("main").assets.directories.add(licenseAssets.get().asFile.absolutePath)
}

// SDK_INT=0 in host Android stubs selects DataStore's renameTo branch, which cannot
// replace existing files on Windows. Use the same release's real JVM file storage
// only for host tests; app and instrumented-test runtimes retain Android storage.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.datastore:datastore-core")).using(
                module("androidx.datastore:datastore-core-jvm:${libs.versions.datastore.get()}"),
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine-mlkit"))
    implementation(project(":engine-sherpa"))
    implementation(project(":speech-models"))
    implementation(project(":engine-nemo"))
    // `:engine-sherpa` owns the runtime through the root's local verified artifact repo.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.jvm)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
