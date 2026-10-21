package com.romcloud.app.libretro

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.romcloud.core.R

/** Repère d'un bouton sur une illustration : position et rayon (pixels de l'image, 291 x 306). */
internal data class Spot(val x: Int, val y: Int, val r: Int)

/**
 * Boutons sur chaque illustration de manette (@rachels_ham) : up / down / left / right (croix), ls /
 * rs (sticks, L3 / R3), rx+, ry- … (directions du stick droit). Même table que padconfig.cpp (Windows).
 */
internal val PAD_SPOTS: Map<String, Map<String, Spot>> = mapOf(
    "nes" to mapOf("up" to Spot(79, 142, 6), "down" to Spot(79, 165, 6), "left" to Spot(67, 153, 6), "right" to Spot(91, 153, 6), "select" to Spot(125, 161, 7), "start" to Spot(152, 161, 7), "b" to Spot(188, 163, 10), "a" to Spot(217, 163, 10)),
    "famicom" to mapOf("up" to Spot(79, 137, 6), "down" to Spot(79, 161, 6), "left" to Spot(66, 149, 6), "right" to Spot(92, 149, 6), "select" to Spot(125, 161, 7), "start" to Spot(152, 161, 7), "b" to Spot(187, 160, 10), "a" to Spot(216, 160, 10), "l" to Spot(60, 104, 8), "r" to Spot(232, 104, 8)),
    "snes" to mapOf("up" to Spot(85, 140, 6), "down" to Spot(85, 166, 6), "left" to Spot(72, 153, 6), "right" to Spot(98, 153, 6), "select" to Spot(128, 159, 7), "start" to Spot(150, 159, 7), "x" to Spot(206, 135, 8), "y" to Spot(189, 152, 8), "a" to Spot(223, 152, 8), "b" to Spot(206, 167, 8), "l" to Spot(90, 101, 8), "r" to Spot(202, 101, 8)),
    "n64" to mapOf("up" to Spot(77, 113, 6), "down" to Spot(77, 135, 6), "left" to Spot(66, 124, 6), "right" to Spot(88, 124, 6), "start" to Spot(145, 137, 7), "b" to Spot(197, 146, 8), "y" to Spot(181, 129, 7), "l2" to Spot(146, 205, 9), "l" to Spot(73, 77, 9), "r" to Spot(217, 77, 9), "ls" to Spot(146, 177, 12), "rs" to Spot(217, 118, 15), "ry-" to Spot(217, 105, 6), "ry+" to Spot(217, 130, 6), "rx-" to Spot(204, 118, 6), "rx+" to Spot(230, 118, 6)),
    "psx" to mapOf("up" to Spot(82, 120, 6), "down" to Spot(82, 152, 6), "left" to Spot(68, 137, 6), "right" to Spot(97, 137, 6), "select" to Spot(133, 153, 7), "start" to Spot(159, 153, 7), "x" to Spot(209, 116, 8), "a" to Spot(229, 136, 8), "b" to Spot(209, 154, 8), "y" to Spot(189, 136, 8), "l" to Spot(82, 96, 8), "l2" to Spot(82, 82, 8), "r" to Spot(208, 96, 8), "r2" to Spot(208, 82, 8)),
    "ps2" to mapOf("up" to Spot(72, 118, 6), "down" to Spot(72, 143, 6), "left" to Spot(59, 131, 6), "right" to Spot(85, 131, 6), "select" to Spot(122, 131, 6), "start" to Spot(167, 131, 6), "x" to Spot(217, 113, 7), "a" to Spot(232, 131, 7), "b" to Spot(218, 147, 7), "y" to Spot(202, 131, 7), "l" to Spot(75, 95, 8), "l2" to Spot(75, 82, 8), "r" to Spot(218, 95, 8), "r2" to Spot(218, 82, 8), "ls" to Spot(113, 167, 13), "rs" to Spot(183, 167, 13)),
    "gamecube" to mapOf("ls" to Spot(82, 121, 13), "up" to Spot(112, 158, 5), "down" to Spot(112, 176, 5), "left" to Spot(103, 167, 5), "right" to Spot(121, 167, 5), "start" to Spot(146, 119, 5), "a" to Spot(209, 120, 10), "b" to Spot(188, 133, 7), "x" to Spot(233, 116, 7), "y" to Spot(203, 97, 7), "l2" to Spot(77, 83, 9), "r2" to Spot(228, 84, 9), "r" to Spot(205, 80, 7), "rs" to Spot(179, 168, 12)),
    "genesis" to mapOf("up" to Spot(80, 133, 6), "down" to Spot(80, 161, 6), "left" to Spot(66, 147, 6), "right" to Spot(94, 147, 6), "start" to Spot(150, 145, 7), "y" to Spot(189, 156, 9), "b" to Spot(213, 139, 9), "a" to Spot(237, 134, 9)),
    "master" to mapOf("up" to Spot(95, 132, 6), "down" to Spot(95, 162, 6), "left" to Spot(80, 147, 6), "right" to Spot(110, 147, 6), "b" to Spot(188, 165, 13), "a" to Spot(228, 165, 13)),
    "saturn" to mapOf("up" to Spot(85, 139, 6), "down" to Spot(85, 167, 6), "left" to Spot(71, 153, 6), "right" to Spot(99, 153, 6), "start" to Spot(143, 172, 7), "b" to Spot(181, 151, 8), "a" to Spot(208, 160, 8), "r" to Spot(238, 154, 8), "y" to Spot(200, 137, 7), "x" to Spot(219, 129, 7), "l" to Spot(238, 124, 7), "l2" to Spot(80, 87, 9), "r2" to Spot(203, 87, 9)),
    "dreamcast" to mapOf("ls" to Spot(55, 105, 14), "up" to Spot(73, 148, 6), "down" to Spot(73, 168, 6), "left" to Spot(63, 158, 6), "right" to Spot(83, 158, 6), "start" to Spot(143, 210, 7), "b" to Spot(228, 147, 8), "a" to Spot(247, 126, 8), "y" to Spot(206, 126, 8), "x" to Spot(228, 107, 8), "l2" to Spot(40, 75, 9), "r2" to Spot(250, 75, 9)),
    "pcengine" to mapOf("up" to Spot(68, 160, 6), "down" to Spot(68, 184, 6), "left" to Spot(56, 172, 6), "right" to Spot(80, 172, 6), "select" to Spot(133, 177, 7), "start" to Spot(159, 177, 7), "b" to Spot(204, 176, 10), "a" to Spot(231, 176, 10)),
    "neogeo" to mapOf("up" to Spot(83, 115, 6), "down" to Spot(83, 183, 6), "left" to Spot(52, 149, 6), "right" to Spot(117, 149, 6), "b" to Spot(203, 165, 9), "a" to Spot(230, 151, 9), "y" to Spot(190, 139, 9), "x" to Spot(214, 124, 9), "start" to Spot(152, 150, 7), "select" to Spot(152, 173, 7)),
    "atari2600" to mapOf("b" to Spot(97, 103, 10), "up" to Spot(146, 125, 8), "down" to Spot(146, 175, 8), "left" to Spot(121, 150, 8), "right" to Spot(171, 150, 8)),
)

