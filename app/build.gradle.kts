plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.absabefi.wasla"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.absabefi.wasla"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Deux copies installables cote a cote : "Wasla" et "Wasla 2" (pour tester avec 2 comptes)
    flavorDimensions += "copie"
    productFlavors {
        create("principal") {
            dimension = "copie"
            manifestPlaceholders["appLabel"] = "Wasla"
        }
        create("deux") {
            dimension = "copie"
            applicationIdSuffix = ".deux"
            versionNameSuffix = "-2"
            manifestPlaceholders["appLabel"] = "Wasla 2"
        }
    }

    // Cle fixe : permet d'installer les mises a jour par-dessus sans desinstaller
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
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

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}
