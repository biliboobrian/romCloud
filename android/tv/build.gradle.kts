import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Application Android TV (même clé, même version que l’application téléphone).
// Clé de signature release : variables d'environnement (CI GitHub) ou fichier
// android/keystore.properties (local, non versionné). À défaut, clé de debug.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("ROMCLOUD_KEYSTORE_FILE", "storeFile")

android {
    namespace = "com.romcloud.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.romcloud.app.tv"
        minSdk = 26
        targetSdk = 36
        // Fournis par le CI (numéro de build, tag de version) ; valeurs par défaut en local.
        versionCode = System.getenv("ROMCLOUD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ROMCLOUD_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0-dev"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Téléviseurs : xhdpi (1080p) et xxhdpi (4K) uniquement, pas de mdpi/hdpi.
        disable += "IconMissingDensityFolder"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
