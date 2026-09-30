package com.romcloud.app.libretro

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.romcloud.core.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Axe analogique utilisé comme bouton (gâchette) : pressé quand il s'écarte de [rest] dans le sens [direction]. */
@Serializable
internal data class AxisButton(val axis: Int, val rest: Float, val direction: Float, val retroKey: Int) {
    fun isPressed(value: Float) = (value - rest) * direction > 0.5f
}

/**
 * Configuration d'une manette faite par l'utilisateur (manettes dont Android mélange les boutons,
 * comme la DualShock 3 en Bluetooth) : touches Android et axes -> boutons RetroPad, axes du stick droit.
 */
@Serializable
internal data class GamepadMapping(
    val keys: Map<Int, Int> = emptyMap(),
    val axes: List<AxisButton> = emptyList(),
    val rightStickX: Int? = null,
    val rightStickY: Int? = null,
    val invertRightX: Boolean = false,
    val invertRightY: Boolean = false,
) {
    /** Bouton RetroPad d'une touche : configuration, sinon correspondance par défaut si ce bouton est libre. */
    fun retroKey(keyCode: Int): Int? {
        keys[keyCode]?.let { return it }
        val fallback = GamepadInput.retroKey(keyCode) ?: return null
        return fallback.takeIf { it !in keys.values && axes.none { a -> a.retroKey == it } }
    }
}

/** Étapes de la configuration : boutons RetroPad (disposition Super Nintendo) puis stick droit. */
internal enum class MappingStep(@StringRes val label: Int, val retroKey: Int = 0, val stick: Boolean = false) {
    UP(R.string.pad_up, KeyEvent.KEYCODE_DPAD_UP),
    DOWN(R.string.pad_down, KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(R.string.pad_left, KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(R.string.pad_right, KeyEvent.KEYCODE_DPAD_RIGHT),
    B(R.string.pad_b, KeyEvent.KEYCODE_BUTTON_B),
    A(R.string.pad_a, KeyEvent.KEYCODE_BUTTON_A),
    Y(R.string.pad_y, KeyEvent.KEYCODE_BUTTON_Y),
    X(R.string.pad_x, KeyEvent.KEYCODE_BUTTON_X),
    L1(R.string.pad_l1, KeyEvent.KEYCODE_BUTTON_L1),
    R1(R.string.pad_r1, KeyEvent.KEYCODE_BUTTON_R1),
    L2(R.string.pad_l2, KeyEvent.KEYCODE_BUTTON_L2),
    R2(R.string.pad_r2, KeyEvent.KEYCODE_BUTTON_R2),
    SELECT(R.string.pad_select, KeyEvent.KEYCODE_BUTTON_SELECT),
    START(R.string.pad_start, KeyEvent.KEYCODE_BUTTON_START),
    L3(R.string.pad_l3, KeyEvent.KEYCODE_BUTTON_THUMBL),
    R3(R.string.pad_r3, KeyEvent.KEYCODE_BUTTON_THUMBR),
    RIGHT_STICK_X(R.string.pad_right_stick_x, stick = true),
    RIGHT_STICK_Y(R.string.pad_right_stick_y, stick = true),
}

/** Configurations mémorisées par modèle de manette (fabricant, produit, nom). */
internal class GamepadMappings(context: Context) {

    private val prefs = context.getSharedPreferences("libretro_gamepads", Context.MODE_PRIVATE)
    private val cache = HashMap<String, GamepadMapping?>()
    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(device: InputDevice) = "${device.vendorId}:${device.productId}:${device.name}"

    fun get(device: InputDevice): GamepadMapping? {
        val key = keyOf(device)
        return cache.getOrPut(key) {
            prefs.getString(key, null)?.let { runCatching { json.decodeFromString(GamepadMapping.serializer(), it) }.getOrNull() }
        }
    }

    fun save(deviceKey: String, mapping: GamepadMapping) {
        cache[deviceKey] = mapping
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString(deviceKey, json.encodeToString(GamepadMapping.serializer(), mapping)).commit()
    }

    fun clear(deviceKey: String) {
        cache[deviceKey] = null
        prefs.edit().remove(deviceKey).commit()
    }
}
