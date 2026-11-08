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
    // kid = KidGuard (Device Owner child app, kidguard.apk). home = "KidGuard Home" (kidguard-home.apk): the kid launcher
    // alone, its own package, no admin / accessibility / relay / location (src/home/AndroidManifest.xml removes them);
    // code checks BuildConfig.HOME (Flavor.kt) to hide what needs a parent
    flavorDimensions += "app"
    productFlavors {
        create("kid") { dimension = "app"; buildConfigField("boolean", "HOME", "false") }
        create("home") { dimension = "app"; applicationIdSuffix = ".home"; buildConfigField("boolean", "HOME", "true") }
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
    // one UI for the APK (LAN) and the worker (away): ui/index.html + ui/assets/
    sourceSets["main"].assets.srcDirs("../../ui")
    // the apps serve only index.html + assets/; the installer pages + their bundle are for the worker / a browser
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

// KidGuard Home has no self-setup (adb over Wi-Fi): drop its native libs (~2.5 MB per ABI); R8 drops the classes
androidComponents {
    onVariants(selector().withFlavor("app" to "home")) { v ->
        v.packaging.jniLibs.excludes.addAll(listOf("**/libconscrypt_jni.so", "**/libspake2.so"))
    }
}

dependencies {
    // self-setup: the phone runs adb against itself over Wireless debugging (SelfSetupActivity)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")   // X509 cert for the adb TLS key
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.google.zxing:core:3.5.3")   // pairing QR on the child screen
    // writes src/main/baseline-prof.txt's AOT profile at first start: the launcher is compiled at once instead of
    // running interpreted / JIT for its first seconds (~70 ms frames on the first swipes after an install or a kill)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
