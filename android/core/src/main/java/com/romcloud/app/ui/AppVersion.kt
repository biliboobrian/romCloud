package com.romcloud.app.ui

import android.content.Context

/** Version de l'application installée (« 1.31.2 »), vide si inconnue. */
fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
