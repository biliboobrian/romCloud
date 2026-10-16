package com.romcloud.app.libretro

import android.content.Context

/**
 * Run-ahead : images du jeu calculées en avance à chaque image, la dernière affichée, puis état réel
 * rétabli (LibretroDroid) : retard propre au jeu entre la touche et l'image supprimé.
 */
internal object RunAhead {
    /** Choix proposés : désactivé, puis 1 à 3 images d'avance. */
    val CHOICES = listOf(0, 1, 2, 3)
}

/** Run-ahead choisi par système (désactivé par défaut). */
internal class RunAheadStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_video", Context.MODE_PRIVATE)

    fun load(systemId: String): Int = prefs.getInt("runahead/$systemId", 0).coerceIn(0, RunAhead.CHOICES.last())

    fun save(systemId: String, frames: Int) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putInt("runahead/$systemId", frames).commit()
    }
}
