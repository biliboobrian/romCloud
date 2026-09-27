package com.romcloud.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
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
                // Barres masquées : seul l'encoche de la caméra reste à éviter.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.displayCutout),
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
