// SPDX-License-Identifier: MIT
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.soprez.vision"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.soprez.vision"
        // Android 5.0 : couvre Fire OS 5+, vieux téléphones et TV.
        minSdk = 21
        // 33 : évite les contraintes de type de service au premier plan
        // d'Android 14 tout en restant installable partout.
        targetSdk = 36
        versionCode = 174
        versionName = "1.90.0"
        // Essais sur émulateur (androidTest) : le lanceur d'essais du système, sans bibliothèque à télécharger.
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    signingConfigs {
        create("release") {
            if (signing.isNotEmpty()) {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Les essais sans appareil (logique pure : le glisser-déposer de l'accueil).
    testImplementation("junit:junit:4.13.2")
    // Les flux HLS des caméras (segments fMP4 de Home Assistant) : le MediaPlayer du système ne les lit pas.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // Le QR de l'appairage : affiché (encodage) et scanné (décodage des images de la caméra).
    implementation("com.google.zxing:core:3.5.3")
    // La caméra, seulement pour scanner le QR de Home Assistant (sans services Google).
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.lifecycle:lifecycle-runtime:2.6.2")
    // Client Sendspin natif (lecteur de Music Assistant) : WebSocket, et X25519 / ChaCha20-Poly1305 / SHA-512 de Noise
    // et de l'appairage (Android ne les a qu'à partir des API 28 à 33).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Sa MediaSession (télécommande, système) : même version que media3-exoplayer.
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
}

// Les essais sur émulateur (androidTest) : mêmes versions d'androidx que l'appli (déjà en cache),
// sinon leur classpath redemande les versions plus anciennes déclarées par CameraX.
dependencies {
    androidTestImplementation("androidx.annotation:annotation:1.3.0")
    androidTestImplementation("androidx.core:core:1.8.0")
    androidTestImplementation("androidx.collection:collection:1.2.0")
}
