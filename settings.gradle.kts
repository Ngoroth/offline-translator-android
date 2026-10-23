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
        exclusiveContent {
            forRepository {
                flatDir {
                    name = "verifiedTencentNative"
                    dirs(rootDir.resolve("engine-tencent/libs"))
                }
            }
            filter { includeGroup("com.example.offlinetranslator.native") }
        }
    }
}

rootProject.name = "OfflineTranslator"
include(":app", ":core", ":speech-models", ":engine-mlkit", ":engine-sherpa", ":engine-sherpa-model-smoke")
include(":translator-test-kit")
include(":engine-tencent")
