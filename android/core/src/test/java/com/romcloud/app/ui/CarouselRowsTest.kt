package com.romcloud.app.ui

import com.romcloud.app.data.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarouselRowsTest {

    private fun game(id: Long, genre: String? = null, addedAt: String = "", screenshot: Boolean = false) = Game(
        id = id, systemId = "snes", fileName = "g$id.sfc", size = 1, title = "Jeu $id",
        genre = genre, addedAt = addedAt, hasScreenshot = screenshot,
    )

    @Test
    fun `genre principal`() {
        assertEquals("Plateforme", game(1, "Plateforme, Action").mainGenre)
        assertEquals("RPG", game(1, " RPG / Aventure").mainGenre)
        assertNull(game(1, "").mainGenre)
        assertNull(game(1).mainGenre)
    }

    @Test
    fun `rangees telecharges puis genres du plus fourni au moins fourni, Autres en dernier`() {
        val games = listOf(
            game(1, "Action"), game(2, "RPG"), game(3, "RPG, Aventure"), game(4), game(5, "RPG"),
        )
        val rows = carouselRows(games, downloaded = setOf(4L))
        assertEquals(listOf("Téléchargés", "RPG", "Action", "Autres"), rows.map { it.title })
        assertEquals(listOf(4L), rows[0].games.map { it.id })
        assertEquals(listOf(2L, 3L, 5L), rows[1].games.map { it.id })
    }

    @Test
    fun `sans genre scrape une seule rangee Tous les jeux`() {
        val rows = carouselRows(listOf(game(1), game(2)), downloaded = emptySet())
        assertEquals(listOf("Tous les jeux"), rows.map { it.title })
    }

    @Test
    fun `rangee Ajoutes recemment seulement au-dela de 20 jeux`() {
        val few = (1L..5L).map { game(it, addedAt = "2026-09-0$it") }
        assertEquals(false, carouselRows(few, emptySet()).any { it.title == "Ajoutés récemment" })

        val many = (1L..30L).map { game(it, addedAt = "2026-09-%02d".format(it)) }
        val recent = carouselRows(many, emptySet()).first { it.title == "Ajoutés récemment" }
        assertEquals(20, recent.games.size)
        assertEquals(30L, recent.games.first().id)
    }

    @Test
    fun `jeu mis en avant prefere un jeu telecharge avec capture`() {
        val games = listOf(
            game(1, screenshot = true, addedAt = "2026-09-03"),
            game(2, screenshot = true, addedAt = "2026-09-01"),
            game(3, addedAt = "2026-09-05"),
        )
        assertEquals(2L, pickFeatured(games, downloaded = setOf(2L))?.id)
        assertEquals(1L, pickFeatured(games, downloaded = emptySet())?.id)
        assertNull(pickFeatured(emptyList(), emptySet()))
    }
}
