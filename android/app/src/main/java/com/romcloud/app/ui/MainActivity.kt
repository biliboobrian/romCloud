package com.romcloud.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.ui.theme.RomCloudTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RomCloudApp
        setContent {
            RomCloudTheme { RomCloudNavHost(app, this) }
        }
    }
}

@Composable
private fun RomCloudNavHost(app: RomCloudApp, activity: ComponentActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val start = remember { if (app.settings.config.value.isConfigured) "systems" else "settings" }

    // Notifications Android 13+ (progression des téléchargements).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Fin de téléchargement : lancement automatique si demandé, sinon proposition « Jouer ».
    LaunchedEffect(Unit) {
        app.downloader.events.collect { event ->
            when (event) {
                is DownloadEvent.Completed -> {
                    val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (app.autoLaunch.remove(event.game.id) && resumed) {
                        app.play(activity, event.system, event.game)?.let { snackbar.showSnackbar(it) }
                    } else {
                        launch {
                            val result = snackbar.showSnackbar(
                                "« ${event.game.title} » est téléchargé",
                                actionLabel = "Jouer",
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                app.play(activity, event.system, event.game)?.let { snackbar.showSnackbar(it) }
                            }
                        }
                    }
                }
                is DownloadEvent.Failed -> {
                    app.autoLaunch.remove(event.game.id)
                    launch { snackbar.showSnackbar("Échec du téléchargement de « ${event.game.title} » : ${event.message}") }
                }
            }
        }
    }

    var storageWarning by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            storageWarning = !app.library.hasStoragePermission() && !app.library.isAppPrivateDir()
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable("systems") {
            val vm = viewModel { SystemsViewModel(app) }
            SystemsScreen(
                viewModel = vm,
                storageWarning = storageWarning,
                onOpenSystem = { nav.navigate("games/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "games/{systemId}",
            arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
        ) { entry ->
            val systemId = entry.arguments?.getString("systemId").orEmpty()
            val vm = viewModel { GamesViewModel(app, systemId) }
            GamesScreen(
                viewModel = vm,
                mediaUrl = { app.api.mediaUrl(it, "boxart") },
                snackbar = snackbar,
                autoLaunch = app.autoLaunch,
                onBack = { nav.popBackStack() },
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
            GameDetailScreen(
                viewModel = vm,
                mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
            )
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
}
