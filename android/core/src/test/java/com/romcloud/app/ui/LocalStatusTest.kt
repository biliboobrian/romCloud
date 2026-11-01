package com.romcloud.app.ui

import com.romcloud.app.data.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStatusTest {

    @Test
    fun `veuillez patienter entre la fin du telechargement et le jeu jouable`() {
        val running = DownloadState.Running("Jeu", bytes = 50, total = 100)
        assertTrue(localStatus(running, downloaded = false) is LocalStatus.Downloading)
        // Tout reçu, décompression en cours : toujours en cours ; décompressé : bientôt jouable.
        val unpacking = DownloadState.Running("Jeu", bytes = 100, total = 100, unpackBytes = 10, unpackTotal = 80)
        assertTrue(localStatus(unpacking, downloaded = false) is LocalStatus.Downloading)
        assertEquals(LocalStatus.Finishing, localStatus(unpacking.copy(unpackBytes = 80), downloaded = false))
        assertEquals(LocalStatus.Finishing, localStatus(running.copy(bytes = 100), downloaded = false))
        // Téléchargement terminé : patienter tant que le jeu n'est pas relu parmi ceux présents.
        val finished = DownloadState.Finishing("Jeu")
        assertEquals(LocalStatus.Finishing, localStatus(finished, downloaded = false))
        assertEquals(LocalStatus.Downloaded, localStatus(finished, downloaded = true))
        assertEquals(LocalStatus.Remote, localStatus(null, downloaded = false))
    }
}
