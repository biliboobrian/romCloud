package com.romcloud.app.libretro

import android.content.Context
import androidx.annotation.StringRes
import com.romcloud.core.R
import com.swordfish.libretrodroid.ShaderColors
import com.swordfish.libretrodroid.ShaderConfig

/** Affichage d'un réglage : pourcentage, nombre décimal, entier, ou oui / non (0 ou 1). */
internal enum class ParamFormat { PERCENT, DECIMAL, INTEGER, SWITCH }

/** Réglage d'un shader : identifiant mémorisé, libellé, valeur par défaut, bornes et pas. */
internal data class ShaderParam(
    val id: String,
    @StringRes val label: Int,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val format: ParamFormat = ParamFormat.DECIMAL,
) {
    /** Valeur ramenée dans les bornes, au pas près. */
    fun clamp(value: Float): Float {
        val stepped = if (step > 0f) Math.round((value - min) / step) * step + min else value
        return stepped.coerceIn(min, max)
    }
}

/** Valeurs des réglages d'un shader (valeur par défaut pour ceux qui n'ont pas été changés). */
internal class ShaderValues(private val values: Map<String, Float>) {
    operator fun get(param: ShaderParam): Float = values[param.id]?.let(param::clamp) ?: param.default
    fun on(param: ShaderParam): Boolean = get(param) >= 0.5f
}

private fun p(id: String, @StringRes label: Int, default: Float, min: Float, max: Float, step: Float, format: ParamFormat = ParamFormat.DECIMAL) =
    ShaderParam(id, label, default, min, max, step, format)

private fun switch(id: String, @StringRes label: Int, default: Boolean) =
    ShaderParam(id, label, if (default) 1f else 0f, 0f, 1f, 1f, ParamFormat.SWITCH)

// Réglages des shaders CUT (LibretroDroid), communs aux trois versions.
private val DYNAMIC_BLEND = switch("useDynamicBlend", R.string.shader_p_dynamic_blend, true)
private fun blendMinContrast(default: Float) = p("blendMinContrastEdge", R.string.shader_p_blend_min_contrast, default, 0f, 1f, 0.05f)
private fun blendMaxContrast(default: Float) = p("blendMaxContrastEdge", R.string.shader_p_blend_max_contrast, default, 0f, 1f, 0.05f)
private fun blendMinSharpness(default: Float) = p("blendMinSharpness", R.string.shader_p_blend_min_sharpness, default, 0f, 1f, 0.05f)
private fun blendMaxSharpness(default: Float) = p("blendMaxSharpness", R.string.shader_p_blend_max_sharpness, default, 0f, 1f, 0.05f)
private fun staticSharpness(default: Float) = p("staticSharpness", R.string.shader_p_static_sharpness, default, 0f, 1f, 0.05f)
private fun fastLuma(default: Boolean) = switch("edgeUseFastLuma", R.string.shader_p_fast_luma, default)
private val SOFT_EDGES = switch("softEdgesSharpening", R.string.shader_p_soft_edges, true)
private val SOFT_EDGES_AMOUNT = p("softEdgesSharpeningAmount", R.string.shader_p_soft_edges_amount, 1f, 0f, 2f, 0.1f)
private val HARD_EDGES_ERROR = p("hardEdgesSearchMaxError", R.string.shader_p_hard_edges_error, 0.25f, 0f, 1f, 0.05f)

// CUT.
private val CUT_MIN_CONTRAST = blendMinContrast(0f)
private val CUT_MAX_CONTRAST = blendMaxContrast(1f)
private val CUT_MIN_SHARPNESS = blendMinSharpness(0f)
private val CUT_MAX_SHARPNESS = blendMaxSharpness(1f)
private val CUT_STATIC_SHARPNESS = staticSharpness(0.5f)
private val CUT_FAST_LUMA = fastLuma(true)
private val CUT_EDGE_MIN_VALUE = p("edgeMinValue", R.string.shader_p_edge_min_value, 0.05f, 0f, 1f, 0.01f)
private val CUT_EDGE_MIN_CONTRAST = p("edgeMinContrast", R.string.shader_p_edge_min_contrast, 2f, 1f, 5f, 0.1f)

