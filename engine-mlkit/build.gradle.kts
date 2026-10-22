plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.offlinetranslator.mlkit"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

androidComponents {
    beforeVariants(selector().withBuildType("modelSmoke")) { variant ->
        variant.enableAndroidTest = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    "androidTestModelSmokeImplementation"(libs.androidx.test.ext.junit)
    "androidTestModelSmokeImplementation"(libs.androidx.test.core)
    "androidTestModelSmokeImplementation"(libs.androidx.test.runner)
}
