package com.romcloud.app.ui

import com.romcloud.app.data.Game

/** Une rangée du carrousel : un titre et ses jeux. */
data class CarouselRow(val title: String, val games: List<Game>)

private const val RECENT_COUNT = 20
private const val OTHER_GENRE = "Autres"

/**
 * Regroupe les jeux (déjà filtrés) en rangées façon Netflix :
 * téléchargés, ajoutés récemment, puis une rangée par genre (le plus fourni d'abord).
 */
fun carouselRows(games: List<Game>, downloaded: Set<Long>): List<CarouselRow> {
    val rows = mutableListOf<CarouselRow>()
    games.filter { it.id in downloaded }.takeIf { it.isNotEmpty() }?.let {
        rows += CarouselRow("Téléchargés", it)
    }
    // Rangée « récents » seulement si elle apporte quelque chose (dates connues, pas toute la liste).
    if (games.size > RECENT_COUNT && games.any { it.addedAt.isNotEmpty() }) {
        rows += CarouselRow("Ajoutés récemment", games.sortedByDescending { it.addedAt }.take(RECENT_COUNT))
    }
    val byGenre = games.groupBy { it.mainGenre ?: OTHER_GENRE }
    byGenre.entries
        .sortedWith(compareBy<Map.Entry<String, List<Game>>> { it.key == OTHER_GENRE }.thenByDescending { it.value.size })
        .forEach { (genre, list) ->
            // Sans aucun genre scrapé, une seule rangée « Tous les jeux ».
            val title = if (byGenre.size == 1 && genre == OTHER_GENRE) "Tous les jeux" else genre
            rows += CarouselRow(title, list)
        }
    return rows
}

/** Jeu mis en avant : de préférence un jeu téléchargé avec capture, sinon le plus récent illustré. */
fun pickFeatured(games: List<Game>, downloaded: Set<Long>): Game? =
    games.filter { it.hasScreenshot && it.id in downloaded }.maxByOrNull { it.addedAt }
        ?: games.filter { it.hasScreenshot }.maxByOrNull { it.addedAt }
        ?: games.filter { it.hasBoxart }.maxByOrNull { it.addedAt }
        ?: games.firstOrNull()
