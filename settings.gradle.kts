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
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":common")
include(":mobile")
include(":automotive")
include(":shared")
// Pure-JVM AirPlay/CarPlay protocol tester (fake iPhone client). Self-contained:
// carries its own crypto copy because :shared is an Android library.
include(":fake-iphone")
// carlito | Client for the separate GD vehicle bridge APK.
include(":vehicle-probe")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
