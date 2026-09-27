package com.romcloud.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.res.stringResource
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
import com.romcloud.core.R
import kotlinx.coroutines.launch

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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val game = state.game
        if (game == null) {
            Column(Modifier.padding(padding)) { Centered(stringResource(if (state.loading) R.string.loading else R.string.game_not_found)) }
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
                        game.releaseDate?.let { stringResource(R.string.meta_release, it) },
                        game.genre,
                        game.developer?.let { stringResource(R.string.meta_developer, it) },
                        game.publisher?.let { stringResource(R.string.meta_publisher, it) },
                        game.players?.let { stringResource(R.string.meta_players, it) },
                        game.rating?.let { stringResource(R.string.meta_rating, it) },
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
                            stringResource(R.string.downloading_progress, formatSize(d.bytes), formatSize(d.total)),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                else -> {
                    if (d is DownloadState.Failed) {
                        Text(stringResource(R.string.download_failed, d.message), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.downloaded) {
                        Text(stringResource(R.string.available_on_device), color = DownloadedGreen, style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.play(activity)?.let { scope.launch { snackbar.showSnackbar(it) } } },
                                modifier = Modifier.weight(1f).height(52.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_play))
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.height(52.dp)) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete_from_device))
                            }
                        }
                    } else {
                        Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (d is DownloadState.Failed) stringResource(R.string.action_retry)
                                else stringResource(R.string.download_with_size, formatSize(game.size)),
                            )
                        }
                        Text(
                            stringResource(R.string.must_download),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            var showRetroArchHelp by remember { mutableStateOf(false) }
            PlayerSelector(
                state = state,
                viewModel = viewModel,
                onInstall = { player ->
                    viewModel.installPlayer(activity, player)?.let { scope.launch { snackbar.showSnackbar(it) } }
                },
                onInfo = if (state.retroArchInfo != null) ({ showRetroArchHelp = true }) else null,
            )
            state.retroArchInfo?.takeIf { showRetroArchHelp }?.let { info ->
                RetroArchHelpDialog(
                    info = info,
                    onDismiss = { showRetroArchHelp = false },
                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                )
            }

            state.launchCommand?.let { LaunchCommand(it) }

            game.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            mediaUrl(game, "screenshot")?.let { url ->
                AsyncImage(
                    model = url, contentDescription = stringResource(R.string.screenshot), contentScale = ContentScale.FillWidth,
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
            title = { Text(stringResource(R.string.delete_device_title)) },
            text = { Text(stringResource(R.string.delete_device_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteLocal(); confirmDelete = false }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Commande « am start » réelle (repliable) : utile pour vérifier le cœur et le chemin de la ROM. */
@Composable
private fun LaunchCommand(command: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (expanded) R.string.launch_command_hide else R.string.launch_command_show))
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
    onInfo: (() -> Unit)?,
) {
    if (state.players.isEmpty()) {
        Text(
            stringResource(R.string.no_emulator_template),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedPlayer
    val installed = state.players.find { it.first.uniqueId == selected?.uniqueId }?.second ?: true
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.emulator)) },
                supportingText = if (!installed) {
                    { Text(stringResource(R.string.emulator_not_installed), color = MaterialTheme.colorScheme.error) }
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
                                    Text(stringResource(R.string.not_installed), style = MaterialTheme.typography.labelSmall,
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
        if (onInfo != null) {
            IconButton(onClick = onInfo) {
                Icon(Icons.Filled.Info, stringResource(R.string.configure_retroarch), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (!installed && selected != null) {
        OutlinedButton(onClick = { onInstall(selected) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Shop, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.install_from_play_store))
        }
    }
}
