package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.app.ui.formatSize
import com.romcloud.core.R
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface DownloadState {
    val title: String

    /**
     * Téléchargement : [bytes] reçus sur [total] ; jeu PC compressé : [unpackBytes] décompressés sur
     * [unpackTotal] (0 : rien à décompresser), le jeu est jouable une fois tout décompressé.
     */
    data class Running(
        override val title: String,
        val bytes: Long,
        val total: Long,
        val unpackBytes: Long = 0,
        val unpackTotal: Long = 0,
    ) : DownloadState {
        val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val unpackProgress: Float get() = if (unpackTotal > 0) (unpackBytes.toFloat() / unpackTotal).coerceIn(0f, 1f) else 0f

        /** Tout est reçu, la décompression continue. */
        val unpacking: Boolean get() = unpackTotal > 0 && bytes >= total && unpackBytes < unpackTotal
    }

    data class Failed(override val title: String, val message: String) : DownloadState
}

sealed interface DownloadEvent {
    /** [romIncluded] = false : seuls les BIOS manquants ont été téléchargés (jeu déjà présent). */
    data class Completed(val system: GameSystem, val game: Game, val romIncluded: Boolean = true) : DownloadEvent
    data class Failed(val game: Game, val message: String) : DownloadEvent
}

/**
 * Télécharge les ROMs (et les BIOS de leur système) depuis le serveur vers les dossiers locaux.
 * Un fichier « .part » est utilisé pendant le transfert, ce qui permet de reprendre un
 * téléchargement interrompu (requête HTTP Range).
 */
