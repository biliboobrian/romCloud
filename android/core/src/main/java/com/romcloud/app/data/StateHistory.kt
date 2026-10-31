package com.romcloud.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/** Informations d'un état de l'historique, écrites à côté de son fichier (« <id>.json »). */
@Serializable
data class StateMeta(
    val id: String,
    val core: String,
    /** Date de l'état (ms). */
    val createdAt: Long,
    val device: String? = null,
    val platform: String? = null,
    /** Épinglé : jamais supprimé par la limite de l'historique. */
    val pinned: Boolean = false,
    /** Envoyé au serveur (profil connecté). */
    val uploaded: Boolean = false,
    /** Disque inséré (jeu en plusieurs disques, 0 pour le premier) ; null : inconnu ou un seul disque. */
    val disk: Int? = null,
)

/** État de l'historique d'un profil sur le serveur (`GET /api/account/states`). */
@Serializable
data class OnlineState(
    val id: String,
    val gameId: Long,
    val core: String,
    val createdAt: String,
    val device: String? = null,
    val platform: String? = null,
    val size: Long = 0,
    val thumbnail: Boolean = false,
    val pinned: Boolean = false,
) {
    val createdAtMillis: Long get() = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
}

/**
 * État affiché dans l'historique : sur cet appareil ([local]), en ligne ([online]), ou les deux
 * (même identifiant : état de cet appareil envoyé au serveur).
 */
data class SavedState(
    val id: String,
    val core: String,
    val createdAt: Long,
    val device: String?,
    val platform: String?,
    val pinned: Boolean,
    val local: StateMeta?,
    val online: OnlineState?,
) {
    /** Disque inséré quand l'état a été fait (état de cet appareil), null s'il n'est pas connu. */
    val disk: Int? get() = local?.disk

    /** Envoi possible : état de cet appareil pas encore sur le serveur. */
    val canUpload: Boolean get() = local != null && online == null
}

/**
 * Historique des états de sauvegarde d'un jeu pour un cœur, sur l'appareil : un fichier par état
 * (« <id>.state »), sa miniature (« <id>.jpg », écran du jeu) et ses informations (« <id>.json »).
 * Les [LIMIT] plus récents sont gardés, plus les états épinglés et, pour un jeu en plusieurs
 * disques, le plus récent de chaque disque (reprise depuis la fiche du jeu).
 */
class StateHistory(val dir: File, private val core: String) {

    fun stateFile(id: String) = File(dir, "$id.state")
    fun thumbnailFile(id: String) = File(dir, "$id.jpg")
    private fun metaFile(id: String) = File(dir, "$id.json")

