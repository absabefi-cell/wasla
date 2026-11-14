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
        versionCode = 6
        versionName = "2.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("boolean", "MIRE", "false")
    }

    buildFeatures { buildConfig = true }

    // Deux copies installables cote a cote : "Wasla", "Wasla 2" et "Wasla 3" (pour tester avec plusieurs comptes)
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
        // Wasla 3 : envoie une image de test animee au lieu de la camera
        create("trois") {
            dimension = "copie"
            applicationIdSuffix = ".trois"
            versionNameSuffix = "-3"
            manifestPlaceholders["appLabel"] = "Wasla 3"
            buildConfigField("boolean", "MIRE", "true")
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
