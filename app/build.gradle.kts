plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("BSP_RELEASE_KEYSTORE")
val releaseStorePassword = System.getenv("BSP_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("BSP_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("BSP_RELEASE_KEY_PASSWORD")

android {
    namespace = "com.bspsms.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bspsms.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        // Permanently built-in Google Apps Script Web App /exec URL
        buildConfigField(
            "String",
            "APPS_SCRIPT_URL",
            "\"https://script.google.com/macros/s/AKfycbxK3DKwsX5S6NFSSurkfHQRzOrWL6I5NEiC9B9Qlty4cX5U1ycdosVychR-QwlKh9c/exec\""
        )

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.rmtheis:tess-two:9.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
