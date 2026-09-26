import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

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
    namespace = "com.romcloud.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.romcloud.app"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
