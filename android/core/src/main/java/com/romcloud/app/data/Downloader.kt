package com.romcloud.app.data

import com.romcloud.app.I18n
import com.romcloud.app.ui.formatSize
import com.romcloud.core.R
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
     * Téléchargement : [bytes] reçus sur [total], à [speed] octets par seconde ; jeu PC compressé :
     * [unpackBytes] décompressés sur [unpackTotal] (0 : rien à décompresser), le jeu est jouable une
     * fois tout décompressé ; jeu PC ou en plusieurs parties : [filesLeft] fichiers restant à recevoir
     * sur [files] (0 : un seul fichier) ; [paused] : mis en pause (fichiers reçus gardés, reprise
     * ou abandon au choix).
     */
    data class Running(
        override val title: String,
        val bytes: Long,
        val total: Long,
        val unpackBytes: Long = 0,
        val unpackTotal: Long = 0,
        val speed: Long = 0,
        val files: Int = 0,
        val filesLeft: Int = 0,
        val paused: Boolean = false,
    ) : DownloadState {
        val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val unpackProgress: Float get() = if (unpackTotal > 0) (unpackBytes.toFloat() / unpackTotal).coerceIn(0f, 1f) else 0f

        /** Tout est reçu, la décompression continue. */
        val unpacking: Boolean get() = unpackTotal > 0 && bytes >= total && unpackBytes < unpackTotal

        /** Tout est reçu et décompressé : le jeu est rangé, il sera jouable dans un instant. */
        val finishing: Boolean get() = total > 0 && bytes >= total && unpackBytes >= unpackTotal
    }

    /**
     * Téléchargement terminé : gardé un instant ([Downloader.FINISHING_SHOWN_MS]), le temps que les
     * écrans relisent les jeux présents (sinon « Télécharger » réapparaît un instant).
     */
    data class Finishing(override val title: String) : DownloadState

    data class Failed(override val title: String, val message: String) : DownloadState
}

/**
 * Téléchargement de plus de [Downloader.METERED_WARNING_BYTES] hors Wi-Fi (données mobiles) : en
 * attente de confirmation ; [bytes] à télécharger.
 */
