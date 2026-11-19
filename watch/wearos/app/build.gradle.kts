plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kidguard.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidguard.wear"
        minSdk = 30
        targetSdk = 34
        versionCode = (project.property("wear.code") as String).toInt()
        versionName = project.property("wear.version") as String
    }
    // own release key (created 2026-11-19): app/wear-signing.jks, git-ignored, in scripts/keys.sh backup. NEVER regenerate it:
    // a watch with the app can only update to an APK signed by the same key. Password like the phone apps' key.
    val wearKey = file("wear-signing.jks")
    signingConfigs {
        create("release") {
            storeFile = wearKey; storePassword = "kidguard"; keyAlias = "kidguard-wear"; keyPassword = "kidguard"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (wearKey.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
