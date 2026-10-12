package com.romcloud.tv

import com.romcloud.core.R
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.romcloud.app.AppLanguage
import com.romcloud.app.RomCloudApp
import com.romcloud.app.ui.GameDetailViewModel
import com.romcloud.app.ui.ProfileViewModel
import com.romcloud.app.ui.GamesViewModel
import com.romcloud.app.ui.LaunchDialogs
import com.romcloud.app.ui.SystemsViewModel
import com.romcloud.app.ui.backgroundColor
import kotlinx.coroutines.launch

class TvActivity : ComponentActivity() {

    /** Langue choisie dans les Paramètres (indépendante de celle du système). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
    /** Touche de la télécommande ou de la manette : « présent » pour les autres joueurs. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        (application as RomCloudApp).touchActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RomCloudApp
        setContent {
            TvTheme { TvRoot(app, this) }
        }
        // Application au premier plan : prête à recevoir un jeu diffusé depuis un téléphone du profil.
        val streamHost = TvStreamHost(this, app)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { streamHost.run() } }
    }
}

@Composable
private fun TvRoot(app: RomCloudApp, activity: ComponentActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    val start = remember { if (app.settings.config.value.isConfigured) "systems" else "settings" }

    // Fins de téléchargement, émulateur manquant, fermeture de RetroArch : communs avec le téléphone.
    LaunchDialogs(app, activity, snackbar)

    var storageWarning by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            storageWarning = !app.library.hasStoragePermission() && !app.library.isAppPrivateDir()
        }
    }

    // Surface racine : fixe les couleurs de contenu des composants tv-material.
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = TvBackground),
    ) {
        // Multijoueurs : invité gardé sur la page du lobby ; lobby fermé : retour à l'accueil.
        val lobbyState by app.lobbies.state.collectAsStateWithLifecycle()
        val route = nav.currentBackStackEntryAsState().value?.destination?.route
        LaunchedEffect(lobbyState.locked, route) {
            if (lobbyState.locked && route != null && route != "lobby") nav.navigate("lobby") { launchSingleTop = true }
        }
        val dissolvedText = stringResource(R.string.lobby_dissolved)
        LaunchedEffect(Unit) {
            app.lobbies.closed.collect { dissolved ->
                nav.navigate("systems") { popUpTo(nav.graph.id) { inclusive = true } }
                if (dissolved) message(dissolvedText)
            }
        }
        val openLobby = { nav.navigate("lobby") { popUpTo("lobby") { inclusive = true }; launchSingleTop = true } }
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = start) {
                composable("lobby") {
                    // Fond : couleur du système du jeu choisi, sinon noir.
                    TvScreenBackground(com.romcloud.app.ui.lobbyBackground(app)) {
                        TvLobbyScreen(app, onMessage = message, onChooseGame = { nav.navigate("systems") })
                    }
                }
                composable("systems") {
                    val vm = viewModel { SystemsViewModel(app) }
                    // Écran des systèmes sur fond noir, chaque carte sur la couleur de son système.
                    TvScreenBackground(Color.Black) {
                        TvSystemsScreen(
                            viewModel = vm,
                            storageWarning = storageWarning,
                            imageUrl = { app.api.systemImageUrl(it) },
                            mediaUrl = { game, type, size -> app.api.mediaUrl(game, type, size) },
                            onOpenSystem = { nav.navigate("games/$it") },
                            onOpenGame = { systemId, gameId -> nav.navigate("game/$systemId/$gameId") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenProfile = { nav.navigate("profile") },
                            onOpenLobby = openLobby,
                        )
                    }
                }
                composable("profile") {
                    TvProfileScreen(viewModel = viewModel { ProfileViewModel(app) }, onMessage = message)
                }
                composable(
                    "games/{systemId}",
                    arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
                ) { entry ->
                    val systemId = entry.arguments?.getString("systemId").orEmpty()
                    val vm = viewModel { GamesViewModel(app, systemId) }
                    val system = vm.state.collectAsStateWithLifecycle().value.system
                    // Liste des jeux sur la couleur du système (réglages du serveur).
                    TvScreenBackground(system?.backgroundColor) {
                        TvGamesScreen(
                            viewModel = vm,
                            mediaUrl = { game, type, size -> app.api.mediaUrl(game, type, size) },
                            autoLaunch = app.autoLaunch,
                            onMessage = message,
                            onOpenGame = { nav.navigate("game/$systemId/$it") },
                            onOpenLobby = openLobby,
                        )
                    }
                }
                composable(
                    "game/{systemId}/{gameId}",
                    arguments = listOf(
                        navArgument("systemId") { type = NavType.StringType },
                        navArgument("gameId") { type = NavType.LongType },
                    ),
                ) { entry ->
                    val systemId = entry.arguments?.getString("systemId").orEmpty()
                    val gameId = entry.arguments?.getLong("gameId") ?: 0L
                    val vm = viewModel { GameDetailViewModel(app, systemId, gameId) }
                    val system = vm.state.collectAsStateWithLifecycle().value.system
                    TvScreenBackground(system?.backgroundColor) {
                        TvGameDetailScreen(
                            viewModel = vm,
                            mediaUrl = { game, type, size -> app.api.mediaUrl(game, type, size) },
                            onMessage = message,
                            onOpenLobby = openLobby,
                        )
                    }
                }
                composable("settings") {
                    TvSettingsScreen(
                        app = app,
                        onSaved = {
                            nav.navigate("systems") { popUpTo(nav.graph.id) { inclusive = true } }
                        },
                    )
                }
            }
            // Hôte parti choisir le jeu : retour au lobby.
            if (lobbyState.isHost && route != "lobby") {
                androidx.tv.material3.Button(onClick = openLobby, modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp)) {
                    androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Groups, null)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                    androidx.tv.material3.Text(stringResource(R.string.lobby_back))
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
        }
    }
}