class MeteredPrompt(val title: String, val bytes: Long, internal val confirm: () -> Unit)

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

    private val _meteredPrompt = MutableStateFlow<MeteredPrompt?>(null)
    val meteredPrompt: StateFlow<MeteredPrompt?> = _meteredPrompt.asStateFlow()

    /** Téléchargement hors Wi-Fi confirmé : lancé. */
    fun confirmMetered() {
        val prompt = _meteredPrompt.value ?: return
        _meteredPrompt.value = null
        prompt.confirm()
    }

    fun dismissMetered() {
        _meteredPrompt.value = null
    }

    /**
     * Réseau sans limite de données : Wi-Fi ou Ethernet (téléviseur) ; aucun réseau : rien à
     * signaler (le téléchargement échouera de lui-même).
     */
    private fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Demande de chaque téléchargement lancé : reprise après une pause. */
    private val requests = ConcurrentHashMap<Long, Request>()

    private class Request(val system: GameSystem, val game: Game, val bios: List<BiosFile>, val includeRom: Boolean)

    /** Vitesse de chaque téléchargement en cours. */
    private val speeds = ConcurrentHashMap<Long, SpeedMeter>()

    /** Publie la progression du téléchargement de [state] (jeu [gameId]), avec sa vitesse. */
    private fun publish(gameId: Long, state: DownloadState.Running) {
        val speed = speeds.getOrPut(gameId) { SpeedMeter() }.update(state.bytes, android.os.SystemClock.elapsedRealtime())
        // Mis en pause : dernière progression de la tâche arrêtée ignorée.
        _states.update { if (isPaused(it[gameId])) it else it + (gameId to state.copy(speed = speed)) }
    }

    private fun isPaused(state: DownloadState?) = (state as? DownloadState.Running)?.paused == true

    val activeCount: Int get() = _states.value.values.count { it is DownloadState.Running && !it.paused }

    /**
     * Télécharge d'abord les [bios] indiqués (BIOS manquants du système), puis le jeu et ses parties
     * (disques, mises à jour, DLC) sauf si [includeRom] vaut false. La progression affichée couvre l'ensemble.
     * Plus de [METERED_WARNING_BYTES] à télécharger hors Wi-Fi : lancé seulement une fois confirmé
     * ([meteredPrompt]), sauf si [meteredConfirmed].
     */
    fun start(system: GameSystem, game: Game, bios: List<BiosFile> = emptyList(), includeRom: Boolean = true, meteredConfirmed: Boolean = false) {
        if (jobs[game.id]?.isActive == true) return
        if (!includeRom && bios.isEmpty()) return
        val total = bios.sumOf { it.size } + if (includeRom) game.fullSize else 0
        if (!meteredConfirmed) {
            // Octets à recevoir : jeu PC compressé, ses archives ; reprise après une pause, ce qui reste.
            val paused = (_states.value[game.id] as? DownloadState.Running)?.takeIf { it.paused }
            val bytes = paused?.let { it.total - it.bytes }
                ?: (bios.sumOf { it.size } + if (includeRom) (if (game.zipped) game.packedSize ?: game.fullSize else game.fullSize) else 0)
            if (bytes > METERED_WARNING_BYTES && !onWifi()) {
                _meteredPrompt.value = MeteredPrompt(game.title, bytes) { start(system, game, bios, includeRom, meteredConfirmed = true) }
                return
            }
        }
        // Jeu en plusieurs parties (disques, mises à jour, DLC) : fichiers restant à recevoir.
        val parts = if (includeRom && !game.folder && game.files.size > 1) game.files.size else 0
        requests[game.id] = Request(system, game, bios, includeRom)
        speeds[game.id] = SpeedMeter()
        _states.update {
            // Reprise après une pause : progression déjà atteinte affichée en attendant la suivante.
            val paused = (it[game.id] as? DownloadState.Running)?.takeIf { s -> s.paused }
            it + (game.id to (paused?.copy(paused = false) ?: DownloadState.Running(game.title, 0, total, files = parts, filesLeft = parts)))
        }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        jobs[game.id] = scope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                var done = 0L
                var partsLeft = parts
                val progress = { bytes: Long ->
                    publish(game.id, DownloadState.Running(game.title, done + bytes, total, files = parts, filesLeft = partsLeft))
                }
                for (file in bios) {
                    // BIOS introuvable sur le serveur (404…) : ignoré, le jeu est téléchargé quand même
                    // (un BIOS facultatif ou absent ne doit pas empêcher de jouer). Annulation : arrêt.
                    try {
                        fetch(api.biosFileUrl(file), library.biosFile(system, file), file.size, progress)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        android.util.Log.w("RomCloudDownload", "BIOS ${file.path} : ${e.message}")
                    }
                    done += file.size
                }
                if (includeRom && game.folder) {
                    fetchFolder(system, game, done) { bytes, folderTotal, unpackBytes, unpackTotal, files, filesLeft ->
                        publish(game.id, DownloadState.Running(game.title, bytes, done + folderTotal, unpackBytes, unpackTotal, files = files, filesLeft = filesLeft))
                    }
                } else if (includeRom) {
                    for (file in game.files) {
                        fetch(api.fileUrl(file.id), File(library.systemDir(system), file.fileName), file.size, progress)
                        done += file.size
                        if (partsLeft > 0) partsLeft--
                    }
                }
                _states.update { it + (game.id to DownloadState.Finishing(game.title)) }
                requests.remove(game.id)
                _events.emit(DownloadEvent.Completed(system, game, includeRom))
                scope.launch {
                    delay(FINISHING_SHOWN_MS)
                    forgetFinished(game.id)
                }
            } catch (e: CancellationException) {
                // Mis en pause : état gardé (reprise ou abandon).
                _states.update { if (isPaused(it[game.id])) it else it - game.id }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                _states.update { it + (game.id to DownloadState.Failed(game.title, message)) }
                _events.emit(DownloadEvent.Failed(game, message))
            } finally {
                // Repris aussitôt après une pause : la nouvelle tâche est gardée.
                if (self != null && jobs.remove(game.id, self)) speeds.remove(game.id)
            }
        }
    }

    fun cancel(gameId: Long) {
        jobs[gameId]?.cancel()
        _states.update { it - gameId }
    }

    /** Met le téléchargement en pause : la tâche s'arrête, les fichiers reçus (et « .part ») sont gardés. */
    fun pause(gameId: Long) {
        _states.update {
            val running = it[gameId] as? DownloadState.Running ?: return@update it
            it + (gameId to running.copy(paused = true, speed = 0))
        }
        jobs[gameId]?.cancel()
    }

    /** Reprend un téléchargement mis en pause là où il s'était arrêté (fichiers complets et « .part » repris). */
    fun resume(gameId: Long) {
        if (!isPaused(_states.value[gameId])) return
        val request = requests[gameId] ?: return
        val previous = jobs[gameId]
        scope.launch {
            previous?.join() // tâche arrêtée tout à fait : un seul écrivain par fichier
            if (isPaused(_states.value[gameId])) start(request.system, request.game, request.bios, request.includeRom)
        }
    }

    /**
     * Abandonne un téléchargement mis en pause : fichiers du jeu déjà reçus supprimés (BIOS gardés).
     * Terminé quand la tâche renvoyée l'est.
     */
    fun abandon(gameId: Long): Job {
        val request = requests.remove(gameId)
        val previous = jobs[gameId]
        _states.update { it - gameId }
        return scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            if (request != null && request.includeRom) library.deletePartial(request.system, request.game)
        }
    }

    /** Fin du « Veuillez patienter » d'un jeu téléchargé (délai écoulé, jeu supprimé de l'appareil). */
    fun forgetFinished(gameId: Long) {
        _states.update { if (it[gameId] is DownloadState.Finishing) it - gameId else it }
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
     * [before] déjà comptés), le total à télécharger, les octets décompressés et le total à décompresser,
     * puis le nombre de fichiers à recevoir (absents de l'appareil) et ceux qui restent.
     * Mise à jour (jeu déjà téléchargé, nouvelle version sur le serveur) : seuls les fichiers changés
     * (taille ou date sur le serveur) sont téléchargés de nouveau, ceux retirés de la nouvelle version
     * supprimés ; à la fin, le marqueur note l'empreinte, la version et les fichiers reçus.
     */
    private suspend fun fetchFolder(
        system: GameSystem,
        game: Game,
        before: Long,
        progress: (bytes: Long, total: Long, unpackBytes: Long, unpackTotal: Long, files: Int, filesLeft: Int) -> Unit,
    ) = coroutineScope {
        val listing = api.folderListing(game.id)
        val files = listing.files
        // Fichiers reçus avec la version déjà téléchargée (chemin -> [taille, date sur le serveur]).
        val previous = if (library.readFolderMarker(system, game) != null) library.readFolderFiles(system, game) else emptyMap()
        val complete = { f: FolderFile ->
            library.folderFile(system, game, f.path).let { it.isFile && it.length() == f.size } &&
                (f.mtime == null || previous[f.path]?.getOrNull(1)?.let { it == f.mtime } ?: true)
        }
        // Fichiers regroupés dans une archive : l'archive, téléchargée une fois pour tous ses fichiers.
        val (todo, bundled) = FolderInstall.downloads(files, files.filterNot(complete), listing.bundles)
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
        val filesLeft = AtomicInteger(todo.size)
        val reportNow = { progress(received.get() + current.get(), total, unpacked.get() + unpacking.get(), unpackTotal, todo.size, filesLeft.get()) }
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
                val onBytes = { bytes: Long ->
                    unpacking.set(bytes)
                    report()
                }
                val archive = FolderInstall.archiveFile(target, file.archiveFormat)
                if (file.bundle != null) {
                    val members = bundled.getValue(file.bundle).map { it to library.folderFile(system, game, it.path) }
                    FolderInstall.unpackBundle(archive, members, downloading::get, onBytes)
                } else {
                    FolderInstall.unpack(archive, file.archiveFormat, target, file.size, file.crc32, downloading::get, onBytes)
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
                val url = if (file.bundle != null) api.folderBundleUrl(game.id, file.bundle) else api.folderFileUrl(game.id, file.path, file.packed)
                fetch(url, if (file.packed) archive else target, file.downloadSize) { bytes ->
                    current.set(bytes)
                    report()
                }
            }
            current.set(0)
            received.addAndGet(file.downloadSize)
            filesLeft.decrementAndGet()
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
        File(folder, FolderInstall.BUNDLES_DIR).delete() // vide une fois les archives regroupées décompressées
        // Fichiers de la version précédente absents de la nouvelle : supprimés.
        val paths = files.mapTo(HashSet()) { it.path }
        for (path in previous.keys - paths) library.folderFile(system, game, path).delete()
        library.writeFolderMarker(
            system, game,
            FolderMarker(
                size = files.sumOf { it.size },
                stamp = listing.contentStamp,
                version = listing.version,
                files = files.associate { it.path to listOf(it.size, it.mtime ?: 0) },
            ),
        )
    }

    private suspend fun fetch(url: String, target: File, size: Long, progress: (Long) -> Unit) =
        fetchFile(api, url, target, size, progress) { dir ->
            if (library.hasStoragePermission()) I18n.get(R.string.err_cannot_create, dir.absolutePath)
            else I18n.get(R.string.err_storage_permission)
        }

    companion object {
        private const val FINISHING_SHOWN_MS = 15_000L

        /** Au-delà, téléchargement hors Wi-Fi confirmé d'abord. */
        const val METERED_WARNING_BYTES = 50L * 1024 * 1024
    }
}
