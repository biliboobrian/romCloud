package com.romcloud.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Élément choisi à la manette ou à la télécommande : fond clair translucide derrière lui et contour,
 * pour voir où se trouve la sélection. À placer avant l'élément cliquable (modifier d'un bouton,
 * ou avant `clickable`).
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(50)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            if (focused) drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = 0.22f))
            drawContent()
            if (focused) drawOutline(shape.createOutline(size, layoutDirection, this), outline, style = Stroke(2.dp.toPx()))
        }
}

/** Forme du contour de sélection des lignes de liste et des onglets. */
val FocusRowShape = RoundedCornerShape(8.dp)
