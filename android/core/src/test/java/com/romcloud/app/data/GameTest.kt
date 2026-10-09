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
}
