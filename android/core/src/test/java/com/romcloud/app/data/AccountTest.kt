package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {

    @Test
    fun `code lu dans le QR code de la TV, ou saisi a la main`() {
        assertEquals("AB23CD45", Account.codeFromQr("romcloud://pair?code=AB23CD45"))
        assertEquals("AB23CD45", Account.codeFromQr("ab23 cd45"))
        assertEquals("AB23CD45", Account.codeFromQr("AB23-CD45"))
        // Autre QR code (adresse web…) : refusé.
        assertNull(Account.codeFromQr("https://example.com/une/page/quelconque"))
        assertNull(Account.codeFromQr("abc"))
    }

    @Test
    fun `qr code de la demande de connexion`() {
        assertEquals("romcloud://pair?code=AB23CD45", PairRequest("AB23CD45", "secret", "2026-01-01T00:00:00Z").qrContent)
    }

    @Test
    fun `etat fait hors ligne pas envoye si assez d'etats plus recents en ligne`() {
        fun at(day: Int, pinned: Boolean = false) =
            OnlineState("s$day", 1, "snes9x", "2026-10-%02dT12:00:00Z".format(day), pinned = pinned)
        val offline = java.time.Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
        // 9 plus récents, un épinglé (ne compte pas) et un plus ancien : encore gardé, envoyé.
        val online = (6..14).map { at(it) } + at(20, pinned = true) + at(1)
        assertFalse(tooManyNewer(online, offline))
        assertTrue(tooManyNewer(online + at(16), offline))
    }

    @Test
    fun `sauvegarde plus recente en ligne pas ecrasee`() {
        val online = listOf(OnlineSave(1, "mupen64plus_next", "sram", 32768, "2026-10-10T12:00:00Z"))
        val before = java.time.Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val after = java.time.Instant.parse("2026-10-11T12:00:00Z").toEpochMilli()
        // Même cœur (variante GLES3 comprise) et même type : plus récente en ligne.
        assertTrue(newerOnline(online, "mupen64plus_next_gles3", "sram", before))
        assertFalse(newerOnline(online, "mupen64plus_next_gles3", "sram", after))
        assertFalse(newerOnline(online, "mupen64plus_next_gles3", "state", before))
        assertFalse(newerOnline(online, "parallel_n64", "sram", before))
    }
}
