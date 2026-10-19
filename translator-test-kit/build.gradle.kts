plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.example.offlinetranslator.testkit"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
}
tasks.withType<Test>().configureEach { systemProperty("evaluation.repo", rootProject.projectDir.absolutePath) }
