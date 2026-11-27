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
