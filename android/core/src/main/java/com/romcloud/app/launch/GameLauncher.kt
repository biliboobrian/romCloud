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
import com.romcloud.app.data.BiosFile
import com.romcloud.app.data.EmulatorReleases
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import com.romcloud.app.data.Settings
import com.romcloud.app.data.StateHistory
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

/**
 * Émulateur à installer, ou à mettre à jour ([installedVersion] : version installée, non nulle pour une
 * mise à jour : [GameLauncher.manualUpdatePackage]).
 */
data class MissingEmulator(val packageName: String, val playerName: String?, val installedVersion: String? = null)

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

/**
 * Reprise ou état de l'historique faits avec un autre cœur que l'émulateur choisi pour le système :
 * confirmation avant d'en faire l'émulateur du système ([target]), puis lancement avec ces options.
 */
data class CoreSwitchPrompt(
    val system: GameSystem,
    val game: Game,
    val target: Player,
    val currentName: String?,
    val resume: Boolean,
    val state: File?,
    val core: String?,
    val stream: StreamReceiver?,
    val streamMode: StreamMode,
    val missingBios: List<BiosFile> = emptyList(),
    val disk: Int? = null,
)

/** Options d'un lancement différé : jeu lancé ainsi à la fin de son téléchargement (ou de celui des BIOS). */
data class PendingLaunch(
    val resume: Boolean = false,
    val state: File? = null,
    val core: String? = null,
    val stream: StreamReceiver? = null,
    val streamMode: StreamMode = StreamMode.NATIVE,
    /** Jeu en plusieurs disques : disque de départ (0 pour le premier). */
    val disk: Int? = null,
)

/** BIOS du système absents de l'appareil : les télécharger avant de lancer le jeu ([launch]) ? */
data class BiosPrompt(
    val system: GameSystem,
    val game: Game,
    val missing: List<BiosFile>,
    val launch: PendingLaunch,
)

class GameLauncher(private val context: Context, private val settings: Settings) {

    private companion object {
        /** Installateur des applications du Google Play Store. */
        const val PLAY_STORE = "com.android.vending"
    }

    /** Émulateurs compatibles avec ce fichier (regex acceptedFilenameRegex du modèle). */
    /** Jeu PC ([folder]) : applications Winlator (RomCloud PC, Winlator Cmod…). */
    fun compatiblePlayers(system: GameSystem, fileName: String, folder: Boolean = false): List<Player> =
        if (folder) Winlator.PLAYERS else PlayerFilter.compatible(system.players, fileName)

