package com.romcloud.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.launch.LaunchException
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

    // Émulateur absent : proposer de l'installer depuis le Google Play Store.
    val missing by app.missingEmulator.collectAsStateWithLifecycle()
    missing?.let { emulator ->
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { app.missingEmulator.value = null },
            title = { Text("Émulateur non installé") },
            text = {
                Text(
                    "Pour lancer ce jeu, installez l’émulateur « ${emulator.playerName ?: emulator.packageName} » " +
                        "(${emulator.packageName}).\n\nVoulez-vous ouvrir sa page sur le Google Play Store ?\n\n" +
                        "Vous pouvez aussi choisir un autre émulateur dans la fiche du jeu (appui long).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.missingEmulator.value = null
                    try {
                        app.launcher.openStore(activity, emulator.packageName)
                    } catch (e: LaunchException) {
                        scope.launch { snackbar.showSnackbar(e.message ?: "Play Store indisponible") }
                    }
                }) { Text("Ouvrir le Play Store") }
            },
            dismissButton = { TextButton(onClick = { app.missingEmulator.value = null }) { Text("Annuler") } },
        )
    }

    NavHost(navController = nav, startDestination = start) {
        composable("systems") {
            val vm = viewModel { SystemsViewModel(app) }
            SystemsScreen(
                viewModel = vm,
                storageWarning = storageWarning,
                imageUrl = { app.api.systemImageUrl(it) },
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
                mediaUrl = { game, type -> app.api.mediaUrl(game, type) },
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
