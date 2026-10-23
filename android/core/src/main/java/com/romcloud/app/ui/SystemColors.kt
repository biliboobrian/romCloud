package com.romcloud.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.romcloud.app.data.GameSystem

private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

/** Couleur de fond du système choisie sur le serveur ; null si absente ou invalide. */
val GameSystem.backgroundColor: Color?
    get() = color?.takeIf { HEX_COLOR.matches(it) }?.let { Color(0xFF000000 or it.substring(1).toLong(16)) }

/** Fond de l'écran des systèmes : dégradé violet en haut à gauche vers le noir en bas à droite (comme sous Windows). */
val SystemsGradient = Brush.linearGradient(
    0f to Color(0xFF2E1A6B),
    0.45f to Color(0xFF140C30),
    1f to Color.Black,
    start = Offset.Zero,
    end = Offset.Infinite,
)

/** Texte lisible sur [background] : noir sur une couleur claire, blanc sinon. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.4f) Color.Black else Color.White

/**
 * [content] sur le fond [background] (écran des systèmes en noir, liste des jeux et fiche d'un jeu
 * sur la couleur du système) : fond, surfaces et textes du thème Material 3 dérivés de cette
 * couleur. Sans couleur, le thème reste inchangé.
 */
@Composable
fun SystemBackground(background: Color?, content: @Composable () -> Unit) {
    if (background == null) return content()
    val on = contentColorOn(background)
    val tint = { amount: Float -> lerp(background, on, amount) }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = background,
            onBackground = on,
            surface = background,
            onSurface = on,
            surfaceVariant = tint(0.12f),
            onSurfaceVariant = on.copy(alpha = 0.72f),
            surfaceContainerLowest = background,
            surfaceContainerLow = tint(0.04f),
            surfaceContainer = tint(0.07f),
            surfaceContainerHigh = tint(0.1f),
            surfaceContainerHighest = tint(0.13f),
            outline = on.copy(alpha = 0.4f),
            outlineVariant = on.copy(alpha = 0.2f),
        ),
        content = content,
    )
}
