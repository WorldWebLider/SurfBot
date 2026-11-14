import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin by itself; no separate kotlin-android plugin here.
    id("com.android.application")
}

android {
    namespace = "uz.nurbek.surfbot"
    compileSdk = 35
    defaultConfig {
        applicationId = "uz.nurbek.surfbot"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "tflite" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.2")
}
