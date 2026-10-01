package com.romcloud.app.libretro

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Arrêt brutal de l'émulateur intégré, à signaler à l'utilisateur au retour dans l'application. */
internal data class CrashReport(val core: String, val game: String, val text: String, val systemId: String = "")

/**
 * Rapports de plantage de l'émulateur intégré. Un plantage d'un cœur (code natif) tue le processus
 * de jeu sans laisser de trace exploitable par l'utilisateur : pendant la partie, le journal du
 * processus est recopié dans un fichier ; au retour dans l'application, une session restée
 * « en cours » (et, depuis Android 11, confirmée par la raison de fin du processus) donne un rapport
 * à partager.
 */
internal object CrashReports {

    private const val MAX_LOG_BYTES = 512 * 1024
    private const val TAIL_LINES = 200

    private fun dir(context: Context) = File(context.filesDir, "libretro").apply { mkdirs() }
    private fun marker(context: Context) = File(dir(context), "session.txt")
    private fun log(context: Context) = File(dir(context), "session.log")

    /** Début de partie (processus de jeu) : note la session et recopie le journal du processus. */
    fun startSession(context: Context, core: String, game: String, systemId: String) {
        marker(context).writeText(
            listOf(
                "core=$core",
                "game=$game",
                "system=$systemId",
                "pid=${android.os.Process.myPid()}",
                "started=${System.currentTimeMillis()}",
                "device=${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.firstOrNull()})",
            ).joinToString("\n"),
        )
        val file = log(context)
        thread(name = "libretro-log", isDaemon = true) {
            runCatching {
                val logcat = ProcessBuilder("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true)
                    .start()
                file.outputStream().bufferedWriter().use { out ->
                    var written = 0
                    logcat.inputStream.bufferedReader().forEachLine { line ->
                        if (written < MAX_LOG_BYTES) {
                            out.write(line)
                            out.newLine()
                            out.flush()
                            written += line.length + 1
                        }
                    }
                }
            }
        }
    }

    /** Fin normale (l'utilisateur quitte le jeu) : pas de rapport. */
    fun endSession(context: Context) {
        marker(context).delete()
    }

    /**
     * Rapport de la dernière session si elle s'est terminée par un plantage ; consommé (renvoyé
     * une seule fois). Appelé depuis le processus principal.
     */
    fun takePending(context: Context): CrashReport? {
        val markerFile = marker(context)
        if (!markerFile.isFile) return null
        val info = markerFile.readLines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val pid = info["pid"]?.toIntOrNull()
        // Processus de jeu encore vivant (partie en cours, ou en train de se fermer normalement).
        if (pid != null && isRunning(context, pid)) return null
        markerFile.delete()
        val exit = exitInfo(context, pid)
        // Android 11+ : le processus a pu être arrêté par le système (mémoire), ce n'est pas un plantage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && exit != null && !exit.isCrash) return null
        val text = buildString {
            appendLine("RomCloud — plantage de l'émulateur intégré (LibretroDroid)")
            info["started"]?.toLongOrNull()?.let {
                appendLine("Date : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(it))}")
            }
            appendLine("Cœur : ${info["core"]}")
            appendLine("Système : ${info["system"]}")
            appendLine("Jeu : ${info["game"]}")
            appendLine("Appareil : ${info["device"]}")
            exit?.let { appendLine("Fin du processus : ${it.summary}") }
            exit?.tombstone?.let {
                appendLine()
                appendLine("--- Trace native ---")
                appendLine(it)
            }
            appendLine()
            appendLine("--- Journal (dernières lignes) ---")
            log(context).takeIf { it.isFile }?.readLines()?.takeLast(TAIL_LINES)?.forEach { appendLine(it) }
        }
        return CrashReport(info["core"].orEmpty(), File(info["game"].orEmpty()).name, text, info["system"].orEmpty())
    }

    private fun isRunning(context: Context, pid: Int): Boolean =
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty().any { it.pid == pid }

    private class ExitSummary(val isCrash: Boolean, val summary: String, val tombstone: String?)

    private fun exitInfo(context: Context, pid: Int?): ExitSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pid == null) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, pid, 1) }
            .getOrNull()?.firstOrNull() ?: return null
        val crash = exit.reason == ApplicationExitInfo.REASON_CRASH ||
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exit.reason == ApplicationExitInfo.REASON_ANR
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif (signal ${exit.status})"
            ApplicationExitInfo.REASON_CRASH -> "exception Java"
            ApplicationExitInfo.REASON_ANR -> "application figée (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire insuffisante"
            else -> "raison ${exit.reason}"
        }
        return ExitSummary(crash, listOfNotNull(reason, exit.description).joinToString(" — "), tombstoneText(exit))
    }

    /**
     * Trace du plantage natif (« tombstone ») : au format protobuf depuis Android 12, on n'en garde
     * que les textes lisibles (message d'erreur, bibliothèques et fonctions de la pile d'appels).
     */
    private fun tombstoneText(exit: ApplicationExitInfo): String? {
        if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return null
        val strings = Regex("[\\x20-\\x7E]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }
        return strings
            .filter { it.contains(".so") || it.contains("signal", true) || it.contains("abort", true) || it.startsWith("_Z") }
            .distinct()
            .take(80)
            .joinToString("\n")
            .ifBlank { null }
    }
}
