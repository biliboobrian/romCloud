package com.romcloud.app.libretro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadInputTest {

    @Test
    fun `touche Retour d'un bouton de manette remplacee par le bouton`() {
        // DualShock 3 (numérotation 0x120…) : Rond, Select.
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BACK, 0x12d))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BACK, 0x120))
        // Pilote récent : BTN_EAST, BTN_SELECT.
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BACK, 0x131))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_MENU, 0x13a))
    }

    @Test
    fun `vraie touche systeme ou autre touche inchangee`() {
        assertNull(GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BACK, 0x13c)) // bouton PS (BTN_MODE)
        assertNull(GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BACK, 158)) // KEY_BACK d'une télécommande
        assertNull(GamepadInput.buttonOfSystemKey(KeyEvent.KEYCODE_BUTTON_A, 0x130))
    }
}
