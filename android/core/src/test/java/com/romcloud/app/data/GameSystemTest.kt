package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GameSystemTest {

    private fun player(id: String, name: String = id) = Player(name = name, uniqueId = id, amStartArguments = "-n a/.B")

    private fun system(vararg players: Player) =
        GameSystem(id = "psx", name = "Sony PlayStation", shortname = "psx", folder = "psx", players = players.toList())

    @Test
    fun `identifiants en double suffixes dans l'ordre`() {
        // SonyPlayStation.json (Daijishou) contient deux modèles « psx.com.github.stenzek.duckstation ».
        val duck = "psx.com.github.stenzek.duckstation"
        val fixed = system(player(duck, "DuckStation"), player("psx.ra64.pcsx_rearmed"), player(duck, "DuckStation (Legacy)"), player(duck))
            .withUniquePlayerIds()
        assertEquals(
            listOf(duck, "psx.ra64.pcsx_rearmed", "$duck#2", "$duck#3"),
            fixed.players.map { it.uniqueId },
        )
        assertEquals("DuckStation (Legacy)", fixed.players[2].name)
    }

    @Test
    fun `ordre des emulateurs choisi sur le serveur pour la plateforme`() {
        val original = system(player("libretrodroid.mednafen_psx_hw"), player("libretrodroid.pcsx_rearmed"), player("duck"), player("duck#2"))
            .copy(emulatorOrder = mapOf("androidtv" to listOf("duck#2", "libretrodroid.pcsx_rearmed", "inconnu"), "windows" to listOf("x")))
        assertEquals(
            listOf("duck#2", "libretrodroid.pcsx_rearmed", "libretrodroid.mednafen_psx_hw", "duck"),
            original.withEmulatorOrder("androidtv").players.map { it.uniqueId },
        )
        // Sans ordre pour cette plateforme : celui de l'application.
        assertSame(original, original.withEmulatorOrder("android"))
    }

    @Test
    fun `systeme sans doublon inchange`() {
        val original = system(player("a"), player("b"))
        assertSame(original, original.withUniquePlayerIds())
    }
}
