pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // Huawei AGConnect plugin (`com.huawei.agconnect`) — required for the
        // Wear Engine SDK build-time integration. Plugin marker is published
        // only in Huawei's repo, not Maven Central.
        maven {
            url = uri("https://developer.huawei.com/repo/")
            content {
                includeGroupByRegex("com\\.huawei.*")
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            // Huawei does NOT publish a standard Gradle plugin marker for
            // `com.huawei.agconnect` — the artifact is `agcp` under the
            // `com.huawei.agconnect` group, applied legacy-style via
            // buildscript classpath. Map the plugin id to the real artifact
            // so we can keep the modern `plugins {}` DSL.
            if (requested.id.id == "com.huawei.agconnect") {
                useModule("com.huawei.agconnect:agcp:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Huawei HMS Core libraries (Wear Engine SDK + AGConnect runtime).
        maven {
            url = uri("https://developer.huawei.com/repo/")
            content {
                includeGroupByRegex("com\\.huawei.*")
            }
        }
    }
}

rootProject.name = "GT Alarm"
include(":app")
