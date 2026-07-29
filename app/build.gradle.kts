plugins {
    id("com.android.application")
}

android {
    namespace = "dev.desmond.gptwakeprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.desmond.gptwakeprobe"
        minSdk = 32
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-sherpa"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "probe"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Kotlin API and native .so both come from the same sherpa-onnx v1.13.4 AAR.
    implementation(files("libs/sherpa-onnx-1.13.4-classes.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
}
