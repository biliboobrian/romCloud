package com.romcloud.app.launch

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import com.romcloud.app.data.Settings
import java.io.File

open class LaunchException(message: String) : Exception(message)

/** L'émulateur requis n'est pas installé : on propose de l'installer depuis le Play Store. */
class MissingEmulatorException(val emulator: MissingEmulator) :
    LaunchException("Émulateur non installé : ${emulator.packageName}")

data class MissingEmulator(val packageName: String, val playerName: String?)

/**
 * Lancement en attente : l'émulateur doit être fermé à la main avant (Android 14+ interdit
 * à RomCloud de le faire), sinon il reste sur un écran noir.
 */
data class CloseEmulatorPrompt(
    val system: GameSystem,
    val game: Game,
    val packageName: String,
    val playerName: String,
)

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

    /** Paquet Android de l'émulateur (option -n ou -p du modèle). */
    fun packageOf(player: Player): String? {
        val intent = AmStartParser.parse(player.amStartArguments, emptyMap())
        return intent.component?.packageName ?: intent.`package`
    }

    fun isInstalled(player: Player): Boolean {
        val pkg = packageOf(player) ?: return true
        return isPackageInstalled(pkg)
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Ouvre la fiche Google Play de l'application (app Play Store, sinon navigateur). */
    fun openStore(activityContext: Context, packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        for (intent in listOf(market, web)) {
            if (activityContext !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activityContext.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // essaie l'intent suivant
            }
        }
        throw LaunchException("Impossible d’ouvrir le Play Store")
    }

    /**
     * Lance le jeu avec l'émulateur choisi. Sans modèle d'émulateur (système personnalisé),
     * ouvre le sélecteur d'applications Android.
     */
    fun launch(activityContext: Context, system: GameSystem, file: File, player: Player?) {
        if (!file.isFile) throw LaunchException("Fichier introuvable : ${file.absolutePath}")
        val uri = fileUri(file)

        if (player == null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            start(activityContext, Intent.createChooser(view, "Ouvrir ${file.name} avec…"), null)
            return
        }

        val intent = AmStartParser.parse(player.amStartArguments, placeholders(file))
        if (player.amStartArguments.contains("{file.uri}")) {
            // Autorise l'émulateur à lire le fichier via le FileProvider.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(file.name, uri)
        }
        intent.component?.packageName?.let { pkg ->
            if (!isPackageInstalled(pkg)) throw MissingEmulatorException(MissingEmulator(pkg, player.name))
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

    private fun fileUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun mimeOf(file: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun placeholders(file: File) = mapOf(
        "file.path" to file.absolutePath,
        "file.uri" to fileUri(file).toString(),
        "file.mime" to mimeOf(file),
        "file.name" to file.name,
        "file.nameWithoutExtension" to file.nameWithoutExtension,
        "file.extension" to file.extension,
        "file.dir" to (file.parent ?: ""),
    )

    /** Commande de lancement avec les valeurs réelles, pour vérifier cœur et chemin de la ROM. */
    fun describe(player: Player, file: File): String {
        val values = runCatching { placeholders(file) }.getOrElse { mapOf("file.path" to file.absolutePath) }
        return player.amStartArguments.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { AmStartParser.substitute(it, values) }
    }

    /**
     * Paquet à faire fermer par l'utilisateur avant le lancement, ou null.
     * Les modèles « killPackageProcesses » (RetroArch…) exigent que l'émulateur soit fermé ;
     * depuis Android 14, killBackgroundProcesses() n'a plus d'effet sur les autres applications.
     */
    fun closeWarningPackage(player: Player?): String? {
        if (player == null || !player.killPackageProcesses) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val pkg = packageOf(player) ?: return null
        return pkg.takeUnless { settings.isCloseWarningDismissed(it) }
    }

    /** Ouvre la page « Infos sur l'application » de l'émulateur (bouton « Forcer l'arrêt »). */
    fun openAppSettings(activityContext: Context, packageName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activityContext.startActivity(intent) }
            .onFailure { throw LaunchException("Impossible d’ouvrir les paramètres de $packageName") }
    }

    private fun start(activityContext: Context, intent: Intent, player: Player?) {
        // Comme « am start » (utilisé par Daijishou) : toujours une nouvelle tâche. Sans ce
        // drapeau, l'émulateur s'ouvre dans la tâche de RomCloud et --activity-clear-task est
        // ignoré, ce qui laisse RetroArch sur un écran noir.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activityContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val pkg = intent.component?.packageName
            if (pkg != null) throw MissingEmulatorException(MissingEmulator(pkg, player?.name))
            throw LaunchException("Aucune application ne peut ouvrir ce fichier")
        } catch (e: SecurityException) {
            throw LaunchException("Lancement refusé par l’émulateur : ${e.message}")
        }
    }
}
