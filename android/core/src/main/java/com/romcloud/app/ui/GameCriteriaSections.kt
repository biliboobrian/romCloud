package com.romcloud.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.romcloud.core.R

/** Choix d'un critère : un appui le sélectionne, un second le retire. */
class CriteriaOption(val label: String, val selected: Boolean, val toggle: (GameCriteria) -> GameCriteria)

/** Critère et ses valeurs, affichés en rangée de puces (téléphone) ou dans une fenêtre (TV). */
class CriteriaSection(val title: String, val options: List<CriteriaOption>)

/** Sections du panneau « Filtres » d'après les valeurs présentes dans la liste ([facets]). */
@Composable
fun criteriaSections(facets: GameFacets, criteria: GameCriteria): List<CriteriaSection> {
    val res = LocalContext.current.resources
    fun <T> section(title: String, values: List<T>, current: T?, label: (T) -> String, set: GameCriteria.(T?) -> GameCriteria) =
        CriteriaSection(
            title,
            values.map { v -> CriteriaOption(label(v), v == current) { c -> c.set(if (v == current) null else v) } },
        )

    val playersLabels = mapOf(
        PlayersCriterion.SOLO to stringResource(R.string.criteria_players_solo),
        PlayersCriterion.MULTI to stringResource(R.string.criteria_players_multi),
        PlayersCriterion.FOUR to stringResource(R.string.criteria_players_four),
        PlayersCriterion.COOP to stringResource(R.string.criteria_players_coop),
    )
    return listOf(
        section(stringResource(R.string.criteria_genre), facets.genres, criteria.genre, { it }) { copy(genre = it) },
        section(stringResource(R.string.criteria_decade), facets.decades, criteria.decade, { res.getString(R.string.criteria_decade_value, it) }) { copy(decade = it) },
        section(stringResource(R.string.criteria_players), facets.players, criteria.players, { playersLabels.getValue(it) }) { copy(players = it) },
        section(stringResource(R.string.criteria_region), facets.regions, criteria.region, { it }) { copy(region = it) },
        section(stringResource(R.string.criteria_rating), facets.ratings, criteria.minRating, { res.getString(R.string.criteria_rating_value, it) }) { copy(minRating = it) },
        section(stringResource(R.string.criteria_publisher), facets.publishers, criteria.publisher, { it }) { copy(publisher = it) },
    ).filter { it.options.isNotEmpty() }
}
