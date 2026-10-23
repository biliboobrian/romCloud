package com.romcloud.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.Surface
import androidx.tv.material3.Typography as TvTypography
import androidx.compose.material3.Typography as M3Typography
import androidx.tv.material3.SurfaceDefaults
import com.romcloud.app.ui.SystemBackground
import com.romcloud.app.ui.contentColorOn
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

/** Taille des textes de la TV par rapport aux styles Material (textes un peu plus petits). */
private const val TextScale = 0.88f

private fun TextUnit.scaled() = if (isSpecified) this * TextScale else this
private fun TextStyle.scaled() = copy(fontSize = fontSize.scaled(), lineHeight = lineHeight.scaled())

private fun TvTypography.scaled() = TvTypography(
    displayLarge.scaled(), displayMedium.scaled(), displaySmall.scaled(),
    headlineLarge.scaled(), headlineMedium.scaled(), headlineSmall.scaled(),
    titleLarge.scaled(), titleMedium.scaled(), titleSmall.scaled(),
    bodyLarge.scaled(), bodyMedium.scaled(), bodySmall.scaled(),
    labelLarge.scaled(), labelMedium.scaled(), labelSmall.scaled(),
)

private fun M3Typography.scaled() = M3Typography(
    displayLarge.scaled(), displayMedium.scaled(), displaySmall.scaled(),
    headlineLarge.scaled(), headlineMedium.scaled(), headlineSmall.scaled(),
    titleLarge.scaled(), titleMedium.scaled(), titleSmall.scaled(),
    bodyLarge.scaled(), bodyMedium.scaled(), bodySmall.scaled(),
    labelLarge.scaled(), labelMedium.scaled(), labelSmall.scaled(),
)

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
        typography = M3Typography().scaled(),
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
            typography = TvTypography().scaled(),
            content = content,
        )
    }
}

/**
 * Thème des écrans et cartes sur le fond [background] (écran des systèmes en noir, liste des jeux
 * et fiche d'un jeu sur la couleur du système) : couleurs tv-material et Material 3 dérivées de
 * cette couleur. Sans couleur, le thème reste inchangé.
 */
@Composable
fun TvScreenTheme(background: Color?, content: @Composable () -> Unit) {
    if (background == null) return content()
    val on = contentColorOn(background)
    SystemBackground(background) {
        TvMaterialTheme(
            colorScheme = TvMaterialTheme.colorScheme.copy(
                background = background,
                onBackground = on,
                surface = lerp(background, on, 0.06f),
                onSurface = on,
                surfaceVariant = lerp(background, on, 0.12f),
                onSurfaceVariant = on.copy(alpha = 0.72f),
            ),
            content = content,
        )
    }
}

/** Écran entier peint sur [background] (voir [TvScreenTheme]), ou sur [brush] (dégradé) s'il est donné. */
@Composable
fun TvScreenBackground(background: Color?, brush: Brush? = null, content: @Composable () -> Unit) {
    if (background == null) return content()
    TvScreenTheme(background) {
        Surface(
            if (brush != null) Modifier.fillMaxSize().background(brush) else Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = if (brush != null) Color.Transparent else background),
        ) { content() }
    }
}
