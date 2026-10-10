package com.romcloud.app.launch

import com.romcloud.app.I18n
import com.romcloud.core.R
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
import com.romcloud.app.data.RetroArchSafMode
import com.romcloud.app.data.Settings
import com.romcloud.app.data.StreamReceiver
import com.romcloud.app.stream.StreamMode
import com.romcloud.app.libretro.LibretroActivity
import com.romcloud.app.libretro.LibretroCores
import com.romcloud.app.netplay.NetplayLaunch
import java.io.File

open class LaunchException(message: String) : Exception(message)

/** L'émulateur requis n'est pas installé : on propose de l'installer depuis le Play Store. */
class MissingEmulatorException(val emulator: MissingEmulator) :
    LaunchException(I18n.get(R.string.err_emulator_not_installed, emulator.packageName))

data class MissingEmulator(val packageName: String, val playerName: String?)

/** Ce qu'il faut configurer dans RetroArch pour un modèle donné (guide « ⓘ » de la fiche du jeu). */
data class RetroArchInfo(
    val packageName: String,
    val installed: Boolean,
    /** Nom du cœur demandé par le modèle (ex. mupen64plus_next_gles3), null s'il n'en impose pas. */
    val core: String?,
    val configFile: String?,
    val fromPlayStore: Boolean,
    /** La ROM est transmise en chemin saf:// (dossier à autoriser dans RetroArch). */
    val usesSaf: Boolean,
    val romsDir: String,
    val quitOnExit: Boolean,
    /** Android 14+ : RomCloud ne peut pas fermer RetroArch lui-même. */
    val mustCloseManually: Boolean,
)

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

    private companion object {
        /** Extra lu par RetroActivityFuture : RetroArch appelle System.exit(0) dans onStop(). */
        const val RETROARCH_QUIT_EXTRA = "QUITFOCUS"
    }

    /** Émulateurs compatibles avec ce fichier (regex acceptedFilenameRegex du modèle). */
    fun compatiblePlayers(system: GameSystem, fileName: String): List<Player> =
        PlayerFilter.compatible(system.players, fileName)

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
        throw LaunchException(I18n.get(R.string.err_open_play_store))
    }

    /** Partie à reprendre : émulateur intégré et état sauvegardé pour ce jeu et ce cœur. */
    fun canResume(file: File, player: Player?): Boolean {
        val core = player?.libretroCore ?: return false
        return LibretroActivity.stateFile(context, core, file).isFile
    }

    /**
     * Lance le jeu avec l'émulateur choisi ([resume] : reprend la partie sauvegardée, émulateur
     * intégré ; [state] : à partir de cet état de l'historique ; [stream] : diffusé sur cette TV du
     * profil, émulateur intégré, image [streamMode]). Sans modèle d'émulateur (système personnalisé), ouvre le sélecteur d'applications.
     */
    fun launch(
        activityContext: Context,
        system: GameSystem,
        file: File,
        player: Player?,
        resume: Boolean = false,
        gameId: Long = 0,
        stream: StreamReceiver? = null,
        streamMode: StreamMode = StreamMode.NATIVE,
        netplay: NetplayLaunch? = null,
        state: File? = null,
        core: String? = null,
    ) {
        if (!file.isFile) throw LaunchException(I18n.get(R.string.err_file_not_found, file.absolutePath))

        // Partie à plusieurs : émulateur intégré seulement ; invité : cœur de l'hôte ; liaison : cœur de la liaison.
        // [core] : cœur imposé (état ou sauvegarde d'un autre cœur intégré du système).
        val libretroCore = core ?: if (netplay != null && (!netplay.host || netplay.link != null)) netplay.game.core else player?.libretroCore
        if (netplay != null && libretroCore == null) throw LaunchException(I18n.get(R.string.netplay_needs_builtin))

        // Émulateur intégré : le cœur est téléchargé si besoin par l'activité de jeu elle-même.
        libretroCore?.let { core ->
            val intent = LibretroActivity.intent(
                activityContext, system.id, core, file, settings.config.value.biosDir, resume, gameId, stream, streamMode, netplay,
                state = state, autoUploadStates = settings.autoUploadStates,
            )
            if (activityContext !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activityContext.startActivity(intent)
            return
        }

        val uri = fileUri(file)

        if (player == null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            start(activityContext, Intent.createChooser(view, I18n.get(R.string.chooser_open_with, file.name)), null)
            return
        }

        val intent = AmStartParser.parse(player.amStartArguments, placeholders(file, packageOf(player)))
        if (quitOnExit(intent.component?.packageName)) intent.putExtra(RETROARCH_QUIT_EXTRA, "1")
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

    private fun placeholders(file: File, packageName: String?) = mapOf(
        "file.path" to (retroArchSafPath(file, packageName) ?: file.absolutePath),
        "file.uri" to fileUri(file).toString(),
        "file.mime" to mimeOf(file),
        "file.name" to file.name,
        "file.nameWithoutExtension" to file.nameWithoutExtension,
        "file.extension" to file.extension,
        "file.dir" to (file.parent ?: ""),
    )

    /** Commande de lancement avec les valeurs réelles, pour vérifier cœur et chemin de la ROM. */
    fun describe(player: Player, file: File): String {
        player.libretroCore?.let { core ->
            val status = if (LibretroCores(context).installed(core) != null) R.string.libretro_core_installed else R.string.libretro_core_on_demand
            return I18n.get(R.string.libretro_describe, core, I18n.get(status), file.absolutePath)
        }
        val values = runCatching { placeholders(file, packageOf(player)) }
            .getOrElse { mapOf("file.path" to file.absolutePath) }
        val lines = player.amStartArguments.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { AmStartParser.substitute(it, values) }
        val extra = if (quitOnExit(packageOf(player))) listOf("-e $RETROARCH_QUIT_EXTRA 1") else emptyList()
        return (lines + extra).joinToString("\n")
    }

    fun isRetroArch(packageName: String?) = packageName?.startsWith("com.retroarch") == true

    /** Configuration RetroArch attendue par ce modèle, ou null si ce n'est pas RetroArch. */
    fun retroArchInfo(player: Player): RetroArchInfo? {
        val pkg = packageOf(player)?.takeIf(::isRetroArch) ?: return null
        val installed = isPackageInstalled(pkg)
        val fromPlayStore = installed && isFromPlayStore(pkg)
        return RetroArchInfo(
            packageName = pkg,
            installed = installed,
            core = LibretroPlayers.coreOf(player),
            configFile = AmStartParser.stringExtra(player.amStartArguments, "CONFIGFILE"),
            fromPlayStore = fromPlayStore,
            usesSaf = when (settings.retroArchSafMode) {
                RetroArchSafMode.SAF -> true
                RetroArchSafMode.PATH -> false
                RetroArchSafMode.AUTO -> fromPlayStore
            },
            romsDir = settings.config.value.romsDir,
            quitOnExit = settings.retroArchQuitOnExit,
            mustCloseManually = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
    }

    /** Ouvre RetroArch (pour installer un cœur, autoriser un dossier…). */
    fun openApp(activityContext: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw LaunchException(I18n.get(R.string.err_cannot_open, packageName))
        activityContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** RetroArch installé depuis le Play Store (sans accès à tous les fichiers). */
    fun isFromPlayStore(packageName: String): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    /**
     * Chemin saf:// pour RetroArch quand il ne peut pas lire le chemin de fichier (version
     * Play Store) ; null pour garder le chemin classique.
     */
    private fun retroArchSafPath(file: File, packageName: String?): String? {
        if (!isRetroArch(packageName)) return null
        val useSaf = when (settings.retroArchSafMode) {
            RetroArchSafMode.SAF -> true
            RetroArchSafMode.PATH -> false
            RetroArchSafMode.AUTO -> isFromPlayStore(packageName!!)
        }
        return if (useSaf) RetroArchSaf.path(settings.config.value.romsDir, file.absolutePath) else null
    }

    /** RetroArch (tous paquets com.retroarch*) avec l'option « se fermer en quittant le jeu ». */
    private fun quitOnExit(packageName: String?) =
        settings.retroArchQuitOnExit && isRetroArch(packageName)

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
            .onFailure { throw LaunchException(I18n.get(R.string.err_cannot_open_settings, packageName)) }
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
            throw LaunchException(I18n.get(R.string.err_no_app))
        } catch (e: SecurityException) {
            throw LaunchException(I18n.get(R.string.err_launch_refused, e.message.orEmpty()))
        }
    }
}
