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

    @Test
    fun `description absente ou mal formee`() {
        val option = CoreOption.parse("key", null, "on")
        assertEquals("key", option.label)
        assertEquals(listOf("on"), option.values)
        assertEquals("key", CoreOption.parse("key", "; a|b", "a").label)
        assertEquals(listOf("x"), CoreOption.parse("key", "Label", "x").values)
    }
}
