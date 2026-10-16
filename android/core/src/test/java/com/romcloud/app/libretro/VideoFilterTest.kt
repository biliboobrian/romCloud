package com.romcloud.app.libretro

import com.swordfish.libretrodroid.ShaderColors
import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFilterTest {

    @Test
    fun valeursParDefautCellesDeLibretroDroid() {
        assertEquals(ShaderConfig.Default(), VideoFilter.PIXELS.shader())
        assertEquals(ShaderConfig.Sharp(), VideoFilter.SHARP.shader())
        assertEquals(ShaderConfig.CUT(), VideoFilter.CUT.shader())
        assertEquals(ShaderConfig.CUT2(), VideoFilter.CUT2.shader())
        assertEquals(ShaderConfig.CUT3(), VideoFilter.CUT3.shader())
        assertEquals(ShaderConfig.CRT(), VideoFilter.CRT.shader())
        assertEquals(ShaderConfig.LCD(), VideoFilter.LCD.shader())
    }

    @Test
    fun reglagesAppliquesAuShader() {
        val cut3 = VideoFilter.CUT3.shader(
            mapOf("staticSharpness" to 0.4f, "softEdgesSharpening" to 0f, "hardEdgesSearchMaxDistance" to 6f, "saturation" to 1.5f)
        ) as ShaderConfig.CUT3
        assertEquals(0.4f, cut3.staticSharpness, 1e-6f)
        assertFalse(cut3.softEdgesSharpening)
        assertEquals(6, cut3.hardEdgesSearchMaxDistance)
        assertEquals(ShaderColors(saturation = 1.5f), cut3.colors)
        val crt = VideoFilter.CRT.shader(mapOf("intensity" to 0.6f)) as ShaderConfig.CRT
        assertEquals(0.6f, crt.intensity, 1e-6f)
    }

    @Test
    fun valeursRameneesDansLesBornesEtAuPas() {
        val distance = VideoFilter.CUT3.params.first { it.id == "hardEdgesSearchMaxDistance" }
        assertEquals(8f, distance.clamp(20f), 1e-6f)
        assertEquals(3f, distance.clamp(3.4f), 1e-6f)
        val crt = VideoFilter.CRT.shader(mapOf("intensity" to 5f)) as ShaderConfig.CRT
        assertEquals(1f, crt.intensity, 1e-6f)
    }

    @Test
    fun couleursProposeesPourTousLesShaders() {
        for (filter in VideoFilter.entries) {
            assertTrue(filter.params.map { it.id }.containsAll(listOf("brightness", "contrast", "saturation", "gamma")))
        }
    }

    @Test
    fun anciensProfilsRegroupes() {
        assertEquals(VideoFilter.CUT3, VideoFilter.LEGACY.getValue("SMOOTH").first)
        assertEquals(VideoFilter.CUT3, VideoFilter.LEGACY.getValue("EDGES_SHARP").first)
        assertEquals(VideoFilter.CUT2, VideoFilter.LEGACY.getValue("EDGES_SOFT").first)
        assertEquals(VideoFilter.CUT, VideoFilter.LEGACY.getValue("SOFT").first)
        // « Contours nets » : CUT3 avec les réglages du profil.
        val sharp = VideoFilter.CUT3.shader(VideoFilter.LEGACY.getValue("EDGES_SHARP").second) as ShaderConfig.CUT3
        assertEquals(ShaderConfig.CUT3(blendMaxSharpness = 1.0f, staticSharpness = 1.0f, softEdgesSharpeningAmount = 1.5f), sharp)
    }
}
