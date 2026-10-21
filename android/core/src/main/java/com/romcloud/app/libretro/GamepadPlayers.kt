package com.romcloud.app.libretro

import android.content.Context
import android.view.InputDevice

/**
 * Mode des préférences des manettes : [fromDisk] (fiche du jeu, processus de l'application) relit le
 * fichier s'il a changé, car le jeu (processus « :libretro ») les modifie aussi depuis son menu.
 */
@Suppress("DEPRECATION")
internal fun prefsMode(fromDisk: Boolean) = if (fromDisk) Context.MODE_MULTI_PROCESS else Context.MODE_PRIVATE

/** Manette branchée : identifiant Android (session), descripteur (stable) et numéro donné par Android. */
internal data class Pad(val id: Int, val descriptor: String, val controllerNumber: Int)

/**
 * Joueurs des manettes : ordre choisi depuis le menu (« Joueurs des manettes »), mémorisé par manette
 * (descripteur Android) pour tous les jeux ; une manette rebranchée retrouve son joueur. Sans choix,
 * le numéro de manette d'Android décide, comme avant.
 */
internal class GamepadPlayers(context: Context, fromDisk: Boolean = false) {
    private val prefs = context.getSharedPreferences("gamepad_players", prefsMode(fromDisk))
    /** Manette mémorisée pour chaque joueur (descripteur Android, vide : aucune). */
    var saved: List<String> = (0 until MAX_PLAYERS).map { prefs.getString("player$it", "").orEmpty() }
        private set
    /** Port de chaque manette branchée (identifiant Android) ; recalculé au branchement / débranchement. */
    private var ports: Map<Int, Int>? = null

    /** Manettes branchées ou débranchées : places recalculées. */
    fun invalidate() {
        ports = null
    }

    /** Joueur (port libretro) de l'appareil : 0 pour une télécommande, un clavier ou l'écran tactile. */
    fun port(device: InputDevice?): Int {
        if (device == null || !GamepadInput.isGamepad(device)) return 0
        val map = ports ?: assign(connected().map(::pad), saved).also { ports = it }
        return map[device.id] ?: (device.controllerNumber - 1).coerceAtLeast(0)
    }

    /** Manette du joueur [port] (null : aucune). */
    fun deviceOf(port: Int): InputDevice? = connected().firstOrNull { port(it) == port }

    /** Manettes [order] aux premières places, dans cet ordre, les autres ensuite ; enregistré. */
    fun setOrder(order: List<InputDevice>) {
        val others = connected().filter { d -> order.none { it.id == d.id } }.sortedBy(::port)
        val descriptors = (order + others).map { it.descriptor }.distinct().take(MAX_PLAYERS)
        saved = (0 until MAX_PLAYERS).map { descriptors.getOrElse(it) { "" } }
        prefs.edit().apply { saved.forEachIndexed { i, d -> putString("player$i", d) } }.apply()
        invalidate()
    }

    companion object {
        const val MAX_PLAYERS = 4

        /** Manettes physiques branchées. */
        fun connected(): List<InputDevice> =
            InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice).filter(GamepadInput::isGamepad)

        private fun pad(device: InputDevice) = Pad(device.id, device.descriptor, device.controllerNumber)

        /**
         * Port de chaque manette : celui mémorisé pour elle ([saved] : descripteur par joueur) s'il est
         * libre ; sinon le numéro donné par Android, s'il est libre et ne revient pas à une autre manette
         * mémorisée ; sinon la première place libre. Au-delà de 4 manettes : pas de place.
         */
        fun assign(pads: List<Pad>, saved: List<String>): Map<Int, Int> {
            val result = LinkedHashMap<Int, Int>()
            val taken = BooleanArray(MAX_PLAYERS)
            fun take(pad: Pad, port: Int) {
                result[pad.id] = port
                taken[port] = true
            }
            for (pad in pads) {
                val port = saved.indexOf(pad.descriptor)
                if (pad.descriptor.isNotEmpty() && port in 0 until MAX_PLAYERS && !taken[port]) take(pad, port)
            }
            // Place réservée à une manette mémorisée qui n'est pas branchée : prise en dernier recours.
            val reserved = (0 until MAX_PLAYERS).filter { p -> saved.getOrElse(p) { "" }.isNotEmpty() }.toSet()
            for (pad in pads.filter { it.id !in result }.sortedBy { if (it.controllerNumber > 0) it.controllerNumber else Int.MAX_VALUE }) {
                val preferred = pad.controllerNumber - 1
                val port = preferred.takeIf { it in 0 until MAX_PLAYERS && !taken[it] && it !in reserved }
                    ?: (0 until MAX_PLAYERS).firstOrNull { !taken[it] && it !in reserved }
                    ?: (0 until MAX_PLAYERS).firstOrNull { !taken[it] }
                    ?: continue
                take(pad, port)
            }
            return result
        }
    }
}
