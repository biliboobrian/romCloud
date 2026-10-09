package com.romcloud.app.libretro

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/** Manette dessinée dans la configuration : celle de la console d'origine (même dessin que sous Windows). */
internal enum class PadStyle {
    DEFAULT, NES, FDS, SNES, N64, PLAYSTATION, PSP, GAME_BOY, GBA, GENESIS, MASTER_SYSTEM, SATURN, DREAMCAST,
    PC_ENGINE, NEO_GEO, NEO_GEO_POCKET, CPS, ARCADE, ATARI_2600, ATARI_7800, LYNX, GX4000, PS2, GAMECUBE,
}

/** Boîte du dessin (unités), mise à l'échelle de la largeur disponible. */
private const val BOX_WIDTH = 340f
private const val BOX_HEIGHT = 150f

/**
 * Manette de la console ([layout]) pendant sa configuration, dessinée d'après l'originale (forme,
 * couleurs, place des boutons). Chaque bouton des étapes ([steps]) porte un repère : anneau de la
 * couleur du thème pour l'étape en cours ([current]), vert pour celles déjà attribuées ([done]).
 */
@Composable
internal fun PadPreview(
    layout: PadLayout,
    steps: List<MappingStep>,
    current: MappingStep?,
    done: Set<MappingStep>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(BOX_WIDTH / BOX_HEIGHT)) {
        PadPainter(this, measurer, steps, current, done, accent).draw(layout.style)
    }
}

private const val BTN_B = KeyEvent.KEYCODE_BUTTON_B
private const val BTN_A = KeyEvent.KEYCODE_BUTTON_A
private const val BTN_Y = KeyEvent.KEYCODE_BUTTON_Y
private const val BTN_X = KeyEvent.KEYCODE_BUTTON_X
private const val BTN_L = KeyEvent.KEYCODE_BUTTON_L1
private const val BTN_R = KeyEvent.KEYCODE_BUTTON_R1
private const val BTN_L2 = KeyEvent.KEYCODE_BUTTON_L2
private const val BTN_R2 = KeyEvent.KEYCODE_BUTTON_R2
private const val BTN_L3 = KeyEvent.KEYCODE_BUTTON_THUMBL
private const val BTN_R3 = KeyEvent.KEYCODE_BUTTON_THUMBR
private const val BTN_SELECT = KeyEvent.KEYCODE_BUTTON_SELECT
private const val BTN_START = KeyEvent.KEYCODE_BUTTON_START
private const val DPAD_UP = KeyEvent.KEYCODE_DPAD_UP
private const val DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
private const val DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
private const val DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT

private fun rgb(r: Int, g: Int, b: Int, a: Int = 255) = Color(r, g, b, a)

private val RED = rgb(206, 46, 52)
private val YELLOW = rgb(232, 190, 46)
private val GREEN = rgb(46, 156, 80)
private val BLUE = rgb(58, 96, 200)
private val SHADOW = rgb(0, 0, 0, 90)
private val DONE = rgb(90, 210, 130)

/** Couleur éclaircie (amount > 0) ou assombrie (amount < 0), même opacité. */
private fun Color.shade(amount: Float): Color =
    (if (amount > 0) lerp(this, Color.White, amount) else lerp(this, Color.Black, -amount)).copy(alpha = alpha)

/** Texte lisible sur cette couleur : noir sur fond clair, blanc sinon. */
private fun Color.ink(): Color =
    if (red * 0.299f + green * 0.587f + blue * 0.114f > 0.59f) rgb(20, 20, 24) else rgb(250, 250, 252)

private enum class LabelPlace { INSIDE, BELOW, ABOVE, NONE }
private enum class Symbol { CROSS, CIRCLE, SQUARE, TRIANGLE }

