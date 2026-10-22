plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.offlinetranslator.speechmodels"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}
