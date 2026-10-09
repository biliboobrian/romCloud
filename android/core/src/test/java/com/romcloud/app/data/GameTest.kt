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
}