// CUT2 et CUT3 (mêmes valeurs par défaut).
private val CUT23_MIN_CONTRAST = blendMinContrast(0f)
private val CUT23_MAX_CONTRAST = blendMaxContrast(0.5f)
private val CUT23_MIN_SHARPNESS = blendMinSharpness(0f)
private val CUT23_MAX_SHARPNESS = blendMaxSharpness(0.75f)
private val CUT23_STATIC_SHARPNESS = staticSharpness(0.75f)
private val CUT23_FAST_LUMA = fastLuma(false)
private val CUT3_DISTANCE = p("hardEdgesSearchMaxDistance", R.string.shader_p_hard_edges_distance, 4f, 1f, 8f, 1f, ParamFormat.INTEGER)

private val SHARP_WIDTH = p("width", R.string.shader_p_width, 1f, 0.5f, 4f, 0.25f)
private val CRT_INTENSITY = p("intensity", R.string.shader_p_scanlines, 0.30f, 0f, 1f, 0.05f, ParamFormat.PERCENT)
private val CRT_BOOST = p("brightBoost", R.string.shader_p_glow, 0.30f, 0f, 1f, 0.05f, ParamFormat.PERCENT)
private val LCD_INTENSITY = p("intensity", R.string.shader_p_grid, 0.25f, 0f, 1f, 0.05f, ParamFormat.PERCENT)
private val LCD_BOOST = p("brightBoost", R.string.shader_p_glow, 0.25f, 0f, 1f, 0.05f, ParamFormat.PERCENT)

// Couleurs de l'image finale, communes à tous les shaders (mémorisées pour chacun).
private val BRIGHTNESS = p("brightness", R.string.shader_p_brightness, 1f, 0.5f, 1.5f, 0.05f, ParamFormat.PERCENT)
private val CONTRAST = p("contrast", R.string.shader_p_contrast, 1f, 0.5f, 1.5f, 0.05f, ParamFormat.PERCENT)
private val SATURATION = p("saturation", R.string.shader_p_saturation, 1f, 0f, 2f, 0.05f, ParamFormat.PERCENT)
private val GAMMA = p("gamma", R.string.shader_p_gamma, 1f, 0.5f, 2f, 0.05f, ParamFormat.PERCENT)
private val COLOR_PARAMS = listOf(BRIGHTNESS, CONTRAST, SATURATION, GAMMA)

private fun ShaderValues.colors() = ShaderColors(this[BRIGHTNESS], this[CONTRAST], this[SATURATION], this[GAMMA])

/**
 * Shader d'image de LibretroDroid, sous son vrai nom ([title]) : rendu des pixels de la console
 * agrandis à l'écran (résolution de l'écran rarement multiple de celle du jeu). [ownParams] : ses
 * réglages, suivis des couleurs (tous les shaders).
 */
