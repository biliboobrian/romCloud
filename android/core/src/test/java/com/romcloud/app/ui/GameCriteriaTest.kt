package com.romcloud.app.ui

import com.romcloud.app.data.Game
import com.romcloud.app.data.GameDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCriteriaTest {

    private fun game(
        id: Long,
        genre: String? = null,
        date: String? = null,
        players: String? = null,
        rating: Double? = null,
        publisher: String? = null,
        details: GameDetails = GameDetails(),
    ) = Game(
        id = id, systemId = "genesis", fileName = "$id.md", size = 1, title = "Jeu $id",
        genre = genre, releaseDate = date, players = players, rating = rating, publisher = publisher, details = details,
    )

    private val sor2 = game(1, "Beat 'em Up, Action", "1992-12-20", "1-2", 4.6, "Sega", GameDetails(regions = listOf("Europe"), cooperative = true))
    private val sonic = game(2, "Platform", "1991", "1", 4.2, "Sega, Tectoy", GameDetails(regions = listOf("World")))
    private val micro = game(3, "Racing", "1994-07", "1-4", 3.1, "Codemasters", GameDetails(modes = listOf("Mode coopératif")))
    private val unknown = game(4)
    private val games = listOf(sor2, sonic, micro, unknown)

    private fun filter(c: GameCriteria) = games.filter(c::matches).map { it.id }

    @Test
    fun `chaque critere filtre la liste, plusieurs se cumulent`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), filter(GameCriteria()))
        assertEquals(listOf(1L), filter(GameCriteria(genre = "beat 'em up")))
        assertEquals(listOf(1L, 2L, 3L), filter(GameCriteria(decade = 1990)))
        assertEquals(listOf(2L), filter(GameCriteria(players = PlayersCriterion.SOLO)))
        assertEquals(listOf(1L, 3L), filter(GameCriteria(players = PlayersCriterion.MULTI)))
        assertEquals(listOf(3L), filter(GameCriteria(players = PlayersCriterion.FOUR)))
        // Coop : indiqué par LaunchBox ou cité dans les modes de jeu.
        assertEquals(listOf(1L, 3L), filter(GameCriteria(players = PlayersCriterion.COOP)))
        assertEquals(listOf(2L), filter(GameCriteria(region = "World")))
        assertEquals(listOf(1L, 2L), filter(GameCriteria(minRating = 4)))
        assertEquals(listOf(1L, 2L), filter(GameCriteria(publisher = "Sega")))
        assertEquals(listOf(1L), filter(GameCriteria(publisher = "Sega", players = PlayersCriterion.MULTI)))
        assertEquals(2, GameCriteria(publisher = "Sega", players = PlayersCriterion.MULTI).count)
    }

    @Test
    fun `valeurs proposees d'apres les jeux de la liste`() {
        val facets = GameFacets.of(games)
        assertEquals(listOf("Beat 'em Up", "Platform", "Racing"), facets.genres)
        assertEquals(listOf(1990), facets.decades)
        assertEquals(PlayersCriterion.entries, facets.players)
        assertEquals(listOf("Europe", "World"), facets.regions)
        assertEquals(listOf(3, 4), facets.ratings)
        // Éditeur le plus fréquent d'abord.
        assertEquals(listOf("Sega", "Codemasters"), facets.publishers)
        assertFalse(facets.isEmpty)
        assertTrue(GameFacets.of(listOf(unknown)).isEmpty)
    }
}
