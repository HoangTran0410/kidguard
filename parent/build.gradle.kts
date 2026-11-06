plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystore = file("../app/signing.jks")   // shared with :app, git-ignored; ./build.sh creates it. Never regenerate.

android {
    namespace = "dev.kidguard.parent"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidguard.parent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.0"
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
    // the same single-file parent page the child phone and the worker serve
    sourceSets["main"].assets.srcDirs("../web")
    lint { checkReleaseBuilds = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
