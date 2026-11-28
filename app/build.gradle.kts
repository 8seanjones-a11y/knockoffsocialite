// app/build.gradle.kts — the settings for the app itself.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The app's unique id on the phone. Changing it later makes Android treat
    // it as a different app, so leave it alone once installed.
    namespace = "com.example.shortsblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.shortsblocker"
        minSdk = 26        // Android 8.0 or newer
        targetSdk = 35     // Android 15
        versionCode = 7
        versionName = "1.6"
    }

    // Every APK must be signed. Debug builds normally use a key that is
    // created separately on each computer, which means an APK built on one
    // computer can't update an APK built on another. This project keeps its
    // own debug key (debug.keystore, standard password "android") so every
    // build can update the app already on your phone.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No dependencies: the app uses only what's built into Android and Kotlin.
