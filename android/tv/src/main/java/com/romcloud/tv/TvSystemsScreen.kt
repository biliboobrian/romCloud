package com.romcloud.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.romcloud.app.data.GameSystem
import com.romcloud.app.ui.SystemsViewModel

@Composable
fun TvSystemsScreen(
    viewModel: SystemsViewModel,
    storageWarning: Boolean,
    imageUrl: (GameSystem) -> String?,
    onOpenSystem: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Au retour d'un système, le focus revient sur la carte d'où l'on venait.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    val restoreFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.systems.size) {
        if (state.systems.isEmpty()) return@LaunchedEffect
        val index = lastFocused.coerceIn(0, state.systems.lastIndex)
        gridState.scrollToItem(index)
        runCatching { restoreFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(TvSafePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RomCloud", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Refresh, null, modifier = IconSize)
                Spacer(Modifier.width(8.dp))
                Text("Actualiser")
            }
            Spacer(Modifier.width(SmallGap))
            Button(onClick = onOpenSettings, modifier = Modifier.focusRequester(settingsFocus)) {
                Icon(Icons.Filled.Settings, null, modifier = IconSize)
                Spacer(Modifier.width(8.dp))
                Text("Paramètres")
            }
        }
        if (storageWarning) {
            TvBanner("Autorisez l’accès aux fichiers (Paramètres) pour pouvoir télécharger des ROMs.", error = true)
        }
        if (state.offline) {
            TvBanner("Hors ligne : liste en cache. Seuls les jeux déjà téléchargés peuvent être lancés.")
        }
        when {
            state.systems.isEmpty() && state.loading -> Message("Chargement…")
            state.systems.isEmpty() && state.error != null -> {
                Message("Impossible de joindre le serveur :\n${state.error}\n\nVérifiez l’adresse dans les Paramètres.")
                LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
            }
            state.systems.isEmpty() -> Message("Aucun système sur le serveur.\nAjoutez-en depuis l’interface web.")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(250.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.systems, key = { _, s -> s.id }) { index, system ->
                    TvSystemCard(
                        system = system,
                        imageUrl = imageUrl(system),
                        onClick = { onOpenSystem(system.id) },
                        modifier = Modifier
                            .then(if (index == lastFocused) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) lastFocused = index },
                    )
                }
            }
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
