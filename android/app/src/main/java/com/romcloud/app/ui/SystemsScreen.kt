package com.romcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.data.GameSystem
import com.romcloud.core.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemsScreen(
    viewModel: SystemsViewModel,
    storageWarning: Boolean,
    imageUrl: (GameSystem) -> String?,
    onOpenSystem: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RomCloud") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (storageWarning) {
                Banner(stringResource(R.string.banner_storage), error = true)
            }
            if (state.offline) {
                Banner(stringResource(R.string.banner_offline_full))
            }
            PullToRefreshBox(
                isRefreshing = state.loading && state.systems.isNotEmpty(),
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.systems.isEmpty() && state.loading -> Centered(stringResource(R.string.loading))
                    state.systems.isEmpty() && state.error != null -> Centered(
                        stringResource(R.string.server_unreachable, state.error.orEmpty()),
                        action = stringResource(R.string.action_settings), onAction = onOpenSettings,
                    )
                    state.systems.isEmpty() -> Centered(stringResource(R.string.no_systems))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.systems, key = { it.id }) { system ->
                            Card(Modifier.fillMaxWidth().clickable { onOpenSystem(system.id) }) {
                                imageUrl(system)?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = system.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(8.dp),
                                    )
                                }
                                Column(Modifier.padding(16.dp)) {
                                    Text(system.shortname.uppercase(), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(system.name, style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2)
                                    Text(
                                        pluralString(R.plurals.games_count, system.gameCount, system.gameCount) + " · " + formatSize(system.totalSize),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Centered(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
