package com.romcloud.app.ui

import com.romcloud.app.data.Game

/** Nombre de joueurs recherché. */
enum class PlayersCriterion { SOLO, MULTI, FOUR, COOP }

/**
 * Critères de recherche avancée d'une liste de jeux (null = indifférent) : genre principal,
 * décennie de sortie (1990 pour 1990-1999), joueurs, région, note minimale sur 5, éditeur.
 */
data class GameCriteria(
    val genre: String? = null,
    val decade: Int? = null,
    val players: PlayersCriterion? = null,
    val region: String? = null,
    val minRating: Int? = null,
    val publisher: String? = null,
) {
    /** Nombre de critères choisis (affiché sur le bouton « Filtres »). */
    val count: Int get() = listOfNotNull(genre, decade, players, region, minRating, publisher).size

    fun matches(game: Game): Boolean =
        (genre == null || game.mainGenre.equals(genre, ignoreCase = true)) &&
            (decade == null || game.decade == decade) &&
            (players == null || game.matchesPlayers(players)) &&
            (region == null || region in game.details.regions) &&
            (minRating == null || (game.rating ?: 0.0) >= minRating) &&
            (publisher == null || game.mainPublisher.equals(publisher, ignoreCase = true))
}

/** Décennie de sortie (« 1994 » -> 1990). */
val Game.decade: Int? get() = year?.toIntOrNull()?.let { it / 10 * 10 }

/** Premier éditeur (« Sega, Acclaim » -> « Sega »). */
val Game.mainPublisher: String?
    get() = publisher?.split(',', '/', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/** Nombre maximal de joueurs (« 1-4 » -> 4, « 2 » -> 2) ; null si inconnu. */
val Game.maxPlayers: Int? get() = players?.let { p -> Regex("\\d+").findAll(p).mapNotNull { it.value.toIntOrNull() }.maxOrNull() }

/** Jeu coopératif : indiqué par LaunchBox, ou cité dans les modes de jeu (« Coop », « mode coopératif »). */
val Game.isCoop: Boolean get() = details.cooperative == true || details.modes.any { it.contains("coop", ignoreCase = true) }

private fun Game.matchesPlayers(criterion: PlayersCriterion): Boolean = when (criterion) {
    PlayersCriterion.SOLO -> maxPlayers == 1
    PlayersCriterion.MULTI -> (maxPlayers ?: 0) >= 2
    PlayersCriterion.FOUR -> (maxPlayers ?: 0) >= 4
    PlayersCriterion.COOP -> isCoop
}

/**
 * Valeurs proposées pour chaque critère, d'après les jeux de la liste : les plus fréquentes
 * d'abord (genres, régions, éditeurs), décennies dans l'ordre, joueurs et notes s'ils sont connus.
 */
data class GameFacets(
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val players: List<PlayersCriterion> = emptyList(),
    val regions: List<String> = emptyList(),
    val ratings: List<Int> = emptyList(),
    val publishers: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && decades.isEmpty() && players.isEmpty() && regions.isEmpty() && ratings.isEmpty() && publishers.isEmpty()

    companion object {
        private const val MAX_PUBLISHERS = 30

        fun of(games: List<Game>): GameFacets {
            fun byFrequency(values: Sequence<String>): List<String> =
                values.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                    .map { it.key }
            return GameFacets(
                genres = byFrequency(games.asSequence().mapNotNull { it.mainGenre }),
                decades = games.mapNotNull { it.decade }.distinct().sorted(),
                players = PlayersCriterion.entries.filter { c -> games.any { it.matchesPlayers(c) } },
                regions = byFrequency(games.asSequence().flatMap { it.details.regions }),
                ratings = listOf(3, 4).filter { r -> games.any { (it.rating ?: 0.0) >= r } },
                publishers = byFrequency(games.asSequence().mapNotNull { it.mainPublisher }).take(MAX_PUBLISHERS),
            )
        }
    }
}
