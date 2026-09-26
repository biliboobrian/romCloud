package com.romcloud.app.launch

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import com.romcloud.app.data.Settings
import java.io.File

class LaunchException(message: String) : Exception(message)

class GameLauncher(private val context: Context, private val settings: Settings) {

    /** Émulateurs compatibles avec ce fichier (regex acceptedFilenameRegex du modèle). */
    fun compatiblePlayers(system: GameSystem, fileName: String): List<Player> =
        system.players.filter { player ->
            val regex = player.acceptedFilenameRegex ?: return@filter true
            runCatching { Regex(regex).matches(fileName) }.getOrDefault(true)
        }

    /** L'émulateur choisi par l'utilisateur s'il est compatible, sinon le premier compatible. */
    fun selectedPlayer(system: GameSystem, fileName: String): Player? {
        val compatible = compatiblePlayers(system, fileName)
        val preferred = settings.preferredPlayer(system.id)
        return compatible.find { it.uniqueId == preferred } ?: compatible.firstOrNull()
    }

    fun isInstalled(player: Player): Boolean {
        val pkg = AmStartParser.parse(player.amStartArguments, emptyMap()).component?.packageName ?: return true
        return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * Lance le jeu avec l'émulateur choisi. Sans modèle d'émulateur (système personnalisé),
     * ouvre le sélecteur d'applications Android.
     */
    fun launch(activityContext: Context, system: GameSystem, file: File, player: Player?) {
        if (!file.isFile) throw LaunchException("Fichier introuvable : ${file.absolutePath}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

        if (player == null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            start(activityContext, Intent.createChooser(view, "Ouvrir ${file.name} avec…"), null)
            return
        }

        val values = mapOf(
            "file.path" to file.absolutePath,
            "file.uri" to uri.toString(),
            "file.mime" to mime,
            "file.name" to file.name,
            "file.nameWithoutExtension" to file.nameWithoutExtension,
            "file.extension" to file.extension,
            "file.dir" to (file.parent ?: ""),
        )
        val intent = AmStartParser.parse(player.amStartArguments, values)
        if (player.amStartArguments.contains("{file.uri}")) {
            // Autorise l'émulateur à lire le fichier via le FileProvider.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(file.name, uri)
        }
        if (player.killPackageProcesses) {
            intent.component?.packageName?.let { pkg ->
                runCatching {
                    context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
                }
            }
        }
        start(activityContext, intent, player)
    }

    private fun start(activityContext: Context, intent: Intent, player: Player?) {
        if (activityContext !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activityContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val pkg = intent.component?.packageName
            throw LaunchException(
                if (pkg != null) "Émulateur non installé : $pkg (${player?.name})"
                else "Aucune application ne peut ouvrir ce fichier",
            )
        } catch (e: SecurityException) {
            throw LaunchException("Lancement refusé par l’émulateur : ${e.message}")
        }
    }
}
