package com.romcloud.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.ui.LobbyContent

/**
 * Page du lobby (TV) : l'invité ne la quitte qu'en quittant le lobby (retour sans effet) ; l'hôte
 * peut partir choisir le jeu ([onChooseGame]) et revenir.
 */
@Composable
fun TvLobbyScreen(app: RomCloudApp, onMessage: (String) -> Unit, onChooseGame: () -> Unit) {
    val state by app.lobbies.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.locked) {}
    LobbyContent(
        app = app,
        onChooseGame = onChooseGame,
        onMessage = onMessage,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        coverWidth = 200.dp,
    )
}
