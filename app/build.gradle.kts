plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.nurbek.surfbot"
    compileSdk = 35
    defaultConfig {
        applicationId = "uz.nurbek.surfbot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
