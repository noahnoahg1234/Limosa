plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.limosa.kdehome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.limosa.kdehome"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so each published APK upgrades the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        ndk {
            // proot is bundled for these ABIs by scripts/fetch_proot_termux.py.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // One fixed key for every build. CI otherwise signs each build with a new random debug key,
    // and Android refuses to update an app whose key changed, so updating meant uninstalling,
    // which deletes Linux. This key is public (sideloading only): don't use it for a store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("signing.keystore")
            storePassword = "kdehome-sideload"
            keyAlias = "kdehome"
            keyPassword = "kdehome-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // proot is executed from nativeLibraryDir, so the .so files must be extracted on install.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Pure-Java XZ decoder for the Debian root filesystem tarball.
    implementation("org.tukaani:xz:1.10")
}
