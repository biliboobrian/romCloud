package com.romcloud.app.libretro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.romcloud.app.AppLanguage
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.SavedState
import com.romcloud.app.data.StateHistory
import com.romcloud.app.data.StreamReceiver
import com.romcloud.app.stream.StreamProtocol
import com.romcloud.app.stream.StreamSender
import com.romcloud.app.stream.StreamMode
import com.romcloud.app.stream.StreamTap
import com.romcloud.app.ui.CastDialog
import com.romcloud.app.ui.StateHistoryController
import com.romcloud.app.ui.StateHistoryList
import com.romcloud.app.ui.formatSize
import com.romcloud.core.R
import com.romcloud.app.netplay.LanPresence
import com.romcloud.app.netplay.tunnelHost
import com.romcloud.app.netplay.tunnelGuest
import com.romcloud.app.netplay.Relay
import com.romcloud.app.netplay.PlayPresence
import com.romcloud.app.netplay.LinkKind
import com.romcloud.app.netplay.LinkRules
import com.romcloud.app.netplay.NetplayRules
import com.romcloud.app.netplay.NetplayHost
import com.romcloud.app.netplay.NetplayLaunch
import com.romcloud.app.netplay.NetplayProtocol
import com.romcloud.app.netplay.NetplayRefused
import com.romcloud.app.netplay.joinNetplay
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Émulateur intégré (LibretroDroid) : télécharge le cœur s'il est absent, puis lance le jeu.
 *
 * Tourne dans un processus séparé (« :libretro ») : un cœur libretro ne peut être chargé
 * qu'une fois par processus, qui est donc arrêté en quittant le jeu.
 * Sauvegardes du jeu (SRAM) écrites en quittant ou en passant en arrière-plan ;
 * un état de sauvegarde rapide par jeu depuis le menu (Retour / bouton Menu de la manette).
 */
class LibretroActivity : ComponentActivity() {

    private sealed interface Phase {
        /** Téléchargement du cœur ou décompression de la ROM ; [fraction] null = indéterminé. */
        data class Loading(val text: String, val fraction: Float? = null, val detail: String? = null) : Phase
        data object Running : Phase
        data class Failed(val message: String) : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Loading(""))
    private var menuOpen by mutableStateOf(false)
    private var showTouchPad by mutableStateOf(false)
    private var toast by mutableStateOf<String?>(null)
    /** Durée d'affichage du message (plus longue pour une erreur à lire en entier). */
    private var toastMillis = TOAST_MS
    /** Écran « Options du cœur » du menu (null = fermé) et option dont on choisit la valeur. */
    private var options by mutableStateOf<List<CoreOption>?>(null)
    /** Historique des états affiché (« Charger l'état » du menu). */
    private var statesOpen by mutableStateOf(false)
    /** Image du jeu à l'ouverture du menu : miniature de l'état sauvegardé depuis le menu. */
    private var pausedFrame: Bitmap? = null
    private var editing by mutableStateOf<CoreOption?>(null)
    /** Onglet affiché des options (types d'options ; gardé en revenant du choix d'une valeur). */
    private var optionTab by mutableStateOf(0)
    /** Configuration de manette en cours (depuis le menu). */
    private var mappingSession by mutableStateOf<MappingSession?>(null)
    // Fenêtre « Caster l'écran » (recopie sur un Chromecast par Android).
    private var showCast by mutableStateOf(false)
    /** TV du profil choisie pour diffuser le jeu (« Jouer sur la TV »), diffusion en cours et nom de la TV. */
    /** Image diffusée sur la TV, choisie au lancement. */
    private val streamMode by lazy {
        intent.getStringExtra(EXTRA_STREAM_MODE)?.let { runCatching { StreamMode.valueOf(it) }.getOrNull() } ?: StreamMode.NATIVE
    }
    private val streamTarget by lazy {
        intent.getStringExtra(EXTRA_STREAM)?.let { runCatching { StreamProtocol.json.decodeFromString(StreamReceiver.serializer(), it) }.getOrNull() }
    }
    private var streamSender: StreamSender? = null
    private var streamingTo by mutableStateOf<String?>(null)

    /** Partie à plusieurs en réseau local demandée au lancement : proposée (hôte) ou rejointe (invité). */
    private val netplayLaunch by lazy {
        intent.getStringExtra(EXTRA_NETPLAY)?.let { runCatching { NetplayProtocol.json.decodeFromString(NetplayLaunch.serializer(), it) }.getOrNull() }
    }
    private var netplayHost: NetplayHost? = null
    /** Nom de l'autre joueur (partie en cours), demande d'un invité à accepter, attente de ses touches. */
    private val partnerState = mutableStateOf<String?>(null)
    private var netplayPartner: String?
        get() = partnerState.value
        set(value) {
            partnerState.value = value
            // Annoncé sur le réseau : plus proposé comme partenaire libre.
            gamePresence?.busy = value != null
        }

    /** Présence de cet appareil pendant le jeu (l'application, en arrière-plan, ne l'annonce plus). */
    private var gamePresence: PlayPresence? = null

    /** Partie proposée aussi par Internet : identifiant sur le relais du serveur (profil connecté). */
    private var relaySession: String? = null

    /** Problème de la partie par Internet (annonce ou relais), affiché dans le bandeau. */
    private var internetProblem by mutableStateOf<String?>(null)
    private var netplayRequest by mutableStateOf<Pair<NetplayProtocol.Join, CompletableDeferred<Boolean>>?>(null)
    private var netplayWaiting by mutableStateOf(false)
    /** Partie proposée, en attente d'un invité (hôte). */
    private var netplayOpen by mutableStateOf(false)

    /** Liaison entre consoles (câble, adaptateur sans fil, ad hoc) : chaque appareil émule sa console. */
    private val link: LinkKind? by lazy { netplayLaunch?.link }

    /**
     * Liaison avec un autre cœur que l'émulateur choisi pour le jeu : sauvegarde de celui-ci, reprise
     * au lancement (plus récente) et mise à jour en quittant.
     */
    private val linkedSram by lazy {
        netplayLaunch?.saveCore?.takeIf { link != null && it != core }
            ?.let { File(filesDir, "libretro/saves/$it/${rom.nameWithoutExtension}.srm") }
    }

    /**
     * Port des touches de cet appareil : en partie à plusieurs, un seul joueur local (port 0, placé par
     * l'adaptateur) ; consoles reliées : chacune ses manettes.
     */
    private fun localPort(port: Int) = if (netplayLaunch != null && link == null) 0 else port

    private val gamepadMappings by lazy { GamepadMappings(this, systemId) }
    /** Boutons RetroPad enfoncés par joueur, et ceux enfoncés par une gâchette analogique. */
    private val heldButtons = HashMap<Int, MutableSet<Int>>()
    private val axisButtons = HashMap<Int, MutableSet<Int>>()

    private var retroView: GLRetroView? = null
    /** Premier image affichée : le cœur et le jeu sont chargés (sauvegardes possibles). */
    private var gameReady = false
    private lateinit var container: FrameLayout

