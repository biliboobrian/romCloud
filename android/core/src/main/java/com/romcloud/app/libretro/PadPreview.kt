package com.romcloud.app.libretro

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Échelle du bloc de boutons de [PadLayout] (pensé pour la manette tactile) dans l'image. */
private const val SCALE = 0.7f

/** Bouton demandé, déjà attribué ou non encore configuré. */
private enum class KeyState { CURRENT, DONE, IDLE }

/**
 * Image de la manette de la console ([layout]) pendant sa configuration : le bouton demandé
 * ([current]) en couleur, ceux déjà attribués ([done]) plus clairs.
 */
@Composable
internal fun PadPreview(layout: PadLayout, current: MappingStep?, done: Set<MappingStep>, modifier: Modifier = Modifier) {
    fun state(match: (MappingStep) -> Boolean) = when {
        current != null && match(current) -> KeyState.CURRENT
        done.any(match) -> KeyState.DONE
        else -> KeyState.IDLE
    }
    fun keyState(retroKey: Int) = state { it.stick == null && it.retroKey == retroKey }
    val shoulderShape = RoundedCornerShape(8.dp)

    Column(modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Tranches : de l'extérieur vers le centre, comme sur la manette tactile.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            layout.left.forEach { PreviewKey(it.label, keyState(it.key), it.width * SCALE, 24.dp, shoulderShape) }
            Spacer(Modifier.weight(1f))
            layout.right.forEach { PreviewKey(it.label, keyState(it.key), it.width * SCALE, 24.dp, shoulderShape) }
        }
        Row(
            Modifier
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(36.dp))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(36.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Stick seul (Nintendo 64, Dreamcast, PSP) : au-dessus de la croix, jamais configuré.
                if (layout.stick && !layout.thumbs) PreviewKey("", KeyState.IDLE, 44.dp, 44.dp, CircleShape)
                PreviewDPad(::keyState)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pill = RoundedCornerShape(10.dp)
                    layout.select?.let { PreviewKey(it, keyState(KeyEvent.KEYCODE_BUTTON_SELECT), 52.dp, 20.dp, pill) }
                    layout.start?.let { PreviewKey(it, keyState(KeyEvent.KEYCODE_BUTTON_START), 52.dp, 20.dp, pill) }
                }
                if (layout.thumbs) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PreviewKey("L3", keyState(KeyEvent.KEYCODE_BUTTON_THUMBL), 44.dp, 44.dp, CircleShape)
                        // Stick droit : R3 et ses deux axes.
                        val right = state { it.stick != null || it.retroKey == KeyEvent.KEYCODE_BUTTON_THUMBR }
                        PreviewKey("R3", right, 44.dp, 44.dp, CircleShape)
                    }
                }
            }
            Box(Modifier.size(layout.width * SCALE, layout.height * SCALE)) {
                layout.buttons.forEach { button ->
                    val stick = button.rightStick
                    val buttonState = if (stick != null) {
                        // Boutons C de la Nintendo 64 : un axe du stick droit pour deux boutons.
                        state { it.stick == if (stick.x != 0f) StickAxis.X else StickAxis.Y }
                    } else {
                        keyState(button.key)
                    }
                    PreviewKey(
                        button.label,
                        buttonState,
                        button.size * SCALE,
                        button.size * SCALE,
                        CircleShape,
                        Modifier.offset(button.x * SCALE, button.y * SCALE),
                        button.color,
                    )
                }
            }
        }
    }
}

/** Croix directionnelle : quatre branches autour d'un centre. */
@Composable
private fun PreviewDPad(keyState: (Int) -> KeyState) {
    val arm = 26.dp
    Box(Modifier.size(arm * 3)) {
        listOf(
            Triple(KeyEvent.KEYCODE_DPAD_UP, "▲", arm to 0.dp),
            Triple(KeyEvent.KEYCODE_DPAD_LEFT, "◀", 0.dp to arm),
            Triple(KeyEvent.KEYCODE_DPAD_RIGHT, "▶", arm * 2 to arm),
            Triple(KeyEvent.KEYCODE_DPAD_DOWN, "▼", arm to arm * 2),
        ).forEach { (key, label, pos) ->
            PreviewKey(label, keyState(key), arm, arm, RectangleShape, Modifier.offset(pos.first, pos.second))
        }
        Box(Modifier.offset(arm, arm).size(arm).background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
private fun PreviewKey(
    label: String,
    state: KeyState,
    width: Dp,
    height: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    color: Color = LABEL,
) {
    val fill = when (state) {
        KeyState.CURRENT -> MaterialTheme.colorScheme.primary
        KeyState.DONE -> Color.White.copy(alpha = 0.35f)
        KeyState.IDLE -> Color.White.copy(alpha = 0.12f)
    }
    val text = if (state == KeyState.CURRENT) MaterialTheme.colorScheme.onPrimary else color
    val border = if (state == KeyState.CURRENT) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.4f)
    Box(
        modifier.size(width, height).background(fill, shape).border(if (state == KeyState.CURRENT) 2.dp else 1.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) Text(label, color = text, fontSize = if (label.length > 2) 8.sp else 13.sp, maxLines = 1)
    }
}
