plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.limosa.xr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.limosa.xr"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so each published APK upgrades the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.2.$build"
        ndk {
            // Headset games are arm64; x86_64 is for emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Games load the runtime into their own process: keep our C++ library private.
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            // The OpenXR loader in each game opens the runtime by file path, so it must be extracted.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
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
    // Document-start scripts (navigator.xr before any page code) and the https asset origin.
    implementation("androidx.webkit:webkit:1.12.1")
}
