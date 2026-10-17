plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.scribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.scribe"
        minSdk = 26          // AudioRecord + MediaCodec paths below assume Oreo
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // The on-device transcriber is native code. 64-bit ARM only: every
        // phone that runs a current Android is, and each extra ABI adds
        // megabytes and build time for nothing.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // One fixed key for every build. Without it each CI run signed with a
        // freshly generated key, Android refused to install the new APK over
        // the old one, and the only way forward was to uninstall — which
        // deletes every saved transcript and the file queue with it.
        //
        // This key is in the repository on purpose: it is a sideload key for
        // a personal app, not a secret that guards anything else. To use a
        // private one instead, put it in the SCRIBE_KEYSTORE_B64 Actions
        // secret (with SCRIBE_KEYSTORE_PASSWORD); the workflow then uses it.
        getByName("debug") {
            val custom = System.getenv("SCRIBE_KEYSTORE")
            storeFile = if (custom.isNullOrBlank()) rootProject.file("signing/scribe-debug.jks") else file(custom)
            storePassword = System.getenv("SCRIBE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "scribe-debug"
            keyAlias = System.getenv("SCRIBE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "scribe"
            keyPassword = System.getenv("SCRIBE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "scribe-debug"
        }
    }

    buildTypes {
        // Debug only: a debug APK installs by sideload without a Play account.
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
