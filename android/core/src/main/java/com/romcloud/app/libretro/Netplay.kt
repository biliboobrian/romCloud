package com.romcloud.app.libretro

/**
 * Jeu à plusieurs dans l'adaptateur natif (libromcloud_audio_shim, section « Jeu à plusieurs » de
 * audio_shim.cpp) : touches échangées image par image sur une connexion déjà établie. Même
 * bibliothèque que celle chargée par LibretroDroid à la place du cœur ([CoreShim]).
 */
object Netplay {
    enum class State { OFF, STARTING, RUNNING, ENDED }

    /**
     * [waiting] : en attente des touches de l'autre joueur ; [desyncs] : états différents constatés
     * (recalés) ; [remotePaused] : l'autre joueur a mis la pause.
     */
    data class Status(val state: State, val waiting: Boolean, val desyncs: Int, val frame: Int, val remotePaused: Boolean = false)

    private val loaded get() = CoreShim.load()

    /**
     * Commence la partie sur la connexion [fd] (gérée ensuite par l'adaptateur) : [host] (son état
     * est copié chez l'invité), [localPort] (0 hôte, 1 invité), [delay] (images entre l'appui et son effet) ;
     * [packets] : liaison entre consoles, paquets du cœur échangés (interface netpacket).
     */
    fun start(fd: Int, host: Boolean, localPort: Int, delay: Int, packets: Boolean = false) {
        if (loaded) nativeStart(fd, host, localPort, delay, packets)
    }

    /** Fin de la partie à plusieurs (chaque appareil continue seul). */
    fun stop() {
        if (loaded) nativeStop()
    }

    /**
     * Pause de ce joueur (menu ouvert, application en arrière-plan), annoncée à l'autre joueur ; à
     * demander avant de suspendre l'émulation.
     */
    fun setPaused(paused: Boolean) {
        if (loaded) nativeSetPaused(paused)
    }

    fun status(): Status {
        if (!loaded) return Status(State.OFF, false, 0, 0)
        val values = IntArray(5)
        nativeStatus(values)
        return Status(State.entries.getOrElse(values[0]) { State.OFF }, values[1] != 0, values[2], values[3], values[4] != 0)
    }

    private external fun nativeStart(fd: Int, host: Boolean, localPort: Int, delay: Int, packets: Boolean)
    private external fun nativeStop()
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeStatus(out: IntArray)
}
