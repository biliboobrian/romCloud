package com.romcloud.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import com.romcloud.app.data.BiosFile
import com.romcloud.app.data.Game
import com.romcloud.app.data.Player
import com.romcloud.app.ui.CriteriaSection
import com.romcloud.app.ui.GameCriteria
import com.romcloud.app.ui.formatSize
import com.romcloud.core.R

/** Donne le focus à l'élément dès l'ouverture de la fenêtre (utilisation à la télécommande). */
@Composable
private fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}

@Composable
fun DownloadDialog(
    game: Game,
    error: String?,
    missingBios: List<BiosFile>,
    onDownload: (launchAfter: Boolean, withBios: Boolean) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    var withBios by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_download_title, game.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tv_download_text))
                Text(
                    "${game.fileName} · ${formatSize(game.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(stringResource(R.string.download_last_error, it), color = MaterialTheme.colorScheme.error) }
                if (missingBios.isNotEmpty()) {
                    // Case à cocher dans un bouton : sélectionnable à la télécommande.
                    TextButton(onClick = { withBios = !withBios }) {
                        Checkbox(checked = withBios, onCheckedChange = null)
                        Text(
                            stringResource(R.string.bios_download_too, missingBios.size, formatSize(missingBios.sumOf { it.size })),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(true, withBios) }, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.tv_download_and_play))
            }
            TextButton(onClick = { onDownload(false, withBios) }) { Text(stringResource(R.string.action_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDetails) { Text(stringResource(R.string.tv_game_details)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Jeu présent mais BIOS du système absents : les télécharger avant de jouer ? */
@Composable
fun MissingBiosDialog(missingBios: List<BiosFile>, onDownloadAndPlay: () -> Unit, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bios_before_play_title)) },
        text = { Text(stringResource(R.string.bios_before_play_text, missingBios.size, formatSize(missingBios.sumOf { it.size }))) },
        confirmButton = {
            TextButton(onClick = onDownloadAndPlay, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.action_download_and_play))
            }
        },
        dismissButton = { TextButton(onClick = onPlay) { Text(stringResource(R.string.action_play_anyway)) } },
    )
}

@Composable
fun CancelDownloadDialog(game: Game, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloading_title)) },
        text = { Text(stringResource(R.string.downloading_text, game.title)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.cancel_download)) } },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_continue)) }
        },
    )
}

@Composable
fun SearchDialog(initial: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSearch(text.trim()) }) { Text(stringResource(R.string.action_search)) } },
        dismissButton = { TextButton(onClick = { onSearch("") }) { Text(stringResource(R.string.clear_search)) } },
    )
}

/** Liste des émulateurs compatibles (installé ou non), sélection à la télécommande. */
@Composable
fun EmulatorPickerDialog(
    players: List<Pair<Player, Boolean>>,
    selectedId: String?,
    onSelect: (Player) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_emulator)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                // Pas de clé : l'identifiant d'un modèle n'est pas forcément unique (voir GameSystem.withUniquePlayerIds).
                items(players) { (player, installed) ->
                    val selected = player.uniqueId == selectedId
                    ListItem(
                        selected = selected,
                        onClick = { onSelect(player) },
                        headlineContent = { androidx.tv.material3.Text(player.name) },
                        supportingContent = if (!installed) {
                            { androidx.tv.material3.Text(stringResource(R.string.not_installed)) }
                        } else null,
                        modifier = if (selected || (selectedId == null && player == players.first().first)) {
                            Modifier.focusRequester(focus)
                        } else Modifier,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Recherche avancée : une rangée de puces par critère, parcourue à la télécommande. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun CriteriaDialog(sections: List<CriteriaSection>, criteria: GameCriteria, onChange: (GameCriteria) -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.criteria_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sections.size) { i ->
                    val section = sections[i]
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(section.options.size) { j ->
                                val option = section.options[j]
                                FilterChip(
                                    selected = option.selected,
                                    onClick = { onChange(option.toggle(criteria)) },
                                    modifier = if (i == 0 && j == 0) Modifier.focusRequester(focus) else Modifier,
                                ) { androidx.tv.material3.Text(option.label) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = { onChange(GameCriteria()) }) { Text(stringResource(R.string.criteria_reset)) } },
    )
}
