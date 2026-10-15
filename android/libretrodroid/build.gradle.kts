// Moteur d'émulation intégré : LibretroDroid 0.14.0 (Filippo Scognamiglio, GPL 3, voir LICENSE),
// repris dans le projet (plus maintenu en amont) et complété : rendu Vulkan des cœurs (vulkan.cpp).
// Oboe (son, Apache 2) et la partie utile de libretro-common : versions figées de LibretroDroid 0.14.0.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
