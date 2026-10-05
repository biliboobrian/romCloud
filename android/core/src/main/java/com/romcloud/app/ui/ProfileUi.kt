package com.romcloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.Account
import com.romcloud.app.data.AccountState
import com.romcloud.app.data.OnlineSave
import com.romcloud.core.R
import java.text.DateFormat
import java.util.Date

/** Temps de jeu du profil connecté pour ce jeu (secondes ; 0 sans profil ou sans partie). */
@Composable
fun gamePlaytime(gameId: Long): Long {
    val app = LocalContext.current.applicationContext as RomCloudApp
    val account by app.account.state.collectAsStateWithLifecycle()
    val playtime by app.account.playtime.collectAsStateWithLifecycle()
    return if (account.signedIn) playtime[gameId] ?: 0 else 0
}

/** Profil du joueur (nom affiché sur le bouton du profil de la TV). */
@Composable
fun accountState(): AccountState {
    val app = LocalContext.current.applicationContext as RomCloudApp
    return app.account.state.collectAsStateWithLifecycle().value
}

/** Profil connecté (icône du profil colorée dans la barre du haut). */
@Composable
fun isSignedIn(): Boolean {
    val app = LocalContext.current.applicationContext as RomCloudApp
    return app.account.state.collectAsStateWithLifecycle().value.signedIn
}

/** « 3 h 05 de jeu » (bannière, fiche du jeu) ; rien sans partie. [color] : couleur du texte (TV). */
@Composable
fun PlaytimeLabel(seconds: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (seconds <= 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Timer, null, Modifier.size(16.dp), tint = color)
        Text(
            stringResource(R.string.profile_playtime, Account.formatDuration(seconds)),
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** « Sauvegarde en ligne : appareil, date » (fiche du jeu). */
@Composable
fun OnlineSaveLabel(save: OnlineSave, modifier: Modifier = Modifier, color: Color = LocalContentColor.current.copy(alpha = 0.75f)) {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(save.savedAtMillis))
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.CloudDone, null, Modifier.size(16.dp), tint = color)
        Text(stringResource(R.string.profile_online_save, save.device ?: "?", date), color = color, style = MaterialTheme.typography.bodySmall)
    }
}
