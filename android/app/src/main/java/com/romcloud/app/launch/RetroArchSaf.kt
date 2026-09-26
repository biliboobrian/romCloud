package com.romcloud.app.launch

import android.annotation.SuppressLint

/**
 * Chemins « saf:// » compris par RetroArch (libretro-common/vfs/vfs_implementation_saf.c).
 *
 * La version Play Store de RetroArch n'a pas l'accès à tous les fichiers : elle ne lit les
 * dossiers partagés que via le Storage Access Framework, avec des chemins de la forme
 *   saf://<URI de l'arborescence, « % » -> « %25 » et « / » -> « %2F »>/<chemin relatif>
 * par ex. saf://content:%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ARomCloud/n64/jeu.zip
 *
 * L'arborescence utilisée est le dossier des ROMs de RomCloud : c'est ce dossier qu'il faut
 * ajouter dans RetroArch (Charger du contenu), qui en garde l'autorisation d'accès.
 */
object RetroArchSaf {

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Chemin saf:// de [filePath] relatif à l'arborescence [treeDir], ou null si hors de celle-ci. */
    fun path(treeDir: String, filePath: String): String? {
        val tree = treeDir.trimEnd('/')
        if (!filePath.startsWith("$tree/")) return null
        val relative = filePath.removePrefix("$tree/")
        val treeUri = "content://$AUTHORITY/tree/" + uriEncode(documentId(tree) ?: return null)
        return "saf://" + serializeTree(treeUri) + "/" + relative
    }

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

    /** Équivalent de android.net.Uri.encode (utilisé par DocumentsContract pour les identifiants). */
    fun uriEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "_-!.~'()*")) append(c)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }

    /** Sérialisation de l'arborescence comme retro_vfs_path_join_saf(). */
    private fun serializeTree(treeUri: String) = buildString {
        for (c in treeUri) when (c) {
            '%' -> append("%25")
            '/' -> append("%2F")
            else -> append(c)
        }
    }
}