/** Partie de l'illustration affichée (la manette, sans le fond autour). */
private val ART_OFFSET = IntOffset(10, 55)
private val ART_SIZE = IntSize(271, 190)

/** Illustration de la manette d'une console (style de sa disposition), null s'il n'y en a pas. */
internal fun padArtName(style: PadStyle): String? = when (style) {
    PadStyle.NES -> "nes"
    PadStyle.FDS -> "famicom"
    PadStyle.SNES -> "snes"
    PadStyle.N64 -> "n64"
    PadStyle.PLAYSTATION -> "psx"
    PadStyle.PS2 -> "ps2"
    PadStyle.GAMECUBE -> "gamecube"
    PadStyle.GENESIS -> "genesis"
    PadStyle.MASTER_SYSTEM -> "master"
    PadStyle.SATURN -> "saturn"
    PadStyle.DREAMCAST -> "dreamcast"
    PadStyle.PC_ENGINE -> "pcengine"
    PadStyle.NEO_GEO -> "neogeo"
    PadStyle.ATARI_2600 -> "atari2600"
    else -> null
}

@DrawableRes
private fun padArtDrawable(name: String): Int = when (name) {
    "nes" -> R.drawable.pad_nes
    "famicom" -> R.drawable.pad_famicom
    "snes" -> R.drawable.pad_snes
    "n64" -> R.drawable.pad_n64
    "psx" -> R.drawable.pad_psx
    "ps2" -> R.drawable.pad_ps2
    "gamecube" -> R.drawable.pad_gamecube
    "genesis" -> R.drawable.pad_genesis
    "master" -> R.drawable.pad_master
    "saturn" -> R.drawable.pad_saturn
    "dreamcast" -> R.drawable.pad_dreamcast
    "pcengine" -> R.drawable.pad_pcengine
    "neogeo" -> R.drawable.pad_neogeo
    else -> R.drawable.pad_atari2600
}