    private val core by lazy { intent.getStringExtra(EXTRA_CORE).orEmpty() }
    private val systemId by lazy { intent.getStringExtra(EXTRA_SYSTEM).orEmpty() }
    private val optionsStore by lazy { CoreOptionsStore(this) }
    private val videoFilters by lazy { VideoFilterStore(this) }
    /** Filtre d'image du système (lissage), changé depuis le menu ; [filterPicker] : liste affichée. */
    private var videoFilter by mutableStateOf(VideoFilter.DEFAULT)
    private var filterPicker by mutableStateOf(false)
    private val aspectRatios by lazy { AspectRatioStore(this) }
    /** Format de l'image du système, changé depuis le menu ; [aspectPicker] : liste affichée. */
    private var aspectRatio by mutableStateOf(AspectRatio.ORIGINAL)
    private var aspectPicker by mutableStateOf(false)
    /** Cœur chargé par l'adaptateur natif ([CoreShim]) : format d'image modifiable en cours de partie. */
    private var throughShim = false
    /** Disposition de la manette tactile propre à la console. */
    private val padLayout by lazy { PadLayouts.forGame(systemId, core) }
    private val rom by lazy { File(intent.getStringExtra(EXTRA_ROM).orEmpty()) }
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    private val sramFile by lazy { File(filesDir, "libretro/saves/$core/${rom.nameWithoutExtension}.srm") }
    private val stateFile by lazy { stateFile(this, core, rom) }
    /** Historique des états du jeu pour ce cœur (plusieurs états, avec miniature). */
    private val history by lazy { StateHistory(StateHistory.dir(this, core, rom), core) }
    private val historyController by lazy {
        StateHistoryController(account, history, gameId, core, cacheDir, lifecycleScope) { toast = it }
    }
    /** État de l'historique choisi dans la fiche du jeu, chargé dès la première image. */
    private val startState by lazy { intent.getStringExtra(EXTRA_STATE)?.let(::File) }
    /** Profil connecté : états envoyés en ligne dès leur sauvegarde (réglage de l'appareil). */
    private val autoUploadStates by lazy { intent.getBooleanExtra(EXTRA_AUTO_UPLOAD_STATES, true) }
    private val platform get() = if (isTv) "androidtv" else "android"
    /** Reprendre la partie : l'état sauvegardé est chargé dès la première image. */
    private val resume by lazy { intent.getBooleanExtra(EXTRA_RESUME, false) }
    /** Jeu du serveur (temps de jeu et sauvegardes en ligne du profil) ; 0 = inconnu. */
    private val gameId by lazy { intent.getLongExtra(EXTRA_GAME_ID, 0L) }
    private val account by lazy { (application as RomCloudApp).account }
    /** Sauvegardes synchronisées avec le profil : état de la partie et mémoire du jeu. */
    private val saveFiles by lazy { mapOf("state" to stateFile, "sram" to sramFile) }
    private val sessionStart = System.currentTimeMillis()
    /** Temps de jeu de la session : partie affichée, menu fermé (ms), et début de la période en cours. */
    private var playedMillis = 0L
    private var playingSince = 0L

    private fun startPlayClock() {
        if (playingSince == 0L && gameReady && !menuOpen) playingSince = System.currentTimeMillis()
    }

    private fun stopPlayClock() {
        if (playingSince != 0L) playedMillis += System.currentTimeMillis() - playingSince
        playingSince = 0L
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        container = FrameLayout(this).apply { setBackgroundColor(AndroidColor.BLACK) }
        container.addView(
            ComposeView(this).apply {
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        // Couleur du texte du thème sombre (sinon noir sur fond noir).
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { Overlay() }
                    }
                }
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(container)

        onBackPressedDispatcher.addCallback(this) {
            when {
                phase != Phase.Running -> finish()
                mappingSession != null -> mappingSession = null
                filterPicker -> filterPicker = false
                aspectPicker -> aspectPicker = false
                statesOpen -> statesOpen = false
                editing != null -> editing = null
                options != null -> options = null
                menuOpen -> closeMenu()
                else -> openMenu()
            }
        }
        CrashReports.startSession(this, core, rom.absolutePath, systemId)
        lifecycleScope.launch { prepare() }
    }

