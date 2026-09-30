package com.romcloud.app.libretro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import com.romcloud.app.ui.formatSize
import com.romcloud.core.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private var retroView: GLRetroView? = null
    /** Premier image affichée : le cœur et le jeu sont chargés (sauvegardes possibles). */
    private var gameReady = false
    private lateinit var container: FrameLayout

    private val core by lazy { intent.getStringExtra(EXTRA_CORE).orEmpty() }
    private val rom by lazy { File(intent.getStringExtra(EXTRA_ROM).orEmpty()) }
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    private val sramFile by lazy { File(filesDir, "libretro/saves/$core/${rom.nameWithoutExtension}.srm") }
    private val stateFile by lazy { File(filesDir, "libretro/states/$core/${rom.nameWithoutExtension}.state") }

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
                setContent { MaterialTheme(colorScheme = darkColorScheme()) { Overlay() } }
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(container)

        onBackPressedDispatcher.addCallback(this) {
            when {
                phase != Phase.Running -> finish()
                menuOpen -> closeMenu()
                else -> openMenu()
            }
        }
        lifecycleScope.launch { prepare() }
    }

    private suspend fun prepare() {
        try {
            if (!rom.isFile) {
                phase = Phase.Failed(getString(R.string.err_file_not_found, rom.absolutePath))
                return
            }
            val cores = LibretroCores(this)
            val coreFile = cores.installed(core) ?: run {
                val text = getString(R.string.libretro_downloading_core, core)
                phase = Phase.Loading(text)
                cores.download(core) { bytes, total ->
                    phase = Phase.Loading(
                        text,
                        fraction = if (total > 0) bytes.toFloat() / total else null,
                        detail = if (total > 0) "${formatSize(bytes)} / ${formatSize(total)}" else formatSize(bytes),
                    )
                }
            }
            val game = if (RomArchives.needsExtraction(core, rom)) {
                phase = Phase.Loading(getString(R.string.libretro_preparing))
                withContext(Dispatchers.IO) { RomArchives.extract(rom, File(cacheDir, "libretro-rom")) }
            } else {
                rom
            }
            startGame(coreFile, game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: getString(R.string.libretro_error_generic))
        }
    }

    private suspend fun startGame(coreFile: File, game: File) {
        val sram = withContext(Dispatchers.IO) { sramFile.takeIf { it.isFile }?.readBytes() }
        val data = GLRetroViewData(this).apply {
            coreFilePath = coreFile.absolutePath
            gameFilePath = game.absolutePath
            systemDirectory = intent.getStringExtra(EXTRA_SYSTEM_DIR) ?: filesDir.absolutePath
            savesDirectory = sramFile.parentFile!!.apply { mkdirs() }.absolutePath
            saveRAMState = sram
            preferLowLatencyAudio = true
        }
        val view = GLRetroView(this, data).apply { isFocusable = false }
        retroView = view
        lifecycle.addObserver(view)
        container.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        lifecycleScope.launch { view.getGLRetroErrors().collect(::onRetroError) }
        lifecycleScope.launch {
            view.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) gameReady = true }
        }
        phase = Phase.Running
        showTouchPad = !isTv && !GamepadInput.hasGamepad()
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
        gameReady = false
        menuOpen = false
        retroView?.let { container.removeView(it) }
    }

    // ---- Menu : l'émulation est suspendue (thread OpenGL en pause) tant qu'il est ouvert ----

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        retroView?.apply {
            audioEnabled = false
            onPause()
        }
    }

    private fun closeMenu() {
        menuOpen = false
        retroView?.apply {
            onResume()
            audioEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        // LibretroDroid relance l'émulation après onResume() : on la suspend de nouveau si le menu est ouvert.
        window.decorView.post { if (menuOpen) retroView?.onPause() }
    }

    override fun onPause() {
        super.onPause()
        // L'émulation est déjà en pause ici (LibretroDroid suit le cycle de vie de l'activité).
        saveSram()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Libère le cœur : un autre jeu pourra en charger un nouveau dans un processus neuf.
        if (isFinishing) Process.killProcess(Process.myPid())
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
            getString(R.string.libretro_state_saved)
        }.getOrElse { getString(R.string.libretro_state_error) }
        closeMenu()
    }

    private fun loadState() {
        val view = retroView ?: return
        toast = when {
            !stateFile.isFile -> getString(R.string.libretro_no_state)
            runCatching { view.unserializeState(stateFile.readBytes(), false) }.getOrDefault(false) ->
                getString(R.string.libretro_state_loaded)
            else -> getString(R.string.libretro_state_error)
        }
        closeMenu()
    }

    private fun reset() {
        retroView?.reset(false)
        closeMenu()
    }

    // ---- Manettes physiques et télécommande ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        if (view == null || phase != Phase.Running || menuOpen) return super.dispatchKeyEvent(event)
        if (event.keyCode in GamepadInput.MENU_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        val key = GamepadInput.retroKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.repeatCount == 0) view.sendKeyEvent(event.action, key, GamepadInput.port(event))
        if (showTouchPad && event.isFromSource(InputDevice.SOURCE_GAMEPAD)) showTouchPad = false
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (view == null || phase != Phase.Running || menuOpen ||
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        val port = ((event.device?.controllerNumber ?: 0) - 1).coerceAtLeast(0)
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port)
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
                    if (menuOpen) {
                        PauseMenu()
                    } else if (showTouchPad) {
                        TouchGamepad(
                            onKey = { action, key -> retroView?.sendKeyEvent(action, key, 0) },
                            onMenu = ::openMenu,
                        )
                    } else if (!isTv) {
                        // Manette tactile masquée (manette physique utilisée) : un appui la réaffiche.
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showTouchPad = true } })
                    }
                }
            }
            toast?.let { text ->
                LaunchedEffect(text) {
                    delay(2000)
                    toast = null
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
            Column(
                Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FocusedButton(stringResource(R.string.libretro_menu_resume), ::closeMenu, Modifier.fillMaxWidth())
                if (gameReady) {
                    MenuButton(stringResource(R.string.libretro_menu_save_state), ::saveState)
                    MenuButton(stringResource(R.string.libretro_menu_load_state), ::loadState)
                    MenuButton(stringResource(R.string.libretro_menu_reset), ::reset)
                }
                if (!isTv) {
                    MenuButton(
                        stringResource(if (showTouchPad) R.string.libretro_menu_hide_touchpad else R.string.libretro_menu_show_touchpad),
                    ) {
                        showTouchPad = !showTouchPad
                        closeMenu()
                    }
                }
                MenuButton(stringResource(R.string.libretro_menu_quit), ::finish)
            }
        }
    }

    @Composable
    private fun MenuButton(text: String, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
    }

    /** Bouton qui prend le focus à l'affichage (navigation à la manette ou à la télécommande). */
    @Composable
    private fun FocusedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val focus = remember { FocusRequester() }
        Button(onClick = onClick, modifier = modifier.focusRequester(focus)) { Text(text) }
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

        /** [systemDir] : dossier des BIOS (dossier « system » libretro). */
        fun intent(context: Context, core: String, rom: File, systemDir: String): Intent =
            Intent(context, LibretroActivity::class.java)
                .putExtra(EXTRA_CORE, core)
                .putExtra(EXTRA_ROM, rom.absolutePath)
                .putExtra(EXTRA_SYSTEM_DIR, systemDir)
    }
}
