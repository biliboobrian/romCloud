package com.romcloud.app.libretro

import android.content.Context
import androidx.annotation.StringRes
import com.romcloud.core.R
import com.swordfish.libretrodroid.ShaderConfig

/**
 * Filtre d'image de LibretroDroid : rendu des pixels de la console agrandis à l'écran (résolution
 * de l'écran rarement multiple de celle du jeu).
 */
internal enum class VideoFilter(@StringRes val label: Int, val shader: () -> ShaderConfig) {
    /** Pixels nets, sans lissage : largeurs inégales quand l'agrandissement n'est pas entier. */
    PIXELS(R.string.libretro_filter_pixels, { ShaderConfig.Default }),
    /** Pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux. */
    SHARP(R.string.libretro_filter_sharp, { ShaderConfig.Sharp }),
    /** Agrandissement avec détection des contours (CUT3, comme le lissage de Lemuroid). */
    SMOOTH(R.string.libretro_filter_smooth, { ShaderConfig.CUT3() }),
    CRT(R.string.libretro_filter_crt, { ShaderConfig.CRT }),
    LCD(R.string.libretro_filter_lcd, { ShaderConfig.LCD }),
    ;

    val next: VideoFilter get() = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SHARP
    }
}

/** Filtre d'image choisi par système (lissage net par défaut). */
internal class VideoFilterStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_video", Context.MODE_PRIVATE)

    fun load(systemId: String): VideoFilter =
        prefs.getString(systemId, null)?.let { name -> VideoFilter.entries.firstOrNull { it.name == name } }
            ?: VideoFilter.DEFAULT

    fun save(systemId: String, filter: VideoFilter) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString(systemId, filter.name).commit()
    }
}
