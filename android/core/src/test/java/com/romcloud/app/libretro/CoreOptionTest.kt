package com.romcloud.app.libretro

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreOptionTest {

    @Test
    fun `libelle et valeurs au format libretro`() {
        val option = CoreOption.parse("beetle_psx_hw_internal_resolution", "Internal GPU Resolution; 1x(native)|2x|4x|8x", "2x")
        assertEquals("Internal GPU Resolution", option.label)
        assertEquals(listOf("1x(native)", "2x", "4x", "8x"), option.values)
        assertEquals("1x(native)", option.default)
        assertEquals("2x", option.value)
    }

    private fun option(key: String) = CoreOption(key, key, listOf("a", "b"), "a")

    @Test
    fun `options regroupees par type apres le prefixe du coeur`() {
        val groups = CoreOptionGroups.group(
            listOf(
                option("beetle_psx_hw_gpu_overclock"),
                option("beetle_psx_hw_renderer"),
                option("beetle_psx_hw_cpu_freq_scale"),
                option("beetle_psx_hw_gpu_scale"),
                option("beetle_psx_hw_cpu_dynarec"),
                option("beetle_psx_hw_skip_bios"),
            ),
        )
        assertEquals(listOf(null, "CPU", "GPU"), groups.map { it.title })
        // « renderer » (sans mot suivant) et « skip » (seul de son type) : options générales.
        assertEquals(listOf("beetle_psx_hw_renderer", "beetle_psx_hw_skip_bios"), groups[0].options.map { it.key })
        assertEquals(listOf("beetle_psx_hw_gpu_overclock", "beetle_psx_hw_gpu_scale"), groups[2].options.map { it.key })
    }

    @Test
    fun `separateur tiret et titre long`() {
        val groups = CoreOptionGroups.group(
            listOf(option("mupen64plus-parallel-rdp-upscaling"), option("mupen64plus-parallel-rdp-dither"), option("mupen64plus-43screensize")),
        )
        assertEquals(listOf(null, "Parallel"), groups.map { it.title })
    }

    @Test
    fun `description absente ou mal formee`() {
        val option = CoreOption.parse("key", null, "on")
        assertEquals("key", option.label)
        assertEquals(listOf("on"), option.values)
        assertEquals("key", CoreOption.parse("key", "; a|b", "a").label)
        assertEquals(listOf("x"), CoreOption.parse("key", "Label", "x").values)
    }
}
