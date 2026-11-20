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
                ivy {
                    name = "verifiedSherpaRuntime"
                    url = uri("engine-sherpa/libs")
                    patternLayout { artifact("[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("local.verified.sherpa") }
        }
        exclusiveContent {
            forRepository {
                flatDir {
                    name = "verifiedTencentNative"
                    dirs(rootDir.resolve("engine-tencent/libs"), rootDir.resolve("engine-mozilla/libs"))
                }
            }
            filter { includeGroup("io.github.ngoroth.offlinetranslator.native") }
        }
    }
}

rootProject.name = "OfflineTranslator"
include(":app", ":core", ":speech-models", ":engine-sherpa", ":engine-sherpa-model-smoke")
include(":translator-test-kit")
include(":engine-tencent")
include(":engine-mozilla")
