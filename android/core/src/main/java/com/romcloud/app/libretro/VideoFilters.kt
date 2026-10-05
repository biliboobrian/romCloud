package com.romcloud.app.libretro

import android.content.Context
import androidx.annotation.StringRes
import com.romcloud.core.R
import com.swordfish.libretrodroid.ShaderConfig

/**
 * Filtre d'image de LibretroDroid : rendu des pixels de la console agrandis à l'écran (résolution
 * de l'écran rarement multiple de celle du jeu).
 */
internal enum class VideoFilter(@StringRes val label: Int, @StringRes val description: Int, val shader: () -> ShaderConfig) {
    /** Pixels nets, sans lissage : largeurs inégales quand l'agrandissement n'est pas entier. */
    PIXELS(R.string.libretro_filter_pixels, R.string.libretro_filter_pixels_desc, { ShaderConfig.Default }),
    /** Pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux. */
    SHARP(R.string.libretro_filter_sharp, R.string.libretro_filter_sharp_desc, { ShaderConfig.Sharp }),
    /** Lissage simple et rapide avec détection des contours (CUT). */
    SOFT(R.string.libretro_filter_soft, R.string.libretro_filter_soft_desc, { ShaderConfig.CUT() }),
    /** Agrandissement avec détection des contours (CUT3, comme le lissage de Lemuroid). */
    SMOOTH(R.string.libretro_filter_smooth, R.string.libretro_filter_smooth_desc, { ShaderConfig.CUT3() }),
    /** CUT3 plus net : contours francs, aplats lissés. */
    EDGES_SHARP(R.string.libretro_filter_edges_sharp, R.string.libretro_filter_edges_sharp_desc, {
        ShaderConfig.CUT3(blendMaxSharpness = 1.0f, staticSharpness = 1.0f, softEdgesSharpeningAmount = 1.5f)
    }),
    /** CUT2 adouci : rendu proche d'un dessin. */
    EDGES_SOFT(R.string.libretro_filter_edges_soft, R.string.libretro_filter_edges_soft_desc, {
        ShaderConfig.CUT2(blendMaxSharpness = 0.4f, staticSharpness = 0.4f, softEdgesSharpening = false)
    }),
    CRT(R.string.libretro_filter_crt, R.string.libretro_filter_crt_desc, { ShaderConfig.CRT }),
    LCD(R.string.libretro_filter_lcd, R.string.libretro_filter_lcd_desc, { ShaderConfig.LCD }),
    ;

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
