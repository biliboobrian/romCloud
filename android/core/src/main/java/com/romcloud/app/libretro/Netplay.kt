package com.romcloud.app.libretro

/**
 * Jeu à plusieurs dans l'adaptateur natif (libromcloud_audio_shim, section « Jeu à plusieurs » de
 * audio_shim.cpp) : touches échangées image par image sur une connexion déjà établie. Même
 * bibliothèque que celle chargée par LibretroDroid à la place du cœur ([CoreShim]).
 */
object Netplay {
    enum class State { OFF, STARTING, RUNNING, ENDED }

    /** [waiting] : en attente des touches de l'autre joueur ; [desyncs] : états différents constatés (recalés). */
    data class Status(val state: State, val waiting: Boolean, val desyncs: Int, val frame: Int)

    private val loaded get() = CoreShim.load()

    /**
     * Commence la partie sur la connexion [fd] (gérée ensuite par l'adaptateur) : [host] (son état
     * est copié chez l'invité), [localPort] (0 hôte, 1 invité), [delay] (images entre l'appui et son effet).
     */
    fun start(fd: Int, host: Boolean, localPort: Int, delay: Int) {
        if (loaded) nativeStart(fd, host, localPort, delay)
    }

    /** Fin de la partie à plusieurs (chaque appareil continue seul). */
    fun stop() {
        if (loaded) nativeStop()
    }

    fun status(): Status {
        if (!loaded) return Status(State.OFF, false, 0, 0)
        val values = IntArray(4)
        nativeStatus(values)
        return Status(State.entries.getOrElse(values[0]) { State.OFF }, values[1] != 0, values[2], values[3])
    }

    private external fun nativeStart(fd: Int, host: Boolean, localPort: Int, delay: Int)
    private external fun nativeStop()
    private external fun nativeStatus(out: IntArray)
}
