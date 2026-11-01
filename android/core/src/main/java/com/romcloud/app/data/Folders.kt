package com.romcloud.app.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Dossier RomCloud choisi dans les Paramètres (par défaut /storage/emulated/0/RomCloud) et ses
 * sous-dossiers :
 *  - roms/<dossier du système>/…   jeux (jeux PC : roms/windows/<jeu>/, lecteur R: de Winlator) ;
 *  - bios/<dossier du système>/…   BIOS et données des cœurs libretro : dossier système du cœur ;
 *  - system/<console>/<type>/…     clés, firmwares et données des émulateurs externes, importés par
 *    leur sélecteur de fichiers (system/switch/keys/prod.keys, system/ps3/firmware/PS3UPDAT.PUP) ;
 *  - drivers/…                     pilotes graphiques (Turnip) des émulateurs externes ;
 *  - saves/<cœur>/, states/<cœur>/ sauvegardes et états de l'émulateur intégré.
 * Emplacement d'un fichier du serveur : sa zone ([BiosFile.area]) et son chemin dans la zone
 * ([BiosFile.devicePath]), donnés par le serveur. Les cœurs restent dans le stockage privé de
 * l'application (Android n'y charge que du code natif de ce dossier).
 */
class Folders(val root: File) {

    val roms get() = File(root, ROMS)
    val biosRoot get() = File(root, BIOS)
    val system get() = File(root, SYSTEM)
    val drivers get() = File(root, DRIVERS)
    val saves get() = File(root, SAVES)
    val states get() = File(root, STATES)

    /** Dossier système des cœurs libretro pour ce système (BIOS et données des cœurs). */
    fun bios(system: GameSystem) = File(biosRoot, system.folder)

    /** Emplacement d'un fichier du serveur (BIOS, clés, firmware, pilote) pour le système [system]. */
    fun file(system: GameSystem, entry: BiosFile): File = when (entry.zone) {
        SYSTEM -> under(this.system, entry.zonePath)
        DRIVERS -> under(drivers, entry.zonePath)
        else -> under(bios(system), entry.zonePath)
    }

    /** Clés, firmwares et données d'une console dans la zone « system » (system/switch…), selon son identifiant. */
    fun systemFiles(system: GameSystem): File? =
        listOf(system.id, system.shortname).filter { it.isNotEmpty() }.map { File(this.system, it) }.firstOrNull { it.isDirectory }

