package com.romcloud.app.libretro

import android.content.Context

/**
 * Présentation en portrait choisie par système (téléphone) : image du jeu en haut, manette tactile
 * en bas, comme une Game Boy. Paysage par défaut.
 */
internal class PortraitLayoutStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_video", Context.MODE_PRIVATE)

    fun load(systemId: String): Boolean = prefs.getBoolean("portrait/$systemId", false)

    fun save(systemId: String, portrait: Boolean) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putBoolean("portrait/$systemId", portrait).commit()
    }

    companion object {
        /** Hauteur de l'image en portrait : au plus 9/10 de la largeur (Game Boy) et 55 % de l'écran. */
        fun screenHeight(width: Int, height: Int): Int = minOf(width * 9 / 10, height * 55 / 100)
    }
}
