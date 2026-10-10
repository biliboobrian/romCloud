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
    /** Envoi possible : état de cet appareil pas encore sur le serveur. */
    val canUpload: Boolean get() = local != null && online == null
}

/**
 * Historique des états de sauvegarde d'un jeu pour un cœur, sur l'appareil : un fichier par état
 * (« <id>.state »), sa miniature (« <id>.jpg », écran du jeu) et ses informations (« <id>.json »).
 * Les [LIMIT] plus récents sont gardés, plus les états épinglés.
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

    /** Ajoute un état (et sa miniature) ; les plus anciens au-delà de la limite sont supprimés. */
    fun add(bytes: ByteArray, thumbnail: Bitmap?, device: String?, platform: String?, createdAt: Long = System.currentTimeMillis()): StateMeta {
        dir.mkdirs()
        val meta = StateMeta(UUID.randomUUID().toString(), core, createdAt, device, platform)
        writeAtomically(stateFile(meta.id)) { it.writeBytes(bytes) }
        thumbnail?.let { bitmap ->
            runCatching { writeAtomically(thumbnailFile(meta.id)) { f -> f.outputStream().use { scaled(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
        }
        save(meta)
        prune()
        return meta
    }

    /**
     * Ancien état unique du jeu (« Sauvegarder et quitter » d'une version précédente) : repris dans
     * l'historique quand celui-ci est encore vide.
     */
    fun adopt(legacy: File, device: String?, platform: String?) {
        if (!legacy.isFile || dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().isNotEmpty()) return
        runCatching { add(legacy.readBytes(), null, device, platform, legacy.lastModified()) }
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
        list().filterNot { it.pinned }.drop(LIMIT).forEach { delete(it.id) }
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

        /** Même chose pour plusieurs cœurs (fiche du jeu : tous les cœurs intégrés du système). */
        fun merge(local: List<StateMeta>, online: List<OnlineState>, cores: Collection<String>): List<SavedState> {
            val remote = online.filter { s -> cores.any { sameSaveCore(s.core, it) } }.associateBy { it.id }
            val mine = local.map { meta ->
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
