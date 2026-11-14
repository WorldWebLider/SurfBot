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
    // One fixed key for every build, so a new APK installs over the old one.
    signingConfigs {
        create("fixed") {
            storeFile = file("surfbot.keystore")
            storePassword = "surfbot123"
            keyAlias = "surfbot"
            keyPassword = "surfbot123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
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
