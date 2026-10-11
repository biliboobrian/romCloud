package com.romcloud.app.launch

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Jeux PS Vita pour Vita3K : identifiant du jeu (« PCSB00061 »), donné à Vita3K pour démarrer un
 * jeu installé ({tags.vita_game_id} des modèles Daijishou : « -r,PCSB00061 »). Lu dans le nom du
 * fichier (identifiant de contenu ou numéro de série), sinon dans l'en-tête du .pkg ou le
 * param.sfo du .vpk / .zip.
 */
object VitaGames {

    private val SERIAL = Regex("(?<![A-Za-z0-9])(PCS[A-H]|VCS[A-Z]|VLAS|NPX[A-Z])[-_]?(\\d{5})(?!\\d)", RegexOption.IGNORE_CASE)
    private val VALID = Regex("(PCS[A-H]|VCS[A-Z]|VLAS|NPX[A-Z])\\d{5}")

    /** Identifiant dans un nom de fichier : « EP0001-PCSB00061_00-LUMINES… », « Lumines [PCSB-00061] ». */
    fun titleIdFromName(name: String): String? =
        SERIAL.find(name)?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }

    /** Identifiant du jeu : nom du fichier, sinon contenu (.pkg, .vpk, .zip) ; null si introuvable. */
    fun titleId(file: File): String? = titleIdFromName(file.name) ?: runCatching {
        when (file.extension.lowercase()) {
            "pkg" -> fromPkg(file)
            "vpk", "zip" -> fromArchive(file)
            else -> null
        }
    }.getOrNull()

    /** Identifiant de contenu de l'en-tête d'un paquet Sony (36 caractères à l'octet 0x30). */
    private fun fromPkg(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(0x54)
        raf.readFully(header)
        if (header[0] != 0x7F.toByte() || String(header, 1, 3, Charsets.US_ASCII) != "PKG") return null
        String(header, 0x30, 0x24, Charsets.US_ASCII).substring(7, 16).takeIf { VALID.matches(it) }
    }

    /** TITLE_ID du sce_sys/param.sfo d'une archive. */
    private fun fromArchive(file: File): String? = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().filter { it.name.lowercase().endsWith("sce_sys/param.sfo") }.minByOrNull { it.name.length }
            ?: return null
        titleIdFromSfo(zip.getInputStream(entry).use { it.readBytes() })
    }

    /** TITLE_ID d'un param.sfo (format PSF : table des clés, table des valeurs). */
    fun titleIdFromSfo(sfo: ByteArray): String? {
        fun u32(o: Int) = if (o + 4 > sfo.size) -1 else (sfo[o].toInt() and 0xFF) or ((sfo[o + 1].toInt() and 0xFF) shl 8) or
            ((sfo[o + 2].toInt() and 0xFF) shl 16) or ((sfo[o + 3].toInt() and 0xFF) shl 24)
        fun u16(o: Int) = if (o + 2 > sfo.size) -1 else (sfo[o].toInt() and 0xFF) or ((sfo[o + 1].toInt() and 0xFF) shl 8)
        if (sfo.size < 20 || String(sfo, 0, 4, Charsets.US_ASCII) != "\u0000PSF") return null
        val keys = u32(8)
        val data = u32(12)
        for (i in 0 until u32(16)) {
            val entry = 20 + i * 16
            val keyStart = keys + u16(entry)
            if (keyStart < 0 || keyStart >= sfo.size) return null
            val keyEnd = (keyStart until sfo.size).firstOrNull { sfo[it] == 0.toByte() } ?: return null
            if (String(sfo, keyStart, keyEnd - keyStart, Charsets.US_ASCII) != "TITLE_ID") continue
            val start = data + u32(entry + 12)
            val len = u32(entry + 4)
            if (start < 0 || len < 0 || start + len > sfo.size) return null
            return String(sfo, start, len, Charsets.US_ASCII).trimEnd('\u0000').takeIf { VALID.matches(it) }
        }
        return null
    }

    /** Modèle d'émulateur qui lance Vita3K (paquets org.vita3k…). */
    fun isVita3k(packageName: String?) = packageName?.startsWith("org.vita3k") == true
}
