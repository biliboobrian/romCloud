package com.romcloud.app.netplay

import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRulesTest {

    private fun system(id: String) = GameSystem(id = id, name = id, shortname = id, folder = id)
    private fun game(players: String?) = Game(1, "gb", "a.gb", 1, "A", players = players)
    private fun builtin(core: String) = Player(name = core, uniqueId = core, amStartArguments = "", libretroCore = core)

    @Test
    fun `consoles qui se relient`() {
        assertEquals(LinkKind.GAME_LINK, LinkRules.kind(system("gbc")))
        assertEquals(LinkKind.GBA_LINK, LinkRules.kind(system("gba")))
        assertEquals(LinkKind.PSP_ADHOC, LinkRules.kind(system("psp")))
        assertNull(LinkRules.kind(system("snes")))
        assertTrue(LinkRules.canLink(system("gb"), game("1-2")))
        assertFalse(LinkRules.canLink(system("gb"), game("1")))
        // GBA : seulement les jeux que gpSP sait relier, même annoncés à un joueur.
        val emeraude = Game(2, "gba", "Pokemon - Version Emeraude (France).gba", 1, "Pokémon Version Émeraude", players = "1")
        val bubble = Game(3, "gba", "Bubble Bobble - Old & New (Europe).gba", 1, "Bubble Bobble - Old & New", players = "1-2")
        assertTrue(LinkRules.canLink(system("gba"), emeraude))
        assertFalse(LinkRules.canLink(system("gba"), bubble))
        assertTrue(LinkRules.gbaLinkGame("Pokémon Rouge Feu", ""))
        assertTrue(LinkRules.gbaLinkGame("Mario Golf : Advance Tour", ""))
    }

    @Test
    fun `jeu synchronise d'abord, liaison pour les portables`() {
        assertEquals(Together.NETPLAY, NetplayRules.together(system("snes"), game("1-2"), builtin("snes9x")))
        assertEquals(Together.LINK, NetplayRules.together(system("gb"), game("1-2"), builtin("sameboy")))
        assertNull(NetplayRules.together(system("gb"), game("1"), builtin("sameboy")))
        assertNull(NetplayRules.together(system("gba"), game("1-4"), builtin("mgba")))
    }

    @Test
    fun `adresse en douze chiffres`() {
        assertEquals("192168001005", LinkRules.ipDigits("192.168.1.5"))
        assertNull(LinkRules.ipDigits("fe80::1"))
        assertNull(LinkRules.ipDigits("300.1.1.1"))
    }

    @Test
    fun `options de gambatte`() {
        assertEquals("Network Server", LinkRules.options(LinkKind.GAME_LINK, true, "", "id")["gambatte_gb_link_mode"])
        val guest = LinkRules.options(LinkKind.GAME_LINK, false, "10.0.0.42", "id")
        assertEquals("Network Client", guest["gambatte_gb_link_mode"])
        assertEquals("0", guest["gambatte_gb_link_network_server_ip_1"])
        assertEquals("1", guest["gambatte_gb_link_network_server_ip_2"])
        assertEquals("2", guest["gambatte_gb_link_network_server_ip_12"])
    }

    @Test
    fun `options de ppsspp`() {
        val host = LinkRules.options(LinkKind.PSP_ADHOC, true, "", "a", ownAddress = "192.168.1.20")
        assertEquals("enabled", host["ppsspp_enable_builtin_pro_ad_hoc_server"])
        assertEquals("10000", host["ppsspp_port_offset"])
        // Hôte relié à son serveur par son adresse sur le réseau (pas 127.0.0.1, annoncée aux invités).
        assertEquals("IP address", host["ppsspp_change_pro_ad_hoc_server_address"])
        assertEquals("1", host["ppsspp_pro_ad_hoc_server_address09"])
        assertEquals("2", host["ppsspp_pro_ad_hoc_server_address11"])
        assertEquals("0", host["ppsspp_pro_ad_hoc_server_address12"])
        assertEquals("localhost", LinkRules.options(LinkKind.PSP_ADHOC, true, "", "a")["ppsspp_change_pro_ad_hoc_server_address"])
        val guest = LinkRules.options(LinkKind.PSP_ADHOC, false, "192.168.1.5", "b")
        assertEquals("IP address", guest["ppsspp_change_pro_ad_hoc_server_address"])
        assertEquals("5", guest["ppsspp_pro_ad_hoc_server_address12"])
        // Adresses MAC différentes d'un appareil à l'autre, adresse locale (2e bit du premier octet).
        val macHost = (1..12).joinToString("") { host["ppsspp_change_mac_address%02d".format(it)]!! }
        val macGuest = (1..12).joinToString("") { guest["ppsspp_change_mac_address%02d".format(it)]!! }
        assertTrue(macHost != macGuest)
        assertEquals(2, macHost.substring(0, 2).toInt(16) and 3)
    }

    @Test
    fun `appareils libres et parties proposees`() {
        val zelda = Game(7, "gbc", "zelda.gbc", 10, "Zelda", players = "1-2")
        val hosted = NetplayProtocol.HostedGame(7, "gbc", "Zelda", "zelda.gbc", 10, "gambatte", "gb")
        val host = Peer("a", "A", "android", "10.0.0.2", 4000, hosted, 0)
        val busy = Peer("b", "B", "android", "10.0.0.3", 0, null, 0, busy = true)
        val free = Peer("c", "C", "windows", "10.0.0.4", 0, null, 0)
        assertTrue(host.hosts(zelda))
        assertFalse(free.hosts(zelda))
        // Hôte et appareil en partie : plus de partenaire libre, la partie ne peut plus être proposée.
        assertFalse(listOf(host, busy).anyAvailable())
        assertTrue(listOf(host, busy, free).anyAvailable())
    }
}
