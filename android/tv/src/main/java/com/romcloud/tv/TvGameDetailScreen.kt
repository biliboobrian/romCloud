package com.romcloud.tv

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.romcloud.app.netplay.Together
import com.romcloud.app.ui.togetherIcon
import com.romcloud.app.data.DownloadState
import com.romcloud.app.data.Game
import com.romcloud.app.data.starsText
import com.romcloud.app.ui.DownloadedGreen
import com.romcloud.app.ui.GameDetailViewModel
import com.romcloud.app.ui.GameFact
import com.romcloud.app.ui.OfflineBadge
import com.romcloud.app.ui.OnlineSaveLabel
import com.romcloud.app.ui.PlaytimeLabel
import com.romcloud.app.ui.RetroArchHelpDialog
import com.romcloud.app.ui.StateHistoryList
import com.romcloud.app.ui.formatSize
import com.romcloud.app.ui.gameFacts
import com.romcloud.app.ui.gamePlaytime
import com.romcloud.app.ui.partsSummary
import com.romcloud.core.R
import kotlinx.coroutines.launch

@Composable
fun TvGameDetailScreen(
    viewModel: GameDetailViewModel,
    mediaUrl: (Game, String) -> String?,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val activity = LocalContext.current as Activity
    var pickEmulator by remember { mutableStateOf(false) }
    var showRetroArchHelp by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showStates by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshLocal() }
    }

    val game = state.game
    if (game == null) {
        Message(stringResource(if (state.loading) R.string.loading else R.string.game_not_found))
        return
    }
    val download = downloads[game.id]
    LaunchedEffect(game.id, state.downloaded, download is DownloadState.Running) {
        runCatching { primaryFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(mediaUrl(game, "screenshot") ?: mediaUrl(game, "boxart"))
        // Pas de marge en haut : la jaquette commence en haut de l'écran.
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = 27.dp)) {
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
                        game.players?.let { stringResource(R.string.players_count, it) },
                        game.stars?.let { stringResource(R.string.rating_short, starsText(it)) },
                        partsSummary(game),
                        formatSize(game.fullSize),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlaytimeLabel(gamePlaytime(game.id), color = MaterialTheme.colorScheme.primary)
                state.onlineSave?.let { OnlineSaveLabel(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                // État local
                when {
                    download is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth(0.7f))
                        Text(stringResource(R.string.downloading_progress, formatSize(download.bytes), formatSize(download.total)))
                    }
                    download is DownloadState.Failed ->
                        Text(stringResource(R.string.download_failed, download.message), color = MaterialTheme.colorScheme.error)
                    state.downloaded -> Text(stringResource(R.string.tv_on_tv), color = DownloadedGreen, style = MaterialTheme.typography.labelLarge)
                    else -> Text(
                        stringResource(R.string.tv_must_download),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // BIOS du système : téléchargés avec le jeu, ou via le bouton si le jeu est déjà présent.
                if (state.bios.isNotEmpty() && download !is DownloadState.Running) {
                    val missing = state.missingBios
                    if (missing.isEmpty()) {
                        Text(stringResource(R.string.bios_present, state.bios.size), color = DownloadedGreen, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            stringResource(R.string.bios_missing, missing.size, formatSize(missing.sumOf { it.size })),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
                    val primary = Modifier.focusRequester(primaryFocus)
                    when {
                        download is DownloadState.Running ->
                            ActionButton(stringResource(R.string.tv_cancel_percent, (download.progress * 100).toInt()), Icons.Filled.Close, primary) { viewModel.cancel() }
                        state.downloaded -> {
                            // Partie sauvegardée dans l'émulateur intégré : « Reprendre » d'abord, puis « Jouer ».
                            if (state.canResume) {
                                ActionButton(stringResource(R.string.action_resume_game), Icons.Filled.PlayArrow, primary) {
                                    viewModel.play(activity, resume = true)?.let(onMessage)
                                }
                                SecondaryButton(stringResource(R.string.action_play), Icons.Filled.Replay) { viewModel.play(activity)?.let(onMessage) }
                            } else {
                                ActionButton(stringResource(R.string.action_play), Icons.Filled.PlayArrow, primary) { viewModel.play(activity)?.let(onMessage) }
                            }
                            // Historique des états (émulateur intégré) : partie lancée à partir de l'un d'eux.
                            if (state.savedStates > 0) {
                                SecondaryButton("${stringResource(R.string.states_button)} (${state.savedStates})", Icons.Filled.History) { showStates = true }
                            }
                            viewModel.together(peers)?.let { kind ->
                                SecondaryButton(
                                    stringResource(if (kind == Together.LINK) R.string.link_host else R.string.netplay_play_together),
                                    togetherIcon(kind),
                                ) { viewModel.hostNetplay(activity)?.let(onMessage) }
                            }
                            if (state.missingBios.isNotEmpty()) {
                                SecondaryButton(stringResource(R.string.action_download_bios), Icons.Filled.CloudDownload) { viewModel.downloadBios() }
                            }
                        }
                        else -> ActionButton(
                            if (download is DownloadState.Failed) stringResource(R.string.action_retry)
                            else stringResource(R.string.download_with_size, formatSize(game.fullSize)),
                            Icons.Filled.CloudDownload,
                            primary,
                        ) { viewModel.download() }
                    }
                    // Ce jeu proposé par un autre appareil : partie rejointe (jeu téléchargé d'abord s'il manque).
                    if (download !is DownloadState.Running) {
                        viewModel.hostsOfGame(peers).forEach { peer ->
                            val link = peer.hosting?.link != null
                            SecondaryButton(
                                stringResource(if (link) R.string.link_join_peer else R.string.netplay_join_peer, peer.name),
                                togetherIcon(if (link) Together.LINK else Together.NETPLAY),
                            ) { scope.launch { viewModel.join(activity, peer)?.let(onMessage) } }
                        }
                    }
                    if (state.players.isNotEmpty()) {
                        SecondaryButton(stringResource(R.string.tv_emulator_value, state.selectedPlayer?.name ?: "—"), Icons.Filled.SportsEsports) {
                            pickEmulator = true
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
                    val selected = state.selectedPlayer
                    val installed = state.players.find { it.first.uniqueId == selected?.uniqueId }?.second ?: true
                    if (selected != null && !installed) {
                        SecondaryButton(stringResource(R.string.tv_install_emulator), Icons.Filled.Shop) {
                            viewModel.installPlayer(selected)?.let(onMessage)
                        }
                    }
                    if (state.retroArchInfo != null) {
                        SecondaryButton(stringResource(R.string.configure_retroarch), Icons.Filled.Info) { showRetroArchHelp = true }
                    }
                    if (state.downloaded && download !is DownloadState.Running) {
                        SecondaryButton(stringResource(R.string.tv_delete_from_tv), Icons.Filled.Delete) { confirmDelete = true }
                    }
                }
                if (state.players.isEmpty()) {
                    Text(
                        stringResource(R.string.tv_no_emulator),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                game.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(0.9f))
                }
                TvGameInfo(gameFacts(game))
            }
        }
        OfflineBadge(Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp), size = 40.dp, explainOnClick = false)
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
    if (showStates) {
        viewModel.stateHistory(onMessage)?.let { controller ->
            Dialog(onDismissRequest = { showStates = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                StateHistoryList(
                    controller = controller,
                    title = stringResource(R.string.states_title),
                    loadLabel = stringResource(R.string.states_play),
                    onLoad = {
                        showStates = false
                        viewModel.playState(activity, it, onMessage)
                    },
                    onClose = {
                        showStates = false
                        viewModel.refreshLocal()
                    },
                )
            }
        } ?: run { showStates = false }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.tv_delete_title),
            text = stringResource(R.string.tv_delete_text),
            confirm = stringResource(R.string.action_delete),
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

/**
 * Informations détaillées du jeu. Chaque ligne peut recevoir le focus : la télécommande descend
 * ainsi jusqu'en bas de la fiche (le texte seul ne ferait pas défiler la page).
 */
@Composable
private fun TvGameInfo(facts: List<GameFact>) {
    if (facts.isEmpty()) return
    Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        for (fact in facts) {
            var focused by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (focused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    fact.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(220.dp),
                )
                // Pas de navigateur sur la plupart des téléviseurs : l'adresse complète est affichée.
                Text(fact.url ?: fact.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
