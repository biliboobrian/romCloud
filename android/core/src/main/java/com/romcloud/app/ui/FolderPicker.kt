package com.romcloud.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.romcloud.core.R

/**
 * Choix d'un dossier avec le sélecteur d'Android (paramètres : dossier des ROMs, des BIOS) : renvoie
 * une fonction qui l'ouvre sur le dossier actuel ; [onPicked] reçoit le chemin du dossier choisi. Un
 * dossier hors du stockage de l'appareil (Drive…), ou pas de sélecteur (certaines TV) : message.
 */
@Composable
fun rememberFolderPicker(onPicked: (String) -> Unit): (current: String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = uri.takeIf { it.authority == StorageDocuments.AUTHORITY }
            ?.let { folderPath(DocumentsContract.getTreeDocumentId(it)) }
        if (path != null) onPicked(path)
        else Toast.makeText(context, R.string.folder_unsupported, Toast.LENGTH_LONG).show()
    }
    return { current ->
        val initial = StorageDocuments.documentId(current.trimEnd('/'))
            ?.let { DocumentsContract.buildDocumentUri(StorageDocuments.AUTHORITY, it) }
        try {
            launcher.launch(initial)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.folder_picker_missing, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Chemin d'un dossier du stockage de l'appareil d'après son identifiant de document :
 * « primary:RomCloud » -> « /storage/emulated/0/RomCloud » ; « 1234-ABCD:Roms » -> « /storage/1234-ABCD/Roms » ;
 * « home:Jeux » (dossier Documents) -> « /storage/emulated/0/Documents/Jeux » ; autre : null.
 */
internal fun folderPath(documentId: String): String? {
    val volume = documentId.substringBefore(':', "")
    val relative = documentId.substringAfter(':', "").trim('/')
    val root = when {
        volume == "primary" -> StorageDocuments.PRIMARY_ROOT
        volume == "home" -> StorageDocuments.PRIMARY_ROOT + "/Documents"
        Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "/storage/$volume"
        else -> return null
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}

/** Dossiers du stockage de l'appareil vus par le sélecteur d'Android (fournisseur externalstorage). */
internal object StorageDocuments {

    const val AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_ROOT = "/storage/emulated/0"

    /** « /storage/emulated/0/RomCloud » -> « primary:RomCloud » ; « /storage/1234-ABCD/Roms » -> « 1234-ABCD:Roms ». */
    @SuppressLint("SdCardPath") // alias du stockage principal à reconnaître, pas des chemins d'écriture
    fun documentId(dir: String): String? {
        val primary = listOf(PRIMARY_ROOT, "/sdcard", "/storage/self/primary").firstOrNull {
            dir == it || dir.startsWith("$it/")
        }
        if (primary != null) return "primary:" + dir.removePrefix(primary).trimStart('/')
        val match = Regex("^/storage/([^/]+)(?:/(.*))?$").matchEntire(dir) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        return volume + ":" + match.groupValues[2]
    }
}
