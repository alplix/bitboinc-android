import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Signing: keystore and passwords live OUTSIDE the repo (never committed): ~/.bitboinc-android/signing.properties
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".bitboinc-android/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "edu.berkeley.boinc"
    compileSdk = 35

    buildFeatures { aidl = true; buildConfig = true }

    defaultConfig {
        applicationId = "org.athena.bitboinc"
        minSdk = 26
        targetSdk = 28          // BOINC's client runs from app data; API 29+ forbids executing files there
        versionCode = 9
        versionName = "0.1.8"
        resourceConfigurations += listOf("en", "tr", "de", "fr", "es", "it", "pt-rBR", "ru", "pl", "nl", "uk", "cs", "ro", "sv", "ja", "ko", "zh-rCN", "vi")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps["storeFile"] as String)
                storePassword = signingProps["storePassword"] as String
                keyAlias = signingProps["keyAlias"] as String
                keyPassword = signingProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okio:okio:3.9.0")
    implementation("com.google.guava:guava:33.2.1-android")
    implementation("org.apache.commons:commons-lang3:3.14.0")
    implementation("com.google.dagger:dagger:2.52")
    ksp("com.google.dagger:dagger-compiler:2.52")
}
