package com.romcloud.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.romcloud.app.data.Game
import com.romcloud.app.data.RegionText
import com.romcloud.core.R

/** Ligne de la section « Informations » de la fiche d'un jeu ; [url] : lien à ouvrir. */
data class GameFact(val label: String, val value: String, val url: String? = null)

/**
 * Informations détaillées du jeu (scraping et nom de fichier), communes aux applications
 * téléphone et TV ; chacune les affiche à sa manière.
 */
@Composable
fun gameFacts(game: Game): List<GameFact> {
    val d = game.details
    val world = stringResource(R.string.region_world)
    // Codes de région ScreenScraper (« eu », « wor ») : en majuscules, « Monde » pour « wor ».
    fun region(code: String?) = when (code?.lowercase()) {
        null, "" -> null
        "wor" -> world
        else -> code.uppercase()
    }
    fun byRegion(list: List<RegionText>) =
        list.joinToString("\n") { e -> region(e.region)?.let { "$it : ${e.text}" } ?: e.text }

    return buildList {
        fun add(label: String, value: String?) {
            if (!value.isNullOrBlank()) add(GameFact(label, value))
        }
        add(stringResource(R.string.info_other_titles), byRegion(d.otherTitles))
        add(stringResource(R.string.info_release_dates), byRegion(d.releaseDates))
        add(stringResource(R.string.info_regions), d.regions.joinToString(", "))
        add(stringResource(R.string.info_languages), d.languages.joinToString(", "))
        add(stringResource(R.string.info_series), d.series)
        add(stringResource(R.string.info_modes), d.modes.joinToString(", "))
        add(stringResource(R.string.info_themes), d.themes.joinToString(", "))
        add(stringResource(R.string.info_age_ratings), d.ageRatings.joinToString(", ") { r -> listOfNotNull(r.type, r.text).joinToString(" ") })
        add(stringResource(R.string.info_serial), d.serial)
        add(stringResource(R.string.info_rom_flags), d.romFlags.joinToString(", "))
        add(stringResource(R.string.info_arcade_set), listOfNotNull(d.arcadeSet, d.arcadeParent?.let { "(${stringResource(R.string.info_arcade_clone, it)})" }).joinToString(" ").ifBlank { null })
        add(stringResource(R.string.info_resolution), d.resolution)
        add(stringResource(R.string.info_rotation), d.rotation)
        add(stringResource(R.string.info_controls), d.controls)
        val features = listOfNotNull(
            stringResource(R.string.info_rumble).takeIf { d.rumble == true },
            stringResource(R.string.info_analog).takeIf { d.analog == true },
        )
        add(stringResource(R.string.info_features), features.joinToString(", "))
        add(stringResource(R.string.info_file), game.fileName)
        add(stringResource(R.string.info_size), formatSize(game.size))
        add("CRC32", game.crc32?.uppercase())
        add("MD5", game.md5)
        for (link in d.links) {
            add(GameFact(link.label, link.url.removePrefix("https://").removePrefix("http://").substringBefore('/'), link.url))
        }
    }
}
