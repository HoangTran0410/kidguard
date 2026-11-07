plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystore = file("../app/signing.jks")   // shared with :app, git-ignored; scripts/build.sh creates it. Never regenerate.

// Optional FCM (instant SOS): android/parent/google-services.json from the Firebase console, git-ignored. Its values become the
// resources the Firebase SDK auto-initialises from (what the google-services plugin would generate). Missing = FCM off.
val fcm: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val j = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val info = j["project_info"] as Map<String, Any?>
    val client = (j["client"] as List<Map<String, Any?>>).first {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "dev.kidguard.parent"
    }
    mapOf(
        "google_app_id" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
        "google_api_key" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "project_id" to info["project_id"] as String,
        "gcm_defaultSenderId" to info["project_number"] as String,
    )
} ?: emptyMap()

android {
    namespace = "dev.kidguard.parent"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidguard.parent"
        minSdk = 26
        targetSdk = 34
        versionCode = (property("kg.code") as String).toInt()
        versionName = property("kg.version") as String
        fcm.forEach { (k, v) -> resValue("string", k, v) }
        buildConfigField("String", "FCM_APP_ID", "\"" + (fcm["google_app_id"] ?: "") + "\"")
    }
    buildFeatures { buildConfig = true; resValues = true }
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
    sourceSets["main"].assets.srcDirs("../../web")
    androidResources {
        ignoreAssetsPatterns += listOf("!install.html", "!qr.html", "!provision.json", "!vendor")
        // UI languages: English (res/values, default) + Vietnamese (res/values-vi). Also drops libraries' other languages.
        // New language: add res/values-<lang>/strings.xml, list it here and in res/xml/locales_config.xml (Android 13+ per-app language).
        localeFilters += listOf("en", "vi")
    }
    lint { checkReleaseBuilds = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
