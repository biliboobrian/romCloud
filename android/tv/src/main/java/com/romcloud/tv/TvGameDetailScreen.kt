package com.romcloud.tv

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.ui.DownloadedGreen
import com.romcloud.app.ui.GameDetailViewModel
import com.romcloud.app.ui.RetroArchHelpDialog
import com.romcloud.app.ui.formatSize
import java.util.Locale

@Composable
fun TvGameDetailScreen(
    viewModel: GameDetailViewModel,
    mediaUrl: (Game, String) -> String?,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    var pickEmulator by remember { mutableStateOf(false) }
    var showRetroArchHelp by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshLocal() }
    }

    val game = state.game
    if (game == null) {
        Message(if (state.loading) "Chargement…" else "Jeu introuvable")
        return
    }
    val download = downloads[game.id]
    LaunchedEffect(game.id, state.downloaded, download is DownloadState.Running) {
        runCatching { primaryFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(mediaUrl(game, "screenshot") ?: mediaUrl(game, "boxart"))
        Row(Modifier.fillMaxSize().padding(TvSafePadding)) {
            // Jaquette
            Box(
                Modifier
                    .width(260.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val cover = mediaUrl(game, "boxart")
                if (cover != null) {
                    AsyncImage(cover, game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.SportsEsports, null, modifier = Modifier.width(72.dp))
                }
            }
            Spacer(Modifier.width(40.dp))

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.system?.name.orEmpty().uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(game.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        game.releaseDate,
                        game.genre,
                        game.developer,
                        game.players?.let { "$it joueur(s)" },
                        game.rating?.let { String.format(Locale.FRANCE, "%.1f/5", it) },
                        formatSize(game.size),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // État local
                when {
                    download is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth(0.7f))
                        Text("Téléchargement… ${formatSize(download.bytes)} / ${formatSize(download.total)}")
                    }
                    download is DownloadState.Failed ->
                        Text("Échec du téléchargement : ${download.message}", color = MaterialTheme.colorScheme.error)
                    state.downloaded -> Text("✓ Sur le téléviseur", color = DownloadedGreen, style = MaterialTheme.typography.labelLarge)
                    else -> Text(
                        "Doit être téléchargé avant de pouvoir y jouer.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
                    val primary = Modifier.focusRequester(primaryFocus)
                    when {
                        download is DownloadState.Running ->
                            ActionButton("Annuler (${(download.progress * 100).toInt()} %)", Icons.Filled.Close, primary) { viewModel.cancel() }
                        state.downloaded -> {
                            ActionButton("Jouer", Icons.Filled.PlayArrow, primary) { viewModel.play(activity)?.let(onMessage) }
                        }
                        else -> ActionButton(
                            if (download is DownloadState.Failed) "Réessayer" else "Télécharger (${formatSize(game.size)})",
                            Icons.Filled.CloudDownload,
                            primary,
                        ) { viewModel.download() }
                    }
                    if (state.players.isNotEmpty()) {
                        SecondaryButton("Émulateur : ${state.selectedPlayer?.name ?: "—"}", Icons.Filled.SportsEsports) {
                            pickEmulator = true
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
                    val selected = state.selectedPlayer
                    val installed = state.players.find { it.first.uniqueId == selected?.uniqueId }?.second ?: true
                    if (selected != null && !installed) {
                        SecondaryButton("Installer l’émulateur", Icons.Filled.Shop) {
                            viewModel.installPlayer(activity, selected)?.let(onMessage)
                        }
                    }
                    if (state.retroArchInfo != null) {
                        SecondaryButton("Configurer RetroArch", Icons.Filled.Info) { showRetroArchHelp = true }
                    }
                    if (state.downloaded && download !is DownloadState.Running) {
                        SecondaryButton("Supprimer du téléviseur", Icons.Filled.Delete) { confirmDelete = true }
                    }
                }
                if (state.players.isEmpty()) {
                    Text(
                        "Aucun émulateur défini pour ce système : ajoutez-en depuis l’interface web du serveur.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                game.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(0.9f))
                }
                Text(
                    game.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pickEmulator) {
        EmulatorPickerDialog(
            players = state.players,
            selectedId = state.selectedPlayer?.uniqueId,
            onSelect = {
                viewModel.selectPlayer(it)
                pickEmulator = false
            },
            onDismiss = { pickEmulator = false },
        )
    }
    state.retroArchInfo?.takeIf { showRetroArchHelp }?.let { info ->
        RetroArchHelpDialog(info = info, onDismiss = { showRetroArchHelp = false }, onMessage = onMessage)
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer du téléviseur ?",
            text = "Le fichier sera supprimé pour libérer de l’espace. Le jeu reste disponible sur le serveur.",
            confirm = "Supprimer",
            onConfirm = {
                viewModel.deleteLocal()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(icon, null, modifier = IconSize)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SecondaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, null, modifier = IconSize)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
