package com.romcloud.app.netplay

import com.romcloud.app.data.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LobbyStateTest {

    private val host = LobbyMember("pc", "ZEPC", "helmut", host = true)
    private val guest = LobbyMember("phone", "POCO F3", "alice")
    private val bomberman = LobbyGame(9, "snes", "Super Bomberman", "b.sfc", 1, players = 4)
    private val lobby = Lobby("abc", host = "pc", game = bomberman, members = listOf(host, guest))

    @Test
    fun hoteInviteEtJoueursMinimum() {
        val asHost = LobbyState(lobbies = listOf(lobby), me = "pc")
        assertTrue(asHost.isHost)
        assertFalse(asHost.locked)
        assertEquals(2, asHost.minPlayers)
        val asGuest = LobbyState(lobbies = listOf(lobby), me = "phone")
        assertTrue(asGuest.locked)
        val outside = LobbyState(lobbies = listOf(lobby), me = "tv")
        assertNull(outside.mine)
        assertEquals(0, outside.minPlayers)
        // Lobby seul (hôte) : jeux à 2 joueurs au moins.
        assertEquals(2, LobbyState(lobbies = listOf(lobby.copy(members = listOf(host))), me = "pc").minPlayers)
    }

    @Test
    fun jeuDuLobbyReconnu() {
        val game = Game(id = 9, systemId = "snes", fileName = "b.sfc", size = 1, title = "Super Bomberman")
        val state = LobbyState(lobbies = listOf(lobby), me = "tv")
        assertEquals(listOf(lobby), state.lobbiesOf(game, "snes"))
        assertTrue(state.lobbiesOf(game, "nes").isEmpty())
        assertTrue(bomberman.matches(game.copy(id = 99), "snes")) // même fichier, autre identifiant
    }
}
