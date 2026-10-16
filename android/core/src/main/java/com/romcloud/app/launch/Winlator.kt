package com.romcloud.app.launch

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.romcloud.app.I18n
import com.romcloud.core.R
import java.io.File

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

    /** Winlator officielle (lancement depuis une autre application impossible). */
    const val OFFICIAL = "com.winlator"

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
     * une espace : relu par StringUtils.unescape de Winlator), conteneur [containerId].
     */
    fun desktopEntry(title: String, winePath: String, containerId: Int = CONTAINER_ID): String {
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
        }
    }

    /** Application Winlator installée qui accepte les raccourcis : paquet et activité, ou null. */
    fun installed(context: Context): Pair<String, String>? =
        APPS.firstOrNull { (pkg, _) -> isInstalled(context, pkg) }

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Lance l'exécutable [exe] (dans le dossier du jeu [gameDir]) avec Winlator ; renvoie un conseil à
     * afficher (lecteur des ROMs à ajouter dans le conteneur), ou null. Lève LaunchException si
     * Winlator Cmod manque.
     */
    fun launch(activityContext: Context, title: String, gameDir: File, exe: String, romsRoot: File): String? {
        val (pkg, activity) = installed(activityContext) ?: throw LaunchException(
            I18n.get(if (isInstalled(activityContext, OFFICIAL)) R.string.winlator_official else R.string.winlator_missing),
        )
        val target = File(gameDir, exe)
        if (!target.isFile) throw LaunchException(I18n.get(R.string.err_file_not_found, target.absolutePath))
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val (path, needsDrive) = winePath(target, downloads, romsRoot)
        val shortcut = File(gameDir, "romcloud.desktop")
        shortcut.writeText(desktopEntry(title, path))
        val intent = Intent().setClassName(pkg, activity)
            .putExtra("shortcut_path", shortcut.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        activityContext.startActivity(intent)
        return if (needsDrive) I18n.get(R.string.winlator_drive_hint, ROMS_DRIVE.toString(), romsRoot.absolutePath) else null
    }
}
