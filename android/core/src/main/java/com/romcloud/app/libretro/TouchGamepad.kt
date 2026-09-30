package com.romcloud.app.libretro

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2

private val Idle = Color.White.copy(alpha = 0.18f)
private val Pressed = Color.White.copy(alpha = 0.45f)
private val Outline = Color.White.copy(alpha = 0.5f)
private val Label = Color.White.copy(alpha = 0.8f)

/**
 * Manette tactile superposée au jeu (téléphones sans manette physique). [onKey] reçoit
 * (KeyEvent.ACTION_DOWN / ACTION_UP, code RetroPad) ; les codes sont déjà au format RetroPad.
 */
@Composable
internal fun TouchGamepad(onKey: (Int, Int) -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadButton("L2", KeyEvent.KEYCODE_BUTTON_L2, onKey, width = 64.dp, height = 36.dp, shape = RoundedCornerShape(10.dp))
            PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, onKey, width = 84.dp, height = 36.dp, shape = RoundedCornerShape(10.dp))
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, onKey, width = 84.dp, height = 36.dp, shape = RoundedCornerShape(10.dp))
            PadButton("R2", KeyEvent.KEYCODE_BUTTON_R2, onKey, width = 64.dp, height = 36.dp, shape = RoundedCornerShape(10.dp))
        }
        Box(
            Modifier.align(Alignment.TopCenter).size(44.dp).background(Idle, CircleShape)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onMenu() } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Menu, contentDescription = null, tint = Label) }

        DPad(onKey, Modifier.align(Alignment.BottomStart))

        Box(Modifier.align(Alignment.BottomEnd).size(170.dp)) {
            // Disposition Super Nintendo : X en haut, A à droite, B en bas, Y à gauche.
            PadButton("X", KeyEvent.KEYCODE_BUTTON_X, onKey, Modifier.align(Alignment.TopCenter))
            PadButton("A", KeyEvent.KEYCODE_BUTTON_A, onKey, Modifier.align(Alignment.CenterEnd))
            PadButton("B", KeyEvent.KEYCODE_BUTTON_B, onKey, Modifier.align(Alignment.BottomCenter))
            PadButton("Y", KeyEvent.KEYCODE_BUTTON_Y, onKey, Modifier.align(Alignment.CenterStart))
        }

        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, onKey, width = 76.dp, height = 30.dp, shape = RoundedCornerShape(15.dp))
            PadButton("START", KeyEvent.KEYCODE_BUTTON_START, onKey, width = 76.dp, height = 30.dp, shape = RoundedCornerShape(15.dp))
        }
    }
}

@Composable
private fun PadButton(
    label: String,
    keyCode: Int,
    onKey: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 58.dp,
    height: Dp = 58.dp,
    shape: Shape = CircleShape,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(width, height)
            .background(if (pressed) Pressed else Idle, shape)
            .border(1.dp, Outline, shape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onKey(KeyEvent.ACTION_DOWN, keyCode)
                    // Relâché quand tous les doigts posés sur le bouton sont levés.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    pressed = false
                    onKey(KeyEvent.ACTION_UP, keyCode)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Label, fontSize = if (label.length > 2) 11.sp else 18.sp)
    }
}

/** Croix directionnelle : 8 directions selon la position du doigt par rapport au centre. */
@Composable
private fun DPad(onKey: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf(emptySet<Int>()) }
    Canvas(
        modifier
            .size(160.dp)
            .pointerInput(Unit) {
                fun update(next: Set<Int>) {
                    (held - next).forEach { onKey(KeyEvent.ACTION_UP, it) }
                    (next - held).forEach { onKey(KeyEvent.ACTION_DOWN, it) }
                    held = next
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(directions(down.position, size.width / 2f, size.height / 2f))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        update(directions(change.position, size.width / 2f, size.height / 2f))
                    }
                    update(emptySet())
                }
            },
    ) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Idle, r, c)
        drawCircle(Outline, r, c, style = Stroke(1.dp.toPx()))
        val arm = r * 0.34f
        mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Offset(0f, -1f),
            KeyEvent.KEYCODE_DPAD_DOWN to Offset(0f, 1f),
            KeyEvent.KEYCODE_DPAD_LEFT to Offset(-1f, 0f),
            KeyEvent.KEYCODE_DPAD_RIGHT to Offset(1f, 0f),
        ).forEach { (key, dir) ->
            drawCircle(if (key in held) Pressed else Idle, arm * 0.8f, c + dir * (r - arm))
        }
    }
}

/** Directions pressées pour un point du pavé (zone morte au centre, diagonales sur 45°). */
private fun directions(p: Offset, cx: Float, cy: Float): Set<Int> {
    val dx = p.x - cx
    val dy = p.y - cy
    if (abs(dx) < cx * 0.2f && abs(dy) < cy * 0.2f) return emptySet()
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = droite, 90 = bas
    val sector = Math.floorMod(Math.round(angle / 45).toInt(), 8)
    return when (sector) {
        0 -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT)
        1 -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)
        2 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN)
        3 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        4 -> setOf(KeyEvent.KEYCODE_DPAD_LEFT)
        5 -> setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)
        6 -> setOf(KeyEvent.KEYCODE_DPAD_UP)
        else -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
