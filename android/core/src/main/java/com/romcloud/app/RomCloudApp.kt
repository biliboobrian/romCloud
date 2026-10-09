package com.romcloud.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.romcloud.app.data.Account
import com.romcloud.app.data.ApiClient
import com.romcloud.app.data.ApkInstaller
import com.romcloud.app.data.AppUpdater
import com.romcloud.app.data.Connectivity
import com.romcloud.app.data.DownloadEvent
import com.romcloud.app.data.DownloadService
import com.romcloud.app.data.Downloader
import com.romcloud.app.data.LocalLibrary
import com.romcloud.app.data.Repository
import com.romcloud.app.data.Settings
import com.romcloud.app.launch.CloseEmulatorPrompt
import com.romcloud.app.launch.GameLauncher
import com.romcloud.app.launch.MissingEmulator
import com.romcloud.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RomCloudApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob())

    /** Émulateur manquant détecté au lancement : affiche la proposition d'installation. */
    val missingEmulator = MutableStateFlow<MissingEmulator?>(null)

    /** Lancement en attente de la fermeture de l'émulateur par l'utilisateur. */
    val closeEmulatorPrompt = MutableStateFlow<CloseEmulatorPrompt?>(null)

    /** Jeux à lancer automatiquement dès la fin de leur téléchargement. */
    val autoLaunch: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    lateinit var settings: Settings
        private set
    lateinit var api: ApiClient
        private set
    lateinit var repository: Repository
        private set
    lateinit var library: LocalLibrary
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var launcher: GameLauncher
        private set
    lateinit var apkInstaller: ApkInstaller
        private set
    lateinit var updater: AppUpdater
        private set
    /** Profil du joueur : temps de jeu, sauvegardes en ligne, erreurs signalées. */
    lateinit var account: Account
        private set
    /** Serveur joignable ou non (pastille « hors ligne », synchronisation au retour de la connexion). */
    lateinit var connectivity: Connectivity
        private set

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        settings = Settings(this)
        api = ApiClient(settings, if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) "androidtv" else "android")
        repository = Repository(api, filesDir, { settings.hideUnidentified }, { settings.gameOrder })
        library = LocalLibrary(this, settings)
        downloader = Downloader(this, api, library, appScope)
        launcher = GameLauncher(this, settings)
        apkInstaller = ApkInstaller(this, api, appScope)
        updater = AppUpdater(this, api)
        account = Account(this, api, appScope)
        connectivity = Connectivity(this, api, appScope)
        // Processus de l'émulateur intégré : ni suivi de la connexion, ni envoi du temps de jeu.
        if (isMainProcess()) watchConnection()
        // Plantage de l'application : signalé à l'administration au lancement suivant.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences("romcloud_account", MODE_PRIVATE).edit()
                    .putString("pendingCrash", "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString().take(8000)}")
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
        getSharedPreferences("romcloud_account", MODE_PRIVATE).getString("pendingCrash", null)?.let { crash ->
            getSharedPreferences("romcloud_account", MODE_PRIVATE).edit().remove("pendingCrash").apply()
            account.reportError("crash", crash.lineSequence().first(), crash)
        }
        DownloadService.createChannel(this)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim(' ', '\u0000') }.getOrNull()
        }
        return name == null || name == packageName
    }

    /**
     * Retour de la connexion : sauvegardes et temps de jeu en attente envoyés, profil relu. Les jeux
     * téléchargés sont notés pour rester listés hors ligne.
     */
    private fun watchConnection() {
        connectivity.start()
        appScope.launch {
            connectivity.reconnected.collect {
                val synced = account.flush()
                account.refresh()
                if (synced > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RomCloudApp, I18n.plural(R.plurals.offline_synced, synced, synced), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        appScope.launch {
            downloader.events.collect { event ->
                if (event is DownloadEvent.Completed && event.romIncluded) {
                    withContext(Dispatchers.IO) { repository.rememberDownloaded(event.system, event.game) }
                }
            }
        }
    }

    /**
     * Coil partage le client OkHttp (et donc la clé d'API) pour charger les jaquettes. Leurs adresses
     * changent avec le jeu (?v=…) : le cache disque est utilisé sans revalidation, donc aussi hors ligne.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(api.http)
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
}
