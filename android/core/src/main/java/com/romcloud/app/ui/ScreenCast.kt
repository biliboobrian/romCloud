package com.romcloud.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.romcloud.core.R

/**
 * Diffusion de l'écran sur un Chromecast. Une application ne peut pas recopier l'écran elle-même
 * sur un Chromecast (l'API Cast ne le permet plus) : RomCloud ouvre l'écran de diffusion d'Android,
 * Smart View (Samsung) ou Google Home, qui recopient tout l'écran et le son, jeux compris.
 */
object ScreenCast {
    private const val SMART_VIEW = "com.samsung.android.smartmirroring"
    private const val GOOGLE_HOME = "com.google.android.apps.chromecast.app"

    enum class Option(val label: Int) { SYSTEM(R.string.cast_system), SMART_VIEW(R.string.cast_smart_view), GOOGLE_HOME(R.string.cast_google_home), INSTALL_HOME(R.string.cast_install_home) }

    private fun intent(context: Context, option: Option): Intent? = when (option) {
        Option.SYSTEM -> Intent(Settings.ACTION_CAST_SETTINGS).takeIf { it.resolveActivity(context.packageManager) != null }
        Option.SMART_VIEW -> context.packageManager.getLaunchIntentForPackage(SMART_VIEW)
        Option.GOOGLE_HOME -> context.packageManager.getLaunchIntentForPackage(GOOGLE_HOME)
        Option.INSTALL_HOME -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_HOME"))
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_HOME"))
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Moyens de diffusion disponibles sur l'appareil, le plus direct en premier. */
    fun options(context: Context): List<Option> {
        val available = listOf(Option.SYSTEM, Option.SMART_VIEW, Option.GOOGLE_HOME).filter { intent(context, it) != null }
        return if (Option.GOOGLE_HOME in available) available else available + Option.INSTALL_HOME
    }

    fun open(context: Context, option: Option): Boolean {
        val intent = intent(context, option) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Appareil sur lequel l'écran est diffusé (route vidéo autre que l'écran du téléphone), sinon null. */
    fun activeRoute(context: Context): String? {
        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return null
        val route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO) ?: return null
        if (route == router.defaultRoute) return null
        return route.getName(context)?.toString()?.takeIf { it.isNotBlank() }
    }
}

/** Nom de l'appareil de diffusion en cours, mis à jour quand la diffusion commence ou s'arrête. */
@Composable
fun rememberCastRoute(): String? {
    val context = LocalContext.current
    var route by remember { mutableStateOf(ScreenCast.activeRoute(context)) }
    DisposableEffect(context) {
        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
        val callback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
                route = ScreenCast.activeRoute(context)
            }

            override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
                route = ScreenCast.activeRoute(context)
            }

            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
                route = ScreenCast.activeRoute(context)
            }
        }
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, callback)
        onDispose { router?.removeCallback(callback) }
    }
    return route
}

/** Bouton de la barre du haut : icône « diffusion en cours » pendant la recopie de l'écran. */
@Composable
fun CastButton() {
    var open by remember { mutableStateOf(false) }
    val route = rememberCastRoute()
    IconButton(onClick = { open = true }) {
        Icon(
            if (route != null) Icons.Filled.CastConnected else Icons.Filled.Cast,
            stringResource(R.string.cast_button),
            tint = if (route != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    if (open) CastDialog(onDismiss = { open = false })
}

/** Fenêtre de diffusion : explications et moyens disponibles (écran de diffusion d'Android, Smart View, Google Home). */
@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = remember { ScreenCast.options(context) }
    val route = rememberCastRoute()
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cast_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (route != null) stringResource(R.string.cast_active, route) else stringResource(R.string.cast_text))
                options.forEachIndexed { index, option ->
                    val onClick = {
                        if (ScreenCast.open(context, option)) onDismiss() else failed = true
                    }
                    if (index == 0) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(option.label)) }
                    else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(option.label)) }
                }
                if (failed) Text(stringResource(R.string.cast_unavailable), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.cast_quick_settings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
