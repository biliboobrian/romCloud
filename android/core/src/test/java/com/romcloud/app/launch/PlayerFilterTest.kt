package com.romcloud.app.launch

import com.romcloud.app.data.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFilterTest {

    private fun player(id: String, regex: String?) = Player(
        name = id, uniqueId = id, acceptedFilenameRegex = regex, amStartArguments = "-n a/.B",
    )

    // Modèles réels du catalogue Daijishou (AmstradCPC.json)
    private val cap32 = player("cpc.ra64.cap32", "^(.*)\\.(?:cdt|cpr|dsk|m3u|sna|tap|voc|zip)$")
    private val crocods = player("cpc.ra64.crocods", "^(.*)\\.(?:dsk|kcr|sna)$")

    @Test
    fun `extension en majuscules acceptee`() {
        assertTrue(PlayerFilter.accepts(cap32, "Gryzor (1987).DSK"))
        assertTrue(PlayerFilter.accepts(cap32, "Burnin' Rubber (GX4000).CPR"))
        assertFalse(PlayerFilter.accepts(crocods, "Burnin' Rubber (GX4000).cpr"))
    }

    @Test
    fun `cartouche GX4000 seulement cap32`() {
        assertEquals(listOf(cap32), PlayerFilter.compatible(listOf(crocods, cap32), "Pang.cpr"))
    }

    @Test
    fun `aucun modele compatible - tous proposes`() {
        assertEquals(listOf(crocods, cap32), PlayerFilter.compatible(listOf(crocods, cap32), "jeu.7z"))
    }

    @Test
    fun `sans regex ou regex invalide - accepte`() {
        assertTrue(PlayerFilter.accepts(player("a", null), "x.bin"))
        assertTrue(PlayerFilter.accepts(player("b", ""), "x.bin"))
        assertTrue(PlayerFilter.accepts(player("c", "(("), "x.bin"))
    }
}
