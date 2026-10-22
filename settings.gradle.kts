pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                flatDir {
                    name = "verifiedSherpaPairedRuntime"
                    dirs(rootDir.resolve("engine-sherpa/.native-cache/android"))
                }
            }
            filter { includeGroup("local.verified.sherpa") }
        }
    }
}

rootProject.name = "OfflineTranslator"
include(":app", ":core", ":speech-models", ":engine-mlkit", ":engine-sherpa", ":engine-nemo", ":engine-sherpa-model-smoke")
include(":translator-test-kit")
