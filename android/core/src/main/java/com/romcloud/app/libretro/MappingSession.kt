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
 * pousse le stick droit. La première manette
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

    val step: MappingStep? get() = steps.getOrNull(stepIndex)

    private var deviceId: Int? = null
    private var deviceKey: String? = null

    private val keys = LinkedHashMap<Int, Int>()
    private val axes = mutableListOf<AxisButton>()
    private var rightX: Pair<Int, Boolean>? = null
    private var rightY: Pair<Int, Boolean>? = null

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
        if (ignoredReleases.remove(code)) return true
        val current = step ?: return true
        when {
            code == lastKey -> skip()
            current.stick != null -> Unit
            code in keys -> message = R.string.pad_already_used
            else -> {
                keys[code] = current.retroKey
                lastKey = code
                settling = true
                done += current
                next()
            }
        }
        return true
    }

    /** Traite un mouvement d'axe (gâchettes, stick droit) ; true s'il vient d'une manette. */
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

        if (settling) {
            if (axesOfDevice.all { abs(delta(it)) < 0.3f }) settling = false
            return true
        }
        val pending = candidate
        if (pending == null) {
            val used = axes.map { it.axis } + listOfNotNull(rightX?.first, rightY?.first) + IGNORED_AXES
            val axis = axesOfDevice.filter { it !in used }.maxByOrNull { abs(delta(it)) } ?: return true
            if (abs(delta(axis)) > 0.6f) candidate = axis to sign(delta(axis))
        } else if (abs(delta(pending.first)) < 0.3f) {
            val (axis, direction) = pending
            when (current.stick) {
                StickAxis.X -> rightX = axis to (direction < 0)
                StickAxis.Y -> rightY = axis to (direction < 0)
                null -> {
                    axes += AxisButton(axis, rest[axis] ?: 0f, direction, current.retroKey)
                    // Certaines manettes envoient aussi une touche pour la gâchette : même bouton.
                    heldKeys.filterNot { it in keys }.forEach { keys[it] = current.retroKey }
                    ignoredReleases += heldKeys
                }
            }
            lastKey = null
            done += current
            next()
        }
        return true
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
            ),
        )
        onDone(R.string.pad_config_saved)
    }

    private companion object {
        /** Stick gauche et croix analogique : jamais pris pour une gâchette ou le stick droit. */
        val IGNORED_AXES = listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
    }
}
