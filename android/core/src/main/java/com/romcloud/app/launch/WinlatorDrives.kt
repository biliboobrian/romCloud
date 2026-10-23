package com.romcloud.app.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import org.json.JSONObject

/**
 * Jeu PC à lancer dès que RomCloud a accès aux fichiers de Winlator (lecteur des ROMs à ajouter) ;
 * [retry] : le dossier autorisé n'était pas celui de Winlator.
 */
data class WinlatorDrivePrompt(val system: GameSystem, val game: Game, val initialUri: Uri, val retry: Boolean = false)

/**
 * Lecteur des ROMs ([Winlator.ROMS_DRIVE]) ajouté par RomCloud au premier conteneur de Winlator Cmod.
 * Les fichiers de Winlator sont ouverts par son fournisseur de documents (WinlatorFilesProvider :
 * dossier de données de l'application, identifiants = chemins des fichiers), autorisé une fois par
 * l'utilisateur dans le sélecteur de dossier d'Android ; Winlator relit la configuration du
 * conteneur au lancement d'un jeu.
 */
object WinlatorDrives {

    /** Fournisseur de documents de l'application Winlator [pkg] (autorité), null s'il n'en a pas. */
    fun authority(context: Context, pkg: String): String? =
        context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
            .firstOrNull { it.providerInfo?.packageName == pkg }?.providerInfo?.authority

    /** Dossier ouvert dans le sélecteur : dossier de données de Winlator. */
    fun initialUri(authority: String, pkg: String): Uri = DocumentsContract.buildDocumentUri(authority, "/data/user/0/$pkg")

    /** Accès autorisé (lecture et écriture) et toujours valable à [tree]. */
    fun hasAccess(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    /** [tree] est-il le dossier de données de Winlator [pkg] (et pas un de ses sous-dossiers ou un autre) ? */
    fun isDataDir(tree: Uri, pkg: String): Boolean =
        DocumentsContract.getTreeDocumentId(tree).trimEnd('/').endsWith("/$pkg")

    /**
     * Lecteur [letter] vers [path] dans la configuration du conteneur ([tree] : dossier de données de
     * Winlator [pkg]) ; renvoie true s'il a fallu l'écrire. Exception sans conteneur (Winlator jamais ouvert).
     */
    fun ensure(context: Context, tree: Uri, pkg: String, letter: Char, path: String): Boolean {
        val base = DocumentsContract.getTreeDocumentId(tree).trimEnd('/')
        val config = DocumentsContract.buildDocumentUriUsingTree(tree, "$base/${Winlator.containerConfigPath()}")
        val resolver = context.contentResolver
        val json = JSONObject(resolver.openInputStream(config)!!.bufferedReader().use { it.readText() })
        val drives = json.optString("drives").ifEmpty { Winlator.defaultDrives(pkg) }
        val wanted = path.trimEnd('/')
        if (Winlator.parseDrives(drives).any { (l, p) -> l.equals(letter, ignoreCase = true) && p.trimEnd('/') == wanted }) return false
        json.put("drives", Winlator.withDrive(drives, letter, wanted))
        resolver.openOutputStream(config, "wt")!!.bufferedWriter().use { it.write(json.toString()) }
        return true
    }
}