class Downloader(
    private val context: Context,
    private val api: ApiClient,
    private val library: LocalLibrary,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, DownloadState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    val activeCount: Int get() = _states.value.values.count { it is DownloadState.Running }

    /**
     * Télécharge d'abord les [bios] indiqués (BIOS manquants du système), puis le jeu et ses parties
     * (disques, mises à jour, DLC) sauf si [includeRom] vaut false. La progression affichée couvre l'ensemble.
     */
    fun start(system: GameSystem, game: Game, bios: List<BiosFile> = emptyList(), includeRom: Boolean = true) {
        if (jobs[game.id]?.isActive == true) return
        if (!includeRom && bios.isEmpty()) return
        val total = bios.sumOf { it.size } + if (includeRom) game.fullSize else 0
        _states.update { it + (game.id to DownloadState.Running(game.title, 0, total)) }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        jobs[game.id] = scope.launch(Dispatchers.IO) {
            try {
                var done = 0L
                val progress = { bytes: Long ->
                    _states.update { it + (game.id to DownloadState.Running(game.title, done + bytes, total)) }
                }
                for (file in bios) {
                    // BIOS introuvable sur le serveur (404…) : ignoré, le jeu est téléchargé quand même
                    // (un BIOS facultatif ou absent ne doit pas empêcher de jouer). Annulation : arrêt.
                    try {
                        fetch(api.biosFileUrl(file), library.biosFile(file), file.size, progress)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        android.util.Log.w("RomCloudDownload", "BIOS ${file.path} : ${e.message}")
                    }
                    done += file.size
                }
                // Clés et firmware importés par l'émulateur externe (Eden) : là où son sélecteur les trouve.
                if (bios.isNotEmpty()) runCatching { library.exportForEmulators(system) }
                if (includeRom && game.folder) {
                    fetchFolder(system, game, done) { bytes, folderTotal, unpackBytes, unpackTotal ->
                        _states.update { it + (game.id to DownloadState.Running(game.title, bytes, done + folderTotal, unpackBytes, unpackTotal)) }
                    }
                    library.folderMarker(system, game).writeText(game.size.toString())
                } else if (includeRom) {
                    for (file in game.files) {
                        fetch(api.fileUrl(file.id), File(library.systemDir(system), file.fileName), file.size, progress)
                        done += file.size
                    }
                }
                _states.update { it - game.id }
                _events.emit(DownloadEvent.Completed(system, game, includeRom))
            } catch (e: CancellationException) {
                _states.update { it - game.id }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                _states.update { it + (game.id to DownloadState.Failed(game.title, message)) }
                _events.emit(DownloadEvent.Failed(game, message))
            } finally {
                jobs.remove(game.id)
            }
        }
    }

    fun cancel(gameId: Long) {
        jobs[gameId]?.cancel()
        _states.update { it - gameId }
    }

    fun dismissError(gameId: Long) {
        _states.update { if (it[gameId] is DownloadState.Failed) it - gameId else it }
    }

    /**
     * Jeu en dossier (PC) : ses fichiers un par un, les plus lourds d'abord, ceux déjà complets gardés
     * (reprise) ; jeu compressé : chaque archive reçue part dans une file de décompression (une à la
     * fois) qui avance pendant les téléchargements suivants ; le jeu est jouable une fois la file vide.
     * Refusé d'avance s'il n'y a pas assez de place ; si la décompression prend du retard au point de
     * manquer de place, le téléchargement suivant l'attend. [progress] reçoit les octets reçus (avec les
     * [before] déjà comptés), le total à télécharger, puis les octets décompressés et le total à décompresser.
     */
    private suspend fun fetchFolder(
        system: GameSystem,
        game: Game,
        before: Long,
        progress: (bytes: Long, total: Long, unpackBytes: Long, unpackTotal: Long) -> Unit,
    ) = coroutineScope {
        val files = api.folderFiles(game.id)
        val complete = { f: FolderFile -> library.folderFile(system, game, f.path).let { it.isFile && it.length() == f.size } }
        val todo = FolderInstall.order(files.filterNot(complete))
        val folder = library.fileFor(system, game)
        val needed = FolderInstall.neededBytes(todo)
        val free = library.freeBytes(folder)
        if (free in 0 until needed) {
            throw java.io.IOException(I18n.get(R.string.err_not_enough_space, formatSize(needed), formatSize(free)))
        }
        val total = files.sumOf { it.downloadSize }
        val received = AtomicLong(before + total - todo.sumOf { it.downloadSize })
        val current = AtomicLong(0)
        val unpackTotal = todo.filter { it.packed }.sumOf { it.size }
        val unpacked = AtomicLong(0)
        val unpacking = AtomicLong(0)
        val reportNow = { progress(received.get() + current.get(), total, unpacked.get() + unpacking.get(), unpackTotal) }
        // Progression de la décompression : quelques fois par seconde (elle avance par petits morceaux).
        val lastReport = AtomicLong(0)
        val report = {
            val now = System.currentTimeMillis()
            if (now - lastReport.get() >= 200) {
                lastReport.set(now)
                reportNow()
            }
        }
        // Octets encore à écrire sur le disque (fichiers en clair) ; archives en attente ou en décompression.
        val pendingBytes = AtomicLong(todo.sumOf { it.size })
        val queued = AtomicInteger(0)
        reportNow()

        // Téléchargement du jeu en cours : décompression en priorité très basse (voir FolderInstall.unpack).
        val downloading = java.util.concurrent.atomic.AtomicBoolean(true)
        val unpackQueue = Channel<Pair<FolderFile, File>>(Channel.UNLIMITED)
        val unpacker = launch {
            for ((file, target) in unpackQueue) {
                FolderInstall.unpack(FolderInstall.archiveFile(target, file.archiveFormat), file.archiveFormat, target, file.size, file.crc32, downloading::get) { bytes ->
                    unpacking.set(bytes)
                    report()
                }
                unpacking.set(0)
                unpacked.addAndGet(file.size)
                pendingBytes.addAndGet(-file.size)
                queued.decrementAndGet()
                reportNow()
            }
        }
        for (file in todo) {
            // Décompression en retard et disque presque plein : attente de la place libérée.
            while (FolderInstall.mustWait(file, pendingBytes.get() - unpacking.get(), library.freeBytes(folder))) {
                if (queued.get() == 0) {
                    val left = library.freeBytes(folder)
                    throw java.io.IOException(I18n.get(R.string.err_not_enough_space, formatSize(pendingBytes.get() + (file.archiveSize ?: 0)), formatSize(left)))
                }
                delay(500)
            }
            val target = library.folderFile(system, game, file.path)
            val archive = FolderInstall.archiveFile(target, file.archiveFormat)
            // Archive déjà reçue (téléchargement interrompu pendant la décompression) : décompressée seulement.
            if (!(file.packed && archive.isFile && archive.length() == file.archiveSize)) {
                fetch(api.folderFileUrl(game.id, file.path, file.packed), if (file.packed) archive else target, file.downloadSize) { bytes ->
                    current.set(bytes)
                    report()
                }
            }
            current.set(0)
            received.addAndGet(file.downloadSize)
            if (file.packed) {
                queued.incrementAndGet()
                unpackQueue.send(file to target)
            } else {
                pendingBytes.addAndGet(-file.size)
            }
            reportNow()
        }
        // Tout est reçu : la décompression repasse en priorité normale.
        downloading.set(false)
        unpackQueue.close()
        unpacker.join()
    }

    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) =
        fetchFile(api, url, target, size, progress) { dir ->
            if (library.hasStoragePermission()) I18n.get(R.string.err_cannot_create, dir.absolutePath)
            else I18n.get(R.string.err_storage_permission)
        }
}
