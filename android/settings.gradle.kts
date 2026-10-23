pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RomCloud"
// Moteur intégré (LibretroDroid repris dans le projet, rendu Vulkan ajouté).
include(":libretrodroid")
include(":core")
include(":app")
include(":tv")
// RomCloud PC : moteur des jeux PC (Winlator Cmod), application compagnon (targetSdk 28).
include(":pc")
