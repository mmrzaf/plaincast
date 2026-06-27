pluginManagement {
    val runningInGithubActions = System.getenv("GITHUB_ACTIONS") == "true"
    val useIranMirrors = !runningInGithubActions && (
            providers.gradleProperty("useIranMirrors").orNull == "true" ||
                    System.getenv("USE_IRAN_MIRRORS") == "true"
            )

    repositories {
        if (useIranMirrors) {
            maven { url = uri("https://maven.myket.ir") }
            maven { url = uri("https://maven.devneeds.ir") }
            maven { url = uri("https://gradle.iranrepo.ir") }
            maven { url = uri("https://gradle.jamko.ir") }
            maven { url = uri("https://en-mirror.ir") }
            maven { url = uri("https://archive.ito.gov.ir/gradle/maven-plugin/") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }

        google()
        mavenCentral()
        gradlePluginPortal()

        if (!useIranMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    val runningInGithubActions = System.getenv("GITHUB_ACTIONS") == "true"
    val useIranMirrors = !runningInGithubActions && (
            providers.gradleProperty("useIranMirrors").orNull == "true" ||
                    System.getenv("USE_IRAN_MIRRORS") == "true"
            )

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useIranMirrors) {
            maven { url = uri("https://maven.myket.ir") }
            maven { url = uri("https://maven.devneeds.ir") }
            maven { url = uri("https://gradle.iranrepo.ir") }
            maven { url = uri("https://gradle.jamko.ir") }
            maven { url = uri("https://en-mirror.ir") }
            maven { url = uri("https://archive.ito.gov.ir/gradle/maven-plugin/") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }

        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        if (!useIranMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
    }
}

rootProject.name = "PlainCast"
include(":app")
