package com.romcloud.app.libretro

import android.content.Context
import androidx.annotation.StringRes
import com.romcloud.core.R

/** Format de l'image du jeu à l'écran : celui du cœur, imposé, ou étiré sur toute la vue du jeu. */
internal enum class AspectRatio(@StringRes val label: Int, private val ratio: Float) {
    ORIGINAL(R.string.libretro_aspect_original, 0f),
    RATIO_4_3(R.string.libretro_aspect_4_3, 4f / 3f),
    RATIO_16_9(R.string.libretro_aspect_16_9, 16f / 9f),
    STRETCH(R.string.libretro_aspect_stretch, 0f),
    ;

    /** Largeur / hauteur à afficher dans une vue de [width] x [height] ; 0 : celui du cœur. */
    fun ratio(width: Int, height: Int): Float = when {
        this != STRETCH -> ratio
        width > 0 && height > 0 -> width.toFloat() / height
        else -> 0f
    }
}

/** Format d'image choisi par système (celui du cœur par défaut). */
internal class AspectRatioStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_video", Context.MODE_PRIVATE)

    fun load(systemId: String): AspectRatio =
        prefs.getString("aspect/$systemId", null)?.let { name -> AspectRatio.entries.firstOrNull { it.name == name } }
            ?: AspectRatio.ORIGINAL

    fun save(systemId: String, aspect: AspectRatio) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString("aspect/$systemId", aspect.name).commit()
    }
}