private val KEY_SPOTS = mapOf(
    KeyEvent.KEYCODE_DPAD_UP to "up", KeyEvent.KEYCODE_DPAD_DOWN to "down", KeyEvent.KEYCODE_DPAD_LEFT to "left",
    KeyEvent.KEYCODE_DPAD_RIGHT to "right", KeyEvent.KEYCODE_BUTTON_B to "b", KeyEvent.KEYCODE_BUTTON_A to "a",
    KeyEvent.KEYCODE_BUTTON_Y to "y", KeyEvent.KEYCODE_BUTTON_X to "x", KeyEvent.KEYCODE_BUTTON_L1 to "l",
    KeyEvent.KEYCODE_BUTTON_R1 to "r", KeyEvent.KEYCODE_BUTTON_L2 to "l2", KeyEvent.KEYCODE_BUTTON_R2 to "r2",
    KeyEvent.KEYCODE_BUTTON_SELECT to "select", KeyEvent.KEYCODE_BUTTON_START to "start",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "ls", KeyEvent.KEYCODE_BUTTON_THUMBR to "rs",
)

/** Repère d'une étape sur l'illustration [name] : bouton, croix ou stick ; null s'il n'y figure pas. */
internal fun spotOf(name: String, step: MappingStep): Spot? {
    val spots = PAD_SPOTS[name] ?: return null
    val key = step.stick?.let { if (it.left) "ls" else "rs" } ?: KEY_SPOTS[step.retroKey] ?: return null
    return spots[key]
}

/**
 * Illustration de la manette de la console pendant sa configuration (à la place du dessin) : repère
 * de la couleur du thème sur le bouton de l'étape en cours ([current]), vert sur ceux déjà attribués
 * ([done]), discret sur les autres.
 */
@Composable
internal fun PadArt(name: String, steps: List<MappingStep>, current: MappingStep?, done: Set<MappingStep>, modifier: Modifier = Modifier) {
    val bitmap: ImageBitmap = ImageBitmap.imageResource(padArtDrawable(name))
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(ART_SIZE.width.toFloat() / ART_SIZE.height)) {
        // Pixel art : agrandi sans lissage.
        drawImage(bitmap, srcOffset = ART_OFFSET, srcSize = ART_SIZE, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        val scale = size.width / ART_SIZE.width
        for (step in steps) {
            val spot = spotOf(name, step) ?: continue
            val center = Offset((spot.x - ART_OFFSET.x) * scale, (spot.y - ART_OFFSET.y) * scale)
            val radius = spot.r * scale + 2.dp.toPx()
            when {
                step == current -> {
                    drawCircle(accent.copy(alpha = 0.4f), radius, center)
                    drawCircle(accent, radius + 2.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                }
                step in done -> drawCircle(Color(0xFF46D26E), radius, center, style = Stroke(2.5.dp.toPx()))
                else -> drawCircle(Color.White.copy(alpha = 0.45f), radius, center, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
