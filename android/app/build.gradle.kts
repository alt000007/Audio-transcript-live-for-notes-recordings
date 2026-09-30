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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Debug only: a debug APK is self-signed, so it installs by sideload
        // without a Play account or a release keystore.
        getByName("debug") {
            isMinifyEnabled = false
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
