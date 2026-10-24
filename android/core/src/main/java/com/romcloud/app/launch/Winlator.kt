package com.romcloud.app.launch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import com.romcloud.app.I18n
import com.romcloud.app.data.Player
import com.romcloud.app.data.Settings
import com.romcloud.core.R
import java.io.File
import java.util.Locale

/**
 * Jeux PC (jeux en dossiers) lancés par Winlator (Wine / Proton et Box64). De préférence par
 * RomCloud PC ([COMPANION], android/pc : moteur de Winlator Cmod repris dans RomCloud, sans écrans),
 * qui prépare lui-même le système, le conteneur et ses lecteurs ; sinon par Winlator Cmod (ou
 * Ludashi) : raccourci « .desktop » écrit dans le dossier du jeu, ouvert par son activité de jeu
 * (extra « shortcut_path »). La version officielle de Winlator ne permet pas ce lancement.
 *
 * Wine ne voit que les lecteurs du conteneur : D: est le dossier Téléchargements (réglage par
 * défaut de Winlator). Un dossier des ROMs ailleurs est lu par le lecteur [ROMS_DRIVE], ajouté au
 * conteneur par RomCloud ([WinlatorDrives]) ou, à défaut, à la main (vers le dossier des ROMs).
 */
object Winlator {

    /** RomCloud PC (application compagnon de RomCloud) : paquet. */
    const val COMPANION = "com.romcloud.wine"

    /** Demande de lancement à RomCloud PC (lancé pour un résultat : il vérifie la signature de l'appelant). */
    const val COMPANION_REQUEST = 0x5743

    /** Applications Winlator lançables : paquet et activité (RomCloud PC d'abord, proposé à l'installation). */
    val APPS = listOf(
        COMPANION to "com.romcloud.wine.LaunchActivity",
        "com.winlator.cmod" to "com.winlator.cmod.XServerDisplayActivity",
        "com.ludashi.benchmark" to "com.winlator.cmod.XServerDisplayActivity",
    )

    /** Nom affiché de l'application proposée à l'installation. */
    const val COMPANION_NAME = "RomCloud PC"

    /** Nom affiché de chaque application de [APPS]. */
    fun appName(pkg: String): String = when (pkg) {
        COMPANION -> COMPANION_NAME
        "com.winlator.cmod" -> "Winlator Cmod"
        else -> "Winlator Cmod (Ludashi)"
    }

    /** Émulateurs d'un jeu PC dans la fiche du jeu : applications de [APPS], RomCloud PC d'abord. */
    val PLAYERS: List<Player> = APPS.map { (pkg, activity) ->
        Player(name = appName(pkg), uniqueId = "winlator:$pkg", amStartArguments = "-n $pkg/$activity")
    }

    /** Lecteur du dossier des ROMs dans le conteneur, quand il n'est pas dans Téléchargements. */
    const val ROMS_DRIVE = 'R'

    /** Conteneur de Winlator utilisé (le premier créé). */
    const val CONTAINER_ID = 1

    /**
     * Chemin Windows de [file] dans le conteneur : lecteur D: (dossier [downloads]) s'il y est, sinon
     * lecteur [ROMS_DRIVE] (dossier des ROMs [romsRoot]) ; second élément : lecteur à ajouter.
     */
    fun winePath(file: File, downloads: File, romsRoot: File): Pair<String, Boolean> {
        fun relative(base: File): String? {
            val root = base.normalize().invariantSeparatorsPath.trimEnd('/')
            val path = file.normalize().invariantSeparatorsPath
            return if (path.startsWith("$root/")) path.removePrefix("$root/") else null
        }
        relative(downloads)?.let { return "D:\\" + it.replace('/', '\\') to false }
        val inRoms = relative(romsRoot) ?: file.name
        return "$ROMS_DRIVE:\\" + inRoms.replace('/', '\\') to true
    }

    /** Le jeu [exe] du dossier [gameDir] se lance-t-il depuis le lecteur des ROMs ([ROMS_DRIVE]) ? */
    fun needsRomsDrive(gameDir: File, exe: String, romsRoot: File): Boolean =
        winePath(File(gameDir, exe), downloadsDir(), romsRoot).second

    @Suppress("DEPRECATION")
    private fun downloadsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Configuration du conteneur [containerId] (JSON, clé « drives »), relative au dossier de données de Winlator. */
    fun containerConfigPath(containerId: Int = CONTAINER_ID): String = "files/imagefs/home/xuser-$containerId/.container"

    /** Lecteurs par défaut d'un conteneur de Winlator [pkg] (Container.DEFAULT_DRIVES), sans clé « drives ». */
    fun defaultDrives(pkg: String): String = "D:${downloadsDir().absolutePath}E:/data/data/$pkg/storage"