internal enum class VideoFilter(
    val title: String,
    @StringRes val description: Int,
    private val ownParams: List<ShaderParam>,
    private val build: (ShaderValues) -> ShaderConfig,
) {
    /** Pixels bruts (plus proche voisin) : largeurs inégales quand l'agrandissement n'est pas entier. */
    PIXELS("Nearest", R.string.libretro_filter_pixels_desc, emptyList(), { v -> ShaderConfig.Default(v.colors()) }),
    /** Pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux. */
    SHARP("Sharp Bilinear", R.string.libretro_filter_sharp_desc, listOf(SHARP_WIDTH), { v ->
        ShaderConfig.Sharp(v[SHARP_WIDTH], v.colors())
    }),
    /** Lissage simple et rapide avec détection des contours. */
    CUT("CUT", R.string.libretro_filter_cut_desc, listOf(
        DYNAMIC_BLEND, CUT_MIN_CONTRAST, CUT_MAX_CONTRAST, CUT_MIN_SHARPNESS, CUT_MAX_SHARPNESS,
        CUT_STATIC_SHARPNESS, CUT_FAST_LUMA, CUT_EDGE_MIN_VALUE, CUT_EDGE_MIN_CONTRAST,
    ), { v ->
        ShaderConfig.CUT(
            useDynamicBlend = v.on(DYNAMIC_BLEND),
            blendMinContrastEdge = v[CUT_MIN_CONTRAST],
            blendMaxContrastEdge = v[CUT_MAX_CONTRAST],
            blendMinSharpness = v[CUT_MIN_SHARPNESS],
            blendMaxSharpness = v[CUT_MAX_SHARPNESS],
            staticSharpness = v[CUT_STATIC_SHARPNESS],
            edgeUseFastLuma = v.on(CUT_FAST_LUMA),
            edgeMinValue = v[CUT_EDGE_MIN_VALUE],
            edgeMinContrast = v[CUT_EDGE_MIN_CONTRAST],
            colors = v.colors(),
        )
    }),
    /** Lissage des contours en deux passes, proche d'un dessin. */
    CUT2("CUT2", R.string.libretro_filter_cut2_desc, listOf(
        DYNAMIC_BLEND, CUT23_MIN_CONTRAST, CUT23_MAX_CONTRAST, CUT23_MIN_SHARPNESS, CUT23_MAX_SHARPNESS,
        CUT23_STATIC_SHARPNESS, CUT23_FAST_LUMA, SOFT_EDGES, SOFT_EDGES_AMOUNT, HARD_EDGES_ERROR,
    ), { v ->
        ShaderConfig.CUT2(
            useDynamicBlend = v.on(DYNAMIC_BLEND),
            blendMinContrastEdge = v[CUT23_MIN_CONTRAST],
            blendMaxContrastEdge = v[CUT23_MAX_CONTRAST],
            blendMinSharpness = v[CUT23_MIN_SHARPNESS],
            blendMaxSharpness = v[CUT23_MAX_SHARPNESS],
            staticSharpness = v[CUT23_STATIC_SHARPNESS],
            edgeUseFastLuma = v.on(CUT23_FAST_LUMA),
            softEdgesSharpening = v.on(SOFT_EDGES),
            softEdgesSharpeningAmount = v[SOFT_EDGES_AMOUNT],
            hardEdgesSearchMaxError = v[HARD_EDGES_ERROR],
            colors = v.colors(),
        )
    }),
    /** Lissage des contours en trois passes (celui de Lemuroid). */
    CUT3("CUT3", R.string.libretro_filter_cut3_desc, listOf(
        DYNAMIC_BLEND, CUT23_MIN_CONTRAST, CUT23_MAX_CONTRAST, CUT23_MIN_SHARPNESS, CUT23_MAX_SHARPNESS,
        CUT23_STATIC_SHARPNESS, CUT23_FAST_LUMA, SOFT_EDGES, SOFT_EDGES_AMOUNT, HARD_EDGES_ERROR, CUT3_DISTANCE,
    ), { v ->
        ShaderConfig.CUT3(
            useDynamicBlend = v.on(DYNAMIC_BLEND),
            blendMinContrastEdge = v[CUT23_MIN_CONTRAST],
            blendMaxContrastEdge = v[CUT23_MAX_CONTRAST],
            blendMinSharpness = v[CUT23_MIN_SHARPNESS],
            blendMaxSharpness = v[CUT23_MAX_SHARPNESS],
            staticSharpness = v[CUT23_STATIC_SHARPNESS],
            edgeUseFastLuma = v.on(CUT23_FAST_LUMA),
            softEdgesSharpening = v.on(SOFT_EDGES),
            softEdgesSharpeningAmount = v[SOFT_EDGES_AMOUNT],
            hardEdgesSearchMaxError = v[HARD_EDGES_ERROR],
            hardEdgesSearchMaxDistance = v[CUT3_DISTANCE].toInt(),
            colors = v.colors(),
        )
    }),
    CRT("CRT", R.string.libretro_filter_crt_desc, listOf(CRT_INTENSITY, CRT_BOOST), { v ->
        ShaderConfig.CRT(v[CRT_INTENSITY], v[CRT_BOOST], v.colors())
    }),
    LCD("LCD", R.string.libretro_filter_lcd_desc, listOf(LCD_INTENSITY, LCD_BOOST), { v ->
        ShaderConfig.LCD(v[LCD_INTENSITY], v[LCD_BOOST], v.colors())
    }),
    ;

    /** Réglages proposés : ceux du shader, puis les couleurs. */
    val params: List<ShaderParam> get() = ownParams + COLOR_PARAMS

    fun shader(values: Map<String, Float> = emptyMap()): ShaderConfig = build(ShaderValues(values))

    companion object {
        val DEFAULT = SHARP

        /**
         * Anciens profils (nom mémorisé) devenus un shader avec ses réglages : « lissage doux » (CUT),
         * « lissage des contours » (CUT3), « contours nets » (CUT3 plus net), « contours doux » (CUT2
         * adouci, sans renfort des contours doux).
         */
        val LEGACY: Map<String, Pair<VideoFilter, Map<String, Float>>> = mapOf(
            "SOFT" to (CUT to emptyMap()),
            "SMOOTH" to (CUT3 to emptyMap()),
            "EDGES_SHARP" to (CUT3 to mapOf("blendMaxSharpness" to 1f, "staticSharpness" to 1f, "softEdgesSharpeningAmount" to 1.5f)),
            "EDGES_SOFT" to (CUT2 to mapOf("blendMaxSharpness" to 0.4f, "staticSharpness" to 0.4f, "softEdgesSharpening" to 0f)),
        )
    }
}

