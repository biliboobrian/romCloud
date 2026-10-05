package com.romcloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.romcloud.app.data.Account
import com.romcloud.core.R

/**
 * Profil du joueur (téléphone) : connexion ou création de compte ; une fois connecté, temps de
 * jeu, connexion d'une TV (scan de son QR code, ou saisie du code) et déconnexion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.message.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (account.signedIn) SignedIn(viewModel, account.username, account.playSeconds, account.gamesPlayed, account.offline)
                else SignInForm(viewModel)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPair,
            icon = { Icon(Icons.Filled.Tv, null) },
            title = { Text(stringResource(R.string.profile_pair_confirm_title)) },
            text = { Text(stringResource(R.string.profile_pair_confirm_text, confirm.device, account.username)) },
            confirmButton = { TextButton(onClick = viewModel::approvePair) { Text(stringResource(R.string.profile_pair_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelPair) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SignInForm(viewModel: ProfileViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (creating) viewModel.register(username, password, confirm) else viewModel.login(username, password)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !creating, onClick = { creating = false; viewModel.clearError() }, label = { Text(stringResource(R.string.profile_login)) })
        FilterChip(selected = creating, onClick = { creating = true; viewModel.clearError() }, label = { Text(stringResource(R.string.profile_register)) })
    }
    Text(
        stringResource(if (creating) R.string.profile_register_hint else R.string.profile_login_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.take(32) },
        label = { Text(stringResource(R.string.profile_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.profile_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (creating) ImeAction.Next else ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    if (creating) {
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.profile_confirm)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(onClick = submit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (creating) R.string.profile_create else R.string.profile_sign_in))
    }
}

@Composable
private fun SignedIn(viewModel: ProfileViewModel, username: String, playSeconds: Long, gamesPlayed: Int, offline: Boolean) {
    val context = LocalContext.current
    var typing by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Filled.AccountCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(username, style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    offline -> stringResource(R.string.profile_offline)
                    playSeconds > 0 -> stringResource(R.string.profile_stats, Account.formatDuration(playSeconds), gamesPlayed)
                    else -> stringResource(R.string.profile_no_play)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(stringResource(R.string.profile_cloud_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Tv, null)
                Text(stringResource(R.string.profile_pair_tv), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.profile_pair_tv_hint), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    // Lecteur des services Google Play : pas d'autorisation caméra pour l'application.
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode -> viewModel.checkCode(barcode.rawValue.orEmpty()) }
                        .addOnFailureListener {
                            typing = true
                            viewModel.message.value = context.getString(R.string.profile_scan_unavailable)
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QrCodeScanner, null)
                Text(stringResource(R.string.profile_scan_qr), Modifier.padding(start = 8.dp))
            }
            if (typing) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(12) },
                    label = { Text(stringResource(R.string.profile_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.checkCode(code) }, enabled = code.length >= 6, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_pair_confirm))
                }
            } else {
                OutlinedButton(onClick = { typing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Keyboard, null)
                    Text(stringResource(R.string.profile_enter_code), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.Logout, null)
        Text(stringResource(R.string.profile_logout), Modifier.padding(start = 8.dp))
    }
}
