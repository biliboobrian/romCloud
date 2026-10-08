package com.romcloud.app.libretro

/**
 * Réglages de l'adaptateur natif des cœurs libretro (libromcloud_audio_shim, voir audio_shim.cpp).
 * Même bibliothèque que celle chargée par LibretroDroid à la place du cœur (même nom, même
 * processus) : chargée avant le cœur, ses variables sont partagées.
 */
internal object CoreShim {
    private val loaded = runCatching { System.loadLibrary("romcloud_audio_shim") }.isSuccess

    /** Charge la bibliothèque (avant le cœur) ; false en cas d'échec. */
    fun load(): Boolean = loaded

    /** Format d'image (largeur / hauteur ; 0 : celui du cœur), appliqué à la prochaine image. */
    fun setAspectRatio(aspect: Float) {
        if (loaded) nativeSetAspectRatio(aspect)
    }

    private external fun nativeSetAspectRatio(aspect: Float)
}
