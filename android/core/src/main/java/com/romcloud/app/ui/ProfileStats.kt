package com.romcloud.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romcloud.app.data.Account
import com.romcloud.app.data.ProfileStats
import com.romcloud.core.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parties de l'historique affichées au plus. */
private const val HISTORY_SHOWN = 20

/**
 * Statistiques du profil (téléphone et TV) : totaux, jeux téléchargés et temps de jeu par appareil,
 * jeux les plus joués et dernières parties. Cartes sélectionnables : la télécommande fait défiler l'écran.
 */
@Composable
fun ProfileStatsSection(stats: ProfileStats?) {
    if (stats == null) return
    StatsCard(stringResource(R.string.stats_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Figure(Account.formatDuration(stats.totals.seconds), stringResource(R.string.stats_playtime), Modifier.weight(1f))
            Figure(stats.totals.sessions.toString(), stringResource(R.string.stats_sessions), Modifier.weight(1f))
            Figure(stats.totals.gamesPlayed.toString(), stringResource(R.string.stats_games_played), Modifier.weight(1f))
            Figure(stats.totals.gamesDownloaded.toString(), stringResource(R.string.stats_games_downloaded), Modifier.weight(1f))
        }
    }
    if (stats.devices.isNotEmpty()) {
        StatsCard(stringResource(R.string.stats_devices)) {
            stats.devices.forEach { d ->
                Line(
                    listOfNotNull(d.device?.takeIf { it.isNotBlank() } ?: stringResource(R.string.stats_unknown_device), platformLabel(d.platform)).joinToString(" · "),
                    listOfNotNull(
                        pluralString(R.plurals.stats_device_games, d.games, d.games),
                        d.seconds.takeIf { it > 0 }?.let { stringResource(R.string.stats_device_playtime, Account.formatDuration(it)) },
                    ).joinToString(" · "),
                )
            }
        }
    }
    if (stats.topGames.isNotEmpty()) {
        StatsCard(stringResource(R.string.stats_top_games)) {
            stats.topGames.forEach { g ->
                Line(
                    g.title ?: "#${g.gameId}",
                    Account.formatDuration(g.seconds) + " · " + pluralString(R.plurals.stats_game_sessions, g.sessions, g.sessions),
                )
            }
        }
    }
    StatsCard(stringResource(R.string.stats_history)) {
        if (stats.history.isEmpty()) {
            Text(stringResource(R.string.stats_history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stats.history.take(HISTORY_SHOWN).forEach { h ->
            Line(
                h.title ?: "#${h.gameId}",
                listOfNotNull(localDate(h.endedAt), h.device?.takeIf { it.isNotBlank() }, Account.formatDuration(h.seconds)).joinToString(" · "),
            )
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Card(
        Modifier.fillMaxWidth().focusable(interactionSource = interaction),
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun Line(title: String, detail: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Plateforme envoyée par l'application (en-tête X-RomCloud-Platform). */
private fun platformLabel(platform: String?): String? = when (platform) {
    "android" -> "Android"
    "androidtv" -> "Android TV"
    "windows" -> "Windows"
    null, "" -> null
    else -> platform
}

/** Date du serveur (« 2026-10-24 18:03:12 », UTC) à l'heure de l'appareil. */
private fun localDate(utc: String): String? = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(parser.parse(utc)!!)
}.getOrNull()
