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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
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
 * Manette tactile superposée au jeu (téléphones sans manette physique), disposée selon la
 * console ([layout]). [onKey] reçoit (KeyEvent.ACTION_DOWN / ACTION_UP, code RetroPad) ;
 * [onAnalog] reçoit la position d'un stick (true = stick droit), de -1 à 1.
 */
@Composable
internal fun TouchGamepad(
    layout: PadLayout,
    onKey: (Int, Int) -> Unit,
    onAnalog: (right: Boolean, x: Float, y: Float) -> Unit,
    onMenu: () -> Unit,
) {
    // Boutons C de la Nintendo 64 enfoncés : leur somme donne la position du stick droit.
    var rightStick by remember(layout) { mutableStateOf(emptySet<PadButton>()) }
    fun pressRightStick(button: PadButton, pressed: Boolean) {
        rightStick = if (pressed) rightStick + button else rightStick - button
        val x = rightStick.sumOf { it.rightStick!!.x.toDouble() }.toFloat().coerceIn(-1f, 1f)
        val y = rightStick.sumOf { it.rightStick!!.y.toDouble() }.toFloat().coerceIn(-1f, 1f)
        onAnalog(true, x, y)
    }
    val shoulderShape = RoundedCornerShape(10.dp)

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            layout.left.forEach { TouchButton(it.label, onKey.forKey(it.key), width = it.width, height = 36.dp, shape = shoulderShape) }
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            layout.right.forEach { TouchButton(it.label, onKey.forKey(it.key), width = it.width, height = 36.dp, shape = shoulderShape) }
        }
        Box(
            Modifier.align(Alignment.TopCenter).size(44.dp).background(Idle, CircleShape)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onMenu() } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Menu, contentDescription = null, tint = Label) }

        if (layout.stick) {
            TouchStick({ x, y -> onAnalog(false, x, y) }, Modifier.align(Alignment.BottomStart))
        } else {
            DPad(onKey, Modifier.align(Alignment.BottomStart))
        }

        Box(Modifier.align(Alignment.BottomEnd).size(layout.width, layout.height)) {
            layout.buttons.forEach { button ->
                TouchButton(
                    button.label,
                    onPress = if (button.rightStick != null) {
                        { pressed -> pressRightStick(button, pressed) }
                    } else {
                        onKey.forKey(button.key)
                    },
                    modifier = Modifier.offset(button.x, button.y),
                    width = button.size,
                    height = button.size,
                    color = button.color,
                )
            }
        }

        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val pill = RoundedCornerShape(15.dp)
            layout.select?.let { TouchButton(it, onKey.forKey(KeyEvent.KEYCODE_BUTTON_SELECT), width = 76.dp, height = 30.dp, shape = pill) }
            layout.start?.let { TouchButton(it, onKey.forKey(KeyEvent.KEYCODE_BUTTON_START), width = 76.dp, height = 30.dp, shape = pill) }
        }
    }
}

/** Appui / relâchement d'un bouton -> évènement de touche RetroPad. */
private fun ((Int, Int) -> Unit).forKey(keyCode: Int): (Boolean) -> Unit =
    { pressed -> this(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode) }

@Composable
private fun TouchButton(
    label: String,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 58.dp,
    height: Dp = 58.dp,
    shape: Shape = CircleShape,
    color: Color = Label,
) {
    var pressed by remember { mutableStateOf(false) }
    // Détection d'appui jamais relancée (sinon un bouton tenu pendant une recomposition resterait enfoncé).
    val currentOnPress by rememberUpdatedState(onPress)
    Box(
        modifier
            .size(width, height)
            .background(if (pressed) Pressed else Idle, shape)
            .border(1.dp, Outline, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnPress(true)
                    // Relâché quand tous les doigts posés sur le bouton sont levés.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    pressed = false
                    currentOnPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = if (label.length > 2) 11.sp else 18.sp)
    }
}

/** Stick analogique : position du doigt par rapport au centre, ramenée dans le cercle (-1 à 1). */
@Composable
private fun TouchStick(onMove: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .size(160.dp)
            .pointerInput(Unit) {
                val radius = size.width / 2f
                fun move(position: Offset) {
                    val v = (position - Offset(radius, size.height / 2f)) / radius
                    val length = v.getDistance()
                    knob = if (length > 1f) v / length else v
                    onMove(knob.x, knob.y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    move(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        move(change.position)
                    }
                    knob = Offset.Zero
                    onMove(0f, 0f)
                }
            },
    ) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Idle, r, c)
        drawCircle(Outline, r, c, style = Stroke(1.dp.toPx()))
        drawCircle(Pressed, r * 0.4f, c + knob * (r * 0.6f))
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
