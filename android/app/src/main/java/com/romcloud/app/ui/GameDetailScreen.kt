package com.romcloud.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.data.Player
import com.romcloud.app.ui.theme.DownloadedGreen
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    viewModel: GameDetailViewModel,
    mediaUrl: (Game, String) -> String?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshLocal() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.system?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val game = state.game
        if (game == null) {
            Column(Modifier.padding(padding)) { Centered(if (state.loading) "Chargement…" else "Jeu introuvable") }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row {
                Cover(mediaUrl(game, "boxart"), game.title, 130.dp, 175.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    listOfNotNull(
                        game.releaseDate?.let { "Sortie : $it" },
                        game.genre,
                        game.developer?.let { "Développeur : $it" },
                        game.publisher?.let { "Éditeur : $it" },
                        game.players?.let { "Joueurs : $it" },
                        game.rating?.let { String.format(Locale.FRANCE, "Note : %.1f / 5", it) },
                    ).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- Action principale : télécharger / progression / jouer ----
            when (val d = downloads[game.id]) {
                is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Téléchargement… ${formatSize(d.bytes)} / ${formatSize(d.total)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::cancel) { Text("Annuler") }
                    }
                }
                else -> {
                    if (d is DownloadState.Failed) {
                        Text("Échec du téléchargement : ${d.message}", color = MaterialTheme.colorScheme.error)
                    }
                    if (state.downloaded) {
                        Text("✓ Disponible sur l’appareil", color = DownloadedGreen, style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.play(activity)?.let { scope.launch { snackbar.showSnackbar(it) } } },
                                modifier = Modifier.weight(1f).height(52.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Jouer")
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.height(52.dp)) {
                                Icon(Icons.Filled.Delete, "Supprimer de l’appareil")
                            }
                        }
                    } else {
                        Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (d is DownloadState.Failed) "Réessayer" else "Télécharger (${formatSize(game.size)})")
                        }
                        Text(
                            "Le jeu doit être téléchargé avant de pouvoir y jouer.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PlayerSelector(state, viewModel) { player ->
                viewModel.installPlayer(activity, player)?.let { scope.launch { snackbar.showSnackbar(it) } }
            }

            state.launchCommand?.let { LaunchCommand(it) }

            game.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            mediaUrl(game, "screenshot")?.let { url ->
                AsyncImage(
                    model = url, contentDescription = "Capture d’écran", contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
            }

            Text(
                "${game.fileName} · ${formatSize(game.size)}" + (state.localPath?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer de l’appareil ?") },
            text = { Text("Le fichier local sera supprimé pour libérer de l’espace. Le jeu reste disponible sur le serveur.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteLocal(); confirmDelete = false }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

/** Commande « am start » réelle (repliable) : utile pour vérifier le cœur et le chemin de la ROM. */
@Composable
private fun LaunchCommand(command: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "Masquer la commande de lancement" else "Voir la commande de lancement")
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSelector(
    state: GameDetailViewModel.UiState,
    viewModel: GameDetailViewModel,
    onInstall: (Player) -> Unit,
) {
    if (state.players.isEmpty()) {
        Text(
            "Aucun modèle d’émulateur pour ce système : le jeu sera ouvert avec le sélecteur d’applications Android.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedPlayer
    val installed = state.players.find { it.first.uniqueId == selected?.uniqueId }?.second ?: true
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Émulateur") },
            supportingText = if (!installed) {
                { Text("Cet émulateur n’est pas installé", color = MaterialTheme.colorScheme.error) }
            } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.players.forEach { (player, isInstalled) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(player.name)
                            if (!isInstalled) {
                                Text("non installé", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leadingIcon = if (isInstalled) {
                        { Spacer(Modifier.size(8.dp)) }
                    } else null,
                    onClick = {
                        viewModel.selectPlayer(player)
                        expanded = false
                    },
                )
            }
        }
    }
    if (!installed && selected != null) {
        OutlinedButton(onClick = { onInstall(selected) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Shop, null)
            Spacer(Modifier.width(8.dp))
            Text("Installer depuis le Play Store")
        }
    }
}
