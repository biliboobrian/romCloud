package com.romcloud.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romcloud.app.RomCloudApp
import com.romcloud.app.netplay.Peer
import com.romcloud.app.netplay.Together
import com.romcloud.core.R
import kotlinx.coroutines.launch

/** Icône d'une façon de jouer à plusieurs : jeu synchronisé (joueurs) ou consoles reliées (maillon). */
fun togetherIcon(kind: Together) = if (kind == Together.LINK) Icons.Filled.Link else Icons.Filled.Groups

/**
 * Icône « jeu à plusieurs » : jeu jouable à plusieurs avec un appareil RomCloud du réseau local
 * ([Together.NETPLAY]) ou console qui peut se relier à la sienne ([Together.LINK], autre icône et couleur).
 */
@Composable
fun TogetherBadge(kind: Together, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Icon(
        togetherIcon(kind),
        contentDescription = stringResource(if (kind == Together.LINK) R.string.link_badge else R.string.netplay_badge),
        tint = if (kind == Together.LINK) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        modifier = modifier.size(size),
    )
}

/** Plateforme d'un appareil, pour la liste (« téléphone », « TV », « PC »). */
@Composable
private fun platformLabel(platform: String): String = stringResource(
    when (platform) {
        "androidtv" -> R.string.netplay_platform_tv
        "windows" -> R.string.netplay_platform_pc
        else -> R.string.netplay_platform_phone
    },
)

/**
 * Appareils RomCloud du réseau local et parties qu'ils proposent : « Rejoindre » lance le même jeu
 * avec le cœur de l'hôte (téléchargé d'abord s'il manque).
 */
@Composable
fun NetplayDialog(app: RomCloudApp, peers: List<Peer>, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.netplay_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.netplay_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (peers.isEmpty()) Text(stringResource(R.string.netplay_nobody))
                peers.forEach { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            val via = if (peer.internet) " · ${stringResource(R.string.netplay_via_internet)}" else ""
                            Text("${peer.displayName} · ${platformLabel(peer.platform)}$via", style = MaterialTheme.typography.titleSmall)
                            val hosting = peer.hosting
                            Text(
                                when {
                                    hosting?.link != null -> stringResource(R.string.link_hosting_game, hosting.title)
                                    hosting != null -> stringResource(R.string.netplay_hosting_game, hosting.title)
                                    peer.busy -> stringResource(R.string.netplay_peer_busy)
                                    else -> stringResource(R.string.netplay_available)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (peer.hosting != null) {
                            Button(
                                enabled = joining == null,
                                onClick = {
                                    joining = peer.id
                                    scope.launch {
                                        val message = app.joinNetplay(context as? Activity ?: context, peer)
                                        joining = null
                                        message?.let(onMessage)
                                        onDismiss()
                                    }
                                },
                            ) { Text(stringResource(if (peer.hosting?.link != null) R.string.link_join_short else R.string.netplay_join)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
