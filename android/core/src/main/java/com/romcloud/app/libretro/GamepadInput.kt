package com.romcloud.app.libretro

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Conversion des touches Android vers la manette libretro (RetroPad) attendue par LibretroDroid.
 * Sur une manette Android, A/B et X/Y sont inversés par rapport au RetroPad (disposition Nintendo).
 */
internal object GamepadInput {

    private val GAMEPAD_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
    )

    /** Touches qui ouvrent le menu de l'émulateur (Retour, bouton central de la manette, Menu). */
    val MENU_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU)

    /** Code RetroPad de la touche, ou null si elle ne correspond à aucun bouton. */
    fun retroKey(keyCode: Int): Int? {
        val code = when (keyCode) {
            // Télécommande TV : OK = bouton A.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_BUTTON_A
            else -> keyCode
        }
        if (code !in GAMEPAD_KEYS) return null
        return when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> code
        }
    }

    /** Joueur (port libretro) correspondant à la manette : 0 pour la première ou une télécommande. */
    fun port(event: KeyEvent): Int = ((event.device?.controllerNumber ?: 0) - 1).coerceAtLeast(0)

    /** Une manette physique est-elle branchée ? (sinon la manette tactile est affichée) */
    fun hasGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        !device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }
}
