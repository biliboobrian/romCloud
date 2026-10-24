package com.romcloud.app.data

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Installation d'un jeu PC (jeu en dossier) : fichiers téléchargés en parallèle, les plus lourds
 * d'abord ; jeu compressé par le serveur : chaque fichier arrive dans sa propre archive « X.zip »,
 * décompressée dès sa réception (une à la fois) pendant que les suivantes se téléchargent.
 */
object FolderInstall {
    /** Fichiers téléchargés en même temps. */
    const val PARALLEL = 3

    /** Archives reçues en attente de décompression au plus (au-delà, les téléchargements attendent). */
    const val UNZIP_QUEUE = 1

    /** Fichiers à télécharger, les plus lourds d'abord. */
    fun order(files: List<FolderFile>): List<FolderFile> = files.sortedByDescending { it.downloadSize }

    /**
     * Place nécessaire pour installer [todo] (fichiers absents de l'appareil) : leur taille
     * décompressée, plus les archives présentes en même temps sur le disque (en téléchargement, en
     * attente et en décompression), comptées parmi les plus grosses.
     */
    fun neededBytes(todo: List<FolderFile>): Long =
        todo.sumOf { it.size } +
            todo.mapNotNull { it.zipSize }.sortedDescending().take(PARALLEL + UNZIP_QUEUE + 1).sum()

    /** Archive d'un fichier, à côté de lui. */
    fun zipFile(target: File) = File(target.parentFile, "${target.name}.zip")

    /**
     * Décompresse [zip] (archive d'un seul fichier, stocké ou « deflate ») vers [target] (via un
     * fichier temporaire), vérifié par sa taille et son [crc32] ; l'archive est ensuite supprimée
     * (abîmée aussi, pour être téléchargée de nouveau).
     */
    fun unzip(zip: File, target: File, size: Long, crc32: String?, isActive: () -> Boolean = { true }) {
        val temp = File(target.parentFile, "${target.name}.unzip")
        val inflater = Inflater(true)
        try {
            FileInputStream(zip).use { raw ->
                val input = BufferedInputStream(raw, 256 * 1024)
                val method = skipLocalHeader(input)
                val data: InputStream = when (method) {
                    0 -> input
                    8 -> InflaterInputStream(input, inflater, 256 * 1024)
                    else -> throw IOException("zip: compression $method non prise en charge (${zip.name})")
                }
                val crc = CRC32()
                var written = 0L
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (written < size) {
                        if (!isActive()) throw java.util.concurrent.CancellationException()
                        val read = data.read(buffer, 0, minOf(buffer.size.toLong(), size - written).toInt())
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        written += read
                    }
                }
                val got = "%08x".format(crc.value)
                if (written != size || (crc32 != null && !got.equals(crc32, ignoreCase = true))) {
                    throw IOException("zip: contenu incorrect (${target.name})")
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("zip: impossible de renommer ${temp.name}")
            zip.delete()
        } catch (e: IOException) {
            // Archive abîmée : supprimée, téléchargée de nouveau au prochain essai.
            zip.delete()
            throw e
        } finally {
            inflater.end()
            temp.delete()
        }
    }

    /** Lit l'en-tête local du premier fichier de l'archive ; renvoie sa méthode de compression. */
    private fun skipLocalHeader(input: InputStream): Int {
        val header = ByteArray(30)
        DataInputStream(input).readFully(header)
        fun u16(i: Int) = (header[i].toInt() and 0xff) or ((header[i + 1].toInt() and 0xff) shl 8)
        if (u16(0) != 0x4b50 || u16(2) != 0x0403) throw IOException("zip: en-tête invalide")
        var skip = (u16(26) + u16(28)).toLong()
        while (skip > 0) {
            val n = input.skip(skip)
            if (n <= 0) throw IOException("zip: archive tronquée")
            skip -= n
        }
        return u16(8)
    }
}
