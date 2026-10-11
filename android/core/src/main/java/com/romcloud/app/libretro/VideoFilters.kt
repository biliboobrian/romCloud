package com.romcloud.app.libretro

import android.content.Context
import androidx.annotation.StringRes
import com.romcloud.core.R
import com.swordfish.libretrodroid.ShaderConfig

/**
 * Filtre d'image de LibretroDroid : rendu des pixels de la console agrandis à l'écran (résolution
 * de l'écran rarement multiple de celle du jeu).
 */
internal enum class VideoFilter(
    @StringRes val label: Int,
    @StringRes val description: Int,
    /** Shader avec la netteté réglée ([sharpness] : 1 = celle du filtre). */
    private val build: (sharpness: Float) -> ShaderConfig,
    /** Netteté réglable (lissages de LibretroDroid ; CRT, LCD et pixels nets n'ont pas de réglage). */
    val adjustable: Boolean = false,
) {
    /** Pixels nets, sans lissage : largeurs inégales quand l'agrandissement n'est pas entier. */
    PIXELS(R.string.libretro_filter_pixels, R.string.libretro_filter_pixels_desc, { ShaderConfig.Default }),
    /** Pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux. */
    SHARP(R.string.libretro_filter_sharp, R.string.libretro_filter_sharp_desc, { ShaderConfig.Sharp }),
    /** Lissage simple et rapide avec détection des contours (CUT). */
    SOFT(R.string.libretro_filter_soft, R.string.libretro_filter_soft_desc, { m -> ShaderConfig.CUT().sharpened(m) }, adjustable = true),
    /** Agrandissement avec détection des contours (CUT3, comme le lissage de Lemuroid). */
    SMOOTH(R.string.libretro_filter_smooth, R.string.libretro_filter_smooth_desc, { m -> ShaderConfig.CUT3().sharpened(m) }, adjustable = true),
    /** CUT3 plus net : contours francs, aplats lissés. */
    EDGES_SHARP(R.string.libretro_filter_edges_sharp, R.string.libretro_filter_edges_sharp_desc, { m ->
        ShaderConfig.CUT3(blendMaxSharpness = 1.0f, staticSharpness = 1.0f, softEdgesSharpeningAmount = 1.5f).sharpened(m)
    }, adjustable = true),
    /** CUT2 adouci : rendu proche d'un dessin. */
    EDGES_SOFT(R.string.libretro_filter_edges_soft, R.string.libretro_filter_edges_soft_desc, { m ->
        ShaderConfig.CUT2(blendMaxSharpness = 0.4f, staticSharpness = 0.4f, softEdgesSharpening = false).sharpened(m)
    }, adjustable = true),
    CRT(R.string.libretro_filter_crt, R.string.libretro_filter_crt_desc, { ShaderConfig.CRT }),
    LCD(R.string.libretro_filter_lcd, R.string.libretro_filter_lcd_desc, { ShaderConfig.LCD }),
    ;

    fun shader(sharpness: Float = 1f): ShaderConfig = build(sharpness)

    companion object {
        val DEFAULT = SHARP
        /** Netteté réglable : de 0 à 200 % de celle du filtre, par pas de 10 %. */
        const val SHARPNESS_MIN = 0f
        const val SHARPNESS_MAX = 2f
        const val SHARPNESS_STEP = 0.1f
    }
}

/** Netteté du shader multipliée par [m], dans les bornes du shader (0 à 1). */
internal fun sharpness(value: Float, m: Float) = (value * m).coerceIn(0f, 1f)

private fun ShaderConfig.CUT.sharpened(m: Float) = copy(
    blendMinSharpness = sharpness(blendMinSharpness, m),
    blendMaxSharpness = sharpness(blendMaxSharpness, m),
    staticSharpness = sharpness(staticSharpness, m),
)

private fun ShaderConfig.CUT2.sharpened(m: Float) = copy(
    blendMinSharpness = sharpness(blendMinSharpness, m),
    blendMaxSharpness = sharpness(blendMaxSharpness, m),
    staticSharpness = sharpness(staticSharpness, m),
)

private fun ShaderConfig.CUT3.sharpened(m: Float) = copy(
    blendMinSharpness = sharpness(blendMinSharpness, m),
    blendMaxSharpness = sharpness(blendMaxSharpness, m),
    staticSharpness = sharpness(staticSharpness, m),
)

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

    /** Netteté réglée pour ce filtre et ce système (1 = celle du filtre). */
    fun sharpness(systemId: String, filter: VideoFilter): Float =
        prefs.getFloat("$systemId.${filter.name}.sharpness", 1f).coerceIn(VideoFilter.SHARPNESS_MIN, VideoFilter.SHARPNESS_MAX)

    fun saveSharpness(systemId: String, filter: VideoFilter, value: Float) {
        prefs.edit().putFloat("$systemId.${filter.name}.sharpness", value).commit()
    }
}
