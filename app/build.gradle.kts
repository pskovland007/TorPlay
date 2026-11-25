plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.torplay.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torplay.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 12
        versionName = "1.0.0"
    }

    signingConfigs {
        create("torplaySign") {
            storeFile = file("torplay-release.jks")
            storePassword = System.getenv("TORPLAY_STORE_PASSWORD") ?: "TorPlayKey2026!"
            keyAlias = System.getenv("TORPLAY_KEY_ALIAS") ?: "torplay"
            keyPassword = System.getenv("TORPLAY_KEY_PASSWORD") ?: "TorPlayKey2026!"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("torplaySign")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("torplaySign")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Media3 / ExoPlayer 1.3.1 + Jellyfin FFmpeg decoder from Maven Central (AC3/EAC3/DTS)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")
}
