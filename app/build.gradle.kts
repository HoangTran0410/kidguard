plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystore = file("signing.jks")   // git-ignored; setup.sh / build.sh creates one

android {
    namespace = "dev.kidguard"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("release") {
            storeFile = keystore
            storePassword = "kidguard"
            keyAlias = "kidguard"
            keyPassword = "kidguard"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { if (keystore.exists()) signingConfig = signingConfigs.getByName("release") }
    }
    // one UI file for the APK (LAN) and the worker (away): web/index.html
    sourceSets["main"].assets.srcDirs("../web")
    lint { checkReleaseBuilds = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
