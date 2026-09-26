package com.romcloud.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.romcloud.app.data.ApiClient
import com.romcloud.app.data.DownloadService
import com.romcloud.app.data.Downloader
import com.romcloud.app.data.LocalLibrary
import com.romcloud.app.data.Repository
import com.romcloud.app.data.Settings
import com.romcloud.app.launch.GameLauncher
import com.romcloud.app.launch.MissingEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

class RomCloudApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob())

    /** Émulateur manquant détecté au lancement : affiche la proposition d'installation. */
    val missingEmulator = MutableStateFlow<MissingEmulator?>(null)

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

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        api = ApiClient(settings)
        repository = Repository(api, filesDir)
        library = LocalLibrary(this, settings)
        downloader = Downloader(this, api, library, appScope)
        launcher = GameLauncher(this, settings)
        DownloadService.createChannel(this)
    }

    /** Coil partage le client OkHttp (et donc la clé d'API) pour charger les jaquettes. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(api.http)
            .crossfade(true)
            .build()
}
