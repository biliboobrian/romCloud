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

    /**
     * Bouton réel d'une manette qui envoie une touche système (Retour, Menu) à la place d'un bouton :
     * la DualShock 3 en Bluetooth, selon le système, signale ainsi Rond ou Select. Retrouvé d'après le
     * code du bouton côté Linux ([scanCode]) ; null pour une vraie touche système (bouton PS / Home :
     * menu de l'émulateur) ou un code inconnu.
     */
    fun buttonOfSystemKey(keyCode: Int, scanCode: Int): Int? {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU) return null
        return LINUX_BUTTONS[scanCode]
    }

    /**
     * Codes des boutons de manette sous Linux -> touches Android : pilote récent (BTN_SOUTH… : même
     * correspondance que Generic.kl) et numérotation de la DualShock 3 (0x120…, comme
     * Vendor_054c_Product_0268.kl). Bouton central (BTN_MODE, 0x2c0) absent : il ouvre le menu.
     */
    private val LINUX_BUTTONS = mapOf(
        0x130 to KeyEvent.KEYCODE_BUTTON_A, 0x131 to KeyEvent.KEYCODE_BUTTON_B,
        0x133 to KeyEvent.KEYCODE_BUTTON_X, 0x134 to KeyEvent.KEYCODE_BUTTON_Y,
        0x136 to KeyEvent.KEYCODE_BUTTON_L1, 0x137 to KeyEvent.KEYCODE_BUTTON_R1,
        0x138 to KeyEvent.KEYCODE_BUTTON_L2, 0x139 to KeyEvent.KEYCODE_BUTTON_R2,
        0x13a to KeyEvent.KEYCODE_BUTTON_SELECT, 0x13b to KeyEvent.KEYCODE_BUTTON_START,
        0x13d to KeyEvent.KEYCODE_BUTTON_THUMBL, 0x13e to KeyEvent.KEYCODE_BUTTON_THUMBR,
        // DualShock 3 : Select, L3, R3, Start, croix, gâchettes, Triangle, Rond, Croix, Carré.
        0x120 to KeyEvent.KEYCODE_BUTTON_SELECT, 0x121 to KeyEvent.KEYCODE_BUTTON_THUMBL,
        0x122 to KeyEvent.KEYCODE_BUTTON_THUMBR, 0x123 to KeyEvent.KEYCODE_BUTTON_START,
        0x124 to KeyEvent.KEYCODE_DPAD_UP, 0x125 to KeyEvent.KEYCODE_DPAD_RIGHT,
        0x126 to KeyEvent.KEYCODE_DPAD_DOWN, 0x127 to KeyEvent.KEYCODE_DPAD_LEFT,
        0x128 to KeyEvent.KEYCODE_BUTTON_L2, 0x129 to KeyEvent.KEYCODE_BUTTON_R2,
        0x12a to KeyEvent.KEYCODE_BUTTON_L1, 0x12b to KeyEvent.KEYCODE_BUTTON_R1,
        0x12c to KeyEvent.KEYCODE_BUTTON_Y, 0x12d to KeyEvent.KEYCODE_BUTTON_B,
        0x12e to KeyEvent.KEYCODE_BUTTON_A, 0x12f to KeyEvent.KEYCODE_BUTTON_X,
    )

    /** Joueur (port libretro) correspondant à la manette : 0 pour la première ou une télécommande. */
    fun port(event: KeyEvent): Int = ((event.device?.controllerNumber ?: 0) - 1).coerceAtLeast(0)

    /** Manette physique (boutons de manette ou axes de joystick) : pas une télécommande ni un clavier. */
    fun isGamepad(device: InputDevice): Boolean =
        !device.isVirtual && (
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            )

    /** Une manette physique est-elle branchée ? (sinon la manette tactile est affichée) */
    fun hasGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let(::isGamepad) == true
    }
}
