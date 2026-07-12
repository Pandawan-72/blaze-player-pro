import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
    // REMOVED: alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.isFile) {
        FileInputStream(localPropsFile).use { input -> load(input) }
    }
}

fun signingValue(propertyName: String, environmentName: String = propertyName): String =
    localProperties.getProperty(propertyName)?.trim().orEmpty()
        .ifBlank { System.getenv(environmentName)?.trim().orEmpty() }

// Le chemin peut être absolu ou relatif à la racine du projet.
// BLAZE_KEYSTORE_PATH est également accepté pour compatibilité avec les commandes Terminal.
val releaseKeystorePath = signingValue("BLAZE_KEYSTORE_FILE")
    .ifBlank { signingValue("BLAZE_KEYSTORE_PATH") }
    .ifBlank { "../blaze-player.keystore" }

val releaseKeystoreFile = File(releaseKeystorePath).let { candidate ->
    if (candidate.isAbsolute) candidate else rootProject.file(releaseKeystorePath)
}
val releaseStorePassword = signingValue("BLAZE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("BLAZE_KEY_ALIAS")
val releaseKeyPassword = signingValue("BLAZE_KEY_PASSWORD")

val missingReleaseSigningValues = buildList {
    if (!releaseKeystoreFile.isFile) add("BLAZE_KEYSTORE_FILE/BLAZE_KEYSTORE_PATH")
    if (releaseStorePassword.isBlank()) add("BLAZE_KEYSTORE_PASSWORD")
    if (releaseKeyAlias.isBlank()) add("BLAZE_KEY_ALIAS")
    if (releaseKeyPassword.isBlank()) add("BLAZE_KEY_PASSWORD")
}
val hasReleaseSigning = missingReleaseSigningValues.isEmpty()
val releaseBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("bundleRelease", ignoreCase = true) ||
        taskName.contains("assembleRelease", ignoreCase = true) ||
        taskName.contains("publishRelease", ignoreCase = true)
}

// Empêche explicitement la création accidentelle d'un bundle release non signé.
if (releaseBuildRequested && !hasReleaseSigning) {
    throw GradleException(
        "Configuration de signature release incomplète. Valeurs manquantes : " +
            missingReleaseSigningValues.joinToString() +
            ". Renseigne-les dans local.properties ou dans les variables d'environnement."
    )
}

android {
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    namespace = "fr.retrospare.blazeplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.retrospare.blazeplayer"
        minSdk = 28
        targetSdk = 36
        versionCode = 57
        versionName = "0.9.90-Beta RC1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Clé API YouTube Data v3 : lue depuis local.properties (jamais commité), à ajouter
        // toi-même sous la forme YOUTUBE_API_KEY=ta_cle dans ce fichier à la racine du projet.
        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${localProperties.getProperty("YOUTUBE_API_KEY", "")}\""
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // REMOVED: // Firebase
    // REMOVED: implementation(platform(libs.firebase.bom))
    // REMOVED: implementation(libs.firebase.auth)
    // REMOVED: implementation(libs.firebase.firestore)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation("androidx.media3:media3-database:1.9.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // Fork maintenu de FFmpegKit avec build complet compatible pages 16 KB arm64-v8a.
    // Le package précédent `ffmpeg-kit-16kb` était trop minimal pour l'export MP3 : selon le
    // binaire embarqué, `libmp3lame`/`libshine` pouvaient être absents, ce qui faisait échouer
    // systématiquement la conversion audio. Le package full inclut les bibliothèques audio
    // nécessaires, dont LAME et Shine.
    implementation("com.mrljdx:ffmpeg-kit-full:6.1.4")
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    // Bibliothèque éprouvée pour la lecture YouTube embarquée — gère en interne la configuration
    // WebView/referrer que trois tentatives maison n'ont pas réussi à reproduire fiablement
    // (erreurs 150/152/153 systématiques).
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.media:media:1.7.0")
    // REMOVED: implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    // LibVLC pour codecs legacy (AVI, XVID, DIVX, FLAC, DTS, etc.)
    // implementation("androidx.media3:media3-exoplayer:1.5.1")
    // implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    // implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // implementation("androidx.media3:media3-session:1.5.1")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Bibliothèque audio locale : Room est la source de vérité persistante de l’UI.
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")

    // RevenueCat
    implementation(libs.revenuecat)

    // Coil
    implementation(libs.coil)

    // SMB
    implementation(libs.smbj)
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Génération de QR code (invitations Blaze Party) : bibliothèque de référence, testée contre
    // la quasi-totalité des scanners du marché — remplace l'ancien encodeur QR fait main.
    implementation("com.google.zxing:core:3.5.3")
    // Scan QR intégré côté client : évite de déléguer à l'appareil photo/lecteur QR Android,
    // qui peut afficher la feuille native de partage au lieu de renvoyer le contenu à Blaze Player.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Material Design (Views) + AppCompat
    implementation(libs.material)
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.appcompat)

    // Google Sign-In (Credential Manager)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // Hilt Navigation
    implementation(libs.hilt.navigation.fragment)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
