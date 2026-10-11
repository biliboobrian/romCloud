package com.romcloud.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.romcloud.app.AppLanguage
import com.romcloud.app.RomCloudApp
import com.romcloud.app.ui.theme.RomCloudTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Langue choisie dans les Paramètres (indépendante de celle du système). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val app get() = application as RomCloudApp

    /** Toucher ou touche (manette, clavier) : « présent » pour les autres joueurs (multijoueurs). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        app.touchActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        app.touchActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Réglage modifiable depuis les Paramètres : appliqué dès qu'il change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settings.fullscreen.collect { applyFullscreen() }
            }
        }
        setContent {
            RomCloudTheme {
                // Encoche de la caméra évitée sur les côtés et en bas (paysage, portrait inversé) ; en haut,
                // l'écran est utilisé jusqu'au bord et les barres d'écran se placent elles-mêmes (topBarInsets).
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                ) {
                    RomCloudNavHost(app, this@MainActivity)
                }
            }
        }
    }

    /** Les barres réapparaissent après un dialogue ou au retour d'un émulateur : on les re-masque. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (app.settings.fullscreen.value) {
            // Un glissement depuis le bord affiche les barres temporairement.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun RomCloudNavHost(app: RomCloudApp, activity: ComponentActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val start = remember { if (app.settings.config.value.isConfigured) "systems" else "settings" }

    LaunchDialogs(app, activity, snackbar)

    var storageWarning by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            storageWarning = !app.library.hasStoragePermission() && !app.library.isAppPrivateDir()
        }
    }

    // Multijoueurs : invité gardé sur la page du lobby ; lobby fermé : retour à l'accueil.
    val lobbyState by app.lobbies.state.collectAsStateWithLifecycle()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(lobbyState.locked, route) {
        if (lobbyState.locked && route != null && route != "lobby") nav.navigate("lobby") { launchSingleTop = true }
    }
    val dissolvedText = stringResource(com.romcloud.core.R.string.lobby_dissolved)
    LaunchedEffect(Unit) {
        app.lobbies.closed.collect { dissolved ->
            nav.navigate("systems") { popUpTo(nav.graph.id) { inclusive = true } }
            if (dissolved) snackbar.showSnackbar(dissolvedText)
        }
    }

    Box(Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = start) {
        composable("lobby") {
            LobbyScreen(app, snackbar, onBack = { nav.popBackStack() }, onChooseGame = { nav.navigate("systems") })
        }
        composable("systems") {
            val vm = viewModel { SystemsViewModel(app) }
            // Écran des systèmes sur fond noir, chaque carte sur la couleur de son système.
            SystemBackground(Color.Black) {
                SystemsScreen(
                    viewModel = vm,
                    storageWarning = storageWarning,
                    imageUrl = { app.api.systemImageUrl(it) },
                    mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                    onOpenSystem = { nav.navigate("games/$it") },
                    onOpenGame = { systemId, gameId -> nav.navigate("game/$systemId/$gameId") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenProfile = { nav.navigate("profile") },
                    onOpenLobby = { nav.navigate("lobby") { launchSingleTop = true } },
                )
            }
        }
        composable("profile") {
            ProfileScreen(viewModel = viewModel { ProfileViewModel(app) }, onBack = { nav.popBackStack() })
        }
        composable(
            "games/{systemId}",
            arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
        ) { entry ->
            val systemId = entry.arguments?.getString("systemId").orEmpty()
            val vm = viewModel { GamesViewModel(app, systemId) }
            val system = vm.state.collectAsStateWithLifecycle().value.system
            // Liste des jeux sur la couleur du système (réglages du serveur).
            SystemBackground(system?.backgroundColor) {
                GamesScreen(
                    viewModel = vm,
                    mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                    snackbar = snackbar,
                    autoLaunch = app.autoLaunch,
                    onBack = { nav.popBackStack() },
                    onOpenGame = { nav.navigate("game/$systemId/$it") },
                    onOpenLobby = { nav.navigate("lobby") { popUpTo("lobby") { inclusive = true } } },
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
            SystemBackground(system?.backgroundColor) {
                GameDetailScreen(
                    viewModel = vm,
                    mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                    snackbar = snackbar,
                    onBack = { nav.popBackStack() },
                    onOpenLobby = { nav.navigate("lobby") { popUpTo("lobby") { inclusive = true } } },
                )
            }
        }
        composable("settings") {
            SettingsScreen(
                app = app,
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onSaved = {
                    nav.navigate("systems") {
                        popUpTo(nav.graph.id) { inclusive = true }
                    }
                },
            )
        }
    }
    // Hôte parti choisir le jeu : retour au lobby.
    if (lobbyState.isHost && route != "lobby") {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { nav.navigate("lobby") { popUpTo("lobby") { inclusive = true }; launchSingleTop = true } },
            icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Groups, null) },
            text = { androidx.compose.material3.Text(stringResource(com.romcloud.core.R.string.lobby_back)) },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(20.dp),
        )
    }
    }
}