    /** États de l'appareil, du plus récent au plus ancien. */
    fun list(): List<StateMeta> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(StateMeta.serializer(), f.readText()) }.getOrNull() }
            .filter { stateFile(it.id).isFile }
            .sortedByDescending { it.createdAt }

    fun get(id: String): StateMeta? =
        runCatching { json.decodeFromString(StateMeta.serializer(), metaFile(id).readText()) }.getOrNull()?.takeIf { stateFile(id).isFile }

    /**
     * Ajoute un état (et sa miniature) ; les plus anciens au-delà de la limite sont supprimés.
     * [disk] : disque inséré (jeu en plusieurs disques), null sinon.
     */
    fun add(
        bytes: ByteArray,
        thumbnail: Bitmap?,
        device: String?,
        platform: String?,
        createdAt: Long = System.currentTimeMillis(),
        disk: Int? = null,
    ): StateMeta {
        dir.mkdirs()
        val meta = StateMeta(UUID.randomUUID().toString(), core, createdAt, device, platform, disk = disk)
        writeAtomically(stateFile(meta.id)) { it.writeBytes(bytes) }
        thumbnail?.let { bitmap ->
            runCatching { writeAtomically(thumbnailFile(meta.id)) { f -> f.outputStream().use { scaled(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
        }
        save(meta)
        prune()
        return meta
    }

    /**
     * Toute sauvegarde de la partie est dans l'historique : l'état de reprise [quick] (écrit en
     * quittant, téléchargé depuis le profil — sauvegarde d'un autre appareil —, ou d'une version
     * précédente) y est ajouté s'il n'y est pas déjà (même taille, date à 2 s près), daté de son
     * fichier, avec l'appareil [device] qui l'a fait. Renvoie vrai s'il a été ajouté.
     */
    fun record(quick: File, device: String?, platform: String?): Boolean {
        if (!quick.isFile || quick.length() == 0L) return false
        val date = quick.lastModified()
        val size = quick.length()
        if (sameAs(quick) != null) return false
        return runCatching { add(quick.readBytes(), null, device, platform, date) }.isSuccess
    }

    /** État de l'historique identique à l'état de reprise [quick] (même taille, date à 2 s près). */
    fun sameAs(quick: File): StateMeta? {
        if (!quick.isFile) return null
        val date = quick.lastModified()
        val size = quick.length()
        return list().firstOrNull { Math.abs(it.createdAt - date) <= 2000 && stateFile(it.id).length() == size }
    }

    /**
     * Disque inséré quand l'état [file] (de cet historique, ou état de reprise [quick]) a été fait,
     * null s'il n'est pas connu.
     */
    fun diskOf(file: File, quick: File): Int? =
        if (file == quick) sameAs(quick)?.disk else get(file.nameWithoutExtension)?.disk

    /**
     * État à charger pour reprendre la partie et sa date : l'état de reprise [quick] (écrit en
     * quittant), ou l'état de l'historique le plus récent s'il est plus récent ou que l'état de
     * reprise manque. [disk] : parmi les états faits sur ce disque (disque inconnu : le premier).
     */
    fun resumePoint(quick: File, disk: Int? = null): Pair<File, Long>? {
        val onDisk = { d: Int? -> disk == null || (d ?: 0) == disk }
        val q = quick.takeIf { it.isFile && onDisk(sameAs(it)?.disk) }
        val latest = list().firstOrNull { onDisk(it.disk) }
        return when {
            latest != null && (q == null || latest.createdAt > q.lastModified()) -> stateFile(latest.id) to latest.createdAt
            q != null -> q to q.lastModified()
            else -> null
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        val meta = get(id) ?: return
        save(meta.copy(pinned = pinned))
        if (!pinned) prune()
    }

    fun markUploaded(id: String) {
        get(id)?.let { save(it.copy(uploaded = true)) }
    }

    fun delete(id: String) {
        stateFile(id).delete()
        thumbnailFile(id).delete()
        metaFile(id).delete()
    }

    private fun prune() {
        // Le plus récent de chaque disque est gardé en plus des [LIMIT] plus récents.
        val lastOfDisk = list().filter { it.disk != null }.distinctBy { it.disk }.map { it.id }.toSet()
        list().filterNot { it.pinned || it.id in lastOfDisk }.drop(LIMIT).forEach { delete(it.id) }
    }

    private fun save(meta: StateMeta) {
        writeAtomically(metaFile(meta.id)) { it.writeText(json.encodeToString(StateMeta.serializer(), meta)) }
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.path + ".tmp")
        write(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** États gardés par jeu (hors états épinglés), comme sur le serveur. */
        const val LIMIT = 10
        /** Écart de date (ms) entre deux copies d'un même état (date du fichier, date de l'état). */
        private const val SAME_STATE_MS = 2000L
        private const val THUMBNAIL_WIDTH = 320
        private val json = Json { ignoreUnknownKeys = true }

        fun dir(context: Context, core: String, rom: File): File =
            File(context.filesDir, "libretro/states/$core/${rom.nameWithoutExtension}.history")

        private fun scaled(bitmap: Bitmap): Bitmap {
            if (bitmap.width <= THUMBNAIL_WIDTH) return bitmap
            val height = (bitmap.height * THUMBNAIL_WIDTH / bitmap.width).coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH, height, true)
        }

        /**
         * États de l'appareil et en ligne réunis (même identifiant : un seul état), du plus récent au
         * plus ancien ; en ligne, seulement ceux d'un cœur compatible avec [core].
         */
        fun merge(local: List<StateMeta>, online: List<OnlineState>, core: String): List<SavedState> = merge(local, online, listOf(core))

        /**
         * Même chose pour plusieurs cœurs (fiche du jeu : tous les cœurs intégrés du système). Un état
         * de l'appareil jamais envoyé, daté comme un état en ligne d'un autre identifiant (à
         * [SAME_STATE_MS] près), en est une copie : état de reprise d'un autre appareil, téléchargé
         * puis ajouté à l'historique ([record]) ; seul l'état en ligne est affiché.
         */
        fun merge(local: List<StateMeta>, online: List<OnlineState>, cores: Collection<String>): List<SavedState> {
            val remote = online.filter { s -> cores.any { sameSaveCore(s.core, it) } }.associateBy { it.id }
            val copies = local.filter { meta ->
                !meta.uploaded && meta.id !in remote &&
                    remote.values.any { sameSaveCore(it.core, meta.core) && Math.abs(it.createdAtMillis - meta.createdAt) <= SAME_STATE_MS }
            }.map { it.id }.toSet()
            val mine = local.filter { it.id !in copies }.map { meta ->
                val match = remote[meta.id]
                SavedState(meta.id, meta.core, meta.createdAt, meta.device, meta.platform, match?.pinned ?: meta.pinned, meta, match)
            }
            val ids = local.map { it.id }.toSet()
            val others = remote.values.filter { it.id !in ids }.map {
                SavedState(it.id, it.core, it.createdAtMillis, it.device, it.platform, it.pinned, null, it)
            }
            return (mine + others).sortedByDescending { it.createdAt }
        }
    }
}
