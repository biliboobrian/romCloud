package com.romcloud.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.romcloud.app.ui.StorageContent
import com.romcloud.app.ui.StorageViewModel
import com.romcloud.core.R

/** Page du stockage (TV) : ouverte par la barre du disque de l'écran des systèmes. */
@Composable
fun TvStorageScreen(viewModel: StorageViewModel, onMessage: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TvSafePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        StorageContent(viewModel, state, onMessage = onMessage, modifier = Modifier.padding(top = 4.dp))
    }
}
