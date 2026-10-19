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
                    name = "verifiedSherpaRelease"
                    dirs(rootDir.resolve("engine-sherpa/libs"))
                }
            }
            filter { includeGroup("local.verified.sherpa") }
        }
    }
}

rootProject.name = "OfflineTranslator"
include(":app", ":core", ":engine-mlkit", ":engine-sherpa", ":engine-sherpa-model-smoke")
include(":translator-test-kit")
