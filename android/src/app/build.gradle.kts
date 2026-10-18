// SPDX-License-Identifier: MIT
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signature : jamais dans un dépôt (l'ancien mot de passe a fui dans l'historique public). Dans l'ordre :
// 1. VISION_SIGNATURE = chemin d'un fichier .properties (storeFile, storePassword, keyAlias, keyPassword) ;
// 2. ~/.vision/vision-signature.properties ;
// 3. keystore.properties à la racine du projet (ancien mode, ignoré par git).
// VISION_KEYSTORE et VISION_KEYSTORE_PASSWORD (variables d'environnement) remplacent le fichier ou le complètent.
// Le magasin est en PKCS12 : la clé a le mot de passe du magasin. Sans rien de tout cela : APK non signé.
val signing = Properties().apply {
    listOfNotNull(
        System.getenv("VISION_SIGNATURE")?.takeIf { it.isNotBlank() }?.let { file(it) },
        file(System.getProperty("user.home") + "/.vision/vision-signature.properties"),
        rootProject.file("keystore.properties"),
    ).firstOrNull { it.isFile }?.inputStream()?.use { load(it) }
    System.getenv("VISION_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { setProperty("storeFile", it) }
    System.getenv("VISION_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let {
        setProperty("storePassword", it)
        setProperty("keyPassword", it)
    }
    if (containsKey("storeFile") && !containsKey("keyAlias")) setProperty("keyAlias", "vision")
}

android {
    namespace = "org.soprez.vision"
    // Compilé contre l'API qu'on vise : targetSdk 36 (depuis la 1.34.2) contre un
    // compileSdk 34 laissait le code ignorer les API et changements de comportement
    // d'Android 15 et 16 qu'il subit pourtant sur les appareils récents.
    compileSdk = 36

    defaultConfig {
        applicationId = "org.soprez.vision"
        // Android 5.0 : couvre Fire OS 5+, vieux téléphones et TV.
        minSdk = 21
        // 36 (Android 16) depuis la 1.34.2. Le service au premier plan déclare
        // foregroundServiceType="specialUse", exigé à partir de la cible 34.
        targetSdk = 36
        versionCode = 189
        versionName = "1.93.05"
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

    // Recette calculée (version suivante, 17/10) : captures d'écran sans émulateur (Robolectric, rendu natif, Roborazzi).
    // Les ressources de l'appli (textes, polices, couleurs) sont données aux essais JVM pour qu'ils dessinent pour de vrai.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// La recette calculée (essais du paquet org.soprez.vision.captures) est lente à démarrer : elle ne tourne qu'à la demande,
//   gradle :app:testDebugUnitTest -Pcaptures --tests "org.soprez.vision.captures.*"
// et écrit ses images et son rapport dans app/build/captures-recette/. Sans -Pcaptures, les essais JVM restent rapides.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("captures")) {
        maxHeapSize = "1536m"
        systemProperty("roborazzi.test.record", "true")
        systemProperty("captures.dossier", layout.buildDirectory.dir("captures-recette").get().asFile.absolutePath)
        // La langue des textes (fr par défaut, comme la recette sur émulateur) : -Pcaptures.langue=de pour les textes longs.
        systemProperty("captures.langue", (project.findProperty("captures.langue") ?: "fr").toString())
        // Le rendu natif de Robolectric (polices, mesure réelle des textes : c'est elle qui trouve les « … »).
        systemProperty("robolectric.graphicsMode", "NATIVE")
    } else {
        exclude("org/soprez/vision/captures/**")
    }
}

dependencies {
    // Les essais sans appareil (logique pure : le glisser-déposer de l'accueil).
    testImplementation("junit:junit:4.13.2")
    // Recette calculée (captures sans émulateur) : Robolectric pour faire tourner les écrans sur la JVM, Roborazzi pour
    // les images. Seulement dans les essais : rien n'entre dans l'APK.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
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
    androidTestImplementation("androidx.core:core:1.19.1")
    androidTestImplementation("androidx.collection:collection:1.2.0")
}
