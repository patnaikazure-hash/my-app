plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.patnaik.sam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.patnaik.sam"
        minSdk = 26                 // API 26 required for onRenderProcessGone + RENDERER_PRIORITY_IMPORTANT
        targetSdk = 34              // Deliberate: stays on 34 to dodge Android 15's 6h dataSync FGS cap
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        viewBinding = true
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
    // 1.11.0 is the current stable version of androidx.webkit with all the features we need:
    // WebViewAssetLoader, WebViewFeature, WebViewClientCompat, setOffscreenPreRaster
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
}
