package com.romcloud.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Fichiers de réglages d'un émulateur externe (Eden, Dolphin), ouverts par son fournisseur de documents
 * (dossier de données de l'émulateur, Android/data/<paquet>/files), autorisé une fois par l'utilisateur
 * dans le sélecteur de dossier d'Android (comme Winlator, WinlatorDrives) : RomCloud ne peut pas lire
 * ces dossiers directement. Fichiers trouvés par leur chemin ([EmulatorConfig.files] : config/config.ini
 * pour Eden, Config/Dolphin.ini et Config/GFX.ini pour Dolphin), dossier par dossier.
 */
class EmulatorConfigFiles(private val context: Context, private val tree: Uri) {

    private val resolver get() = context.contentResolver

    /** Enfant [name] du document [parentId] (nom sans tenir compte de la casse) : son identifiant. */
    private fun child(parentId: String, name: String): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1).equals(name, ignoreCase = true)) return c.getString(0)
            }
        }
        return null
    }

    /** Identifiant du document au chemin [path] (« config/config.ini ») sous le dossier autorisé, null s'il manque. */
    private fun find(path: String): String? =
        path.split('/').filter { it.isNotEmpty() }.fold(DocumentsContract.getTreeDocumentId(tree) as String?) { id, name ->
            id?.let { child(it, name) }
        }

    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Contenu du fichier [path], null s'il manque. */
    fun read(path: String): String? = find(path)?.let { id ->
        resolver.openInputStream(uri(id))?.bufferedReader()?.use { it.readText() }
    }

    /**
     * Remplace le contenu du fichier [path] (qui doit exister : créé par l'émulateur à sa première
     * ouverture) ; l'original est gardé une fois en « <nom>.romcloud.bak » à côté.
     */
    fun write(path: String, text: String) {
        val id = find(path) ?: throw IllegalStateException("$path missing")
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val backup = "$name.romcloud.bak"
        val parentId = if (parent.isEmpty()) DocumentsContract.getTreeDocumentId(tree) else find(parent)
        if (parentId != null && child(parentId, backup) == null) {
            runCatching {
                val before = read(path).orEmpty()
                DocumentsContract.createDocument(resolver, uri(parentId), "application/octet-stream", backup)?.let { created ->
                    resolver.openOutputStream(created, "wt")?.bufferedWriter()?.use { it.write(before) }
                }
            }
        }
        resolver.openOutputStream(uri(id), "wt")!!.bufferedWriter().use { it.write(text) }
    }

    companion object {

        /** Fournisseur de documents de l'application [pkg] (autorité), null s'il n'en a pas. */
        fun authority(context: Context, pkg: String): String? =
            context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
                .firstOrNull { it.providerInfo?.packageName == pkg }?.providerInfo?.authority

        /** Identifiant du dossier racine du fournisseur [authority] (« root/ » pour Eden, « root » pour Dolphin). */
        private fun rootId(context: Context, authority: String): String? = runCatching {
            context.contentResolver.query(DocumentsContract.buildRootsUri(authority), arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

        /** Dossier ouvert dans le sélecteur : dossier racine de l'émulateur. */
        fun initialUri(context: Context, authority: String): Uri? =
            rootId(context, authority)?.let { DocumentsContract.buildDocumentUri(authority, it) }

        /** Accès autorisé (lecture et écriture) et toujours valable à [tree]. */
        fun hasAccess(context: Context, tree: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

        /** [tree] est-il le dossier racine du fournisseur [authority] (et pas un sous-dossier ou un autre dossier) ? */
        fun isRoot(context: Context, tree: Uri, authority: String): Boolean =
            tree.authority == authority && DocumentsContract.getTreeDocumentId(tree).trimEnd('/') == rootId(context, authority)?.trimEnd('/')
    }
}
