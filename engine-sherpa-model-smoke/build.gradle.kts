import java.security.MessageDigest
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

abstract class EvaluationSourceSync : Sync() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val generateEvaluationUi = tasks.register<EvaluationSourceSync>("generateEvaluationUi") {
    from(rootProject.file("app/src/main/java")) {
        include("com/example/offlinetranslator/translation/TranslationScreen.kt", "com/example/offlinetranslator/translation/TranslationUiState.kt",
            "com/example/offlinetranslator/conversation/ConversationScreen.kt", "com/example/offlinetranslator/conversation/ConversationUiState.kt",
            "com/example/offlinetranslator/ui/LanguageUiCatalog.kt", "com/example/offlinetranslator/ui/ProviderAttribution.kt",
            "com/example/offlinetranslator/ui/LanguagePairControls.kt", "com/example/offlinetranslator/ui/TranslatorHelp.kt",
            "com/example/offlinetranslator/TranslatorHomeLayout.kt",
            "com/example/offlinetranslator/conversation/SystemVoiceDataInstaller.kt")
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/evaluationUi/java"))
    into(outputDirectory)
}
val generateEvaluationResources = tasks.register<EvaluationSourceSync>("generateEvaluationResources") {
    from(rootProject.file("app/src/main/res")) {
        include("values/strings.xml", "values-ru/strings.xml", "drawable-nodpi/powered_by_google_translate.png",
            "drawable/conversation_empty.xml")
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/evaluationUi/res"))
    into(outputDirectory)
}
val generateEvaluationUiTests = tasks.register<EvaluationSourceSync>("generateEvaluationUiTests") {
    from(rootProject.file("app/src/androidTest/java")) {
        include("com/example/offlinetranslator/translation/TranslationScreenTest.kt", "com/example/offlinetranslator/conversation/ConversationScreenTest.kt",
            "com/example/offlinetranslator/ui/SystemBack.kt")
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/evaluationUi/androidTest"))
    into(outputDirectory)
}
tasks.matching { it.name == "preModelSmokeBuild" }.configureEach { dependsOn(generateEvaluationUi, generateEvaluationResources) }
tasks.matching { it.name == "preModelSmokeAndroidTestBuild" }.configureEach { dependsOn(generateEvaluationUiTests) }

val canonicalSmoke = rootProject.layout.projectDirectory.dir("test-data/corpus/v1/smoke")
val verifyModelSmokeOfficialFixtureAssets = tasks.register("verifyModelSmokeOfficialFixtureAssets") {
    inputs.files(canonicalSmoke.file("ru-official-0.wav"), canonicalSmoke.file("en-official-0.wav"))
    doLast {
        mapOf(
            "ru-official-0.wav" to (101804L to "980b4327e684725859e437f05d15dfa4ec8afb994ef2e8fb356365af76547c41"),
            "en-official-0.wav" to (212044L to "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"),
        ).forEach { (name, pin) ->
            val file = inputs.files.files.single { it.name == name }
            require(file.length() == pin.first && MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == pin.second) { "Official fixture mismatch: $name" }
        }
    }
}
tasks.matching { it.name == "preModelSmokeAndroidTestBuild" || it.name == "mergeModelSmokeAndroidTestAssets" }.configureEach {
    dependsOn(verifyModelSmokeOfficialFixtureAssets)
}

android {
    namespace = "com.example.offlinetranslator"
    compileSdk = 37
    testBuildType = "modelSmoke"

    defaultConfig {
        applicationId = "com.example.offlinetranslator.modelsmoke"
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "com.example.offlinetranslator.modelsmoke.EvaluationInstrumentation"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        create("modelSmoke") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf(canonicalSmoke))
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":speech-models"))
    implementation(project(":engine-sherpa"))
    implementation(project(":engine-mlkit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(project(":translator-test-kit"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    "modelSmokeImplementation"(libs.androidx.compose.ui.test.manifest)
    // Only the isolated instrumentation APK calls VAD's native API directly.
    androidTestImplementation(libs.sherpa.onnx.runtime) {
        artifact { type = "aar" }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("modelSmoke")) { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(generateEvaluationUi) { it.outputDirectory }
    variant.sources.res!!.addGeneratedSourceDirectory(generateEvaluationResources) { it.outputDirectory }
    variant.androidTest!!.sources.kotlin!!.addGeneratedSourceDirectory(generateEvaluationUiTests) { it.outputDirectory }
}