private class PadPainter(
    private val scope: DrawScope,
    private val measurer: TextMeasurer,
    private val steps: List<MappingStep>,
    private val current: MappingStep?,
    private val done: Set<MappingStep>,
    private val accent: Color,
) {
    private val k = scope.size.width / BOX_WIDTH

    /** Unités de la boîte -> pixels (origine commune). */
    private fun u(v: Number) = v.toFloat() * k
    private fun at(x: Number, y: Number) = Offset(u(x), u(y))

    private fun stepOf(key: Int) = steps.indexOfFirst { it.stick == null && it.retroKey == key }
    private fun axisStep(axis: StickAxis) = steps.indexOfFirst { it.stick == axis }
    private fun isCurrent(i: Int) = i >= 0 && steps[i] == current
    private fun isDone(i: Int) = i >= 0 && steps[i] in done
    private fun labelOf(i: Int) = if (i >= 0) steps[i].label.orEmpty() else ""

    /** Étape du stick droit à repérer : l'axe en cours, sinon l'horizontal. */
    private fun rightAxis(): Int = axisStep(StickAxis.Y).takeIf(::isCurrent) ?: axisStep(StickAxis.X)

    // ---- Formes (unités de la boîte) ----

    private fun rect(x: Number, y: Number, w: Number, h: Number, r: Number, color: Color) =
        scope.drawRoundRect(color, at(x, y), Size(u(w), u(h)), CornerRadius(u(r)))

    private fun disc(cx: Number, cy: Number, r: Number, color: Color) = scope.drawCircle(color, u(r), at(cx, cy))

    private fun ring(cx: Number, cy: Number, r: Number, t: Number, color: Color) =
        scope.drawCircle(color, u(r), at(cx, cy), style = Stroke(max(1f, u(t))))

    private fun line(x0: Number, y0: Number, x1: Number, y1: Number, t: Number, color: Color) =
        scope.drawLine(color, at(x0, y0), at(x1, y1), max(1f, u(t)), StrokeCap.Round)

    private fun text(cx: Number, cy: Number, s: String, maxWidth: Number, color: Color) {
        if (s.isEmpty()) return
        val style = TextStyle(color = color, fontSize = with(scope) { (8.5f * k).toSp() }, fontWeight = FontWeight.Bold)
        val result = measurer.measure(
            s, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = max(1, u(maxWidth).toInt())),
        )
        scope.drawText(result, topLeft = Offset(u(cx) - result.size.width / 2f, u(cy) - result.size.height / 2f))
    }

    /** Corps : [shape](grossissement, couleur) en liseré clair, puis bord, puis couleur du corps. */
    private inline fun body(color: Color, edge: Color, shape: (Float, Color) -> Unit) {
        shape(3f, Color.White.copy(alpha = 0.16f))  // liseré : manettes noires visibles sur le fond sombre
        shape(1.5f, edge)
        shape(0f, color)
    }

    /** Repère d'état de l'étape [i] autour d'une forme. */
    private fun mark(i: Int, x: Number, y: Number, w: Number, h: Number, r: Number) {
        val (pad, stroke, color) = when {
            isCurrent(i) -> Triple(4.dp, 3.dp, accent)
            isDone(i) -> Triple(2.5.dp, 2.dp, DONE)
            else -> return
        }
        val p = with(scope) { pad.toPx() }
        scope.drawRoundRect(
            color, Offset(u(x) - p, u(y) - p), Size(u(w) + 2 * p, u(h) + 2 * p), CornerRadius(u(r) + p),
            style = Stroke(with(scope) { stroke.toPx() }),
        )
        if (isCurrent(i)) scope.drawRoundRect(accent.copy(alpha = 0.4f), at(x, y), Size(u(w), u(h)), CornerRadius(u(r)))
    }

    private fun label(i: Int, where: LabelPlace, cx: Float, cy: Float, halfH: Float, maxWidth: Float, ink: Color, outside: Color) {
        when (where) {
            LabelPlace.INSIDE -> text(cx, cy, labelOf(i), maxWidth, ink)
            LabelPlace.BELOW -> text(cx, cy + halfH + 7, labelOf(i), max(maxWidth, 48f), outside)
            LabelPlace.ABOVE -> text(cx, cy - halfH - 7, labelOf(i), max(maxWidth, 48f), outside)
            LabelPlace.NONE -> Unit
        }
    }

    /** Bouton rond de la console (absent de la configuration : non dessiné). */
    private fun button(key: Int, cx: Float, cy: Float, r: Float, color: Color, where: LabelPlace = LabelPlace.INSIDE, outside: Color = rgb(230, 230, 236)) {
        val i = stepOf(key)
        if (i < 0) return
        disc(cx, cy + 1.5f, r + 1, SHADOW)
        disc(cx, cy, r, color)
        disc(cx - r * 0.25f, cy - r * 0.3f, r * 0.45f, color.shade(0.2f))
        mark(i, cx - r, cy - r, 2 * r, 2 * r, r)
        label(i, where, cx, cy, r, 2 * r + 4, color.ink(), outside)
    }

    /** Bouton PlayStation : symbole de couleur sur un bouton sombre. */
    private fun symbol(key: Int, cx: Float, cy: Float, r: Float, color: Color, shape: Symbol, ink: Color) {
        val i = stepOf(key)
        if (i < 0) return
        disc(cx, cy + 1.5f, r + 1, SHADOW)
        disc(cx, cy, r, color)
        disc(cx - r * 0.25f, cy - r * 0.3f, r * 0.45f, color.shade(0.12f))
        val s = r * 0.45f
        val t = 1.8f
        when (shape) {
            Symbol.CROSS -> {
                line(cx - s, cy - s, cx + s, cy + s, t, ink)
                line(cx - s, cy + s, cx + s, cy - s, t, ink)
            }
            Symbol.CIRCLE -> ring(cx, cy, s + 0.8f, t, ink)
            Symbol.SQUARE -> scope.drawRect(ink, at(cx - s, cy - s), Size(u(2 * s), u(2 * s)), style = Stroke(max(1f, u(t))))
            Symbol.TRIANGLE -> {
                line(cx, cy - s - 1, cx + s + 1, cy + s * 0.75f, t, ink)
                line(cx + s + 1, cy + s * 0.75f, cx - s - 1, cy + s * 0.75f, t, ink)
                line(cx - s - 1, cy + s * 0.75f, cx, cy - s - 1, t, ink)
            }
        }
        mark(i, cx - r, cy - r, 2 * r, 2 * r, r)
    }

    /** Bouton allongé (Select, Start…) centré en ([cx], [cy]). */
    private fun pill(key: Int, cx: Float, cy: Float, w: Float, h: Float, color: Color, where: LabelPlace = LabelPlace.INSIDE, outside: Color = rgb(230, 230, 236)) {
        val i = stepOf(key)
        if (i < 0) return
        val r = h / 2
        rect(cx - w / 2, cy - h / 2 + 1.2f, w, h, r, SHADOW)
        rect(cx - w / 2, cy - h / 2, w, h, r, color)
        if (h >= 12) rect(cx - w / 2 + 3, cy - h / 2 + 2, w - 6, h * 0.3f, r * 0.5f, color.shade(0.2f))
        mark(i, cx - w / 2, cy - h / 2, w, h, r)
        label(i, where, cx, cy, h / 2, w - 2, color.ink(), outside)
    }

    /** Bouton en biais (Select / Start de la Super Nintendo et de la Game Boy). */
    private fun slanted(key: Int, x0: Float, y0: Float, x1: Float, y1: Float, t: Float, color: Color, where: LabelPlace, outside: Color) {
        val i = stepOf(key)
        if (i < 0) return
        if (isCurrent(i)) line(x0, y0, x1, y1, t + 10 / k, accent)
        else if (isDone(i)) line(x0, y0, x1, y1, t + 6 / k, DONE)
        line(x0, y0 + 1, x1, y1 + 1, t, SHADOW)
        line(x0, y0, x1, y1, t, color)
        if (isCurrent(i)) line(x0, y0, x1, y1, t, accent.copy(alpha = 0.4f))
        label(i, where, (x0 + x1) / 2, (y0 + y1) / 2, abs(y1 - y0) / 2 + t / 2, 50f, color.ink(), outside)
    }

    /** Tranche (vue de face, derrière le corps). */
    private fun shoulder(key: Int, x: Float, y: Float, w: Float, h: Float, color: Color, edge: Color) {
        val i = stepOf(key)
        if (i < 0) return
        rect(x - 1, y - 1, w + 2, h + 2, h / 2 + 1, edge)
        rect(x, y, w, h, h / 2, color)
        rect(x + 3, y + 2, w - 6, h * 0.28f, h * 0.2f, color.shade(0.25f))
        mark(i, x, y, w, h, h / 2)
        text(x + w / 2, y + h * 0.42f, labelOf(i), w - 6, color.ink())
    }

    /** Croix directionnelle ; [separate] : quatre flèches séparées (PlayStation). */
    private fun dpad(cx: Float, cy: Float, arm: Float, half: Float, color: Color, separate: Boolean = false) {
        val gap = if (separate) 3f else -1f
        val arms = listOf(
            DPAD_UP to floatArrayOf(cx - half, cy - half - arm, 2 * half, arm + half - gap),
            DPAD_DOWN to floatArrayOf(cx - half, cy + gap, 2 * half, arm + half - gap),
            DPAD_LEFT to floatArrayOf(cx - half - arm, cy - half, arm + half - gap, 2 * half),
            DPAD_RIGHT to floatArrayOf(cx + gap, cy - half, arm + half - gap, 2 * half),
        )
        if (!separate) {
            rect(cx - half - 1, cy - half - arm - 1, 2 * half + 2, 2 * (half + arm) + 2, 3, color.shade(-0.45f))
            rect(cx - half - arm - 1, cy - half - 1, 2 * (half + arm) + 2, 2 * half + 2, 3, color.shade(-0.45f))
        }
        for ((_, a) in arms) {
            if (separate) rect(a[0], a[1] + 1, a[2], a[3], 2.5f, SHADOW)
            rect(a[0], a[1], a[2], a[3], if (separate) 2.5f else 2f, color)
        }
        if (!separate) {
            disc(cx, cy, half * 0.45f, color.shade(-0.35f))
            // Flèches gravées.
            val engraved = color.shade(0.3f)
            line(cx, cy - half - arm * 0.6f, cx, cy - half - arm * 0.2f, 1.5f, engraved)
            line(cx, cy + half + arm * 0.2f, cx, cy + half + arm * 0.6f, 1.5f, engraved)
            line(cx - half - arm * 0.6f, cy, cx - half - arm * 0.2f, cy, 1.5f, engraved)
            line(cx + half + arm * 0.2f, cy, cx + half + arm * 0.6f, cy, 1.5f, engraved)
        }
        // Branches attribuées teintées en vert (des anneaux se croiseraient au centre).
        for ((key, a) in arms) {
            val i = stepOf(key)
            if (isCurrent(i)) mark(i, a[0], a[1], a[2], a[3], 2) else if (isDone(i)) rect(a[0], a[1], a[2], a[3], 2, DONE.copy(alpha = 0.6f))
        }
    }

    /** Directions d'un joystick (arcade, Atari, Neo Geo) : quatre flèches autour, repérées. */
    private fun directions(cx: Float, cy: Float, dist: Float, color: Color) {
        listOf(DPAD_UP to (0f to -1f), DPAD_DOWN to (0f to 1f), DPAD_LEFT to (-1f to 0f), DPAD_RIGHT to (1f to 0f)).forEach { (key, d) ->
            val (dx, dy) = d
            val px = cx + dx * dist
            val py = cy + dy * dist
            val s = 5f
            // Triangle pointant vers l'extérieur.
            val ax = px + dx * s
            val ay = py + dy * s
            val bx = px - dx * s + dy * s
            val by = py - dy * s + dx * s
            val qx = px - dx * s - dy * s
            val qy = py - dy * s - dx * s
            line(ax, ay, bx, by, 2, color)
            line(bx, by, qx, qy, 2, color)
            line(qx, qy, ax, ay, 2, color)
            mark(stepOf(key), px - s, py - s, 2 * s, 2 * s, s)
        }
    }

    /** Stick analogique : [step] (L3 / R3) ou [axis] (axe du stick droit) ; -1 : décor. */
    private fun stick(cx: Float, cy: Float, r: Float, cap: Color, well: Color, step: Int, axis: Int, name: String) {
        disc(cx, cy, r + 5, well)
        disc(cx, cy + 1.5f, r + 0.5f, rgb(0, 0, 0, 100))
        disc(cx, cy, r, cap)
        ring(cx, cy, r * 0.72f, 1.6f, cap.shade(0.18f))
        val shown = if (isCurrent(axis)) axis else if (step >= 0) step else axis
        mark(shown, cx - r, cy - r, 2 * r, 2 * r, r)
        if (name.isNotEmpty() && step >= 0) text(cx, cy, name, 2 * r, cap.ink())
    }

    /** Écran d'une console portable. */
    private fun screen(x: Float, y: Float, w: Float, h: Float, bezel: Color, glass: Color) {
        rect(x, y, w, h, 6, bezel)
        rect(x + w * 0.14f, y + h * 0.12f, w * 0.72f, h * 0.76f, 2, glass)
        rect(x + w * 0.14f, y + h * 0.12f, w * 0.72f, h * 0.16f, 2, rgb(255, 255, 255, 16))
    }

    fun draw(style: PadStyle) {
        when (style) {
            PadStyle.DEFAULT -> defaultPad()
            PadStyle.NES -> nes(rgb(204, 204, 204), rgb(30, 30, 32), rgb(150, 150, 152), rgb(196, 30, 40), rgb(196, 30, 40))
            // Famicom : manette rouge, façade crème et bande dorée.
            PadStyle.FDS -> nes(rgb(176, 34, 40), rgb(226, 208, 164), rgb(196, 166, 104), rgb(150, 26, 32), rgb(140, 26, 30))
            PadStyle.SNES -> snes()
            PadStyle.N64 -> n64()
            // PlayStation : DualShock grise ; PlayStation 2 : DualShock 2 noire.
            PadStyle.PLAYSTATION -> playstation(rgb(202, 202, 208), rgb(112, 112, 120), rgb(170, 170, 178), rgb(72, 72, 78), rgb(70, 70, 80))
            PadStyle.PS2 -> playstation(rgb(40, 40, 46), rgb(12, 12, 14), rgb(62, 62, 70), rgb(96, 96, 104), rgb(190, 190, 200))
            PadStyle.GAMECUBE -> gamecube()
            PadStyle.PSP -> psp()
            PadStyle.GAME_BOY -> gameBoy()
            PadStyle.GBA -> gba()
            PadStyle.GENESIS -> genesis()
            PadStyle.MASTER_SYSTEM -> master()
            PadStyle.SATURN -> saturn()
            PadStyle.DREAMCAST -> dreamcast()
            PadStyle.PC_ENGINE -> pcEngine()
            PadStyle.NEO_GEO -> neoGeo()
            PadStyle.NEO_GEO_POCKET -> ngp()
            PadStyle.CPS -> arcade(cps = true)
            PadStyle.ARCADE -> arcade(cps = false)
            PadStyle.ATARI_2600 -> atari(proLine = false)
            PadStyle.ATARI_7800 -> atari(proLine = true)
            PadStyle.LYNX -> lynx()
            PadStyle.GX4000 -> gx4000()
        }
    }

    // ---- Manettes ----

    /** RetroPad : manette de type Xbox (A vert en bas, B rouge à droite, X bleu à gauche, Y jaune en haut). */
    private fun defaultPad() {
        val color = rgb(54, 56, 64)
        val edge = rgb(22, 22, 26)
        val trigger = rgb(40, 42, 48)
        shoulder(BTN_L2, 18f, 0f, 40f, 20f, trigger, edge)
        shoulder(BTN_L, 62f, 2f, 62f, 18f, trigger, edge)
        shoulder(BTN_R, 216f, 2f, 62f, 18f, trigger, edge)
        shoulder(BTN_R2, 282f, 0f, 40f, 20f, trigger, edge)
        body(color, edge) { g, c ->
            rect(12 - g, 16 - g, 316 + 2 * g, 92 + 2 * g, 40 + g, c)
            disc(66, 100, 46 + g, c)
            disc(274, 100, 46 + g, c)
        }
        rect(40, 20, 260, 9, 4.5f, color.shade(0.12f))
        stick(70f, 54f, 15f, rgb(34, 34, 38), color.shade(-0.25f), stepOf(BTN_L3), -1, "L3")
        disc(118, 96, 28, color.shade(-0.15f))
        dpad(118f, 96f, 14f, 7.5f, rgb(28, 28, 32))
        stick(222f, 96f, 15f, rgb(34, 34, 38), color.shade(-0.25f), stepOf(BTN_R3), rightAxis(), "R3")
        pill(BTN_SELECT, 146f, 58f, 24f, 11f, rgb(30, 30, 34), LabelPlace.BELOW)
        pill(BTN_START, 194f, 58f, 24f, 11f, rgb(30, 30, 34), LabelPlace.BELOW)
        button(BTN_X, 272f, 32f, 11f, YELLOW)
        button(BTN_Y, 250f, 54f, 11f, BLUE)
        button(BTN_A, 294f, 54f, 11f, RED)
        button(BTN_B, 272f, 76f, 11f, rgb(70, 168, 80))
    }

    /** NES (et Famicom) : rectangle, façade, bandes grises au centre, B et A dans des cadres carrés. */
    private fun nes(color: Color, panel: Color, strip: Color, buttons: Color, print: Color) {
        val edge = color.shade(-0.45f)
        shoulder(BTN_L, 34f, 6f, 70f, 18f, color.shade(-0.15f), edge)  // Famicom Disk System
        shoulder(BTN_R, 236f, 6f, 70f, 18f, color.shade(-0.15f), edge)
        body(color, edge) { g, c -> rect(16 - g, 20 - g, 308 + 2 * g, 110 + 2 * g, 6 + g, c) }
        rect(26, 32, 288, 86, 4, panel)
        rect(126, 38, 88, 74, 5, strip)
        repeat(4) { rect(132, 44 + it * 7f, 76, 3, 1.5f, strip.shade(-0.22f)) }
        disc(78, 75, 32, panel.shade(0.08f))
        dpad(78f, 75f, 17f, 9f, rgb(48, 48, 50))
        pill(BTN_SELECT, 146f, 96f, 26f, 9f, rgb(30, 30, 32), LabelPlace.ABOVE, print)
        pill(BTN_START, 194f, 96f, 26f, 9f, rgb(30, 30, 32), LabelPlace.ABOVE, print)
        rect(232, 58, 34, 34, 4, strip.shade(0.35f))
        rect(276, 58, 34, 34, 4, strip.shade(0.35f))
        button(BTN_B, 249f, 75f, 12f, buttons, LabelPlace.BELOW, print)
        button(BTN_A, 293f, 75f, 12f, buttons, LabelPlace.BELOW, print)
    }

    /** Super Nintendo : « os de chien » gris, boutons violets (A et B foncés, X et Y lavande), Select / Start en biais. */
    private fun snes() {
        val color = rgb(206, 206, 214)
        val edge = rgb(128, 128, 138)
        shoulder(BTN_L, 36f, 6f, 96f, 22f, rgb(168, 168, 178), edge)
        shoulder(BTN_R, 208f, 6f, 96f, 22f, rgb(168, 168, 178), edge)
        body(color, edge) { g, c ->
            disc(84, 84, 58 + g, c)
            disc(256, 84, 58 + g, c)
            rect(84, 26 - g, 172, 116 + 2 * g, 8, c)
        }
        disc(84, 84, 36, color.shade(-0.1f))
        dpad(84f, 84f, 18f, 10f, rgb(48, 48, 54))
        disc(256, 84, 48, rgb(150, 146, 172))
        val lavender = rgb(176, 166, 224)
        val purple = rgb(92, 72, 168)
        button(BTN_X, 256f, 58f, 13f, lavender)
        button(BTN_Y, 230f, 84f, 13f, lavender)
        button(BTN_A, 282f, 84f, 13f, purple)
        button(BTN_B, 256f, 110f, 13f, purple)
        slanted(BTN_SELECT, 132f, 102f, 144f, 90f, 8f, rgb(100, 100, 108), LabelPlace.BELOW, rgb(80, 80, 92))
        slanted(BTN_START, 196f, 102f, 208f, 90f, 8f, rgb(100, 100, 108), LabelPlace.BELOW, rgb(80, 80, 92))
    }

    /** Nintendo 64 : grise claire, trois poignées, stick au centre (Z dessous), Start rouge, A bleu, B vert, boutons C jaunes. */
    private fun n64() {
        val color = rgb(200, 200, 208)
        val edge = rgb(118, 118, 128)
        shoulder(BTN_L, 36f, 4f, 70f, 18f, rgb(176, 176, 186), edge)
        shoulder(BTN_R, 234f, 4f, 70f, 18f, rgb(176, 176, 186), edge)
        body(color, edge) { g, c ->
            rect(16 - g, 18 - g, 308 + 2 * g, 60 + 2 * g, 30 + g, c)
            rect(30 - g, 48 - g, 70 + 2 * g, 100 + 2 * g, 33 + g, c)
            rect(136 - g, 56 - g, 68 + 2 * g, 92 + 2 * g, 33 + g, c)
            rect(240 - g, 48 - g, 70 + 2 * g, 100 + 2 * g, 33 + g, c)
        }
        disc(66, 50, 28, color.shade(-0.08f))
        dpad(66f, 50f, 14f, 8f, rgb(84, 84, 92))
        stick(170f, 84f, 12f, rgb(150, 150, 158), color.shade(-0.3f), -1, -1, "")
        pill(BTN_L2, 170f, 132f, 34f, 14f, rgb(96, 96, 104))
        button(BTN_START, 170f, 42f, 9f, rgb(200, 40, 40), LabelPlace.ABOVE, rgb(40, 40, 48))
        button(BTN_Y, 230f, 46f, 12f, GREEN)
        button(BTN_B, 254f, 66f, 12f, rgb(42, 82, 200))
        // Boutons C (stick droit : gauche / droite, haut / bas).
        val stepX = axisStep(StickAxis.X)
        val stepY = axisStep(StickAxis.Y)
        if (stepX >= 0 || stepY >= 0) {
            val ccx = 288f
            val ccy = 42f
            val off = 13f
            val cr = 7.5f
            val cs = listOf(Triple(-off, 0f, stepX), Triple(off, 0f, stepX), Triple(0f, -off, stepY), Triple(0f, off, stepY))
            for ((ox, oy, _) in cs) {
                disc(ccx + ox, ccy + oy + 1.2f, cr + 1, SHADOW)
                disc(ccx + ox, ccy + oy, cr, YELLOW)
            }
            for ((ox, oy, step) in cs) mark(step, ccx + ox - cr, ccy + oy - cr, 2 * cr, 2 * cr, cr)
            text(ccx, ccy, "C", 10, rgb(70, 60, 20))
        }
    }

    /**
     * PlayStation (DualShock) et PlayStation 2 (DualShock 2, noire) : deux poignées, croix en quatre
     * flèches, symboles de couleur, deux sticks ; [print] : noms de Select / Start.
     */
    private fun playstation(color: Color, edge: Color, trigger: Color, arrows: Color, print: Color) {
        shoulder(BTN_L2, 22f, 0f, 44f, 20f, trigger, edge)
        shoulder(BTN_L, 70f, 2f, 58f, 18f, trigger, edge)
        shoulder(BTN_R, 212f, 2f, 58f, 18f, trigger, edge)
        shoulder(BTN_R2, 274f, 0f, 44f, 20f, trigger, edge)
        body(color, edge) { g, c ->
            rect(12 - g, 16 - g, 316 + 2 * g, 88 + 2 * g, 40 + g, c)
            disc(70, 100, 44 + g, c)
            disc(270, 100, 44 + g, c)
        }
        val dark = color.red < 0.4f
        disc(72, 56, 30, color.shade(if (dark) 0.08f else -0.1f))
        dpad(72f, 56f, 15f, 8f, arrows, separate = true)
        disc(268, 56, 32, color.shade(if (dark) 0.08f else -0.1f))
        val buttons = if (dark) rgb(26, 26, 30) else rgb(46, 46, 54)
        symbol(BTN_X, 268f, 34f, 10.5f, buttons, Symbol.TRIANGLE, rgb(76, 196, 156))
        symbol(BTN_Y, 246f, 56f, 10.5f, buttons, Symbol.SQUARE, rgb(232, 136, 206))
        symbol(BTN_A, 290f, 56f, 10.5f, buttons, Symbol.CIRCLE, rgb(232, 86, 96))
        symbol(BTN_B, 268f, 78f, 10.5f, buttons, Symbol.CROSS, rgb(120, 150, 236))
        val small = color.shade(if (dark) 0.3f else -0.55f)
        pill(BTN_SELECT, 146f, 56f, 24f, 8f, small, LabelPlace.BELOW, print)
        pill(BTN_START, 194f, 56f, 24f, 8f, small, LabelPlace.BELOW, print)
        disc(170, 82, 3, rgb(220, 40, 40))  // voyant « Analog »
        val cap = if (dark) rgb(28, 28, 32) else rgb(50, 50, 56)
        val well = color.shade(if (dark) 0.12f else -0.22f)
        stick(128f, 100f, 15f, cap, well, stepOf(BTN_L3), -1, "L3")
        stick(212f, 100f, 15f, cap, well, stepOf(BTN_R3), rightAxis(), "R3")
    }

    /** PSP : console portable noire, écran au centre, croix et petit stick à gauche, symboles à droite. */
    private fun psp() {
        val color = rgb(40, 40, 44)
        val edge = rgb(12, 12, 14)
        shoulder(BTN_L, 22f, 8f, 74f, 20f, rgb(64, 64, 70), edge)
        shoulder(BTN_R, 244f, 8f, 74f, 20f, rgb(64, 64, 70), edge)
        body(color, edge) { g, c -> rect(8 - g, 26 - g, 324 + 2 * g, 104 + 2 * g, 50 + g, c) }
        screen(94f, 32f, 152f, 84f, rgb(24, 24, 28), rgb(18, 22, 32))
        dpad(50f, 62f, 12f, 7f, rgb(84, 84, 90), separate = true)
        stick(50f, 104f, 8f, rgb(110, 110, 116), rgb(24, 24, 26), -1, -1, "")
        val dark = rgb(30, 30, 34)
        symbol(BTN_X, 290f, 46f, 9f, dark, Symbol.TRIANGLE, rgb(76, 196, 156))
        symbol(BTN_Y, 271f, 66f, 9f, dark, Symbol.SQUARE, rgb(232, 136, 206))
        symbol(BTN_A, 309f, 66f, 9f, dark, Symbol.CIRCLE, rgb(232, 86, 96))
        symbol(BTN_B, 290f, 86f, 9f, dark, Symbol.CROSS, rgb(120, 150, 236))
        pill(BTN_SELECT, 152f, 122f, 20f, 6f, rgb(96, 96, 102), LabelPlace.NONE)
        pill(BTN_START, 188f, 122f, 20f, 6f, rgb(96, 96, 102), LabelPlace.NONE)
    }

    /** Game Boy : console verticale grise, écran vert, B et A magenta en biais. */
    private fun gameBoy() {
        val color = rgb(196, 196, 186)
        val edge = rgb(112, 112, 104)
        body(color, edge) { g, c -> rect(108 - g, 0 - g, 124 + 2 * g, 150 + 2 * g, 9 + g, c) }
        rect(116, 8, 108, 66, 5, rgb(96, 96, 112))
        rect(136, 16, 68, 50, 1, rgb(150, 170, 72))
        disc(126, 36, 2.5f, rgb(220, 40, 40))
        dpad(140f, 102f, 11f, 6f, rgb(40, 40, 42))
        button(BTN_B, 192f, 110f, 9f, rgb(160, 32, 90), LabelPlace.BELOW, rgb(50, 50, 120))
        button(BTN_A, 214f, 98f, 9f, rgb(160, 32, 90), LabelPlace.BELOW, rgb(50, 50, 120))
        slanted(BTN_SELECT, 150f, 140f, 158f, 134f, 5f, rgb(130, 130, 128), LabelPlace.NONE, Color.Transparent)
        slanted(BTN_START, 170f, 140f, 178f, 134f, 5f, rgb(130, 130, 128), LabelPlace.NONE, Color.Transparent)
        repeat(4) { line(196 + it * 6f, 146, 212 + it * 6f, 130, 2, color.shade(-0.3f)) }  // haut-parleur
    }

    /** Game Boy Advance : console horizontale violette, écran au centre, A et B à droite. */
    private fun gba() {
        val color = rgb(92, 80, 172)
        val edge = rgb(48, 40, 110)
        shoulder(BTN_L, 30f, 10f, 80f, 20f, rgb(160, 160, 170), edge)
        shoulder(BTN_R, 230f, 10f, 80f, 20f, rgb(160, 160, 170), edge)
        body(color, edge) { g, c -> rect(16 - g, 28 - g, 308 + 2 * g, 100 + 2 * g, 48 + g, c) }
        screen(104f, 36f, 132f, 84f, rgb(40, 36, 70), rgb(20, 24, 32))
        dpad(62f, 70f, 13f, 7f, rgb(34, 34, 40))
        button(BTN_SELECT, 50f, 108f, 5f, rgb(204, 204, 214), LabelPlace.NONE)
        button(BTN_START, 70f, 108f, 5f, rgb(204, 204, 214), LabelPlace.NONE)
        button(BTN_B, 264f, 88f, 11f, rgb(204, 204, 214))
        button(BTN_A, 290f, 72f, 11f, rgb(204, 204, 214))
    }

    /** Mega Drive (6 boutons) : noire, deux lobes arrondis, croix ronde, A B C en bas et X Y Z au-dessus. */
    private fun genesis() {
        val color = rgb(34, 34, 38)
        val edge = rgb(10, 10, 12)
        val buttons = rgb(58, 58, 66)
        body(color, edge) { g, c ->
            rect(14 - g, 30 - g, 312 + 2 * g, 72 + 2 * g, 36 + g, c)
            disc(84, 96, 46 + g, c)
            disc(256, 96, 46 + g, c)
        }
        rect(130, 36, 80, 12, 6, color.shade(0.1f))  // logo
        disc(80, 76, 31, rgb(22, 22, 24))
        dpad(80f, 76f, 16f, 9f, rgb(48, 48, 52))
        pill(BTN_START, 170f, 58f, 44f, 12f, rgb(64, 64, 70))
        button(BTN_Y, 220f, 98f, 13f, buttons)  // A
        button(BTN_B, 252f, 88f, 13f, buttons)  // B
        button(BTN_A, 284f, 78f, 13f, buttons)  // C
        button(BTN_L, 214f, 64f, 9.5f, buttons)  // X
        button(BTN_X, 242f, 54f, 9.5f, buttons)  // Y
        button(BTN_R, 270f, 44f, 9.5f, buttons)  // Z
    }

    /** Master System (et Game Gear) : rectangle noir, croix dans un cadre rouge, boutons 1 et 2. */
    private fun master() {
        val color = rgb(36, 36, 40)
        val edge = rgb(12, 12, 14)
        body(color, edge) { g, c -> rect(20 - g, 32 - g, 300 + 2 * g, 90 + 2 * g, 8 + g, c) }
        rect(28, 40, 284, 74, 4, rgb(44, 44, 50))
        rect(48, 41, 72, 72, 4, rgb(196, 40, 44))  // cadre rouge de la croix
        rect(51, 44, 66, 66, 3, rgb(30, 30, 34))
        dpad(84f, 77f, 18f, 10f, rgb(56, 56, 62))
        rect(222, 104, 78, 3, 1.5f, rgb(196, 40, 44))  // filet rouge sous 1 et 2
        pill(BTN_START, 170f, 54f, 40f, 10f, rgb(84, 84, 90), LabelPlace.BELOW)
        button(BTN_B, 238f, 82f, 15f, rgb(64, 64, 70))
        button(BTN_A, 284f, 82f, 15f, rgb(64, 64, 70))
    }

    /** Saturn : « os de chien » noir, boutons gris (A B C en bas, X Y Z plus petits au-dessus), gâchettes L / R en haut. */
    private fun saturn() {
        val color = rgb(44, 44, 50)
        val edge = rgb(14, 14, 16)
        val buttons = rgb(100, 100, 110)
        shoulder(BTN_L2, 36f, 6f, 96f, 22f, rgb(72, 72, 80), edge)
        shoulder(BTN_R2, 208f, 6f, 96f, 22f, rgb(72, 72, 80), edge)
        body(color, edge) { g, c ->
            disc(84, 84, 58 + g, c)
            disc(256, 84, 58 + g, c)
            rect(84, 26 - g, 172, 116 + 2 * g, 8, c)
        }
        disc(84, 84, 34, color.shade(-0.3f))
        dpad(84f, 84f, 18f, 10f, rgb(78, 78, 86))
        pill(BTN_START, 170f, 108f, 34f, 12f, rgb(110, 110, 120), LabelPlace.BELOW)
        button(BTN_B, 226f, 104f, 12f, buttons)  // A
        button(BTN_A, 256f, 94f, 12f, buttons)  // B
        button(BTN_R, 286f, 84f, 12f, buttons)  // C
        button(BTN_Y, 222f, 72f, 9f, buttons)  // X
        button(BTN_X, 250f, 62f, 9f, buttons)  // Y
        button(BTN_L, 278f, 52f, 9f, buttons)  // Z
    }

    /** Dreamcast : blanche, fente de la carte mémoire, stick en haut à gauche, A rouge, B bleu, X jaune, Y vert. */
    private fun dreamcast() {
        val color = rgb(232, 232, 234)
        val edge = rgb(140, 140, 146)
        shoulder(BTN_L2, 40f, 4f, 74f, 18f, rgb(196, 196, 200), edge)
        shoulder(BTN_R2, 226f, 4f, 74f, 18f, rgb(196, 196, 200), edge)
        body(color, edge) { g, c ->
            rect(22 - g, 16 - g, 296 + 2 * g, 84 + 2 * g, 34 + g, c)
            disc(86, 104, 42 + g, c)
            disc(254, 104, 42 + g, c)
        }
        rect(132, 24, 76, 46, 4, rgb(170, 170, 178))
        rect(140, 30, 60, 34, 2, rgb(120, 164, 104))  // écran de la carte mémoire (VMU)
        rect(140, 30, 60, 8, 2, rgb(255, 255, 255, 30))
        stick(74f, 52f, 13f, rgb(206, 206, 210), color.shade(-0.25f), -1, -1, "")
        dpad(104f, 98f, 13f, 7.5f, rgb(70, 70, 76))
        button(BTN_X, 262f, 48f, 10.5f, GREEN)  // Y
        button(BTN_Y, 240f, 70f, 10.5f, YELLOW)  // X
        button(BTN_A, 284f, 70f, 10.5f, BLUE)  // B
        button(BTN_B, 262f, 92f, 10.5f, RED)  // A
        button(BTN_START, 170f, 96f, 7f, rgb(70, 70, 76), LabelPlace.BELOW, rgb(80, 80, 92))
    }

    /** PC Engine (TurboPad de la TurboGrafx-16) : noir, bosse au centre, Select / Run, interrupteurs turbo au-dessus de II et I. */
    private fun pcEngine() {
        val color = rgb(36, 36, 40)
        val edge = rgb(10, 10, 12)
        body(color, edge) { g, c ->
            rect(16 - g, 30 - g, 308 + 2 * g, 92 + 2 * g, 10 + g, c)
            rect(122 - g, 22 - g, 96 + 2 * g, 40 + 2 * g, 14 + g, c)  // bosse
        }
        rect(26, 40, 288, 72, 6, rgb(44, 44, 50))
        rect(128, 26, 84, 30, 10, rgb(54, 54, 60))
        rect(34, 46, 30, 6, 2, rgb(200, 40, 40))  // « TURBO »
        disc(80, 78, 30, rgb(28, 28, 32))
        dpad(80f, 78f, 18f, 10f, rgb(64, 64, 70))
        pill(BTN_SELECT, 148f, 92f, 26f, 9f, rgb(104, 104, 112), LabelPlace.ABOVE, rgb(200, 200, 210))
        pill(BTN_START, 192f, 92f, 26f, 9f, rgb(104, 104, 112), LabelPlace.ABOVE, rgb(200, 200, 210))
        for (x in listOf(232f, 276f)) {
            rect(x, 46, 16, 8, 2, rgb(150, 150, 156))
            rect(x + 6, 46, 4, 8, 1, rgb(200, 40, 40))
        }
        button(BTN_B, 240f, 84f, 14f, rgb(72, 72, 80))  // II
        button(BTN_A, 284f, 84f, 14f, rgb(72, 72, 80))  // I
    }

    /** Neo Geo (manette du Neo Geo CD) : noire, joystick à gauche, A rouge, B jaune, C vert, D bleu en arc. */
    private fun neoGeo() {
        val color = rgb(40, 40, 44)
        val edge = rgb(12, 12, 14)
        body(color, edge) { g, c -> rect(14 - g, 28 - g, 312 + 2 * g, 98 + 2 * g, 34 + g, c) }
        disc(82, 78, 32, rgb(24, 24, 26))
        disc(82, 79.5f, 14, rgb(0, 0, 0, 100))
        disc(82, 78, 14, rgb(76, 76, 82))
        ring(82, 78, 9, 1.5f, rgb(110, 110, 118))
        directions(82f, 78f, 24f, rgb(150, 150, 160))
        pill(BTN_SELECT, 148f, 64f, 24f, 9f, rgb(90, 90, 96), LabelPlace.BELOW)
        pill(BTN_START, 192f, 64f, 24f, 9f, rgb(90, 90, 96), LabelPlace.BELOW)
        button(BTN_B, 212f, 100f, 12f, RED)  // A
        button(BTN_A, 242f, 86f, 12f, YELLOW)  // B
        button(BTN_Y, 272f, 74f, 12f, GREEN)  // C
        button(BTN_X, 302f, 66f, 12f, BLUE)  // D
    }

    /** Neo Geo Pocket : console horizontale, écran au centre, joystick à gauche, A et B à droite. */
    private fun ngp() {
        val color = rgb(62, 64, 74)
        val edge = rgb(24, 24, 28)
        body(color, edge) { g, c -> rect(20 - g, 28 - g, 300 + 2 * g, 100 + 2 * g, 46 + g, c) }
        screen(104f, 34f, 132f, 88f, rgb(30, 30, 36), rgb(36, 46, 40))
        disc(62, 80, 22, rgb(30, 30, 34))
        disc(62, 80, 11, rgb(116, 116, 124))
        directions(62f, 80f, 17f, rgb(180, 180, 190))
        button(BTN_B, 264f, 94f, 11f, rgb(186, 186, 194))  // A
        button(BTN_A, 292f, 74f, 11f, rgb(186, 186, 194))  // B
        button(BTN_START, 280f, 118f, 5f, rgb(140, 140, 148), LabelPlace.NONE)  // Option
    }

    /**
     * Arcade : panneau noir, joystick à boule rouge, deux rangées de trois boutons, Pièce / Start.
     * Capcom (CPS) : poings en haut (rouges), pieds en bas (bleus).
     */
    private fun arcade(cps: Boolean) {
        val color = rgb(28, 28, 32)
        val edge = rgb(8, 8, 10)
        body(color, edge) { g, c -> rect(10 - g, 24 - g, 320 + 2 * g, 114 + 2 * g, 10 + g, c) }
        rect(10, 30, 320, 5, 0, if (cps) rgb(200, 40, 40) else rgb(60, 100, 210))
        disc(76, 86, 30, rgb(16, 16, 18))
        disc(76, 86, 6, rgb(110, 110, 118))
        disc(76, 83.5f, 16, rgb(0, 0, 0, 110))
        disc(76, 82, 16, RED)
        disc(71, 77, 6, RED.shade(0.35f))
        directions(76f, 86f, 40f, rgb(160, 160, 170))
        // Rangées des dispositions Android : CPS LP MP HP / LK MK HK ; arcade 4 5 6 / 1 2 3.
        val top = if (cps) listOf(BTN_Y, BTN_X, BTN_L) else listOf(BTN_X, BTN_L, BTN_R)
        val bottom = if (cps) listOf(BTN_B, BTN_A, BTN_R) else listOf(BTN_B, BTN_A, BTN_Y)
        val topColors = if (cps) listOf(RED, RED, RED) else listOf(BLUE, YELLOW, rgb(224, 224, 228))
        val bottomColors = if (cps) listOf(BLUE, BLUE, BLUE) else listOf(RED, GREEN, rgb(232, 120, 40))
        top.forEachIndexed { i, key -> button(key, 176f + 38 * i, if (i == 0) 66f else 62f, 13f, topColors[i]) }
        bottom.forEachIndexed { i, key -> button(key, 180f + 38 * i, if (i == 0) 104f else 100f, 13f, bottomColors[i]) }
        button(BTN_SELECT, 300f, 54f, 7f, rgb(224, 224, 228), LabelPlace.BELOW)
        button(BTN_START, 300f, 96f, 7f, rgb(224, 224, 228), LabelPlace.BELOW)
    }

    /**
     * Atari 2600 (CX40) : joystick noir carré, bouton de tir rouge ; 7800 (ProLine) : poignée et deux
     * boutons latéraux. Select / Reset (ou Pause) : interrupteurs de la console, à gauche.
     */
    private fun atari(proLine: Boolean) {
        val color = rgb(30, 30, 32)
        val edge = rgb(8, 8, 10)
        val metal = rgb(192, 192, 198)
        pill(BTN_SELECT, 52f, 56f, 44f, 14f, metal, LabelPlace.BELOW)
        pill(BTN_START, 52f, 104f, 44f, 14f, metal, LabelPlace.BELOW)
        if (!proLine) {
            // CX40 : socle carré biseauté, soufflet et manche vus de dessus.
            body(color, edge) { g, c -> rect(110 - g, 14 - g, 120 + 2 * g, 122 + 2 * g, 10 + g, c) }
            rect(118, 22, 104, 106, 8, rgb(44, 44, 48))
            disc(170, 82, 26, rgb(20, 20, 22))
            ring(170, 82, 20, 1.6f, rgb(52, 52, 56))
            ring(170, 82, 14, 1.6f, rgb(52, 52, 56))
            disc(173, 85, 10, rgb(0, 0, 0, 120))  // ombre du manche
            disc(170, 82, 9, rgb(14, 14, 16))
            disc(167, 79, 3.5f, rgb(84, 84, 90))
            directions(170f, 82f, 42f, rgb(150, 150, 160))
            button(BTN_B, 132f, 36f, 11f, RED)
            return
        }
        body(color, edge) { g, c -> rect(100 - g, 20 - g, 140 + 2 * g, 116 + 2 * g, 26 + g, c) }
        disc(170, 80, 26, rgb(20, 20, 22))
        disc(170, 81.5f, 12, rgb(0, 0, 0, 110))
        disc(170, 80, 12, rgb(64, 64, 68))
        directions(170f, 80f, 36f, rgb(150, 150, 160))
        button(BTN_B, 116f, 80f, 10f, RED)
        button(BTN_A, 224f, 80f, 10f, RED)
    }

    /** Lynx : longue console horizontale, écran au centre, croix à gauche, A et B à droite. */
    private fun lynx() {
        val color = rgb(50, 50, 56)
        val edge = rgb(16, 16, 18)
        body(color, edge) { g, c -> rect(4 - g, 30 - g, 332 + 2 * g, 96 + 2 * g, 46 + g, c) }
        screen(100f, 38f, 140f, 80f, rgb(30, 30, 34), rgb(28, 40, 36))
        disc(54, 78, 26, color.shade(-0.2f))
        dpad(54f, 78f, 14f, 7.5f, rgb(30, 30, 34))
        button(BTN_A, 300f, 64f, 11f, rgb(72, 72, 78))
        button(BTN_B, 276f, 92f, 11f, rgb(72, 72, 78))
        pill(BTN_L, 262f, 44f, 22f, 8f, rgb(96, 96, 104), LabelPlace.NONE)  // Option 1
        pill(BTN_R, 262f, 114f, 22f, 8f, rgb(96, 96, 104), LabelPlace.NONE)  // Option 2
        pill(BTN_START, 80f, 114f, 22f, 8f, rgb(96, 96, 104), LabelPlace.NONE)  // Pause
    }

    /** Amstrad GX4000 : manette grise, façade sombre, croix et deux boutons de tir. */
    private fun gx4000() {
        val color = rgb(176, 176, 182)
        val edge = rgb(100, 100, 106)
        body(color, edge) { g, c -> rect(20 - g, 34 - g, 300 + 2 * g, 86 + 2 * g, 12 + g, c) }
        rect(30, 44, 280, 66, 6, rgb(70, 70, 80))
        dpad(86f, 77f, 18f, 10f, rgb(30, 30, 34))
        button(BTN_B, 236f, 72f, 14f, RED, LabelPlace.BELOW)
        button(BTN_A, 284f, 72f, 14f, BLUE, LabelPlace.BELOW)
    }

    /**
     * GameCube : indigo, stick en haut à gauche, croix dessous, Start au centre, gros A vert, B rouge,
     * X et Y gris, stick C jaune (stick droit), gâchettes L / R et bouton Z sur la tranche droite.
     */
    private fun gamecube() {
        val color = rgb(84, 72, 170)
        val edge = rgb(40, 34, 96)
        val trigger = rgb(118, 108, 196)
        shoulder(BTN_L2, 40f, 2f, 76f, 20f, trigger, edge)
        shoulder(BTN_R, 186f, 8f, 44f, 14f, rgb(124, 112, 220), edge)  // Z
        shoulder(BTN_R2, 234f, 2f, 68f, 20f, trigger, edge)
        body(color, edge) { g, c ->
            rect(18 - g, 20 - g, 304 + 2 * g, 82 + 2 * g, 40 + g, c)
            disc(72, 104, 40 + g, c)
            disc(268, 104, 40 + g, c)
        }
        stick(76f, 56f, 14f, rgb(170, 170, 180), color.shade(-0.3f), -1, -1, "")
        disc(112, 102, 20, color.shade(-0.2f))
        dpad(112f, 102f, 11f, 6f, rgb(170, 170, 180))
        button(BTN_START, 170f, 64f, 6f, rgb(200, 200, 208), LabelPlace.BELOW)
        button(BTN_X, 296f, 50f, 9f, rgb(206, 206, 214))  // X, à droite de A
        button(BTN_Y, 258f, 32f, 9f, rgb(206, 206, 214))  // Y, au-dessus de A
        button(BTN_A, 264f, 60f, 15f, rgb(60, 176, 104))
        button(BTN_B, 236f, 84f, 9f, rgb(206, 46, 52))
        // Stick C : axes du stick droit.
        val axis = rightAxis()
        if (axis >= 0) {
            disc(232, 112, 17, color.shade(-0.3f))
            disc(232, 113.5f, 11.5f, rgb(0, 0, 0, 100))
            disc(232, 112, 11, rgb(232, 190, 46))
            text(232, 112, "C", 12, rgb(90, 70, 10))
            mark(axis, 221, 101, 22, 22, 11)
        }
    }
}
