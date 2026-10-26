package com.romcloud.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.SeekableFileInputStream
import org.tukaani.xz.SeekableXZInputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Installation d'un jeu PC (jeu en dossier) : fichiers téléchargés un par un, les plus lourds
 * d'abord ; jeu compressé par le serveur : chaque fichier arrive dans sa propre archive (« X.zst »,
 * ou « X.xz » et « X.zip » pour les compressions d'avant le zstd), mise dans une file de
 * décompression qui avance
 * en même temps que les téléchargements, une archive à la fois.
 */
object FolderInstall {
    /** Marge gardée libre sur le disque pendant l'installation. */
    const val SPACE_MARGIN = 64L * 1024 * 1024

    private const val BUFFER = 256 * 1024

    /** Mémoire d'un décodeur xz (dictionnaire de 64 Mo de xz -9e, et ses tampons). */
    private const val XZ_DECODER_BYTES = 80L * 1024 * 1024

    /** Fichiers à télécharger, les plus lourds d'abord. */
    fun order(files: List<FolderFile>): List<FolderFile> = files.sortedByDescending { it.downloadSize }

    /**
     * Place nécessaire pour installer [todo] (fichiers absents de l'appareil) : leur taille
     * décompressée et la plus grosse archive (téléchargée pendant qu'une autre se décompresse ; si la
     * décompression prend du retard, les téléchargements attendent la place libérée, voir [mustWait]).
     */
    fun neededBytes(todo: List<FolderFile>): Long =
        todo.sumOf { it.size } + (todo.mapNotNull { it.archiveSize }.maxOrNull() ?: 0) + SPACE_MARGIN

    /**
     * Téléchargement de [next] à retarder : la place libre [free] ne suffit pas pour lui (son archive,
     * ou le fichier lui-même) et pour les fichiers pas encore écrits ([pendingBytes] : en attente de
     * décompression, en décompression, à télécharger) ; [free] négatif : place inconnue, pas d'attente.
     */
    fun mustWait(next: FolderFile, pendingBytes: Long, free: Long): Boolean =
        free >= 0 && free < pendingBytes + (next.archiveSize ?: 0) + SPACE_MARGIN

    /** Fenêtre maximale des archives zstd du serveur (2^27 octets : zstd -19 --long=27). */
    private const val ZSTD_WINDOW_LOG = 27

    /** Archive d'un fichier, à côté de lui (« X.zst », « X.xz » ou « X.zip »). */
    fun archiveFile(target: File, format: String) = File(target.parentFile, "${target.name}.$format")

    /**
     * Priorité du fil courant : très basse (téléchargement du jeu en cours, pour ne pas le
     * ralentir) ou normale ; [restore] la remet comme avant.
     */
    private class Priority {
        private val thread = Thread.currentThread()
        private val initial = thread.priority
        private var low = false

        fun set(low: Boolean) {
            if (low == this.low) return
            this.low = low
            thread.priority = if (low) Thread.MIN_PRIORITY else initial
        }

        fun restore() = set(false)
    }

