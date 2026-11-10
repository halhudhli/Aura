plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abonoura.aura"
    compileSdk = 37

    signingConfigs {
        // One fixed key for every build, so a new APK installs over the old one and keeps its data.
        getByName("debug") {
            storeFile = file("aura-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.abonoura.aura"
        minSdk = 26
        targetSdk = 36
        versionCode = (providers.gradleProperty("aura.versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("aura.versionName").orNull ?: "1.0.0"
        val subtitleKey = providers.environmentVariable("OPENSUBTITLES_API_KEY").orNull.orEmpty()
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$subtitleKey\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        getByName("debug") {
            // Built as "debug" (no private release key needed) but fully optimised by Android.
            isDebuggable = false
            isJniDebuggable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // mpv: plays every common container and codec, with full subtitle support.
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
