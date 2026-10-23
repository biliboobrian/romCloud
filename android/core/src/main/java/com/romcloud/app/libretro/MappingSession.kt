package com.romcloud.app.libretro

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.romcloud.core.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Configuration d'une manette pour une console, étape par étape ([steps], d'après la manette de la
 * console) : l'utilisateur appuie sur chaque bouton demandé (touche ou gâchette analogique), puis
 * pousse les sticks. La première manette
 * utilisée est celle configurée ; appuyer de nouveau sur la dernière touche passe l'étape.
 * [onDone] reçoit le message à afficher en fin de configuration.
 */
internal class MappingSession(
    private val mappings: GamepadMappings,
    val steps: List<MappingStep>,
    private val onDone: (messageRes: Int) -> Unit,
) {
    var deviceName by mutableStateOf<String?>(null)
        private set
    var stepIndex by mutableIntStateOf(0)
        private set
    @get:StringRes
    var message by mutableStateOf<Int?>(null)
        private set

    /** Étapes dont le bouton a été attribué (pas celles passées). */
    var done by mutableStateOf(emptySet<MappingStep>())
        private set

    /** Commande de la manette attribuée à chaque étape (touche ou axe), pour le récapitulatif. */
    var assigned by mutableStateOf(emptyMap<MappingStep, String>())
        private set

    val step: MappingStep? get() = steps.getOrNull(stepIndex)

    private var deviceId: Int? = null
    private var deviceKey: String? = null

    private val keys = LinkedHashMap<Int, Int>()
    private val axes = mutableListOf<AxisButton>()
    private var rightX: Pair<Int, Boolean>? = null
    private var rightY: Pair<Int, Boolean>? = null
    private var leftX: Pair<Int, Boolean>? = null
    private var leftY: Pair<Int, Boolean>? = null

    private var lastKey: Int? = null
    private val heldKeys = mutableSetOf<Int>()
    /** Touches déjà associées avec une gâchette analogique : leur relâchement ne compte pas. */
    private val ignoredReleases = mutableSetOf<Int>()
    /** Position de repos de chaque axe (-1, 0 ou 1 : certaines gâchettes reposent à -1). */
    private val rest = HashMap<Int, Float>()
    /** Axe en cours d'actionnement (axe, sens) : validé quand il revient au repos. */
    private var candidate: Pair<Int, Float>? = null
    /** Après une touche : attendre que tous les axes soient revenus au repos. */
    private var settling = false
    /** Axes bougés pendant l'appui en cours (une gâchette peut bouger deux axes et envoyer une touche). */
    private val pressAxes = mutableSetOf<Int>()
    /** Axes bougés avec un bouton déjà attribué : jamais repris pour une autre étape. */
    private val claimedAxes = mutableSetOf<Int>()
    private var axesAtRest = true

    /** Traite une touche ; true si l'évènement vient d'une manette (il ne doit pas aller plus loin). */
    fun onKey(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!GamepadInput.isGamepad(device)) return false
        val id = deviceId
        if (id == null) {
            if (event.action == KeyEvent.ACTION_UP) lock(device)
            return true
        }
        if (device.id != id) return true
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                heldKeys += code
                return true
            }
            KeyEvent.ACTION_UP -> heldKeys -= code
            else -> return true
        }
        if (ignoredReleases.remove(code)) return endPress()
        val current = step ?: return true
        when {
            code == lastKey -> skip()
            current.stick != null -> Unit
            code in keys -> reject()
            else -> {
                keys[code] = current.retroKey
                claimedAxes += pressAxes
                lastKey = code
                settling = true
                done += current
                assigned += current to keyName(code)
                next()
            }
        }
        return endPress()
    }

    /** Appui fini (touches relâchées, axes au repos) : ses axes sont oubliés. */
    private fun endPress(): Boolean {
        if (heldKeys.isEmpty() && axesAtRest) pressAxes.clear()
        return true
    }

    /**
     * Bouton déjà attribué : refusé, avec les axes bougés par le même appui (gâchette qui envoie
     * aussi une touche), jusqu'au retour au repos.
     */
    private fun reject() {
        message = R.string.pad_already_used
        candidate = null
        settling = true
    }

    /** Traite un mouvement d'axe (gâchettes, sticks) ; true s'il vient d'une manette. */
    fun onMotion(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        if (device.id != deviceId) return true
        val current = step ?: return true
        val axesOfDevice = device.motionRanges
            .filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
            .map { it.axis }
            .distinct()
        for (axis in axesOfDevice) {
            rest.getOrPut(axis) { event.getAxisValue(axis).roundToInt().coerceIn(-1, 1).toFloat() }
        }
        fun delta(axis: Int) = event.getAxisValue(axis) - (rest[axis] ?: 0f)
        // Stick gauche demandé : ses axes (X / Y en général) sont permis ; sinon jamais pris.
        val ignored = if (current.stick?.left == true) HAT_AXES else IGNORED_AXES
        pressAxes += axesOfDevice.filter { it !in ignored && abs(delta(it)) > 0.6f }
        axesAtRest = axesOfDevice.all { abs(delta(it)) < 0.3f }
        handleMotion(current, axesOfDevice, ignored, ::delta)
        return endPress()
    }

    private fun handleMotion(current: MappingStep, axesOfDevice: List<Int>, ignored: List<Int>, delta: (Int) -> Float) {
        if (settling) {
            if (axesAtRest) settling = false
            return
        }
        val pending = candidate
        if (pending == null) {
            val used = axes.map { it.axis } + listOfNotNull(rightX?.first, rightY?.first, leftX?.first, leftY?.first) + claimedAxes + ignored
            val axis = axesOfDevice.filter { it !in used }.maxByOrNull { abs(delta(it)) } ?: return
            if (abs(delta(axis)) > 0.6f) candidate = axis to sign(delta(axis))
        } else if (abs(delta(pending.first)) < 0.3f) {
            val (axis, direction) = pending
            // Même appui qu'un bouton déjà attribué (touche de la gâchette, autre axe de la gâchette) : refusé.
            if (current.stick == null && (heldKeys.any { it in keys } || pressAxes.any { it in claimedAxes })) return reject()
            when (current.stick) {
                StickAxis.X -> rightX = axis to (direction < 0)
                StickAxis.Y -> rightY = axis to (direction < 0)
                StickAxis.LEFT_X -> leftX = axis to (direction < 0)
                StickAxis.LEFT_Y -> leftY = axis to (direction < 0)
                null -> {
                    axes += AxisButton(axis, rest[axis] ?: 0f, direction, current.retroKey)
                    claimedAxes += pressAxes
                    // Certaines manettes envoient aussi une touche pour la gâchette : même bouton.
                    heldKeys.filterNot { it in keys }.forEach { keys[it] = current.retroKey }
                    ignoredReleases += heldKeys
                }
            }
            lastKey = null
            done += current
            assigned += current to axisName(axis, direction.takeIf { current.stick == null })
            next()
        }
    }

    fun skip() = next()

    /** Oublie la configuration de cette manette (correspondance par défaut d'Android). */
    fun resetDevice() {
        deviceKey?.let(mappings::clear)
        onDone(R.string.pad_config_reset)
    }

    private fun lock(device: InputDevice) {
        deviceId = device.id
        deviceKey = mappings.keyOf(device)
        deviceName = device.name
        stepIndex = 0
    }

    private fun next() {
        candidate = null
        message = null
        stepIndex++
        if (stepIndex < steps.size) return
        mappings.save(
            deviceKey ?: return,
            GamepadMapping(
                keys = keys.toMap(),
                axes = axes.toList(),
                rightStickX = rightX?.first,
                rightStickY = rightY?.first,
                invertRightX = rightX?.second ?: false,
                invertRightY = rightY?.second ?: false,
                leftStickX = leftX?.first,
                leftStickY = leftY?.first,
                invertLeftX = leftX?.second ?: false,
                invertLeftY = leftY?.second ?: false,
            ),
        )
        onDone(R.string.pad_config_saved)
    }

    private companion object {
        /** Nom court d'une touche : « A », « L1 », « DPAD UP »… */
        fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')

        /** Nom court d'un axe, avec le sens pour une gâchette : « RTRIGGER + », « Z »… */
        fun axisName(axis: Int, direction: Float?): String {
            val name = MotionEvent.axisToString(axis).removePrefix("AXIS_").replace('_', ' ')
            return if (direction == null) name else "$name ${if (direction > 0) "+" else "−"}"
        }

        /** Croix analogique : jamais prise pour une gâchette ou un stick. */
        val HAT_AXES = listOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)

        /** Stick gauche (axes standard) et croix : jamais pris pour une gâchette ou le stick droit. */
        val IGNORED_AXES = listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y) + HAT_AXES
    }
}
