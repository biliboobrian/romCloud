package com.romcloud.app.libretro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllersSectionTest {

    private val snes = mappingSteps(PadLayouts.forGame("snes", "snes9x"))

    private fun element(label: String, mapping: GamepadMapping?) = stepElement(snes.first { it.label == label }, mapping)

    @Test
    fun `boutons de la console sur la manette, sans configuration`() {
        // Disposition d'Android : bouton du bas (BUTTON_A) = B du RetroPad, comme le bouton du bas de la Super Nintendo.
        assertEquals("a", element("B", null))
        assertEquals("b", element("A", null))
        assertEquals("x", element("Y", null))
        assertEquals("y", element("X", null))
        assertEquals("leftshoulder", element("L", null))
        assertEquals("dpup", stepElement(snes.first { it.retroKey == KeyEvent.KEYCODE_DPAD_UP }, null))
        assertEquals("back", stepElement(snes.first { it.retroKey == KeyEvent.KEYCODE_BUTTON_SELECT }, null))
    }

    @Test
    fun `boutons de la console sur la manette configuree`() {
        // A et B de la Super Nintendo échangés : le bouton de droite donne B, celui du bas A.
        val swapped = GamepadMapping(
            keys = mapOf(KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_A),
        )
        assertEquals("b", element("B", swapped))
        assertEquals("a", element("A", swapped))
        // Boutons non configurés : correspondance par défaut.
        assertEquals("x", element("Y", swapped))
    }

    @Test
    fun `sticks, axes standard sans configuration`() {
        val psx = mappingSteps(PadLayouts.forGame("psx", "swanstation"))
        assertEquals("leftx", stepElement(psx.first { it.stick == StickAxis.LEFT_X }, null))
        assertEquals("righty", stepElement(psx.first { it.stick == StickAxis.Y }, null))
    }
}
