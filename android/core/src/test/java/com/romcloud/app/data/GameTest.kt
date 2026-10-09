package com.romcloud.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GameTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun game(status: String?): Game {
        val field = status?.let { ""","scrapeStatus":"$it"""" }.orEmpty()
        return json.decodeFromString(Game.serializer(), """{"id":1,"systemId":"snes","fileName":"a.sfc","size":1,"title":"A"$field}""")
    }

    @Test
    fun `jeu identifie seulement quand le scraping a abouti`() {
        assertEquals(listOf(true, false, false, false, true), listOf("ok", "none", "notfound", "error", null).map { game(it).identified })
    }

    @Test
    fun `note en etoiles entieres`() {
        assertEquals(listOf(4, 4, 5, 0, null), listOf(3.5, 4.4, 7.0, -1.0, null).map { Game(1, "snes", "a", 1, "A", rating = it).stars })
        assertEquals("★★★☆☆", starsText(3))
    }

    @Test
    fun `jeux classes par nom ou par note`() {
        val games = listOf(
            Game(1, "snes", "b", 1, "Bravo", rating = 3.0),
            Game(2, "snes", "a", 1, "alpha"),
            Game(3, "snes", "c", 1, "Charlie", rating = 4.6),
            Game(4, "snes", "d", 1, "delta", rating = 5.0),
        )
        assertEquals(listOf("alpha", "Bravo", "Charlie", "delta"), sortGames(games, GameOrder.NAME).map { it.title })
        // Charlie (4,6) et delta (5) ont 5 étoiles : par nom entre eux ; sans note à la fin.
        assertEquals(listOf("Charlie", "delta", "Bravo", "alpha"), sortGames(games, GameOrder.RATING).map { it.title })
    }

    @Test
    fun `jeu en plusieurs fichiers et liste des disques`() {
        val game = Game(
            1, "psx", "FF7 (Disc 1).chd", 10, "FF7",
            parts = listOf(
                GamePart(3, "FF7 (Disc 3).chd", 30, "disc", 3),
                GamePart(9, "FF7 [DLC].chd", 5, "dlc"),
                GamePart(2, "FF7 (Disc 2).chd", 20, "disc", 2),
            ),
        )
        assertEquals(65L, game.fullSize)
        assertEquals(listOf(1L, 3L, 9L, 2L), game.files.map { it.id })
        assertEquals("FF7 (Disc 1).chd\nFF7 (Disc 2).chd\nFF7 (Disc 3).chd\n", playlistText(game))
        assertEquals(null, playlistText(Game(5, "psx", "Tekken.chd", 7, "Tekken")))
        assertEquals(null, playlistText(game.copy(fileName = "FF7 (Disc 1).zip")))
        // Parties envoyées par le serveur
        val decoded = json.decodeFromString(Game.serializer(), """{"id":1,"systemId":"psx","fileName":"a.chd","size":1,"title":"A","parts":[{"id":2,"fileName":"b.chd","size":2,"kind":"disc","index":2}],"totalSize":3}""")
        assertEquals(3L, decoded.fullSize)
    }
}
