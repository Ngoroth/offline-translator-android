plugins { `kotlin-dsl` }
repositories { google(); mavenCentral(); gradlePluginPortal() }
kotlin { jvmToolchain(17) }
dependencies {
    compileOnly("com.android.tools.build:gradle:9.4.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
}
sourceSets.main { java.srcDir("../translator-test-kit/src/main/java") }
tasks.test { systemProperty("evaluation.repo",rootProject.projectDir.parentFile.canonicalPath) }
tasks.register<JavaExec>("probeEvaluationInputReuse") {
    group="verification"
    description="One bounded cold/warm input preparation and isolated emulator import proof; no model-quality run."
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("InputReuseProbe")
    systemProperty("evaluation.repo",rootProject.projectDir.parentFile.canonicalPath)
    javaLauncher.set(javaToolchains.launcherFor {languageVersion.set(JavaLanguageVersion.of(17))})
}
gradlePlugin {
    plugins.create("translatorVerification") {
        id = "translator-verification"
        implementationClass = "TranslatorVerificationPlugin"
    }
}
