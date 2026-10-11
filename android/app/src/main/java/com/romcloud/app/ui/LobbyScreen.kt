package com.romcloud.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.core.R
import kotlinx.coroutines.launch

/**
 * Page du lobby (téléphone) : l'invité ne la quitte qu'en quittant le lobby (retour sans effet) ;
 * l'hôte peut partir choisir le jeu ([onChooseGame]) et revenir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(app: RomCloudApp, snackbar: SnackbarHostState, onBack: () -> Unit, onChooseGame: () -> Unit) {
    val state by app.lobbies.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.locked) {}
    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = topBarInsets,
                title = { Text(state.mine?.let { lobbyTitle(it) } ?: stringResource(R.string.lobby_back)) },
                navigationIcon = {
                    if (!state.locked) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LobbyContent(
            app = app,
            onChooseGame = onChooseGame,
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}
