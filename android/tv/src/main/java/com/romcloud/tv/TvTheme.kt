package com.romcloud.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

val Accent = Color(0xFF9D95FF)
val TvBackground = Color(0xFF111318)
private val TvSurface = Color(0xFF1A1D24)
private val TvSurfaceVariant = Color(0xFF232733)

/** Marges de sécurité des téléviseurs (zone parfois rognée par l'overscan). */
val TvSafePadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * Thème TV : tv-material pour les écrans (focus, cartes, boutons) et Material 3 pour les
 * fenêtres de dialogue et champs de saisie partagés avec l'application téléphone.
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF1B1464),
            primaryContainer = Color(0xFF3B33A8),
            background = TvBackground,
            surface = TvSurface,
            surfaceVariant = TvSurfaceVariant,
            surfaceContainerHigh = TvSurfaceVariant,
        ),
    ) {
        TvMaterialTheme(
            colorScheme = tvDarkColorScheme(
                primary = Accent,
                onPrimary = Color(0xFF1B1464),
                primaryContainer = Color(0xFF3B33A8),
                background = TvBackground,
                surface = TvSurface,
                surfaceVariant = TvSurfaceVariant,
                border = Accent,
            ),
            content = content,
        )
    }
}
