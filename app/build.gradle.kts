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
        versionCode = 3
        versionName = "0.4.0"
        // self-setup over Wireless debugging ships a native lib (conscrypt, spake2): phones + the emulator only
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
    // self-setup: the phone runs adb against itself over Wireless debugging (SelfSetupActivity)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")   // X509 cert for the adb TLS key
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
