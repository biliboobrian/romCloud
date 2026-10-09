package com.romcloud.app.netplay

import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetplayRulesTest {

    private fun system(id: String) = GameSystem(id = id, name = id, shortname = id, folder = id)
    private fun game(players: String?) = Game(1, "snes", "a.sfc", 1, "A", players = players)
    private fun builtin(core: String) = Player(name = core, uniqueId = core, amStartArguments = "", libretroCore = core)

    @Test
    fun `nombre de joueurs lu dans le champ du scraping`() {
        assertEquals(listOf(2, 4, 1, 0, 0, 2), listOf("1-2", "1 - 4", "1", "", null, "2 simultanés").map { NetplayRules.maxPlayers(it) })
    }

    @Test
    fun `consoles de salon a plusieurs joueurs avec le moteur integre`() {
        assertTrue(NetplayRules.canPlayTogether(system("snes"), game("1-2"), builtin("snes9x")))
        // Jeu à un joueur, console portable, émulateur externe, cœur trop lourd.
        assertFalse(NetplayRules.canPlayTogether(system("snes"), game("1"), builtin("snes9x")))
        assertFalse(NetplayRules.canPlayTogether(system("gba"), game("1-2"), builtin("mgba")))
        assertFalse(NetplayRules.canPlayTogether(system("snes"), game("1-2"), Player(name = "RetroArch", uniqueId = "ra", amStartArguments = "")))
        assertFalse(NetplayRules.canPlayTogether(system("gc"), game("1-4"), builtin("dolphin")))
        assertTrue(NetplayRules.canPlayTogether(system("psx"), game("1-2"), builtin("swanstation")))
    }

    @Test
    fun `annonce relue a l'identique`() {
        val hosted = NetplayProtocol.HostedGame(12, "snes", "Super Bomberman", "Super Bomberman (Europe).sfc", 1048576, "snes9x")
        val beacon = NetplayProtocol.Beacon(id = "abc", name = "Pixel 9", platform = "android", port = 40123, hosting = hosted)
        val text = NetplayProtocol.json.encodeToString(NetplayProtocol.Beacon.serializer(), beacon)
        assertEquals(beacon, NetplayProtocol.json.decodeFromString(NetplayProtocol.Beacon.serializer(), text))
        // Annonce d'une version future : champs inconnus ignorés.
        val future = """{"app":"romcloud","v":1,"id":"x","name":"PC","platform":"windows","extra":true}"""
        assertEquals(null, NetplayProtocol.json.decodeFromString(NetplayProtocol.Beacon.serializer(), future).hosting)
    }
}
