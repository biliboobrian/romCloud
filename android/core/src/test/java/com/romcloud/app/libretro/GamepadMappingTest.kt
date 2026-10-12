package com.romcloud.app.libretro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMappingTest {

    @Test
    fun `touches configurees prioritaires, sinon correspondance par defaut si le bouton est libre`() {
        // DualShock 3 mal reconnue : la Croix arrive comme SELECT, le Rond comme THUMBL.
        val mapping = GamepadMapping(
            keys = mapOf(
                KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_A,
            ),
        )
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, mapping.retroKey(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, mapping.retroKey(KeyEvent.KEYCODE_BUTTON_THUMBL))
        // BUTTON_A d'Android donnerait B (déjà attribué) : ignoré ; START reste START.
        assertNull(mapping.retroKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, mapping.retroKey(KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test
    fun `etapes d'apres la manette de la console`() {
        // Mega Drive : rangée du bas d'abord, ni Select ni stick droit.
        val genesis = mappingSteps(PadLayouts.forGame("genesis", "")).mapNotNull { it.label }
        assertEquals(listOf("A", "B", "C", "X", "Y", "Z", "START"), genesis)
        // Nintendo 64 : stick, puis boutons C sur le stick droit.
        val n64 = mappingSteps(PadLayouts.forGame("n64", "")).filter { it.stick != null }
        assertEquals(
            listOf(null to StickAxis.LEFT_X, null to StickAxis.LEFT_Y, "C▶" to StickAxis.X, "C▼" to StickAxis.Y),
            n64.map { it.label to it.stick },
        )
        // PlayStation 2 : L3, R3, stick gauche puis stick droit ; PSP : son stick seul, sans L3.
        val ps2 = mappingSteps(PadLayouts.forGame("ps2", ""))
        assertTrue(ps2.any { it.retroKey == KeyEvent.KEYCODE_BUTTON_THUMBR })
        assertEquals(listOf(StickAxis.LEFT_X, StickAxis.LEFT_Y, StickAxis.X, StickAxis.Y), ps2.mapNotNull { it.stick })
        val psp = mappingSteps(PadLayouts.forGame("psp", ""))
        assertTrue(psp.none { it.label == "L3" })
        assertEquals(listOf(StickAxis.LEFT_X, StickAxis.LEFT_Y), psp.mapNotNull { it.stick })
        // Mega Drive : pas de stick.
        assertTrue(mappingSteps(PadLayouts.forGame("genesis", "")).none { it.stick != null })
    }

    @Test
    fun `gachette au repos a -1`() {
        val l2 = AxisButton(axis = 17, rest = -1f, direction = 1f, retroKey = KeyEvent.KEYCODE_BUTTON_L2)
        assertFalse(l2.isPressed(-1f))
        assertFalse(l2.isPressed(-0.6f))
        assertTrue(l2.isPressed(0f))
    }
}
