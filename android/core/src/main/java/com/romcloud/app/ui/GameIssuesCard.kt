package com.romcloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.romcloud.app.launch.GameIssue
import com.romcloud.core.R

/** Problèmes qui empêchent de jouer avec l'émulateur externe choisi, dans la fiche du jeu. */
@Composable
fun GameIssuesCard(issues: List<GameIssue>, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.issues_title), style = MaterialTheme.typography.titleSmall)
            for (issue in issues) Text("• ${issue.message}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