    companion object {
        private const val TAG = "Folders"
        const val ROMS = "roms"
        const val BIOS = "bios"
        const val SYSTEM = "system"
        const val DRIVERS = "drivers"
        const val SAVES = "saves"
        const val STATES = "states"
        /** Ancien dossier des BIOS quand il était déjà « bios » dans le dossier RomCloud (renommé). */
        const val LEGACY_BIOS = "bios.old"
        private val RESERVED = setOf(ROMS, BIOS, SYSTEM, DRIVERS, SAVES, STATES, LEGACY_BIOS)

        private const val PREFS = "romcloud"
        const val KEY_ROOT = "rootDir"
        private const val KEY_LEGACY_ROMS = "romsDir"
        private const val KEY_LEGACY_BIOS = "biosDir"
        private const val KEY_LEGACY_BIOS_LEFT = "legacyBiosDir"
        private const val KEY_VERSION = "foldersVersion"

        @Suppress("DEPRECATION")
        private val storage: File get() = Environment.getExternalStorageDirectory()

        fun defaultRoot(): String = File(storage, "RomCloud").absolutePath

        /** Dossier RomCloud enregistré : choisi, sinon l'ancien dossier des ROMs, sinon le dossier par défaut. */
        fun rootOf(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString(KEY_ROOT, null) ?: prefs.getString(KEY_LEGACY_ROMS, null) ?: defaultRoot()
        }

        /** Dossiers du dossier RomCloud enregistré (processus de l'émulateur intégré compris). */
        fun of(context: Context) = Folders(File(rootOf(context)))

        /** Chemin relatif sûr (sans « .. ») ajouté à [base]. */
        private fun under(base: File, rel: String) =
            File(base, rel.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/"))

        /**
         * Passage à l'organisation du dossier RomCloud, une fois : dossiers des systèmes de l'ancien
         * dossier des ROMs déplacés dans roms/, ancien dossier des BIOS gardé pour reprendre ses fichiers
         * (« bios » du dossier RomCloud renommé « bios.old »), sauvegardes et états de l'émulateur intégré
         * déplacés du stockage privé, copies de Téléchargements/RomCloud supprimées. Sans accès aux
         * fichiers (autorisation pas encore donnée) : recommencé plus tard.
         */
        fun migrate(context: Context, canWrite: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_VERSION, 0) >= 2 || !canWrite) return
            val root = File(rootOf(context))
            val oldRoms = File(prefs.getString(KEY_LEGACY_ROMS, null) ?: defaultRoot())
            var legacyBios = File(prefs.getString(KEY_LEGACY_BIOS, null) ?: File(root, BIOS).absolutePath)
            var ok = true
            if (legacyBios == File(root, BIOS) && legacyBios.isDirectory && !File(root, LEGACY_BIOS).exists()) {
                ok = ok and legacyBios.renameTo(File(root, LEGACY_BIOS))
                legacyBios = File(root, LEGACY_BIOS)
            } else if (File(root, LEGACY_BIOS).isDirectory) {
                legacyBios = File(root, LEGACY_BIOS)
            }
            oldRoms.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name.lowercase() !in RESERVED && it != legacyBios }
                .forEach { ok = moveTree(it, File(root, "$ROMS/${it.name}")) && ok }
            val engine = File(context.filesDir, "libretro")
            ok = moveTree(File(engine, SAVES), File(root, SAVES)) && ok
            ok = moveTree(File(engine, STATES), File(root, STATES)) && ok
            removeDownloadsCopies()
            prefs.edit().apply {
                putString(KEY_ROOT, root.absolutePath)
                putString(KEY_LEGACY_BIOS_LEFT, legacyBios.takeIf { it.isDirectory }?.absolutePath)
                if (ok) putInt(KEY_VERSION, 2)
            }.apply()
            Log.i(TAG, "Organisation du dossier $root : ${if (ok) "terminée" else "incomplète, reprise plus tard"}")
        }

        /**
         * Fichier [bios] absent de sa place mais présent (même taille) dans l'ancien dossier des BIOS :
         * déplacé s'il était dans le dossier RomCloud (« bios.old »), copié sinon (dossier d'un autre émulateur).
         */
        fun adoptLegacyBios(context: Context, target: File, bios: BiosFile) {
            if (target.isFile && target.length() == bios.size) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacy = prefs.getString(KEY_LEGACY_BIOS_LEFT, null)?.let(::File)?.takeIf { it.isDirectory } ?: return
            val source = under(legacy, bios.path)
            if (!source.isFile || source.length() != bios.size) return
            target.parentFile?.mkdirs()
            val inRoot = legacy.absolutePath.startsWith(rootOf(context))
            if (!(inRoot && source.renameTo(target))) runCatching { source.copyTo(target, overwrite = true) }
        }

        /** Copies faites dans Téléchargements/RomCloud par les versions d'avant (clés, firmware, pilote). */
        private fun removeDownloadsCopies() {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RomCloud")
            val copies = setOf("prod.keys", "title.keys", "firmware.zip", "Turnip (Adreno).zip")
            for (console in dir.listFiles().orEmpty().filter { it.isDirectory }) {
                console.listFiles().orEmpty().filter { it.name in copies || it.name.endsWith(".tmp") }.forEach { it.delete() }
                console.delete() // vide seulement
            }
            dir.delete()
        }

        /**
         * Déplace [from] dans [to] (renommage, sinon copie puis suppression : autre volume) ; un
         * fichier déjà présent à l'arrivée est gardé. Faux si quelque chose n'a pas pu être déplacé.
         */
        internal fun moveTree(from: File, to: File): Boolean {
            if (!from.exists()) return true
            if (!to.exists()) {
                to.parentFile?.mkdirs()
                if (from.renameTo(to)) return true
            }
            if (from.isFile) {
                val copied = to.exists() || runCatching { from.copyTo(to) }.isSuccess
                return copied && from.delete()
            }
            to.mkdirs()
            var ok = true
            from.listFiles().orEmpty().forEach { ok = moveTree(it, File(to, it.name)) && ok }
            return ok && from.delete()
        }
    }
}