    /**
     * L'émulateur choisi par l'utilisateur s'il est compatible, sinon le premier compatible (jeu PC :
     * le premier installé).
     */
    fun selectedPlayer(system: GameSystem, fileName: String, folder: Boolean = false): Player? {
        val compatible = compatiblePlayers(system, fileName, folder)
        val preferred = settings.preferredPlayer(system.id)
        return compatible.find { it.uniqueId == preferred }
            ?: compatible.takeIf { folder }?.firstOrNull(::isInstalled)
            ?: compatible.firstOrNull()
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

    /**
     * Paquet de l'émulateur externe installé que l'utilisateur doit mettre à jour lui-même : APK publié
     * sur un gestionnaire de versions ([EmulatorReleases.SOURCES], RomCloud PC, Winlator Cmod, Eden…)
     * et non installé par le Play Store (qui le met à jour seul) ; null sinon.
     */
    fun manualUpdatePackage(player: Player): String? {
        if (player.libretroCore != null) return null
        val pkg = packageOf(player)?.takeIf { it in EmulatorReleases.SOURCES && isPackageInstalled(it) } ?: return null
        return pkg.takeIf { installerOf(it) != PLAY_STORE }
    }

    /** Version installée du paquet (versionName), ou null. */
    fun installedVersion(pkg: String): String? =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    private fun installerOf(pkg: String): String? = runCatching {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /**
     * Ouvre l'émulateur externe seul, sans jeu (réglages, installation d'un jeu dans Vita3K…) :
     * son écran d'accueil, ou celui pour TV.
     */
    /** L'émulateur externe est-il installé et ouvrable seul (écran d'accueil) ? */
    fun canOpen(player: Player): Boolean {
        val pkg = packageOf(player)?.takeIf(::isPackageInstalled) ?: return false
        val pm = context.packageManager
        return (pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)) != null
    }

    fun openEmulator(activityContext: Context, player: Player) {
        val pkg = packageOf(player) ?: throw LaunchException(I18n.get(R.string.err_unknown_package))
        if (!isPackageInstalled(pkg)) throw MissingEmulatorException(MissingEmulator(pkg, player.name))
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: throw LaunchException(I18n.get(R.string.err_open_emulator, player.name))
        if (activityContext !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activityContext.startActivity(intent)
    }

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

    /**
     * Partie à reprendre : émulateur intégré choisi et état de reprise ou de l'historique pour ce jeu,
     * fait avec l'un des cœurs intégrés du système (pas forcément celui choisi).
     */
    fun canResume(system: GameSystem, file: File, player: Player?): Boolean = latestResume(system, file, player) != null

    /**
     * Partie à reprendre la plus récente parmi les cœurs intégrés du système (celui choisi d'abord) :
     * cœur et état. Un état fait avec Yabause se reprend avec lui même si Beetle Saturn est choisi.
     * [disk] : jeu en plusieurs disques, parmi les états faits sur ce disque (disque inconnu : le premier).
     */
    fun latestResume(system: GameSystem, file: File, player: Player?, disk: Int? = null): Pair<String, File>? {
        if (player?.libretroCore == null) return null
        val cores = (listOfNotNull(player.libretroCore) + system.players.mapNotNull { it.libretroCore }).distinct()
        var best: Triple<String, File, Long>? = null
        for (core in cores) {
            val (state, date) = history(core, file).resumePoint(LibretroActivity.stateFile(context, core, file), disk) ?: continue
            if (best == null || date > best.third) best = Triple(core, state, date)
        }
        return best?.let { it.first to it.second }
    }

    /**
     * Jeu en plusieurs disques ([count] disques, [file] : leur liste) : partie à reprendre sur chaque
     * disque, et disque de la partie la plus récente (null : aucune).
     */
    fun resumeDiscs(system: GameSystem, file: File, player: Player?, count: Int): Pair<List<Boolean>, Int?> {
        val resume = (0 until count).map { latestResume(system, file, player, it) != null }
        val latest = latestResume(system, file, player)?.let { (core, state) -> (diskOf(core, file, state) ?: 0).coerceAtMost(count - 1) }
        return resume to latest
    }

    /** Disque inséré quand l'état [state] du cœur [core] a été fait (jeu en plusieurs disques), null s'il n'est pas connu. */
    fun diskOf(core: String, file: File, state: File): Int? =
        history(core, file).diskOf(state, LibretroActivity.stateFile(context, core, file))

    private fun history(core: String, file: File) = StateHistory(StateHistory.dir(context, core, file), core)

    /**
     * Lance le jeu avec l'émulateur choisi ([resume] : reprend la partie sauvegardée, émulateur
     * intégré ; [state] : à partir de cet état de l'historique ; [stream] : diffusé sur cette TV du
     * profil, émulateur intégré, image [streamMode] ; [disk] : jeu en plusieurs disques (liste .m3u),
     * démarré sur ce disque et repris depuis la dernière partie faite sur lui ; [coreOptions] : options
     * par défaut du cœur intégré choisies sur le serveur, sous celles du joueur). Sans modèle d'émulateur
     * (système personnalisé), ouvre le sélecteur d'applications.
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
        disk: Int? = null,
        coreOptions: Map<String, String> = emptyMap(),
    ) {
        if (!file.isFile) throw LaunchException(I18n.get(R.string.err_file_not_found, file.absolutePath))

        // Partie à plusieurs : émulateur intégré seulement ; invité : cœur de l'hôte ; liaison : cœur de la liaison.
        // [core] : cœur imposé (état ou sauvegarde d'un autre cœur intégré du système).
        // Reprise sans cœur imposé : celui de la partie la plus récente (autre cœur intégré possible).
        val latest = if (resume && state == null && core == null && netplay == null) latestResume(system, file, player, disk) else null
        val libretroCore = core ?: latest?.first ?: if (netplay != null && (!netplay.host || netplay.link != null)) netplay.game.core else player?.libretroCore
        if (netplay != null && libretroCore == null) throw LaunchException(I18n.get(R.string.netplay_needs_builtin))

        // Émulateur intégré : le cœur est téléchargé si besoin par l'activité de jeu elle-même.
        libretroCore?.let { core ->
            // Reprise : état de l'historique s'il est plus récent que l'état de reprise (ou seul).
            val resumeState = latest?.second?.takeIf { it != LibretroActivity.stateFile(activityContext, core, file) }
            // Disque inséré au démarrage : celui de l'état chargé s'il est connu, sinon celui choisi.
            val startDisk = when {
                netplay != null -> null
                state != null -> diskOf(core, file, state)
                latest != null -> diskOf(core, file, latest.second) ?: disk
                else -> disk
            }
            val intent = LibretroActivity.intent(
                activityContext, system.id, core, file, settings.config.value.folders.bios(system).absolutePath, resume, gameId, stream, streamMode, netplay,
                state = state ?: resumeState, autoUploadStates = settings.autoUploadStates, disk = startDisk ?: 0,
                coreOptions = coreOptions,
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

        val intent = AmStartParser.parse(player.amStartArguments, placeholders(file, packageOf(player), player))
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

    private fun placeholders(file: File, packageName: String?, player: Player) = mapOf(
        "file.path" to file.absolutePath,
        "file.uri" to fileUri(file).toString(),
        "file.mime" to mimeOf(file),
        "file.name" to file.name,
        "file.nameWithoutExtension" to file.nameWithoutExtension,
        "file.extension" to file.extension,
        "file.dir" to (file.parent ?: ""),
        // Vita3K (modèles Daijishou « -r,{tags.vita_game_id} ») : jeu installé démarré par son identifiant.
        "tags.vita_game_id" to (if ("{tags.vita_game_id}" in player.amStartArguments) VitaGames.titleId(file) ?: "" else ""),
    )

    /** Commande de lancement avec les valeurs réelles, pour vérifier cœur et chemin de la ROM. */
    fun describe(player: Player, file: File): String {
        player.libretroCore?.let { core ->
            val status = if (LibretroCores(context).installed(core) != null) R.string.libretro_core_installed else R.string.libretro_core_on_demand
            return I18n.get(R.string.libretro_describe, core, I18n.get(status), file.absolutePath)
        }
        val values = runCatching { placeholders(file, packageOf(player), player) }
            .getOrElse { mapOf("file.path" to file.absolutePath) }
        return player.amStartArguments.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { AmStartParser.substitute(it, values) }
    }

    /**
     * Paquet à faire fermer par l'utilisateur avant le lancement, ou null.
     * Les modèles « killPackageProcesses » exigent que l'émulateur soit fermé ;
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
        // ignoré, ce qui laisse l'émulateur sur un écran noir.
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
