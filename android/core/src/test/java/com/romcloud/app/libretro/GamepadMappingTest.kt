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
    fun `gachette au repos a -1`() {
        val l2 = AxisButton(axis = 17, rest = -1f, direction = 1f, retroKey = KeyEvent.KEYCODE_BUTTON_L2)
        assertFalse(l2.isPressed(-1f))
        assertFalse(l2.isPressed(-0.6f))
        assertTrue(l2.isPressed(0f))
    }
}