    /**
     * Décompresse [archive] (format « zst », « xz » ou « zip ») vers [target] (via un fichier
     * temporaire), vérifié par sa taille ([crc32] aussi pour le zip ; zstd et xz portent leur propre
     * empreinte) ; l'archive
     * est ensuite supprimée (abîmée aussi, pour être téléchargée de nouveau). [progress] reçoit les
     * octets décompressés depuis le début. Tant que [downloading] (téléchargement du jeu en cours) :
     * priorité très basse et un seul fil, pour ne pas ralentir le téléchargement ; ensuite, normale.
     */
    suspend fun unpack(
        archive: File,
        format: String,
        target: File,
        size: Long,
        crc32: String?,
        downloading: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ) {
        val temp = File(target.parentFile, "${target.name}.unpack")
        try {
            withContext(Dispatchers.IO) {
                when (format) {
                    "zst" -> unzstd(archive, temp, size, downloading, progress)
                    "xz" -> unxz(archive, temp, size, downloading, progress)
                    else -> unzip(archive, temp, size, crc32, downloading, progress)
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("$format: impossible de renommer ${temp.name}")
            archive.delete()
        } catch (e: IOException) {
            archive.delete()
            throw e
        } finally {
            temp.delete()
        }
    }

    /**
     * Archive xz : blocs indépendants (xz -T du serveur) décompressés en parallèle selon la mémoire
     * de l'application et les cœurs du processeur, chacun écrit à sa place dans le fichier ; pendant
     * le téléchargement, un seul fil (les autres attendent sa fin).
     */
    private suspend fun unxz(archive: File, temp: File, size: Long, downloading: () -> Boolean, progress: (Long) -> Unit) {
        val blocks = SeekableXZInputStream(SeekableFileInputStream(archive)).use { xz ->
            if (xz.length() != size) throw IOException("xz: taille ${xz.length()} au lieu de $size (${archive.name})")
            xz.blockCount
        }
        val workers = minOf(
            blocks,
            Runtime.getRuntime().availableProcessors(),
            (Runtime.getRuntime().maxMemory() / 3 / XZ_DECODER_BYTES).toInt(),
            4,
        ).coerceAtLeast(1)
        RandomAccessFile(temp, "rw").use { it.setLength(size) }
        val written = AtomicLong(0)
        val nextBlock = AtomicInteger(0)
        coroutineScope {
            repeat(workers) { worker ->
                launch(Dispatchers.Default) {
                    SeekableXZInputStream(SeekableFileInputStream(archive)).use { xz ->
                        RandomAccessFile(temp, "rw").use { out ->
                            val channel = out.channel
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                // Téléchargement en cours : seul le premier fil décompresse.
                                if (worker > 0 && downloading()) {
                                    if (nextBlock.get() >= blocks) break
                                    delay(250)
                                    continue
                                }
                                val block = nextBlock.getAndIncrement()
                                if (block >= blocks) break
                                xz.seekToBlock(block)
                                var position = xz.getBlockPos(block)
                                var left = xz.getBlockSize(block)
                                // Bloc décompressé sans suspension : priorité du fil réglée pour lui seul.
                                val priority = Priority()
                                try {
                                    while (left > 0) {
                                        currentCoroutineContext().ensureActive()
                                        priority.set(downloading())
                                        val read = xz.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                        if (read < 0) throw IOException("xz: archive tronquée (${archive.name})")
                                        val data = ByteBuffer.wrap(buffer, 0, read)
                                        while (data.hasRemaining()) position += channel.write(data, position)
                                        left -= read
                                        progress(written.addAndGet(read.toLong()))
                                    }
                                } finally {
                                    priority.restore()
                                }
                            }
                        }
                    }
                }
            }
        }
        if (written.get() != size) throw IOException("xz: contenu incorrect (${archive.name})")
    }

    /**
     * Archive zstd : décodeur natif, un seul fil (plusieurs centaines de Mo/s, plus vite que le
     * stockage de la plupart des appareils).
     */
    private suspend fun unzstd(archive: File, temp: File, size: Long, downloading: () -> Boolean, progress: (Long) -> Unit) {
        val priority = Priority()
        try {
            ZstdInputStream(BufferedInputStream(FileInputStream(archive), BUFFER)).use { input ->
                input.setLongMax(ZSTD_WINDOW_LOG)
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        priority.set(downloading())
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        progress(written)
                    }
                    if (written != size) throw IOException("zstd: taille $written au lieu de $size (${archive.name})")
                }
            }
        } finally {
            priority.restore()
        }
    }

    /** Archive zip d'un seul fichier (stocké ou « deflate »), vérifiée par son CRC32. */
    private suspend fun unzip(zip: File, temp: File, size: Long, crc32: String?, downloading: () -> Boolean, progress: (Long) -> Unit) {
        val inflater = Inflater(true)
        val priority = Priority()
        try {
            FileInputStream(zip).use { raw ->
                val input = BufferedInputStream(raw, BUFFER)
                val method = skipLocalHeader(input)
                val data: InputStream = when (method) {
                    0 -> input
                    8 -> InflaterInputStream(input, inflater, BUFFER)
                    else -> throw IOException("zip: compression $method non prise en charge (${zip.name})")
                }
                val crc = CRC32()
                var written = 0L
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (written < size) {
                        currentCoroutineContext().ensureActive()
                        priority.set(downloading())
                        val read = data.read(buffer, 0, minOf(buffer.size.toLong(), size - written).toInt())
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        written += read
                        progress(written)
                    }
                }
                val got = "%08x".format(crc.value)
                if (written != size || (crc32 != null && !got.equals(crc32, ignoreCase = true))) {
                    throw IOException("zip: contenu incorrect (${zip.name})")
                }
            }
        } finally {
            priority.restore()
            inflater.end()
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
