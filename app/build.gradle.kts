plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Count commits on HEAD → monotonically increasing versionCode so each build
// is considered "newer" than the last one, letting Android do in-place updates.
val buildVersionCode: Int by lazy {
    try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir).redirectErrorStream(true).start()
        p.waitFor()
        p.inputStream.bufferedReader().readText().trim().toInt()
    } catch (_: Throwable) { 100 }
}

android {
    namespace = "com.patnaik.sam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.patnaik.sam"
        minSdk = 24
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = "2.$buildVersionCode"
    }

    // Pinned signing — same key on every build so Android does in-place updates.
    // Explicitly enable v1 + v2 + v3 so older installers that reject v2-only APKs still work.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
}
