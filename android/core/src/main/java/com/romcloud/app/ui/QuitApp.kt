package com.romcloud.app.ui

import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Quitte vraiment l'application (Retour puis « Quitter » sur l'écran des systèmes) : tâche fermée
 * et retirée des applications récentes, puis processus arrêtés (application et émulateur intégré),
 * au lieu de rester en mémoire. Le travail du profil en attente (temps de jeu, sauvegardes) est
 * gardé sur l'appareil et envoyé au lancement suivant.
 */
fun quitApp(activity: Activity) {
    activity.finishAndRemoveTask()
    val manager = activity.getSystemService(ActivityManager::class.java)
    // Après l'animation de fermeture.
    Handler(Looper.getMainLooper()).postDelayed({
        val me = Process.myPid()
        manager?.runningAppProcesses.orEmpty()
            .filter { it.uid == Process.myUid() && it.pid != me }
            .forEach { Process.killProcess(it.pid) }
        Process.killProcess(me)
    }, 300)
}
