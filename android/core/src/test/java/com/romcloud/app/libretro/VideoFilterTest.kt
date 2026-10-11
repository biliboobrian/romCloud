package com.romcloud.app.libretro

import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFilterTest {

    @Test
    fun netteteParDefautInchangee() {
        assertEquals(ShaderConfig.CUT3(), VideoFilter.SMOOTH.shader(1f))
        assertEquals(ShaderConfig.CRT, VideoFilter.CRT.shader(2f))
    }

    @Test
    fun netteteMultiplieeDansLesBornesDuShader() {
        val base = ShaderConfig.CUT2(blendMaxSharpness = 0.4f, staticSharpness = 0.4f, softEdgesSharpening = false)
        val sharper = VideoFilter.EDGES_SOFT.shader(2f) as ShaderConfig.CUT2
        assertEquals(0.8f, sharper.staticSharpness, 1e-6f)
        assertEquals(base.softEdgesSharpening, sharper.softEdgesSharpening)
        val max = VideoFilter.EDGES_SHARP.shader(2f) as ShaderConfig.CUT3
        assertEquals(1f, max.staticSharpness, 1e-6f)
        assertEquals(0f, (VideoFilter.SOFT.shader(0f) as ShaderConfig.CUT).staticSharpness, 1e-6f)
    }

    @Test
    fun seulsLesLissagesSontReglables() {
        assertTrue(VideoFilter.SMOOTH.adjustable)
        assertFalse(VideoFilter.CRT.adjustable)
        assertFalse(VideoFilter.SHARP.adjustable)
    }
}
