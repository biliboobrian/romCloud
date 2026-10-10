package com.romcloud.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romcloud.app.I18n
import com.romcloud.app.data.Account
import com.romcloud.app.data.SavedState
import com.romcloud.app.data.StateHistory
import com.romcloud.core.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Historique des états d'un jeu (menu du jeu et fiche) : états de l'appareil et du profil en ligne,
 * chargement (téléchargé d'abord s'il n'est qu'en ligne), épinglage, suppression et envoi.
 */
class StateHistoryController(
    private val account: Account,
    val history: StateHistory,
    private val gameId: Long,
    private val core: String,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    data class UiState(
        val states: List<SavedState> = emptyList(),
        val loading: Boolean = true,
        /** Profil connecté et serveur joint : états en ligne affichés. */
        val online: Boolean = false,
        /** Opération en cours (téléchargement, envoi). */
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val signedIn: Boolean get() = account.state.value.signedIn

    fun refresh() {
        scope.launch {
            val local = withContext(Dispatchers.IO) { history.list() }
            _state.update { it.copy(states = StateHistory.merge(local, emptyList(), core), loading = signedIn) }
            val online = if (gameId > 0) account.onlineStates(gameId) else null
            _state.update { it.copy(states = StateHistory.merge(local, online.orEmpty(), core), loading = false, online = online != null) }
        }
    }

    /** Fichier de l'état : celui de l'appareil, sinon téléchargé (cache de l'application). */
    suspend fun file(state: SavedState): File {
        state.local?.let { return history.stateFile(it.id) }
        val target = File(cacheDir, "states/${state.id}.state")
        if (!target.isFile) {
            _state.update { it.copy(busy = true) }
            try {
                account.downloadState(state.id, target)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        return target
    }

    /** Miniature : celle de l'appareil, sinon celle du serveur (gardée en cache). */
    suspend fun thumbnail(state: SavedState): ImageBitmap? = withContext(Dispatchers.IO) {
        val local = state.local?.let { history.thumbnailFile(it.id) }?.takeIf { it.isFile }
        val file = local ?: File(cacheDir, "state-thumbs/${state.id}.jpg").takeIf { cached ->
            cached.isFile || (state.online?.thumbnail == true && account.stateThumbnail(state.id)?.let { bytes ->
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
                true
            } == true)
        }
        file?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
    }

    fun setPinned(state: SavedState, pinned: Boolean) = act {
        state.local?.let { withContext(Dispatchers.IO) { history.setPinned(it.id, pinned) } }
        if (state.online != null) account.pinOnlineState(state.id, pinned)
    }

    fun delete(state: SavedState) = act {
        state.local?.let { withContext(Dispatchers.IO) { history.delete(it.id) } }
        if (state.online != null) account.deleteOnlineState(state.id)
    }

    fun upload(state: SavedState) = act {
        val meta = state.local ?: return@act
        _state.update { it.copy(busy = true) }
        account.queueStateUpload(gameId, history, meta.id)
        account.flush(savesOnly = true)
        if (withContext(Dispatchers.IO) { history.get(meta.id)?.uploaded } == true) onMessage(I18n.get(R.string.states_uploaded))
        else onMessage(I18n.get(R.string.states_upload_pending))
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(e.message ?: e.javaClass.simpleName)
            } finally {
                _state.update { it.copy(busy = false) }
                refresh()
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** « 12 oct. 2026, 14:32 » dans la langue de l'appareil. */
fun formatStateDate(millis: Long): String = dateFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

/**
 * Liste de l'historique des états, sur fond sombre (par-dessus le jeu ou dans une fenêtre) ;
 * [onLoad] : état choisi (« Charger », ou « Jouer » depuis la fiche).
 */
@Composable
fun StateHistoryList(
    controller: StateHistoryController,
    title: String,
    loadLabel: String,
    onLoad: (SavedState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by controller.state.collectAsState()
    var confirmDelete by remember { mutableStateOf<SavedState?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { controller.refresh() }
    Surface(color = Color.Black.copy(alpha = 0.9f), contentColor = Color.White, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = if (ui.states.isEmpty()) Modifier.focusRequester(focus) else Modifier) {
                    Text(stringResource(R.string.action_close))
                }
            }
            if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (controller.signedIn && !ui.loading && !ui.online) {
                Text(stringResource(R.string.states_offline), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            if (!ui.loading && ui.states.isEmpty()) {
                Text(stringResource(R.string.states_empty), modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.states, key = { it.id }) { state ->
                    StateRow(
                        state = state,
                        controller = controller,
                        loadLabel = loadLabel,
                        enabled = !ui.busy,
                        loadModifier = if (state == ui.states.first()) Modifier.focusRequester(focus) else Modifier,
                        onLoad = { onLoad(state) },
                        onDelete = { confirmDelete = state },
                    )
                }
            }
        }
    }
    LaunchedEffect(ui.states.isEmpty(), ui.loading) { runCatching { focus.requestFocus() } }
    confirmDelete?.let { state ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.states_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.online != null) R.string.states_delete_text_online else R.string.states_delete_text,
                        formatStateDate(state.createdAt),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    controller.delete(state)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StateRow(
    state: SavedState,
    controller: StateHistoryController,
    loadLabel: String,
    enabled: Boolean,
    loadModifier: Modifier,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(null, state.id, state.online?.thumbnail) { value = controller.thumbnail(state) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.width(128.dp).height(96.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatStateDate(state.createdAt), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (state.local != null) Icons.Filled.PhoneAndroid else Icons.Filled.Cloud, null, Modifier.size(16.dp))
                Text(
                    if (state.local != null) stringResource(R.string.states_this_device) else state.device ?: stringResource(R.string.states_other_device),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.local != null && state.online != null) {
                    Icon(Icons.Filled.Cloud, stringResource(R.string.states_online), Modifier.size(16.dp))
                }
                if (state.pinned) Icon(Icons.Filled.PushPin, stringResource(R.string.states_pinned), Modifier.size(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onLoad, enabled = enabled, modifier = loadModifier) { Text(loadLabel) }
                IconButton(onClick = { controller.setPinned(state, !state.pinned) }, enabled = enabled) {
                    Icon(
                        if (state.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        stringResource(if (state.pinned) R.string.states_unpin else R.string.states_pin),
                    )
                }
                if (state.canUpload && controller.signedIn) {
                    IconButton(onClick = { controller.upload(state) }, enabled = enabled) {
                        Icon(Icons.Filled.CloudUpload, stringResource(R.string.states_upload))
                    }
                }
                IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
            }
        }
    }
}
