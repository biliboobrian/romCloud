@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.romcloud.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.romcloud.app.data.Account
import com.romcloud.app.ui.ProfileViewModel
import com.romcloud.core.R

/**
 * Profil du joueur (TV) : connexion par nom d'utilisateur et mot de passe, ou par QR code scanné
 * avec l'application téléphone déjà connectée (pas de création de compte à la télécommande) ;
 * une fois connecté, temps de jeu et déconnexion.
 */
@Composable
fun TvProfileScreen(viewModel: ProfileViewModel, onMessage: (String) -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            onMessage(it)
            viewModel.message.value = null
        }
    }
    // QR code affiché tant que la TV n'est pas connectée.
    LaunchedEffect(account.signedIn) { if (account.signedIn) viewModel.stopPairing() else viewModel.startPairing() }
    DisposableEffect(Unit) { onDispose { viewModel.stopPairing() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TvSafePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (account.signedIn) {
            SignedIn(viewModel, account.username, account.playSeconds, account.gamesPlayed, account.offline)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                LoginForm(viewModel, Modifier.weight(1f))
                QrPanel(state.pair?.qrContent, state.pair?.code, state.pairError, Modifier.weight(1f))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LoginForm(viewModel: ProfileViewModel, modifier: Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Focus initial sur le bouton (pas sur un champ : le clavier masquerait le QR code).
    val submit = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { submit.requestFocus() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.profile_login), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.profile_login_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = username, onValueChange = { username = it.take(32) },
            label = { androidx.compose.material3.Text(stringResource(R.string.profile_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { androidx.compose.material3.Text(stringResource(R.string.profile_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.login(username, password) }, modifier = Modifier.focusRequester(submit)) {
            Text(stringResource(R.string.profile_sign_in))
        }
    }
}

/** QR code de connexion (à scanner avec le téléphone) et son code en clair. */
@Composable
private fun QrPanel(content: String?, code: String?, error: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tv_profile_qr_title), style = MaterialTheme.typography.headlineSmall)
        Box(
            Modifier.size(260.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(content) { content?.let { qrBitmap(it) } }
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else if (error == null) {
                Text("…", color = Color.Black)
            }
        }
        if (error != null) {
            Text(stringResource(R.string.tv_profile_qr_error, error), color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.tv_profile_qr_text), style = MaterialTheme.typography.bodyMedium)
            code?.let {
                Text(
                    stringResource(R.string.tv_profile_qr_code, it.chunked(4).joinToString(" ")),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SignedIn(viewModel: ProfileViewModel, username: String, playSeconds: Long, gamesPlayed: Int, offline: Boolean) {
    val logout = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { logout.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.AccountCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(username, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    offline -> stringResource(R.string.profile_offline)
                    playSeconds > 0 -> stringResource(R.string.profile_stats, Account.formatDuration(playSeconds), gamesPlayed)
                    else -> stringResource(R.string.profile_no_play)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(stringResource(R.string.profile_cloud_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(0.7f))
    OutlinedButton(onClick = viewModel::logout, modifier = Modifier.focusRequester(logout)) {
        Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = IconSize)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.profile_logout))
    }
}

/** QR code (noir sur blanc, sans marge : le cadre blanc l'entoure). */
private fun qrBitmap(content: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bitmap.asImageBitmap()
}.getOrNull()
