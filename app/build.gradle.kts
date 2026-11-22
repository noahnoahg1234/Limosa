plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.limosa.vm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.limosa.vm"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so each published APK upgrades the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        ndk {
            // QEMU for Android is published for these ABIs; add others if you bundle them.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-friendly: sign release with the debug key unless you configure your own.
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
            // QEMU is executed from nativeLibraryDir, so the .so files must be extracted on install.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
}
