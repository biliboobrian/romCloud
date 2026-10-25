import java.util.Properties

// RomCloud PC : moteur des jeux PC (Wine, Box64, DXVK…) repris de Winlator Cmod (licence MIT,
// LICENSE-winlator), application compagnon sans icône lancée par RomCloud. targetSdk 28 : Android
// interdit d'exécuter les programmes du dossier de l'application (Wine, Box64…) à partir de 29.
// Gros fichiers (système Linux, Proton, pilotes) : téléchargés au premier lancement (ContentPack).
plugins {
    alias(libs.plugins.android.application)
}

// Même clé de signature que RomCloud (seul RomCloud peut lancer les jeux : permission de signature).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("ROMCLOUD_KEYSTORE_FILE", "storeFile")

android {
    namespace = "com.winlator.cmod"
    compileSdk = 37

    defaultConfig {
        // Même longueur que « com.winlator.cmod » : chemins des programmes du système Linux réécrits
        // sans décalage (PackagePath). Jamais de suffixe (applicationIdSuffix) sur ce module.
        applicationId = "com.romcloud.wine"
        minSdk = 26
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = System.getenv("ROMCLOUD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ROMCLOUD_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0-dev"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("ROMCLOUD_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ROMCLOUD_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ROMCLOUD_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Code de Winlator lu par réflexion et JNI : pas de réduction.
            isMinifyEnabled = false
            // Images de Winlator : des GIF nommés .png (animation du mode Big Picture), refusés par la compression.
            isCrunchPngs = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Programmes et bibliothèques extraits à l'installation (lancés depuis le dossier natif).
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/*.version", "META-INF/versions/**") }
    }
    lint {
        // Code repris de Winlator : avertissements d'origine non bloquants.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Dépendances de Winlator Cmod (inutilisées retirées :
    // conscrypt, bouncycastle, openjsse, glide, junixsocket).
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.github.luben:zstd-jni:1.5.7-24@aar")
    implementation(libs.xz)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation(files("libs/MidiSynth/MidiSynth.jar"))
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation(libs.okhttp)
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    testImplementation(libs.junit)
}
