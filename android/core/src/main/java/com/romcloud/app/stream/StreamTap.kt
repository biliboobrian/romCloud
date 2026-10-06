package com.romcloud.app.stream

import kotlin.math.roundToInt

/**
 * Son et proportions de l'image du jeu, lus dans l'adaptateur natif (libromcloud_audio_shim) par
 * lequel passe le cœur libretro pendant une diffusion. Même bibliothèque que celle chargée par
 * LibretroDroid (même nom, même processus) : ses variables sont partagées.
 */
object StreamTap {
    private val loaded = runCatching { System.loadLibrary("romcloud_audio_shim") }.isSuccess

    /** Charge la bibliothèque (avant le cœur, pour que LibretroDroid reprenne la même) ; false en cas d'échec. */
    fun load(): Boolean = loaded

    /** Le cœur du jeu passe-t-il par l'adaptateur (son copiable) ? */
    val active: Boolean get() = loaded && nativeActive()

    /** Copie du son vers la diffusion ; [muteLocal] : silence sur le téléphone. */
    fun setCapture(enabled: Boolean, muteLocal: Boolean) {
        if (loaded) nativeSetCapture(enabled, muteLocal)
    }

    /** Échantillons copiés depuis le dernier appel (stéréo entrelacée) ; renvoie leur nombre. */
    fun read(buffer: ShortArray): Int = if (loaded) nativeRead(buffer) else 0

    /** Fréquence du son du cœur (Hz), 0 si inconnue. */
    val sampleRate: Int get() = if (loaded) nativeSampleRate().roundToInt() else 0

    /** Proportions de l'image du jeu (largeur / hauteur), 0 si inconnues. */
    val aspectRatio: Float get() = if (loaded) nativeAspectRatio() else 0f

    private external fun nativeActive(): Boolean
    private external fun nativeSetCapture(enabled: Boolean, mute: Boolean)
    private external fun nativeRead(buffer: ShortArray): Int
    private external fun nativeSampleRate(): Double
    private external fun nativeAspectRatio(): Float
}
