// Code partagé par les applications téléphone (:app) et TV (:tv) : accès au serveur,
// téléchargements, lancement des émulateurs, réglages, logique des écrans et fenêtres communes.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.romcloud.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Mêmes architectures que LibretroDroid.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        // Adaptateur en C++ sans bibliothèque standard : rien d'autre à embarquer.
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    // Adaptateur audio des cœurs qui envoient leur son échantillon par échantillon (cap32).
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.coil.compose)
    implementation(libs.libretrodroid)
    testImplementation(libs.junit)
}
