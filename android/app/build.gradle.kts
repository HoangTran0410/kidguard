plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystore = file("signing.jks")   // git-ignored; scripts/build.sh creates one

android {
    namespace = "dev.kidguard"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidguard"
        minSdk = 26
        targetSdk = 34
        versionCode = (property("kg.code") as String).toInt()
        versionName = property("kg.version") as String
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
            // self-setup ships a native lib (conscrypt, spake2): real phones are ARM; x86_64 (-2.5 MB) only for debug on an x86 emulator
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }
    // one UI file for the APK (LAN) and the worker (away): web/index.html
    sourceSets["main"].assets.srcDirs("../../web")
    // the apps serve only index.html; the installer pages + their bundle are for the worker / a browser
    androidResources {
        ignoreAssetsPatterns += listOf("!install.html", "!qr.html", "!provision.json", "!vendor")
        // UI languages: English (res/values, default) + Vietnamese (res/values-vi). Also drops libraries' other languages.
        // New language: add res/values-<lang>/strings.xml, list it here and in res/xml/locales_config.xml (Android 13+ per-app language).
        localeFilters += listOf("en", "vi")
    }
    // BouncyCastle (via libadb) ships 1.2 MB of Picnic post-quantum tables; adb pairing never uses them
    packaging { resources { excludes += "org/bouncycastle/pqc/**" } }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    // self-setup: the phone runs adb against itself over Wireless debugging (SelfSetupActivity)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")   // X509 cert for the adb TLS key
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.google.zxing:core:3.5.3")   // pairing QR on the child screen
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
