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

