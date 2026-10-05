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

/** Axe du stick droit demandé par une étape de la configuration. */
internal enum class StickAxis { X, Y }

/**
 * Étape de la configuration : un bouton de la console (libellé [label] de sa manette, bouton
 * RetroPad [retroKey]) ou un axe du stick droit ([stick]) ; [labelRes] le décrit (croix, stick).
 */
internal data class MappingStep(
    val label: String? = null,
    @StringRes val labelRes: Int = 0,
    val retroKey: Int = 0,
    val stick: StickAxis? = null,
)

/**
 * Étapes de la configuration pour la manette d'une console ([layout]) : croix, boutons (rangée du
 * bas d'abord), tranches, Select / Start, puis sticks. Boutons C de la Nintendo 64 : stick droit.
 */
internal fun mappingSteps(layout: PadLayout): List<MappingStep> = buildList {
    add(MappingStep(labelRes = R.string.pad_up, retroKey = KeyEvent.KEYCODE_DPAD_UP))
    add(MappingStep(labelRes = R.string.pad_down, retroKey = KeyEvent.KEYCODE_DPAD_DOWN))
    add(MappingStep(labelRes = R.string.pad_left, retroKey = KeyEvent.KEYCODE_DPAD_LEFT))
    add(MappingStep(labelRes = R.string.pad_right, retroKey = KeyEvent.KEYCODE_DPAD_RIGHT))
    layout.buttons.filter { it.rightStick == null }
        .sortedWith(compareByDescending<PadButton> { it.y }.thenBy { it.x })
        .forEach { add(MappingStep(it.label, retroKey = it.key)) }
    (layout.left.reversed() + layout.right).forEach { add(MappingStep(it.label, retroKey = it.key)) }
    layout.select?.let { add(MappingStep(it, retroKey = KeyEvent.KEYCODE_BUTTON_SELECT)) }
    layout.start?.let { add(MappingStep(it, retroKey = KeyEvent.KEYCODE_BUTTON_START)) }
    if (layout.thumbs) {
        add(MappingStep("L3", R.string.pad_l3, KeyEvent.KEYCODE_BUTTON_THUMBL))
        add(MappingStep("R3", R.string.pad_r3, KeyEvent.KEYCODE_BUTTON_THUMBR))
    }
    val cButtons = layout.buttons.mapNotNull { b -> b.rightStick?.let { b.label to it } }
    if (cButtons.isNotEmpty() || layout.thumbs) {
        add(MappingStep(cButtons.firstOrNull { it.second.x > 0 }?.first, R.string.pad_right_stick_x, stick = StickAxis.X))
        add(MappingStep(cButtons.firstOrNull { it.second.y > 0 }?.first, R.string.pad_right_stick_y, stick = StickAxis.Y))
    }
}

/**
 * Configurations mémorisées par console ([systemId]) et modèle de manette (fabricant, produit, nom).
 * Sans configuration pour la console : celle faite pour toutes les consoles (anciennes versions).
 */
internal class GamepadMappings(context: Context, private val systemId: String) {

    private val prefs = context.getSharedPreferences("libretro_gamepads", Context.MODE_PRIVATE)
    private val cache = HashMap<String, GamepadMapping?>()
    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(device: InputDevice) = "${device.vendorId}:${device.productId}:${device.name}"

    private fun systemKey(deviceKey: String) = if (systemId.isEmpty()) deviceKey else "$systemId/$deviceKey"

    fun get(device: InputDevice): GamepadMapping? {
        val key = keyOf(device)
        return cache.getOrPut(key) { load(systemKey(key)) ?: load(key) }
    }

    private fun load(prefKey: String): GamepadMapping? =
        prefs.getString(prefKey, null)?.let { runCatching { json.decodeFromString(GamepadMapping.serializer(), it) }.getOrNull() }

    fun save(deviceKey: String, mapping: GamepadMapping) {
        cache[deviceKey] = mapping
        // commit() : le processus de jeu est arrêté brutalement en quittant.
        prefs.edit().putString(systemKey(deviceKey), json.encodeToString(GamepadMapping.serializer(), mapping)).commit()
    }

    /**
     * Correspondance par défaut d'Android pour cette console : configuration vide, qui masque aussi
     * celle faite pour toutes les consoles.
     */
    fun clear(deviceKey: String) {
        if (systemId.isEmpty()) {
            cache[deviceKey] = null
            prefs.edit().remove(deviceKey).commit()
        } else {
            save(deviceKey, GamepadMapping())
        }
    }
}
