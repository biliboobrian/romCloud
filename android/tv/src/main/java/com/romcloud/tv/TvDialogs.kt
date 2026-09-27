package com.romcloud.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import com.romcloud.app.data.Game
import com.romcloud.app.data.Player
import com.romcloud.app.ui.formatSize

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
    onDownload: (launchAfter: Boolean) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Télécharger « ${game.title} » ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ce jeu n’est pas encore sur le téléviseur : il doit être téléchargé avant de pouvoir y jouer.")
                Text(
                    "${game.fileName} · ${formatSize(game.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text("Dernier essai : $it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(true) }, modifier = Modifier.focusRequester(focus)) {
                Text("Télécharger et jouer")
            }
            TextButton(onClick = { onDownload(false) }) { Text("Télécharger") }
        },
        dismissButton = {
            TextButton(onClick = onDetails) { Text("Fiche du jeu") }
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
fun CancelDownloadDialog(game: Game, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Téléchargement en cours") },
        text = { Text("« ${game.title} » est en cours de téléchargement. Voulez-vous l’annuler ?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Annuler le téléchargement") } },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Continuer") }
        },
    )
}

@Composable
fun SearchDialog(initial: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rechercher un jeu") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Titre ou nom de fichier") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSearch(text.trim()) }) { Text("Rechercher") } },
        dismissButton = { TextButton(onClick = { onSearch("") }) { Text("Effacer la recherche") } },
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
        title = { Text("Choisir l’émulateur") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(players, key = { it.first.uniqueId }) { (player, installed) ->
                    val selected = player.uniqueId == selectedId
                    ListItem(
                        selected = selected,
                        onClick = { onSelect(player) },
                        headlineContent = { androidx.tv.material3.Text(player.name) },
                        supportingContent = if (!installed) {
                            { androidx.tv.material3.Text("non installé") }
                        } else null,
                        modifier = if (selected || (selectedId == null && player == players.first().first)) {
                            Modifier.focusRequester(focus)
                        } else Modifier,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
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
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Annuler") }
        },
    )
}