    /**
     * Lecteurs d'un conteneur, lus comme Container.drivesIterator de Winlator : « D:/chemin E:/chemin »
     * sans séparateur (la lettre d'un lecteur est le caractère qui précède ses deux-points).
     */
    fun parseDrives(drives: String): List<Pair<Char, String>> {
        val result = mutableListOf<Pair<Char, String>>()
        var index = drives.indexOf(':')
        while (index > 0) {
            val next = drives.indexOf(':', index + 1)
            result += drives[index - 1] to drives.substring(index + 1, if (next != -1) next - 1 else drives.length)
            index = next
        }
        return result
    }

    /** Lecteurs [drives] avec [letter] vers [path] (remplacé s'il existait déjà, sinon ajouté). */
    fun withDrive(drives: String, letter: Char, path: String): String =
        (parseDrives(drives).filterNot { it.first.equals(letter, ignoreCase = true) } + (letter to path))
            .joinToString("") { (l, p) -> "$l:$p" }

    /**
     * Raccourci Winlator : Exec au format des raccourcis de Wine (« \\\\ » pour « \ », « \  » pour
     * une espace : relu par StringUtils.unescape de Winlator), conteneur [containerId] ; [settings] :
     * réglages du raccourci, qui remplacent ceux du conteneur pour ce jeu (voir [gameSettings]).
     */
    fun desktopEntry(title: String, winePath: String, settings: Map<String, String> = emptyMap(), containerId: Int = CONTAINER_ID): String {
        val escaped = winePath.replace("\\", "\\\\\\\\").replace(" ", "\\ ")
        val exeName = winePath.substringAfterLast('\\')
        return buildString {
            appendLine("[Desktop Entry]")
            appendLine("Name=${title.replace('\n', ' ')}")
            appendLine("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine $escaped")
            appendLine("Type=Application")
            appendLine("StartupNotify=true")
            appendLine("StartupWMClass=${exeName.lowercase()}")
            appendLine()
            appendLine("[Extra Data]")
            appendLine("container_id=$containerId")
            for ((key, value) in settings) appendLine("$key=${value.replace('\n', ' ')}")
        }
    }

    /**
     * Réglages de Winlator Cmod choisis par RomCloud pour chaque jeu (section « Extra Data » du
     * raccourci, lue à la place des réglages du conteneur) :
     *  - résolution de l'écran Windows au format de l'écran de l'appareil (720 lignes : rapide sur
     *    tous les appareils, image mise à l'échelle par Winlator), jeu en plein écran ;
     *  - pilote graphique « wrapper » : Turnip ([TURNIP], fourni avec Winlator Cmod, chargé par
     *    adrenotools) sur Adreno ([adreno]) : le pilote Vulkan de Qualcomm n'a pas ce qu'il faut à
     *    Zink (OpenGL des jeux), le jeu se fermait ; sinon le pilote
     *    Vulkan de l'appareil (Mali, Xclipse…) ;
     *  - langue de Windows d'après celle de l'appareil ([locale]) ;
     *  - compteurs de DXVK et de MangoHud masqués (affichés par défaut dans Winlator Cmod).
     * [screenWidth] x [screenHeight] : taille de l'écran de l'appareil (pixels, dans n'importe quel sens).
     */
    fun gameSettings(adreno: Boolean, screenWidth: Int, screenHeight: Int, locale: Locale): Map<String, String> = linkedMapOf(
        "screenSize" to screenSize(screenWidth, screenHeight),
        "forceFullscreen" to "1",
        "fullscreenStretched" to "0",
        "graphicsDriver" to "wrapper",
        "graphicsDriverConfig" to "version=${if (adreno) TURNIP else "System"};blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=${if (adreno) 1 else 0};frameSync=Normal",
        "lc_all" to "${locale.language.ifEmpty { "en" }}_${locale.country.ifEmpty { "US" }}.UTF-8",
        "envVars" to "DXVK_HUD=0 MANGOHUD=0",
    )

    /** Résolution Windows : 720 lignes, largeur au format de l'écran (paire, entre 4:3 et 21:9). */
    fun screenSize(screenWidth: Int, screenHeight: Int): String {
        val long = maxOf(screenWidth, screenHeight).toDouble()
        val short = minOf(screenWidth, screenHeight).coerceAtLeast(1).toDouble()
        val width = (SCREEN_HEIGHT * (long / short).coerceIn(4.0 / 3, 21.0 / 9)).toInt() / 2 * 2
        return "${width}x$SCREEN_HEIGHT"
    }

    /** Pilote Turnip des Adreno (graphics_driver/adrenotools-<nom>.tzst de Winlator Cmod 13.1). */
    const val TURNIP = "turnip25.3.0_R3_Auto"

    /** Hauteur de l'écran Windows des jeux. */
    private const val SCREEN_HEIGHT = 720

    /** Réglages du jeu pour cet appareil ([gameSettings]). */
    private fun deviceSettings(context: Context): Map<String, String> {
        // Taille réelle de l'écran (barres système comprises : le jeu est en plein écran).
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds else null
        val metrics = context.resources.displayMetrics
        return gameSettings(
            adreno = Settings.isSnapdragon(),
            screenWidth = screen?.width() ?: metrics.widthPixels,
            screenHeight = screen?.height() ?: metrics.heightPixels,
            locale = Locale.getDefault(),
        )
    }

