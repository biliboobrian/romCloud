package com.romcloud.tv

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.romcloud.app.ui.GamesViewModel
import com.romcloud.app.ui.LaunchDialogs
import com.romcloud.app.ui.SystemsViewModel
import kotlinx.coroutines.launch

class TvActivity : ComponentActivity() {

    /** Langue choisie dans les Paramètres (indépendante de celle du système). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RomCloudApp
        setContent {
            TvTheme { TvRoot(app, this) }
        }
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
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = start) {
                composable("systems") {
                    val vm = viewModel { SystemsViewModel(app) }
                    TvSystemsScreen(
                        viewModel = vm,
                        storageWarning = storageWarning,
                        imageUrl = { app.api.systemImageUrl(it) },
                        mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                        onOpenSystem = { nav.navigate("games/$it") },
                        onOpenGame = { systemId, gameId -> nav.navigate("game/$systemId/$gameId") },
                        onOpenSettings = { nav.navigate("settings") },
                    )
                }
                composable(
                    "games/{systemId}",
                    arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
                ) { entry ->
                    val systemId = entry.arguments?.getString("systemId").orEmpty()
                    val vm = viewModel { GamesViewModel(app, systemId) }
                    TvGamesScreen(
                        viewModel = vm,
                        mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                        autoLaunch = app.autoLaunch,
                        onMessage = message,
                        onOpenGame = { nav.navigate("game/$systemId/$it") },
                    )
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
                    TvGameDetailScreen(
                        viewModel = vm,
                        mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                        onMessage = message,
                    )
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
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
        }
    }
}
