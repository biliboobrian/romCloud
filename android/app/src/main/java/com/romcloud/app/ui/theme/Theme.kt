package com.romcloud.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val DownloadedGreen = Color(0xFF2EB872)

private val Dark = darkColorScheme(
    primary = Color(0xFF9D95FF),
    onPrimary = Color(0xFF1B1464),
    primaryContainer = Color(0xFF3B33A8),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF232733),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4FE0),
    primaryContainer = Color(0xFFE3E0FF),
)

@Composable
fun RomCloudTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
