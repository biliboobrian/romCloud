package com.romcloud.app.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import com.romcloud.app.I18n
import com.romcloud.core.R
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installation d'un émulateur depuis la dernière Release de l'émulateur ou un APK du serveur (ou
 * d'une mise à jour de RomCloud depuis GitHub, [EmulatorApk.url]) : téléchargement dans le cache de
 * l'application (avec reprise), puis ouverture de l'installateur d'Android. Android demande
 * d'autoriser RomCloud à installer des applications (« sources inconnues ») la première fois.
 */
class ApkInstaller(
    private val context: Context,
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    data class Progress(val apk: EmulatorApk, val bytes: Long) {
        val fraction: Float get() = if (apk.size > 0) (bytes.toFloat() / apk.size).coerceIn(0f, 1f) else 0f
    }

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Téléchargement en cours (null sinon). */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _pending = MutableStateFlow<EmulatorApk?>(null)

    /** APK en attente de l'autorisation « installer des applications inconnues ». */
    val pending: StateFlow<EmulatorApk?> = _pending.asStateFlow()

    private var job: Job? = null

    private val dir get() = File(context.externalCacheDir ?: context.cacheDir, "apks")

    /** RomCloud a-t-il le droit d'ouvrir l'installateur d'applications ? */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Installe l'APK : sans l'autorisation, il est mis en attente ([pending]) et l'interface
     * propose d'ouvrir le réglage correspondant ; [resumePending] reprend ensuite.
     */
    fun install(activity: Activity, apk: EmulatorApk) {
        if (!canInstall()) {
            _pending.value = apk
            return
        }
        _pending.value = null
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val file = File(dir, "${apk.packageName}-${apk.versionCode ?: apk.versionName ?: apk.id}.apk")
                // Anciennes versions du même paquet : inutiles.
                dir.listFiles { f -> f.name.startsWith("${apk.packageName}-") && !f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
                if (!(file.isFile && file.length() == apk.size)) {
                    _progress.value = Progress(apk, 0)
                    fetchFile(api, apk.url ?: api.apkFileUrl(apk), file, apk.size, { _progress.value = Progress(apk, it) }) {
                        I18n.get(R.string.err_cannot_create, it.absolutePath)
                    }
                }
                _progress.value = null
                // APK d'une Release : il doit bien installer le paquet attendu (sinon l'émulateur
                // resterait introuvable au lancement).
                val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
                if (archive != null && archive != apk.packageName) {
                    file.delete()
                    throw IllegalStateException(I18n.get(R.string.apk_wrong_package, archive, apk.packageName))
                }
                withContext(Dispatchers.Main) { openInstaller(activity, file) }
            } catch (e: CancellationException) {
                _progress.value = null
                throw e
            } catch (e: Exception) {
                _progress.value = null
                _errors.emit(I18n.get(R.string.apk_install_failed, apk.label, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = null
    }

    fun dismissPending() {
        _pending.value = null
    }

    /** Au retour du réglage : installe l'APK en attente si l'autorisation a été donnée. */
    fun resumePending(activity: Activity) {
        val apk = _pending.value ?: return
        if (canInstall()) install(activity, apk)
    }

    /** Réglage Android « Installer des applications inconnues » pour RomCloud. */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intents = listOf(
            Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(AndroidSettings.ACTION_SECURITY_SETTINGS),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // réglage suivant
            }
        }
        _errors.tryEmit(I18n.get(R.string.settings_unavailable))
    }

    private suspend fun openInstaller(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            _errors.emit(I18n.get(R.string.apk_no_installer))
        }
    }
}