    /**
     * Jeu [exe] lancé par RomCloud PC : exécutable, dossier des ROMs (son lecteur R:) et réglages du
     * jeu ; lancé pour un résultat depuis une activité (RomCloud PC vérifie la signature de l'appelant).
     */
    private fun launchCompanion(activityContext: Context, activity: String, title: String, exe: File, romsRoot: File) {
        val settings = Bundle().apply { deviceSettings(activityContext).forEach { (key, value) -> putString(key, value) } }
        val intent = Intent().setClassName(COMPANION, activity)
            .putExtra("exe", exe.absolutePath)
            .putExtra("title", title)
            .putExtra("roms_dir", romsRoot.absolutePath)
            .putExtra("settings", settings)
        val caller = activityContext as? Activity ?: throw LaunchException(I18n.get(R.string.err_launch_refused, COMPANION_NAME))
        caller.startActivityForResult(intent, COMPANION_REQUEST)
        // Plantage de la partie à relever au retour dans RomCloud ([takeCompanionCrash]).
        prefs(activityContext).edit().putBoolean(KEY_WATCH, true).apply()
    }

    /** Journaux de RomCloud PC (LogsProvider, appelant vérifié par sa signature). */
    private const val LOGS = "content://$COMPANION.logs"
    private const val KEY_WATCH = "watchCompanion"

    private fun prefs(context: Context) = context.getSharedPreferences("romcloud-pc", Context.MODE_PRIVATE)

    /** Méthode [method] du fournisseur de journaux de RomCloud PC ; null : absent (version sans journaux) ou refusé. */
    private fun companionCall(context: Context, method: String): Bundle? =
        if (!isInstalled(context, COMPANION)) null
        else runCatching { context.contentResolver.call(android.net.Uri.parse(LOGS), method, null, null) }.getOrNull()

    /**
     * Plantage de la dernière partie lancée par RomCloud PC (Wine ou l'application) : titre et rapport,
     * rendu une seule fois ; null sinon, ou si la partie continue. RomCloud PC n'est interrogé qu'après
     * le lancement d'un jeu par RomCloud.
     */
    fun takeCompanionCrash(context: Context): Pair<String, String>? {
        if (!prefs(context).getBoolean(KEY_WATCH, false)) return null
        val result = companionCall(context, "pending")
        if (result?.getBoolean("running") == true) return null
        prefs(context).edit().remove(KEY_WATCH).apply()
        val title = result?.getString("title") ?: return null
        return title to result.getString("text").orEmpty()
    }

    /** Journal de la dernière partie dans RomCloud PC (rapport envoyé par l'utilisateur) ; null : pas installé. */
    fun companionLog(context: Context): String? = companionCall(context, "log")?.getString("text")

    /** Application Winlator installée qui accepte les raccourcis : paquet et activité, ou null. */
    fun installed(context: Context): Pair<String, String>? =
        APPS.firstOrNull { (pkg, _) -> isInstalled(context, pkg) }

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Lance l'exécutable [exe] (dans le dossier du jeu [gameDir]) avec Winlator ; renvoie un conseil à
     * afficher (lecteur des ROMs à ajouter dans le conteneur, sauf [driveReady] : déjà ajouté par
     * RomCloud), ou null. Application [pkg] (choisie dans la fiche du jeu), sinon la première installée de
     * [APPS] ; absente (ou aucune, la version officielle de Winlator ne peut pas être lancée ainsi) :
     * MissingEmulatorException, qui la propose à l'installation (RomCloud PC par défaut).
     */
    fun launch(activityContext: Context, title: String, gameDir: File, exe: String, romsRoot: File, driveReady: Boolean = false, pkg: String? = null): String? {
        val app = if (pkg != null) APPS.find { it.first == pkg }?.takeIf { isInstalled(activityContext, pkg) } else installed(activityContext)
        val (pkg, activity) = app
            ?: throw MissingEmulatorException((pkg ?: COMPANION).let { MissingEmulator(it, appName(it)) })
        val target = File(gameDir, exe)
        if (!target.isFile) throw LaunchException(I18n.get(R.string.err_file_not_found, target.absolutePath))
        if (pkg == COMPANION) {
            launchCompanion(activityContext, activity, title, target, romsRoot)
            return null
        }
        val (path, needsDrive) = winePath(target, downloadsDir(), romsRoot)
        val shortcut = File(gameDir, "romcloud.desktop")
        shortcut.writeText(desktopEntry(title, path, deviceSettings(activityContext)))
        val intent = Intent().setClassName(pkg, activity)
            .putExtra("shortcut_path", shortcut.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        activityContext.startActivity(intent)
        return if (needsDrive && !driveReady) I18n.get(R.string.winlator_drive_hint, ROMS_DRIVE.toString(), romsRoot.absolutePath) else null
    }
}
