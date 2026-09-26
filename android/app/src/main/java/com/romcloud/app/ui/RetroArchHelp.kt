package com.romcloud.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.romcloud.app.RomCloudApp
import com.romcloud.app.launch.LaunchException
import com.romcloud.app.launch.RetroArchInfo

/** Guide de configuration de RetroArch, adapté au modèle choisi et à l'installation détectée. */
@Composable
fun RetroArchHelpDialog(info: RetroArchInfo, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val activity = LocalContext.current as Activity
    val launcher = (activity.application as RomCloudApp).launcher

    fun run(action: () -> Unit) = try {
        action()
    } catch (e: LaunchException) {
        onMessage(e.message ?: "Action impossible")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurer RetroArch") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val origin = when {
                    !info.installed -> "non installé"
                    info.fromPlayStore -> "version Play Store"
                    else -> "installé hors Play Store"
                }
                Text(
                    "${info.packageName} — $origin",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HelpStep(1, "Installer le bon cœur") {
                    if (info.core != null) {
                        Text("Ce modèle utilise le cœur :")
                        Code(info.core)
                        Text(
                            "Dans RetroArch : Menu principal → Charger un cœur → Télécharger un cœur, puis choisissez ce cœur. " +
                                "Le nom doit correspondre exactement ; sinon, choisissez un autre modèle RetroArch dans la liste des émulateurs.",
                        )
                    } else {
                        Text("Ce modèle ne précise pas de cœur : RetroArch utilisera celui associé à ce type de fichier.")
                    }
                }

                HelpStep(2, "Autoriser l’accès aux ROMs") {
                    if (info.usesSaf) {
                        Text(
                            "La version Play Store de RetroArch ne lit que les dossiers que vous lui autorisez. " +
                                "Dans RetroArch : Charger du contenu → ajoutez un dossier, sélectionnez le dossier des ROMs de RomCloud, " +
                                "puis « Utiliser ce dossier » → « Autoriser » :",
                        )
                        Code(info.romsDir)
                        Text(
                            "Autorisez ce dossier lui-même, pas seulement un dossier parent. " +
                                "RomCloud transmet alors les ROMs à RetroArch sous forme de chemin « saf:// ».",
                        )
                    } else {
                        Text(
                            "RetroArch doit pouvoir lire les fichiers de RomCloud : Paramètres Android → Applications → RetroArch → " +
                                "Autorisations → Fichiers → « Autoriser la gestion de tous les fichiers ». Dossier des ROMs :",
                        )
                        Code(info.romsDir)
                    }
                    Text(
                        "Mode réglable dans les Paramètres de RomCloud → « Accès aux ROMs pour RetroArch ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HelpStep(3, "Fermer RetroArch avant de jouer") {
                    if (info.mustCloseManually) {
                        Text(
                            "RetroArch ne peut pas démarrer un jeu s’il est encore ouvert en arrière-plan (écran noir), " +
                                "et depuis Android 14 RomCloud ne peut plus le fermer lui-même. " +
                                "Avant de lancer un jeu, utilisez « Forcer l’arrêt » ci-dessous si RetroArch a été ouvert.",
                        )
                    } else {
                        Text("RomCloud ferme automatiquement RetroArch avant chaque lancement.")
                    }
                    Text(
                        if (info.quitOnExit) {
                            "« Fermer RetroArch en quittant le jeu » est activé : RetroArch se ferme dès que vous le quittez, " +
                                "le lancement suivant fonctionne donc sans manipulation. Sauvegardez avant de quitter une partie."
                        } else {
                            "Conseil : activez « Fermer RetroArch en quittant le jeu » dans les Paramètres de RomCloud " +
                                "pour ne plus avoir à forcer l’arrêt."
                        },
                    )
                }

                HelpStep(4, "En cas d’écran noir") {
                    Text(
                        "Dans RetroArch : Réglages → Journalisation → activez « Verbosité » et « Journaliser dans un fichier », " +
                            "relancez le jeu depuis RomCloud puis consultez le journal. « Impossible de lire le fichier de contenu » " +
                            "indique un problème d’accès au dossier (étape 2) ; une erreur de cœur renvoie à l’étape 1.",
                    )
                    info.configFile?.let {
                        Text("Fichier de configuration utilisé par ce modèle :", style = MaterialTheme.typography.bodySmall)
                        Code(it)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            if (info.installed) {
                Row {
                    TextButton(onClick = { run { launcher.openApp(activity, info.packageName) } }) { Text("Ouvrir RetroArch") }
                    TextButton(onClick = { run { launcher.openAppSettings(activity, info.packageName) } }) { Text("Forcer l’arrêt") }
                }
            }
        },
    )
}

@Composable
private fun HelpStep(number: Int, title: String, content: @Composable () -> Unit) {
    Row {
        Text(
            "$number",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
