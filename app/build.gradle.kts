plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.onscreen.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onscreen.agent"
        minSdk = 35 // Android 15 per request. Target+min = 15+. See README for minSdk 29 trade-off.
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-cloud-rust"
        ndk { abiFilters += listOf("arm64-v8a") } // cloud drop: 64-bit only, keeps APK <25MB
        resConfigs += listOf("en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += setOf("META-INF/*") } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui:1.7.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.3")
}
