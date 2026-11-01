package com.romcloud.app.data

/**
 * Vitesse d'un téléchargement (octets par seconde) : mesurée sur des fenêtres d'au moins une
 * seconde, lissée (une requête lente ou un fichier tout juste reçu ne la font pas bondir).
 */
class SpeedMeter {
    private var sinceMs = -1L
    private var sinceBytes = 0L
    private var bytesPerSecond = 0.0

    /** Octets reçus depuis le début, [bytes], à l'instant [nowMs] ; renvoie la vitesse lissée. */
    @Synchronized
    fun update(bytes: Long, nowMs: Long): Long {
        if (sinceMs < 0 || bytes < sinceBytes) {
            // Premier relevé, ou compteur revenu en arrière : nouvelle fenêtre.
            sinceMs = nowMs
            sinceBytes = bytes
        } else if (nowMs - sinceMs >= WINDOW_MS) {
            val measured = (bytes - sinceBytes) * 1000.0 / (nowMs - sinceMs)
            bytesPerSecond = if (bytesPerSecond == 0.0) measured else 0.5 * measured + 0.5 * bytesPerSecond
            sinceMs = nowMs
            sinceBytes = bytes
        }
        return Math.round(bytesPerSecond)
    }

    private companion object {
        const val WINDOW_MS = 1000L
    }
}