/** Shader d'image choisi par système (Sharp Bilinear par défaut) et ses réglages, par système et shader. */
internal class VideoFilterStore(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_video", Context.MODE_PRIVATE)

    fun load(systemId: String): VideoFilter {
        val name = prefs.getString(systemId, null) ?: return VideoFilter.DEFAULT
        VideoFilter.entries.firstOrNull { it.name == name }?.let { return it }
        // Ancien profil : shader et réglages repris (sans écraser des réglages déjà faits).
        val (filter, values) = VideoFilter.LEGACY[name] ?: return VideoFilter.DEFAULT
        val editor = prefs.edit().putString(systemId, filter.name)
        if (filter.params.none { prefs.contains(key(systemId, filter, it.id)) }) {
            values.forEach { (id, value) -> editor.putFloat(key(systemId, filter, id), value) }
        }
        editor.commit()
        return filter
    }

    fun save(systemId: String, filter: VideoFilter) {
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString(systemId, filter.name).commit()
    }

    /** Réglages changés pour ce shader et ce système (les autres gardent leur valeur par défaut). */
    fun values(systemId: String, filter: VideoFilter): Map<String, Float> =
        filter.params.mapNotNull { param ->
            val key = key(systemId, filter, param.id)
            if (prefs.contains(key)) param.id to param.clamp(prefs.getFloat(key, param.default)) else null
        }.toMap()

    fun saveValue(systemId: String, filter: VideoFilter, param: ShaderParam, value: Float) {
        prefs.edit().putFloat(key(systemId, filter, param.id), param.clamp(value)).commit()
    }

    /** Valeurs par défaut rétablies pour ce shader et ce système. */
    fun reset(systemId: String, filter: VideoFilter) {
        val editor = prefs.edit()
        filter.params.forEach { editor.remove(key(systemId, filter, it.id)) }
        editor.commit()
    }

    private fun key(systemId: String, filter: VideoFilter, id: String) = "$systemId.${filter.name}.$id"
}
