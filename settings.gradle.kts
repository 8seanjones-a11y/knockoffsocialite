// settings.gradle.kts — tells Gradle where to download build tools from
// and which modules make up this project (just one: "app").

pluginManagement {
    repositories {
        google()            // Android build tools come from Google's Maven
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShortsBlocker"
include(":app")
