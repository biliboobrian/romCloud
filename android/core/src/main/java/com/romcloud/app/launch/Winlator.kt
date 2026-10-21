package com.romcloud.app.launch

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.view.WindowManager
import com.romcloud.app.I18n
import com.romcloud.app.data.Settings
import com.romcloud.core.R
import java.io.File
import java.util.Locale

/**
 * Jeux PC (jeux en dossiers) lancés par Winlator (Wine / Proton et Box64) : raccourci « .desktop »
 * écrit dans le dossier du jeu, ouvert par l'activité de jeu de Winlator (extra « shortcut_path »).
 * Seule la lignée Cmod (Winlator Cmod, Ludashi…) accepte un lancement depuis une autre
 * application ; la version officielle ne l'expose pas.
 *
 * Wine ne voit que les lecteurs du conteneur : D: est le dossier Téléchargements (réglage par
 * défaut de Winlator). Un dossier des ROMs ailleurs est lu par le lecteur [ROMS_DRIVE], à ajouter
 * une fois dans le conteneur (vers le dossier des ROMs).
 */
object Winlator {

    /** Applications Winlator lançables par un raccourci : paquet et activité de jeu. */
    val APPS = listOf(
        "com.winlator.cmod" to "com.winlator.cmod.XServerDisplayActivity",
        "com.ludashi.benchmark" to "com.winlator.cmod.XServerDisplayActivity",
    )

    /** Nom affiché de l'application proposée à l'installation. */
    const val CMOD_NAME = "Winlator Cmod"

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
     *  - pilote graphique « wrapper » : Turnip (adrenotools) sur Adreno ([adreno]), sinon le pilote
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
        "graphicsDriverConfig" to "version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=${if (adreno) 1 else 0};frameSync=Normal",
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

    /** Hauteur de l'écran Windows des jeux. */
    private const val SCREEN_HEIGHT = 720

    /** Application Winlator installée qui accepte les raccourcis : paquet et activité, ou null. */
    fun installed(context: Context): Pair<String, String>? =
        APPS.firstOrNull { (pkg, _) -> isInstalled(context, pkg) }

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Lance l'exécutable [exe] (dans le dossier du jeu [gameDir]) avec Winlator ; renvoie un conseil à
     * afficher (lecteur des ROMs à ajouter dans le conteneur), ou null. Winlator Cmod absent (ou
     * seulement la version officielle, qui ne peut pas être lancée ainsi) : MissingEmulatorException,
     * qui propose son APK (Release GitHub, absent du Play Store).
     */
    fun launch(activityContext: Context, title: String, gameDir: File, exe: String, romsRoot: File): String? {
        val (pkg, activity) = installed(activityContext)
            ?: throw MissingEmulatorException(MissingEmulator(APPS.first().first, CMOD_NAME))
        val target = File(gameDir, exe)
        if (!target.isFile) throw LaunchException(I18n.get(R.string.err_file_not_found, target.absolutePath))
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val (path, needsDrive) = winePath(target, downloads, romsRoot)
        val shortcut = File(gameDir, "romcloud.desktop")
        // Taille réelle de l'écran (barres système comprises : le jeu est en plein écran).
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activityContext.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds else null
        val metrics = activityContext.resources.displayMetrics
        val settings = gameSettings(
            adreno = Settings.isSnapdragon(),
            screenWidth = screen?.width() ?: metrics.widthPixels,
            screenHeight = screen?.height() ?: metrics.heightPixels,
            locale = Locale.getDefault(),
        )
        shortcut.writeText(desktopEntry(title, path, settings))
        val intent = Intent().setClassName(pkg, activity)
            .putExtra("shortcut_path", shortcut.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        activityContext.startActivity(intent)
        return if (needsDrive) I18n.get(R.string.winlator_drive_hint, ROMS_DRIVE.toString(), romsRoot.absolutePath) else null
    }
}