    private suspend fun prepare() {
        try {
            if (!rom.isFile) {
                phase = Phase.Failed(getString(R.string.err_file_not_found, rom.absolutePath))
                return
            }
            val cores = LibretroCores(this)
            // Cœur absent, ou corrigé sur le buildbot depuis son téléchargement : (re)téléchargé.
            val outdated = cores.isOutdated(core)
            val coreFile = cores.installed(core)?.takeUnless { outdated } ?: run {
                val text = getString(if (outdated) R.string.libretro_updating_core else R.string.libretro_downloading_core, core)
                phase = Phase.Loading(text)
                cores.download(core) { bytes, total ->
                    phase = Phase.Loading(
                        text,
                        fraction = if (total > 0) bytes.toFloat() / total else null,
                        detail = if (total > 0) "${formatSize(bytes)} / ${formatSize(total)}" else formatSize(bytes),
                    )
                }
            }
            // Fichiers système du cœur (Dolphin : dolphin-emu/Sys), téléchargés au premier lancement ;
            // en cas d'échec, le jeu est lancé quand même.
            val systemDir = File(intent.getStringExtra(EXTRA_SYSTEM_DIR) ?: filesDir.absolutePath)
            if (cores.systemFilesMissing(core, systemDir)) {
                val text = getString(R.string.libretro_downloading_system_files, core)
                phase = Phase.Loading(text)
                runCatching {
                    cores.downloadSystemFiles(core, systemDir) { bytes, total ->
                        phase = Phase.Loading(
                            text,
                            fraction = if (total > 0) bytes.toFloat() / total else null,
                            detail = if (total > 0) "${formatSize(bytes)} / ${formatSize(total)}" else formatSize(bytes),
                        )
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            // Profil connecté : sauvegardes en ligne plus récentes (partie continuée sur un autre appareil).
            if (gameId > 0 && account.state.value.signedIn) {
                phase = Phase.Loading(getString(R.string.libretro_syncing_saves))
                runCatching { account.downloadNewer(gameId, core, saveFiles) }.onFailure { if (it is CancellationException) throw it }
            }
            val game = if (RomArchives.needsExtraction(core, rom)) {
                phase = Phase.Loading(getString(R.string.libretro_preparing))
                withContext(Dispatchers.IO) { RomArchives.extract(rom, File(cacheDir, "libretro-rom")) }
            } else {
                rom
            }
            // Invité d'une liaison : l'hôte accepte avant le lancement (le cœur se relie dès son démarrage).
            val launch = netplayLaunch
            if (launch != null && link != null && !launch.host) {
                phase = Phase.Loading(getString(R.string.link_connecting, launch.peerName))
                joinFailure(launch, coreFile)?.let {
                    phase = Phase.Failed(it)
                    return
                }
                // Câble ouvert par le cœur, par Internet : le cœur se connecte à cet appareil, relié à l'hôte.
                val session = launch.relay
                if (session != null && link == LinkKind.GAME_LINK) {
                    lifecycleScope.tunnelGuest({ account.relayRequest(session, Relay.CHANNEL_LINK, "guest") }, LinkRules.GAMBATTE_PORT.toInt())
                }
            }
            startGame(coreFile, game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: getString(R.string.libretro_error_generic))
            account.reportError("libretro:$core", e.message ?: e.javaClass.name, rom.name)
        }
    }

    private suspend fun startGame(coreFile: File, game: File) {
        // Hôte d'une liaison ad hoc (PSP) : adresse de cet appareil sur le réseau local.
        val ownAddress = if (link == LinkKind.PSP_ADHOC && netplayLaunch?.host == true) withContext(Dispatchers.IO) { LanPresence.localAddress() } else null
        val sram = withContext(Dispatchers.IO) {
            // Liaison : sauvegarde de l'émulateur choisi reprise si elle est plus récente.
            linkedSram?.takeIf { it.isFile && (!sramFile.isFile || it.lastModified() > sramFile.lastModified()) }
                ?.let { runCatching { it.copyTo(sramFile, overwrite = true) } }
            sramFile.takeIf { it.isFile }?.readBytes()
        }
        aspectRatio = aspectRatios.load(systemId)
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath(coreFile)
            gameFilePath = game.absolutePath
            systemDirectory = intent.getStringExtra(EXTRA_SYSTEM_DIR) ?: filesDir.absolutePath
            savesDirectory = sramFile.parentFile!!.apply { mkdirs() }.absolutePath
            saveRAMState = sram
            // Dernières options choisies pour ce système (les autres gardent la valeur par défaut du cœur).
            // Valeurs imposées par le jeu (cartouche GX4000…), sauf choix de l'utilisateur.
            // Liaison entre consoles : options du cœur imposées (serveur chez l'hôte, adresse de l'hôte chez l'invité).
            val linkOptions = netplayLaunch?.let { launch ->
                link?.let { LinkRules.options(it, launch.host, launch.address, LanPresence.deviceId(this@LibretroActivity), ownAddress) }
            }.orEmpty()
            // Jeu synchronisé : options qui diffèrent d'un appareil à l'autre coupées (meilleurs scores de FBNeo).
            val netplayOptions = if (netplayLaunch != null && link == null) NetplayRules.CORE_OPTIONS else emptyMap()
            variables = (GameOptionDefaults.forGame(core, game) + optionsStore.load(systemId) + linkOptions + netplayOptions)
                .map { (key, value) -> Variable(key, value) }.toTypedArray()
            preferLowLatencyAudio = true
            videoFilter = videoFilters.load(systemId)
            shader = videoFilter.shader()
        }
        val view = GLRetroView(this, data).apply { isFocusable = false }
        retroView = view
        lifecycle.addObserver(view)
        container.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Format « étiré » : proportions de la vue, suivies (rotation, fenêtre redimensionnée).
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspectRatio() }
        lifecycleScope.launch { view.getGLRetroErrors().collect(::onRetroError) }
        lifecycleScope.launch {
            view.getGLRetroEvents().collect {
                if (it is GLRetroView.GLRetroEvents.FrameRendered && !gameReady) {
                    gameReady = true
                    startPlayClock()
                    selectControllers(view)
                    // Invité : l'état de l'hôte remplace la partie reprise.
                    if (netplayLaunch?.host != false) {
                        val chosen = startState
                        if (chosen != null) loadStateFile(chosen) else if (resume) resumeGame()
                    }
                    streamTarget?.let { startStreaming(view, it) }
                    netplayLaunch?.let { startNetplay(it, coreFile) }
                }
            }
        }
        phase = Phase.Running
        showTouchPad = !isTv && !GamepadInput.hasGamepad()
    }

    /**
     * Chemin du cœur donné à LibretroDroid. Certains cœurs sont chargés par l'adaptateur audio_shim,
     * qui lit le chemin du cœur réel dans l'environnement : son envoyé échantillon par échantillon
     * (canal ignoré par LibretroDroid : jeu muet), regroupé en lots ; image du cœur restée noire
     * à l'affichage par LibretroDroid (flycast, dolphin), copiée à l'écran par l'adaptateur ; boutons lus d'un
     * coup (masque ignoré par LibretroDroid : aucun bouton, LRPS2), masque reconstitué par l'adaptateur.
     */
    private fun corePath(coreFile: File): String {
        // Play! (PS2) range ses données dans $EXTERNAL_STORAGE/Play Data Files ; sans accès à tous
        // les fichiers, ce dossier ne peut pas être créé (arrêt du cœur) : dossier privé de
        // l'application à la place. Processus de jeu séparé : le reste de l'application n'est pas concerné.
        if (core == "play" && !hasAllFilesAccess()) {
            Os.setenv("EXTERNAL_STORAGE", File(filesDir, "libretro/play").apply { mkdirs() }.absolutePath, true)
        }
        // Diffusion sur une TV : tous les cœurs passent par l'adaptateur, qui copie le son. Sa
        // bibliothèque est chargée avant le cœur (même instance pour LibretroDroid et StreamTap).
        // Format d'image choisi : proportions remplacées par l'adaptateur.
        val streaming = streamTarget != null
        if (streaming) StreamTap.load()
        val aspect = aspectRatio != AspectRatio.ORIGINAL
        // Partie à plusieurs : touches échangées par l'adaptateur ; liaison : paquets du cœur (gpSP).
        val netplay = netplayLaunch != null && link?.packets != false
        if (!streaming && !aspect && !netplay && core !in SAMPLE_AUDIO_CORES && core !in BLIT_CORES && core !in INPUT_MASK_CORES) return coreFile.absolutePath
        throughShim = CoreShim.load()
        applyAspectRatio()
        Os.setenv("ROMCLOUD_SHIM_CORE", coreFile.absolutePath, true)
        Os.setenv("ROMCLOUD_SHIM_BLIT", if (core in BLIT_CORES) "1" else "0", true)
        // Bibliothèques non extraites de l'APK : dlopen les trouve par leur seul nom.
        return File(applicationInfo.nativeLibraryDir, AUDIO_SHIM).takeIf { it.isFile }?.absolutePath ?: AUDIO_SHIM
    }

    /** Stockage partagé accessible en écriture (même règle que LocalLibrary.hasStoragePermission). */
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun onRetroError(code: Int) {
        when (code) {
            GLRetroView.ERROR_SERIALIZATION, GLRetroView.ERROR_CHEAT -> {
                toast = getString(R.string.libretro_state_error)
                return
            }
            GLRetroView.ERROR_LOAD_LIBRARY -> {
                // Cœur corrompu ou incompatible : il sera téléchargé à nouveau au prochain lancement.
                LibretroCores(this).delete(core)
                phase = Phase.Failed(getString(R.string.libretro_error_core, core))
            }
            GLRetroView.ERROR_LOAD_GAME -> phase = Phase.Failed(getString(R.string.libretro_error_game))
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> phase = Phase.Failed(getString(R.string.libretro_error_gl))
            else -> phase = Phase.Failed(getString(R.string.libretro_error_generic))
        }
        (phase as? Phase.Failed)?.let { account.reportError("libretro:$core", it.message, "${rom.name} (code $code)") }
        gameReady = false
        menuOpen = false
        retroView?.let { container.removeView(it) }
    }

    // ---- Menu : l'émulation est suspendue (thread OpenGL en pause) tant qu'il est ouvert ----

    private fun openMenu() {
        if (menuOpen) return
        releaseButtons()
        stopPlayClock()
        captureFrame()
        menuOpen = true
        retroView?.apply {
            audioEnabled = false
            onPause()
        }
    }

    private fun closeMenu() {
        menuOpen = false
        options = null
        editing = null
        mappingSession = null
        filterPicker = false
        aspectPicker = false
        statesOpen = false
        pausedFrame = null
        retroView?.apply {
            onResume()
            audioEnabled = true
        }
        startPlayClock()
    }

    override fun onResume() {
        super.onResume()
        // LibretroDroid relance l'émulation après onResume() : on la suspend de nouveau si le menu est ouvert.
        window.decorView.post { if (menuOpen) retroView?.onPause() }
        startPlayClock()
    }

    override fun onPause() {
        super.onPause()
        stopPlayClock()
        // L'émulation est déjà en pause ici (LibretroDroid suit le cycle de vie de l'activité).
        saveSram()
        if (isFinishing) {
            stopStreaming()
            netplayHost?.stop()
            gamePresence?.stop()
            Netplay.stop()
            saveLinkedSram()
            CrashReports.endSession(this)
            // Processus de jeu arrêté juste après : temps de jeu et sauvegardes mis en file,
            // envoyés au serveur par l'application au retour.
            account.recordPlaytime(gameId, playedMillis / 1000, sendNow = false)
            account.queueUploads(gameId, core, saveFiles, sessionStart - 2000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Libère le cœur : un autre jeu pourra en charger un nouveau dans un processus neuf.
        if (isFinishing) {
            CrashReports.endSession(this)
            Process.killProcess(Process.myPid())
        }
    }

    // ---- Diffusion sur une TV du profil : image et son sur la TV, manette tactile sur le téléphone ----

    private fun startStreaming(view: GLRetroView, target: StreamReceiver) {
        if (streamSender != null) return
        toast = getString(R.string.stream_connecting, target.name)
        // TV revenue au premier plan depuis le lancement : port et clé relus sur le serveur.
        val refresh = {
            runBlocking { account.streamReceivers() }.let { tvs -> tvs.find { it.id == target.id } ?: tvs.find { it.name == target.name } }
        }
        streamSender = StreamSender(view, target, account.deviceName, rom.nameWithoutExtension, streamMode, refresh) { event ->
            when (event) {
                StreamSender.Event.Connected -> {
                    streamingTo = target.name
                    toast = getString(R.string.stream_started, target.name)
                }
                is StreamSender.Event.Stopped -> {
                    streamSender = null
                    streamingTo = null
                    event.error?.let { account.reportError("stream:$core", it, event.details) }
                    if (!isFinishing) {
                        if (event.error != null) toastMillis = ERROR_TOAST_MS
                        toast = event.error?.let { getString(R.string.stream_failed, it) } ?: getString(R.string.stream_stopped)
                    }
                }
            }
        }.also { it.start() }
    }

    // ---- Jeu à plusieurs en réseau local (touches échangées par l'adaptateur natif) ----

    private fun startNetplay(launch: NetplayLaunch, coreFile: File) {
        val link = link
        if (!throughShim && link?.packets != false) {
            toastMillis = ERROR_TOAST_MS
            toast = getString(R.string.netplay_unavailable)
            return
        }
        val app = application as RomCloudApp
        val presence = PlayPresence(this, account, account.deviceName, app.api.platform)
        gamePresence = presence
        lifecycleScope.launch { presence.internet.problem.collect { internetProblem = it } }
        // Invité : annoncé « en partie » ; hôte : annonce de la partie proposée (NetplayHost).
        if (!launch.host) presence.start(lifecycleScope)
        if (link != null && !launch.host) {
            // Invité d'une liaison : déjà accepté par l'hôte (avant le lancement).
            netplayPartner = launch.peerName
            toast = getString(R.string.link_started, launch.peerName)
        } else if (launch.host) {
            // Profil connecté : partie aussi proposée par Internet (relais du serveur), sauf l'ad hoc
            // de la PSP (nombreux ports, réseau local seulement).
            val session = Relay.newSession().takeIf { account.state.value.signedIn && link != LinkKind.PSP_ADHOC }
            relaySession = session
            netplayHost = NetplayHost(
                presence, launch.game.copy(session = session), coreFile,
                ask = { join ->
                    val answer = CompletableDeferred<Boolean>()
                    netplayRequest = join to answer
                    try {
                        answer.await()
                    } finally {
                        netplayRequest = null
                    }
                },
                onStarted = { name, viaRelay ->
                    // Ad hoc de la PSP : toujours proposée aux autres consoles.
                    netplayOpen = link?.multi == true
                    netplayPartner = netplayPartner?.takeIf { link != null }?.let { "$it, $name" } ?: name
                    toast = getString(if (link != null) R.string.link_started else R.string.netplay_started, name)
                    // Câble ouvert par le cœur, invité par Internet : sa connexion au cœur passe par le relais.
                    if (viaRelay && link == LinkKind.GAME_LINK && session != null) {
                        lifecycleScope.tunnelHost({ account.relayRequest(session, Relay.CHANNEL_LINK, "host") }, LinkRules.GAMBATTE_PORT.toInt())
                    }
                },
                relay = session?.let { s -> { channel: String -> account.relayRequest(s, channel, "host") } },
            ).also { it.start(lifecycleScope) }
            netplayOpen = true
        } else {
            toast = getString(R.string.netplay_connecting, launch.peerName)
            lifecycleScope.launch {
                try {
                    val join = NetplayProtocol.Join(
                        name = account.deviceName, platform = app.api.platform,
                        fileName = launch.game.fileName, size = launch.game.size, core = core, coreSize = coreFile.length(),
                        delay = launch.delay,
                    )
                    val answer = joinNetplay(launch, join, guestRelay(launch))
                    netplayPartner = launch.peerName
                    val sameCore = answer.coreSize <= 0 || answer.coreSize == coreFile.length()
                    toast = getString(if (sameCore) R.string.netplay_started else R.string.netplay_core_differs, launch.peerName)
                } catch (e: NetplayRefused) {
                    toastMillis = ERROR_TOAST_MS
                    toast = when (e.reason) {
                        NetplayProtocol.REFUSED -> getString(R.string.netplay_refused, launch.peerName)
                        NetplayProtocol.BUSY -> getString(R.string.netplay_busy, launch.peerName)
                        NetplayProtocol.OTHER_GAME, NetplayProtocol.OTHER_CORE -> getString(R.string.netplay_other_game)
                        NetplayProtocol.OTHER_VERSION -> getString(R.string.netplay_other_version)
                        else -> getString(R.string.netplay_unreachable, launch.peerName, e.message.orEmpty())
                    }
                }
            }
        }
        // Suivi de la partie : attente des touches de l'autre joueur, fin de la partie.
        lifecycleScope.launch {
            while (true) {
                val status = Netplay.status()
                netplayWaiting = status.waiting && netplayPartner != null && !menuOpen
                if (status.state == Netplay.State.ENDED && netplayPartner != null) {
                    toastMillis = ERROR_TOAST_MS
                    toast = getString(if (link != null) R.string.link_ended else R.string.netplay_ended, netplayPartner.orEmpty())
                    netplayPartner = null
                    // Hôte : partie de nouveau proposée.
                    netplayHost?.let {
                        it.reopen()
                        netplayOpen = true
                    }
                }
                delay(300)
            }
        }
    }

    /**
     * Invité d'une liaison : demande à l'hôte ; renvoie le message du refus (ou de l'échec), null si
     * l'hôte accepte (liaison par paquets : connexion remise à l'adaptateur, utilisée au démarrage du cœur).
     */
    private suspend fun joinFailure(launch: NetplayLaunch, coreFile: File): String? {
        val app = application as RomCloudApp
        val join = NetplayProtocol.Join(
            name = account.deviceName, platform = app.api.platform, fileName = launch.game.fileName,
            size = launch.game.size, core = core, coreSize = coreFile.length(), link = launch.game.link,
            delay = launch.delay,
        )
        return try {
            joinNetplay(launch, join, guestRelay(launch))
            null
        } catch (e: NetplayRefused) {
            when (e.reason) {
                NetplayProtocol.REFUSED -> getString(R.string.netplay_refused, launch.peerName)
                NetplayProtocol.BUSY -> getString(R.string.netplay_busy, launch.peerName)
                NetplayProtocol.OTHER_GAME, NetplayProtocol.OTHER_CORE -> getString(R.string.netplay_other_game)
                NetplayProtocol.OTHER_VERSION -> getString(R.string.netplay_other_version)
                else -> getString(R.string.netplay_unreachable, launch.peerName, e.message.orEmpty())
            }
        }
    }

    /** Invité par Internet : connexion à ouvrir sur le relais du serveur (null sans profil ou en réseau local). */
    private fun guestRelay(launch: NetplayLaunch) = launch.relay?.let { account.relayRequest(it, Relay.CHANNEL_PLAY, "guest") }

    /** Liaison avec un autre cœur que l'émulateur choisi : sauvegarde recopiée pour celui-ci (et envoyée en ligne). */
    private fun saveLinkedSram() {
        val target = linkedSram ?: return
        val saveCore = netplayLaunch?.saveCore ?: return
        if (!sramFile.isFile || (target.isFile && target.lastModified() >= sramFile.lastModified())) return
        runCatching {
            sramFile.copyTo(target, overwrite = true)
            account.queueUploads(gameId, saveCore, mapOf("sram" to target), sessionStart - 2000)
        }
    }

    /** Fin de la partie à plusieurs depuis le menu : chacun continue seul. */
    private fun leaveNetplay() {
        Netplay.stop()
        netplayHost?.stop()
        netplayHost = null
        netplayOpen = false
        netplayPartner = null
        gamePresence?.stop()
        gamePresence = null
        closeMenu()
    }

    private fun stopStreaming() {
        streamSender?.stop()
        streamSender = null
        streamingTo = null
    }

    /** Appelé seulement émulation en pause : les appels se font hors du thread d'émulation. */
    private fun saveSram() {
        val view = retroView ?: return
        if (!gameReady) return
        runCatching {
            val bytes = view.serializeSRAM(false)
            if (bytes.isNotEmpty()) {
                sramFile.parentFile?.mkdirs()
                sramFile.writeBytes(bytes)
            }
        }
    }

    private fun saveState() {
        val view = retroView ?: return
        toast = runCatching {
            val bytes = view.serializeState(false)
            stateFile.parentFile?.mkdirs()
            stateFile.writeBytes(bytes)
            // Profil connecté : état envoyé au serveur tout de suite (partie en cours).
            account.queueUploads(gameId, core, mapOf("state" to stateFile), sessionStart, sendNow = true)
            addToHistory(bytes, sendNow = true)
            getString(R.string.libretro_state_saved)
        }.getOrElse { getString(R.string.libretro_state_error) }
        closeMenu()
    }

    /**
     * Manette de chaque port : le premier type proposé par le cœur qui est une manette
     * (« Amstrad Joystick », sous-type de RETRO_DEVICE_JOYPAD), comme RetroArch. Sans ce choix,
     * certains cœurs (cap32) n'interrogent aucune touche : manette tactile et physique sans effet.
     */
    private fun selectControllers(view: GLRetroView) {
        runCatching {
            view.getControllers().forEachIndexed { port, types ->
                types.firstOrNull { (it.id and RETRO_DEVICE_MASK) == RETRO_DEVICE_JOYPAD }
                    ?.let { view.setControllerType(port, it.id) }
            }
        }
    }

    /** Au lancement par « Reprendre » : état sauvegardé de la partie. */
    private fun resumeGame() {
        val view = retroView ?: return
        toast = when {
            !stateFile.isFile -> getString(R.string.libretro_no_state)
            runCatching { view.unserializeState(stateFile.readBytes(), false) }.getOrDefault(false) ->
                getString(R.string.libretro_state_loaded)
            else -> getString(R.string.libretro_state_error)
        }
    }

    /** Enregistre l'état de la partie (repris par « Reprendre » dans la fiche du jeu) puis quitte. */
    private fun saveAndQuit() {
        val view = retroView ?: return
        val saved = runCatching {
            val bytes = view.serializeState(false)
            stateFile.parentFile?.mkdirs()
            stateFile.writeBytes(bytes)
            // Envoi en ligne par l'application, au retour (ce processus s'arrête avec le jeu).
            addToHistory(bytes, sendNow = false)
        }.isSuccess
        if (saved) {
            finish()
        } else {
            toast = getString(R.string.libretro_state_error)
            closeMenu()
        }
    }

    /** « Charger l'état » : historique des états (de l'appareil et du profil en ligne). */
    private fun openStates() {
        // Ancien état unique (version précédente) : repris dans l'historique.
        history.adopt(stateFile, account.deviceName, platform)
        statesOpen = true
    }

    /** État choisi dans l'historique : téléchargé d'abord s'il n'est qu'en ligne. */
    private fun loadSavedState(state: SavedState) {
        lifecycleScope.launch {
            val file = try {
                historyController.file(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = e.message ?: getString(R.string.libretro_state_error)
                return@launch
            }
            loadStateFile(file)
            closeMenu()
        }
    }

    private fun loadStateFile(file: File) {
        val view = retroView ?: return
        toast = when {
            !file.isFile -> getString(R.string.libretro_no_state)
            runCatching { view.unserializeState(file.readBytes(), false) }.getOrDefault(false) ->
                getString(R.string.libretro_state_loaded)
            else -> getString(R.string.libretro_state_error)
        }
    }

    /**
     * Ajoute l'état à l'historique, avec l'image du jeu à l'ouverture du menu ; profil connecté et
     * envoi automatique : mis en file d'envoi ([sendNow] : envoyé tout de suite, partie en cours).
     */
    private fun addToHistory(bytes: ByteArray, sendNow: Boolean) {
        runCatching {
            val entry = history.add(bytes, pausedFrame, account.deviceName, platform)
            if (autoUploadStates && account.state.value.signedIn) account.queueStateUpload(gameId, history, entry.id, sendNow)
        }.onFailure { account.reportError("states:$core", it.message ?: "history", rom.name) }
    }

    /** Image affichée par le jeu (copie de la surface), avant la pause du menu. */
    private fun captureFrame() {
        val view = retroView ?: return
        if (!gameReady || view.width <= 0 || view.height <= 0) return
        val bitmap = runCatching { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        runCatching {
            PixelCopy.request(view, bitmap, { result -> if (result == PixelCopy.SUCCESS) pausedFrame = bitmap }, Handler(Looper.getMainLooper()))
        }
    }

    // ---- Options du cœur (émulation en pause : lues et modifiées hors du thread d'émulation) ----

    private fun openOptions() {
        optionTab = 0
        options = readOptions()
    }

    /** L1 / R1 sur l'écran des options : onglet précédent / suivant. */
    private fun switchOptionTab(keyCode: Int, action: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> -1
            KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> return false
        }
        val tabs = CoreOptionGroups.group(options ?: return false).size
        if (editing != null || tabs < 2) return false
        if (action == KeyEvent.ACTION_DOWN) optionTab = (optionTab + direction + tabs) % tabs
        return true
    }

    private fun readOptions(): List<CoreOption> =
        retroView?.getVariables().orEmpty()
            .mapNotNull { v -> v.key?.let { CoreOption.parse(it, v.description, v.value.orEmpty()) } }
            .sortedBy { it.label.lowercase() }

    private fun setOption(option: CoreOption, value: String) {
        retroView?.updateVariables(Variable(option.key, value))
        optionsStore.set(systemId, option.key, value)
        editing = null
        options = readOptions()
    }

    /** Revient aux valeurs par défaut du cœur et oublie les options mémorisées du système. */
    private fun resetOptions() {
        optionsStore.clear(systemId)
        val changed = options.orEmpty().filter { it.value != it.default }
        retroView?.updateVariables(*changed.map { Variable(it.key, it.default) }.toTypedArray())
        options = readOptions()
        toast = getString(R.string.libretro_options_reset_done)
    }

    private fun reset() {
        retroView?.reset(false)
        closeMenu()
    }

    // ---- Manettes physiques et télécommande ----

    /**
     * Envoie un bouton RetroPad en ignorant les répétitions (touche maintenue, gâchette analogique).
     * Start + Select ensemble ouvrent le menu (manettes sans touche Retour, comme la DualShock 3).
     */
    private fun sendButton(action: Int, retroKey: Int, port: Int) {
        val view = retroView ?: return
        val keys = heldButtons.getOrPut(port) { mutableSetOf() }
        when (action) {
            KeyEvent.ACTION_DOWN -> if (!keys.add(retroKey)) return
            KeyEvent.ACTION_UP -> if (!keys.remove(retroKey)) return
            else -> return
        }
        view.sendKeyEvent(action, retroKey, port)
        if (KeyEvent.KEYCODE_BUTTON_START in keys && KeyEvent.KEYCODE_BUTTON_SELECT in keys) openMenu()
    }

    /** Relâche tous les boutons enfoncés (ouverture du menu : pas de touche bloquée au retour). */
    private fun releaseButtons() {
        val view = retroView
        heldButtons.forEach { (port, keys) -> keys.forEach { view?.sendKeyEvent(KeyEvent.ACTION_UP, it, port) } }
        heldButtons.clear()
        axisButtons.clear()
    }

    override fun dispatchKeyEvent(original: KeyEvent): Boolean {
        val event = gamepadButtonEvent(original)
        mappingSession?.let { session ->
            return session.onKey(event) || super.dispatchKeyEvent(event)
        }
        val view = retroView
        if (view == null || phase != Phase.Running) return super.dispatchKeyEvent(event)
        val mapping = event.device?.let(gamepadMappings::get)
        if (menuOpen) {
            if (switchOptionTab(mapping?.keys?.get(event.keyCode) ?: event.keyCode, event.action)) return true
            return super.dispatchKeyEvent(mapping?.let { menuNavigation(event, it) } ?: event)
        }

        val key = if (mapping != null) mapping.retroKey(event.keyCode) else GamepadInput.retroKey(event.keyCode)
        if (key == null) {
            if (event.keyCode !in GamepadInput.MENU_KEYS) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        sendButton(event.action, key, localPort(GamepadInput.port(event)))
        if (showTouchPad && event.isFromSource(InputDevice.SOURCE_GAMEPAD)) showTouchPad = false
        return true
    }

    /**
     * Touche Retour ou Menu envoyée par un bouton de manette (DualShock 3 en Bluetooth : Rond ou
     * Select) : remplacée par ce bouton, pour qu'il serve au jeu au lieu d'ouvrir le menu.
     */
    private fun gamepadButtonEvent(event: KeyEvent): KeyEvent {
        val device = event.device ?: return event
        if (!GamepadInput.isGamepad(device)) return event
        val button = GamepadInput.buttonOfSystemKey(event.keyCode, event.scanCode) ?: return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, button, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    /**
     * Manette configurée, dans le menu : ses boutons reprennent leur rôle de navigation
     * (croix = déplacement, bouton du bas = valider, bouton de droite = retour).
     */
    private fun menuNavigation(event: KeyEvent, mapping: GamepadMapping): KeyEvent? {
        val nav = when (val key = mapping.keys[event.keyCode]) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> key
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BACK
            else -> return null
        }
        return KeyEvent(
            event.downTime, event.eventTime, event.action, nav, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        mappingSession?.let { session ->
            return session.onMotion(event) || super.dispatchGenericMotionEvent(event)
        }
        val view = retroView
        if (view == null || phase != Phase.Running || menuOpen ||
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        val port = localPort(((event.device?.controllerNumber ?: 0) - 1).coerceAtLeast(0))
        val mapping = event.device?.let(gamepadMappings::get)
        fun axis(axis: Int?, invert: Boolean, default: Int) =
            event.getAxisValue(axis ?: default) * if (axis != null && invert) -1f else 1f

        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
            axis(mapping?.rightStickX, mapping?.invertRightX == true, MotionEvent.AXIS_Z),
            axis(mapping?.rightStickY, mapping?.invertRightY == true, MotionEvent.AXIS_RZ),
            port,
        )

        // Gâchettes analogiques -> L2 / R2 (celles de la configuration, sinon les axes standard d'Android).
        // Seul ce qu'un axe a enfoncé est relâché par cet axe (pas un bouton tenu par une touche).
        val byAxis = axisButtons.getOrPut(port) { mutableSetOf() }
        fun axisButton(retroKey: Int, pressed: Boolean) {
            if (pressed && byAxis.add(retroKey)) sendButton(KeyEvent.ACTION_DOWN, retroKey, port)
            if (!pressed && byAxis.remove(retroKey)) sendButton(KeyEvent.ACTION_UP, retroKey, port)
        }
        mapping?.axes?.forEach { axisButton(it.retroKey, it.isPressed(event.getAxisValue(it.axis))) }
        fun trigger(retroKey: Int, vararg axes: Int) {
            if (mapping != null && (retroKey in mapping.keys.values || mapping.axes.any { it.retroKey == retroKey })) return
            axisButton(retroKey, axes.maxOf { event.getAxisValue(it) } > 0.5f)
        }
        trigger(KeyEvent.KEYCODE_BUTTON_L2, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        trigger(KeyEvent.KEYCODE_BUTTON_R2, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)

        showTouchPad = false
        return true
    }

    // ---- Interface superposée au jeu ----

    @Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize()) {
            when (val p = phase) {
                is Phase.Loading -> Centered {
                    Text(p.text, textAlign = TextAlign.Center)
                    if (p.fraction != null) {
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    p.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    FocusedButton(stringResource(R.string.action_cancel), ::finish)
                }
                is Phase.Failed -> Centered {
                    Text(p.message, textAlign = TextAlign.Center)
                    FocusedButton(stringResource(R.string.action_close), ::finish)
                }
                Phase.Running -> {
                    val opts = options
                    val edited = editing
                    val session = mappingSession
                    if (menuOpen && session != null) {
                        MappingScreen(session)
                    } else if (menuOpen && filterPicker) {
                        FilterPicker()
                    } else if (menuOpen && aspectPicker) {
                        AspectPicker()
                    } else if (menuOpen && statesOpen) {
                        StateHistoryList(
                            controller = historyController,
                            title = stringResource(R.string.states_title),
                            loadLabel = stringResource(R.string.states_load),
                            onLoad = ::loadSavedState,
                            onClose = { statesOpen = false },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    } else if (menuOpen && edited != null) {
                        ValuePicker(edited)
                    } else if (menuOpen && opts != null) {
                        OptionsScreen(opts)
                    } else if (menuOpen) {
                        PauseMenu()
                        if (showCast) CastDialog(onDismiss = { showCast = false })
                    } else if (showTouchPad) {
                        TouchGamepad(
                            layout = padLayout,
                            onKey = { action, key -> retroView?.sendKeyEvent(action, key, 0) },
                            onAnalog = { right, x, y ->
                                val source = if (right) GLRetroView.MOTION_SOURCE_ANALOG_RIGHT else GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                                retroView?.sendMotionEvent(source, x, y, 0)
                            },
                            onMenu = ::openMenu,
                        )
                    } else if (!isTv) {
                        // Manette tactile masquée (manette physique utilisée) : un appui la réaffiche.
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showTouchPad = true } })
                    }
                }
            }
            // Partie à plusieurs : proposée, en cours, ou en attente des touches de l'autre joueur.
            val netplayText = when {
                phase != Phase.Running || menuOpen -> null
                netplayWaiting -> stringResource(R.string.netplay_waiting, netplayPartner.orEmpty())
                netplayPartner != null && link != null -> stringResource(R.string.link_started, netplayPartner.orEmpty())
                netplayPartner != null -> stringResource(R.string.netplay_playing_with, netplayPartner.orEmpty())
                // Partie aussi proposée par Internet : état de l'annonce et du relais (problème : sa raison).
                netplayOpen && relaySession != null -> internetProblem?.let {
                    if (it == com.romcloud.app.netplay.InternetPresence.SIGNED_OUT) stringResource(R.string.netplay_open)
                    else stringResource(R.string.netplay_open_internet_failed, it)
                } ?: stringResource(R.string.netplay_open_internet)
                netplayOpen -> stringResource(R.string.netplay_open)
                else -> null
            }
            netplayText?.let { text ->
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (streamingTo != null) 36.dp else 8.dp),
                ) {
                    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
            netplayRequest?.let { (join, answer) ->
                AlertDialog(
                    onDismissRequest = { answer.complete(false) },
                    title = { Text(stringResource(if (link != null) R.string.link_request_title else R.string.netplay_request_title)) },
                    text = { Text(stringResource(if (link != null) R.string.link_request_text else R.string.netplay_request_text, join.name)) },
                    confirmButton = { FocusedButton(stringResource(R.string.netplay_accept), { answer.complete(true) }) },
                    dismissButton = { TextButton(onClick = { answer.complete(false) }) { Text(stringResource(R.string.netplay_refuse)) } },
                )
            }
            // Diffusion en cours : rappel discret (vue superposée, absente de l'image envoyée à la TV).
            streamingTo?.takeIf { phase == Phase.Running && !menuOpen }?.let { name ->
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.stream_on_tv, name),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            toast?.let { text ->
                LaunchedEffect(text) {
                    delay(toastMillis)
                    toast = null
                    toastMillis = TOAST_MS
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                ) { Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
        }
    }

    @Composable
    private fun PauseMenu() {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            // Entrées du menu (libellé, action), affichées sur deux colonnes ; « Reprendre » en premier.
            val items = buildList<Pair<String, () -> Unit>> {
                add(stringResource(R.string.libretro_menu_resume) to ::closeMenu)
                // Partie à plusieurs : charger un état, redémarrer ou changer une option ne se ferait que
                // sur cet appareil (parties désynchronisées).
                val together = netplayPartner != null
                if (gameReady) {
                    add(stringResource(R.string.libretro_menu_save_state) to ::saveState)
                    if (!together) {
                        add(stringResource(R.string.libretro_menu_load_state) to ::openStates)
                        add(stringResource(R.string.libretro_menu_reset) to ::reset)
                        add(stringResource(R.string.libretro_menu_core_options) to ::openOptions)
                    }
                    add(stringResource(R.string.libretro_menu_filter, stringResource(videoFilter.label)) to { filterPicker = true })
                    add(stringResource(R.string.libretro_menu_aspect, stringResource(aspectRatio.label)) to { aspectPicker = true })
                }
                if (!isTv) {
                    add(
                        stringResource(if (showTouchPad) R.string.libretro_menu_hide_touchpad else R.string.libretro_menu_show_touchpad) to {
                            showTouchPad = !showTouchPad
                            closeMenu()
                        },
                    )
                }
                add(
                    stringResource(R.string.libretro_menu_gamepad) to {
                        mappingSession = MappingSession(gamepadMappings, mappingSteps(padLayout)) { message ->
                            toast = getString(message)
                            mappingSession = null
                        }
                    },
                )
                if (!isTv) add(stringResource(R.string.cast_button) to { showCast = true })
                val target = streamTarget
                if (streamSender != null) {
                    add(stringResource(R.string.stream_menu_stop) to {
                        stopStreaming()
                        closeMenu()
                    })
                } else if (target != null && gameReady) {
                    add(stringResource(R.string.stream_menu_start, target.name) to {
                        retroView?.let { startStreaming(it, target) }
                        closeMenu()
                    })
                }
                // Liaison ouverte par le cœur (Game Boy, PSP) : coupée seulement en quittant le jeu.
                if ((netplayPartner != null || netplayOpen) && link?.packets != false) {
                    add(stringResource(if (link != null) R.string.link_menu_leave else R.string.netplay_menu_leave) to ::leaveNetplay)
                }
                if (gameReady) add(stringResource(R.string.libretro_menu_save_quit) to ::saveAndQuit)
                add(stringResource(R.string.libretro_menu_quit) to ::finish)
            }
            Column(
                // Défilement : téléphone en paysage, menu plus haut que l'écran.
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.chunked(2).forEachIndexed { row, pair ->
                    // Même hauteur pour les deux boutons d'une ligne (libellé sur deux lignes).
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { column, (text, action) ->
                            val modifier = Modifier.weight(1f).fillMaxHeight()
                            if (row == 0 && column == 0) FocusedButton(text, action, modifier) else MenuButton(text, action, modifier)
                        }
                        // Nombre impair d'entrées : la dernière garde la largeur d'une colonne.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    stringResource(R.string.libretro_menu_combo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun MappingScreen(session: MappingSession) {
        // Manette de la console, consignes et boutons en dessous ; récapitulatif des attributions à
        // droite (en bas sur écran étroit).
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        Surface(color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
            if (wide) {
                Row(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { MappingInstructions(session) }
                    MappingSummary(session, Modifier.width(280.dp).fillMaxHeight())
                }
            } else {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MappingInstructions(session)
                    MappingSummary(session, Modifier.fillMaxWidth(), lazy = false)
                }
            }
        }
    }

    /** Manette de la console, puis l'étape en cours et les boutons de la configuration. */
    @Composable
    private fun MappingInstructions(session: MappingSession) {
        val name = session.deviceName
        val step = session.step
        PadPreview(padLayout, session.steps, step.takeIf { name != null }, session.done)
        Text(stringResource(R.string.pad_config_title), style = MaterialTheme.typography.titleMedium)
        if (name == null) {
            Text(stringResource(R.string.pad_config_press_any), textAlign = TextAlign.Center)
        } else {
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (step != null) {
                Text(
                    stringResource(R.string.pad_config_step, session.stepIndex + 1, session.steps.size),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    step.label ?: stringResource(step.labelRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                if (step.label != null && step.labelRes != 0) {
                    Text(stringResource(step.labelRes), textAlign = TextAlign.Center)
                }
                Text(
                    stringResource(if (step.stick != null) R.string.pad_config_hint_stick else R.string.pad_config_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            session.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (name != null) {
                TextButton(onClick = session::skip) { Text(stringResource(R.string.action_skip)) }
                TextButton(onClick = session::resetDevice) { Text(stringResource(R.string.pad_config_default)) }
            }
            FocusedButton(stringResource(R.string.action_cancel), { mappingSession = null })
        }
    }

    /**
     * Récapitulatif : chaque commande de la console et le bouton (ou l'axe) de la manette qui lui
     * est attribué ; l'étape en cours est mise en évidence et gardée visible ([lazy]).
     */
    @Composable
    private fun MappingSummary(session: MappingSession, modifier: Modifier, lazy: Boolean = true) {
        val current = session.stepIndex.takeIf { session.deviceName != null }
        val row = @Composable { index: Int, step: MappingStep ->
            val label = listOfNotNull(
                step.label,
                step.labelRes.takeIf { it != 0 && (step.label == null || step.stick != null) }?.let { stringResource(it) },
            ).joinToString(" · ")
            val color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index == current) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(label, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    session.assigned[step] ?: "—",
                    color = if (step in session.assigned) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pad_config_summary),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (lazy) {
                val list = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                    itemsIndexed(session.steps) { index, step -> row(index, step) }
                }
                LaunchedEffect(current) { current?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
            } else {
                session.steps.forEachIndexed { index, step -> row(index, step) }
            }
        }
    }

    @Composable
    private fun OptionsScreen(list: List<CoreOption>) {
        val focus = remember { FocusRequester() }
        val groups = remember(list) { CoreOptionGroups.group(list) }
        val generalTitle = stringResource(R.string.libretro_options_general)
        val tab = optionTab.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        // Une grille par onglet : chacun repart du haut. Deux colonnes, une seule sur écran étroit.
        val gridState = remember(tab) { LazyGridState() }
        val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 2 else 1
        Surface(color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { options = null }, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.action_back))
                    }
                    Text(
                        stringResource(R.string.libretro_options_title, core),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (list.isNotEmpty()) {
                        TextButton(onClick = ::resetOptions) { Text(stringResource(R.string.libretro_options_reset)) }
                    }
                }
                Text(
                    stringResource(if (list.isEmpty()) R.string.libretro_options_none else R.string.libretro_options_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (groups.size > 1) {
                    ScrollableTabRow(
                        selectedTabIndex = tab,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        groups.forEachIndexed { index, group ->
                            Tab(
                                selected = index == tab,
                                onClick = { optionTab = index },
                                text = { Text("${group.title ?: generalTitle} (${group.options.size})") },
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Télécommande et manette : l'onglet qui reçoit le focus s'affiche.
                                modifier = Modifier.onFocusChanged { if (it.isFocused) optionTab = index },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.libretro_options_tabs_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    OptionItems(groups.getOrNull(tab)?.options.orEmpty())
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    private fun LazyGridScope.OptionItems(list: List<CoreOption>) {
        items(list, key = { it.key }) { option ->
            ListItem(
                headlineContent = { Text(option.label) },
                supportingContent = { Text(option.key, style = MaterialTheme.typography.labelSmall) },
                trailingContent = {
                    // Valeur modifiée : mise en évidence.
                    Text(
                        option.value,
                        color = if (option.value == option.default) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = option.values.size > 1) { editing = option },
            )
        }
    }

    @Composable
    private fun ValuePicker(option: CoreOption) {
        val focus = remember { FocusRequester() }
        val current = option.values.indexOf(option.value).coerceAtLeast(0)
        Surface(color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = current),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                item {
                    Text(option.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(option.values) { value ->
                    val selected = value == option.value
                    ListItem(
                        headlineContent = { Text(value) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        supportingContent = if (value == option.default) {
                            { Text(stringResource(R.string.libretro_options_default), style = MaterialTheme.typography.labelSmall) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .then(if (selected) Modifier.focusRequester(focus) else Modifier)
                            .clickable { setOption(option, value) },
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    /**
     * Choix du filtre d'image : appliqué et jeu repris aussitôt (le rendu est suspendu tant que le
     * menu est ouvert), pour voir le résultat ; le menu permet d'en essayer un autre.
     */
    @Composable
    private fun FilterPicker() {
        ChoicePicker(
            stringResource(R.string.libretro_filter_title),
            VideoFilter.entries,
            videoFilter,
            label = { stringResource(it.label).replaceFirstChar { c -> c.uppercase() } },
            description = { stringResource(it.description) },
        ) { filter ->
            videoFilter = filter
            videoFilters.save(systemId, filter)
            retroView?.shader = filter.shader()
            closeMenu()
        }
    }

    /**
     * Choix du format de l'image, mémorisé pour le système : appliqué et jeu repris aussitôt quand
     * le cœur passe par l'adaptateur, sinon au prochain lancement.
     */
    @Composable
    private fun AspectPicker() {
        ChoicePicker(
            stringResource(R.string.libretro_aspect_title),
            AspectRatio.entries,
            aspectRatio,
            label = { stringResource(it.label).replaceFirstChar { c -> c.uppercase() } },
        ) { aspect ->
            val changed = aspect != aspectRatio
            aspectRatio = aspect
            aspectRatios.save(systemId, aspect)
            if (throughShim) applyAspectRatio() else if (changed) toast = getString(R.string.libretro_aspect_next_launch)
            closeMenu()
        }
    }

    /** Format d'image envoyé à l'adaptateur (« étiré » : proportions de la vue du jeu). */
    private fun applyAspectRatio() {
        if (!throughShim) return
        val view = retroView ?: container
        CoreShim.setAspectRatio(aspectRatio.ratio(view.width, view.height))
    }

    /** Liste de choix (un seul sélectionné) ; [onPick] reçoit le choix touché. */
    @Composable
    private fun <T> ChoicePicker(
        title: String,
        choices: List<T>,
        selected: T,
        label: @Composable (T) -> String,
        description: (@Composable (T) -> String)? = null,
        onPick: (T) -> Unit,
    ) {
        val focus = remember { FocusRequester() }
        Surface(color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = choices.indexOf(selected).coerceAtLeast(0)),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                item {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(choices) { choice ->
                    val isSelected = choice == selected
                    ListItem(
                        headlineContent = { Text(label(choice)) },
                        supportingContent = description?.let { { Text(it(choice), style = MaterialTheme.typography.bodySmall) } },
                        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .then(if (isSelected) Modifier.focusRequester(focus) else Modifier)
                            .clickable { onPick(choice) },
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun MenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text, textAlign = TextAlign.Center) }
    }

    /** Bouton qui prend le focus à l'affichage (navigation à la manette ou à la télécommande). */
    @Composable
    private fun FocusedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val focus = remember { FocusRequester() }
        Button(onClick = onClick, modifier = modifier.focusRequester(focus)) { Text(text, textAlign = TextAlign.Center) }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }

    companion object {
        private const val EXTRA_CORE = "core"
        private const val EXTRA_ROM = "rom"
        private const val EXTRA_SYSTEM_DIR = "systemDir"
        private const val EXTRA_SYSTEM = "system"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_GAME_ID = "gameId"
        private const val EXTRA_STREAM = "stream"
        private const val EXTRA_STREAM_MODE = "streamMode"
        private const val EXTRA_NETPLAY = "netplay"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_AUTO_UPLOAD_STATES = "autoUploadStates"
        private const val TOAST_MS = 2000L
        private const val ERROR_TOAST_MS = 8000L
        private const val RETRO_DEVICE_JOYPAD = 1
        private const val RETRO_DEVICE_MASK = 0xff
        private const val AUDIO_SHIM = "libromcloud_audio_shim.so"
        /** Cœurs dont le son passe par retro_audio_sample (un échantillon à la fois). */
        private val SAMPLE_AUDIO_CORES = setOf("cap32")
        /** Cœurs dont l'image reste noire avec LibretroDroid (Dreamcast, GameCube) : copiée à l'écran par l'adaptateur. */
        private val BLIT_CORES = setOf("flycast", "dolphin")
        /** Cœurs qui lisent les boutons d'un coup (RETRO_DEVICE_ID_JOYPAD_MASK) : masque reconstitué par l'adaptateur. */
        private val INPUT_MASK_CORES = setOf("pcsx2")

        /** État de sauvegarde d'un jeu pour un cœur (« Sauvegarder l'état », « Sauvegarder et quitter »). */
        fun stateFile(context: Context, core: String, rom: File): File =
            File(context.filesDir, "libretro/states/$core/${rom.nameWithoutExtension}.state")

        /**
         * [systemId] : système du jeu (options du cœur mémorisées par système) ;
         * [systemDir] : dossier des BIOS (dossier « system » libretro) ;
         * [resume] : reprend la partie à son état sauvegardé ;
         * [stream] : TV du profil sur laquelle diffuser le jeu ; [streamMode] : image diffusée ;
         * [state] : état de l'historique chargé au lancement ; [autoUploadStates] : états envoyés en ligne
         * (réglage lu par l'application : ce processus garde ses préférences en mémoire).
         */
        fun intent(
            context: Context,
            systemId: String,
            core: String,
            rom: File,
            systemDir: String,
            resume: Boolean = false,
            gameId: Long = 0,
            stream: StreamReceiver? = null,
            streamMode: StreamMode = StreamMode.NATIVE,
            netplay: NetplayLaunch? = null,
            state: File? = null,
            autoUploadStates: Boolean = true,
        ): Intent =
            Intent(context, LibretroActivity::class.java)
                .putExtra(EXTRA_SYSTEM, systemId)
                .putExtra(EXTRA_CORE, core)
                .putExtra(EXTRA_ROM, rom.absolutePath)
                .putExtra(EXTRA_SYSTEM_DIR, systemDir)
                .putExtra(EXTRA_RESUME, resume)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_STREAM, stream?.let { StreamProtocol.json.encodeToString(StreamReceiver.serializer(), it) })
                .putExtra(EXTRA_STREAM_MODE, streamMode.name)
                .putExtra(EXTRA_NETPLAY, netplay?.let { NetplayProtocol.json.encodeToString(NetplayLaunch.serializer(), it) })
                .putExtra(EXTRA_STATE, state?.absolutePath)
                .putExtra(EXTRA_AUTO_UPLOAD_STATES, autoUploadStates)
    }
}
