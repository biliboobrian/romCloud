package com.romcloud.app.libretro

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bouton de la manette tactile, placé en ([x], [y]) dans le bloc de droite.
 * [key] : bouton RetroPad envoyé ; [rightStick] : à la place, direction du stick droit
 * (boutons C de la Nintendo 64, que les cœurs lisent sur le stick droit).
 */
internal data class PadButton(
    val label: String,
    val key: Int,
    val x: Dp,
    val y: Dp,
    val size: Dp = 58.dp,
    val color: Color = LABEL,
    val rightStick: Offset? = null,
)

/** Bouton de tranche (L, R, gâchettes), affiché en haut de l'écran. */
internal data class Shoulder(val label: String, val key: Int, val width: Dp = 76.dp)

/**
 * Disposition de la manette tactile d'une console : croix ou stick analogique à gauche,
 * bloc de boutons à droite, tranches en haut, Select / Start en bas (null = absent).
 */
internal data class PadLayout(
    val buttons: List<PadButton>,
    val left: List<Shoulder> = emptyList(),
    val right: List<Shoulder> = emptyList(),
    val select: String? = "SELECT",
    val start: String? = "START",
    /** Croix directionnelle (absente de la Nintendo 64, qui ne garde que le stick). */
    val dpad: Boolean = true,
    /** Stick analogique (Nintendo 64, Dreamcast, PSP) : au-dessus de la croix s'il y en a une. */
    val stick: Boolean = false,
    /** Deux sticks cliquables (L3 / R3) : configurés sur une manette physique. */
    val thumbs: Boolean = false,
    /** Manette de la console dessinée dans la configuration d'une manette physique. */
    val style: PadStyle = PadStyle.DEFAULT,
) {
    val width: Dp get() = buttons.maxOf { it.x + it.size }
    val height: Dp get() = buttons.maxOf { it.y + it.size }
}

internal val LABEL = Color.White.copy(alpha = 0.8f)

/**
 * Dispositions par console. Les boutons RetroPad utilisés suivent la convention des cœurs
 * libretro : B = bouton du bas, A = droite, Y = gauche, X = haut (disposition Super Nintendo).
 */
internal object PadLayouts {

    private const val B = KeyEvent.KEYCODE_BUTTON_B
    private const val A = KeyEvent.KEYCODE_BUTTON_A
    private const val Y = KeyEvent.KEYCODE_BUTTON_Y
    private const val X = KeyEvent.KEYCODE_BUTTON_X
    private const val L1 = KeyEvent.KEYCODE_BUTTON_L1
    private const val R1 = KeyEvent.KEYCODE_BUTTON_R1
    private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
    private const val R2 = KeyEvent.KEYCODE_BUTTON_R2
    private const val START = KeyEvent.KEYCODE_BUTTON_START

    private val RED = Color(0xFFFF6B6B)
    private val BLUE = Color(0xFF6FA0FF)
    private val GREEN = Color(0xFF5FD39A)
    private val YELLOW = Color(0xFFFFD35C)
    private val PINK = Color(0xFFF08FD0)

    /** Quatre boutons en losange : haut, droite, bas, gauche. */
    private fun diamond(top: Pair<String, Int>, right: Pair<String, Int>, bottom: Pair<String, Int>, left: Pair<String, Int>, colors: List<Color>? = null) =
        listOf(top to (56 to 0), right to (112 to 56), bottom to (56 to 112), left to (0 to 56)).mapIndexed { i, (b, pos) ->
            PadButton(b.first, b.second, pos.first.dp, pos.second.dp, color = colors?.get(i) ?: LABEL)
        }

    /** Boutons en diagonale montante, de gauche à droite (NES : B puis A). */
    private fun diagonal(vararg buttons: Pair<String, Int>, colors: List<Color>? = null): List<PadButton> {
        val n = buttons.size
        return buttons.mapIndexed { i, (label, key) ->
            PadButton(label, key, (74 * i).dp, (26 * (n - 1 - i)).dp, size = 64.dp, color = colors?.get(i) ?: LABEL)
        }
    }

    /** Deux rangées de trois (Mega Drive 6 boutons, bornes d'arcade). */
    private fun grid(top: List<Pair<String, Int>>, bottom: List<Pair<String, Int>>) =
        listOf(top to 0, bottom to 66).flatMap { (row, y) ->
            row.mapIndexed { i, (label, key) -> PadButton(label, key, (66 * i).dp, y.dp, size = 56.dp) }
        }

    private val SNES = PadLayout(
        buttons = diamond("X" to X, "A" to A, "B" to B, "Y" to Y, listOf(BLUE, RED, YELLOW, GREEN)),
        left = listOf(Shoulder("L", L1)),
        right = listOf(Shoulder("R", R1)),
        style = PadStyle.SNES,
    )

    /** Disposition par défaut (consoles sans disposition dédiée) : tous les boutons du RetroPad. */
    val DEFAULT = PadLayout(
        buttons = diamond("X" to X, "A" to A, "B" to B, "Y" to Y),
        left = listOf(Shoulder("L2", L2, 60.dp), Shoulder("L", L1)),
        right = listOf(Shoulder("R", R1), Shoulder("R2", R2, 60.dp)),
        thumbs = true,
    )

    private val NES = PadLayout(buttons = diagonal("B" to B, "A" to A, colors = listOf(RED, RED)), style = PadStyle.NES)
    /** Famicom Disk System : L change la face de la disquette, R l'éjecte / l'insère (FCEUmm, Nestopia). */
    private val FDS = NES.copy(left = listOf(Shoulder("FACE", L1)), right = listOf(Shoulder("DISK", R1)), style = PadStyle.FDS)
    private val GAME_BOY = PadLayout(buttons = diagonal("B" to B, "A" to A), style = PadStyle.GAME_BOY)
    private val GBA = GAME_BOY.copy(left = listOf(Shoulder("L", L1)), right = listOf(Shoulder("R", R1)), style = PadStyle.GBA)

    private val PLAYSTATION = PadLayout(
        buttons = diamond("△" to X, "○" to A, "✕" to B, "□" to Y, listOf(GREEN, RED, BLUE, PINK)),
        left = listOf(Shoulder("L2", L2, 60.dp), Shoulder("L1", L1)),
        right = listOf(Shoulder("R1", R1), Shoulder("R2", R2, 60.dp)),
        thumbs = true,
        style = PadStyle.PLAYSTATION,
    )
    /** PSP : croix et stick (de nombreux jeux se jouent au stick). */
    private val PSP = PLAYSTATION.copy(left = listOf(Shoulder("L", L1)), right = listOf(Shoulder("R", R1)), stick = true, thumbs = false, style = PadStyle.PSP)

    /** Mega Drive 6 boutons : A B C en bas, X Y Z en haut ; Mode = Select (masqué). */
    private val GENESIS = PadLayout(
        buttons = grid(listOf("X" to L1, "Y" to X, "Z" to R1), listOf("A" to Y, "B" to B, "C" to A)),
        select = null,
        style = PadStyle.GENESIS,
    )
    /**
     * Saturn : même disposition que la Mega Drive, mais les cœurs (Beetle Saturn, Yabause, Kronos)
     * lisent A sur B, B sur A, C sur R, X sur Y, Y sur X, Z sur L, et les gâchettes sur L2 / R2.
     */
    private val SATURN = PadLayout(
        buttons = grid(listOf("X" to Y, "Y" to X, "Z" to L1), listOf("A" to B, "B" to A, "C" to R1)),
        left = listOf(Shoulder("L", L2)),
        right = listOf(Shoulder("R", R2)),
        select = null,
        style = PadStyle.SATURN,
    )
    private val MASTER_SYSTEM = PadLayout(buttons = diagonal("1" to B, "2" to A), select = null, style = PadStyle.MASTER_SYSTEM)

    /** Amstrad GX4000 (cap32, joystick Amstrad) : Feu 1 sur B, Feu 2 sur A, ni Select ni Start. */
    private val GX4000 = PadLayout(buttons = diagonal("1" to B, "2" to A), select = null, start = null, style = PadStyle.GX4000)

    private val PC_ENGINE = PadLayout(buttons = diagonal("II" to B, "I" to A), start = "RUN", style = PadStyle.PC_ENGINE)

    /** Nintendo 64 : stick, A et B, boutons C (stick droit), Z (L2), L et R. */
    private val N64 = PadLayout(
        buttons = listOf(
            PadButton("C▲", 0, 132.dp, 0.dp, 36.dp, YELLOW, rightStick = Offset(0f, -1f)),
            PadButton("C▶", 0, 168.dp, 36.dp, 36.dp, YELLOW, rightStick = Offset(1f, 0f)),
            PadButton("C▼", 0, 132.dp, 72.dp, 36.dp, YELLOW, rightStick = Offset(0f, 1f)),
            PadButton("C◀", 0, 96.dp, 36.dp, 36.dp, YELLOW, rightStick = Offset(-1f, 0f)),
            PadButton("B", Y, 36.dp, 80.dp, 56.dp, GREEN),
            PadButton("A", B, 104.dp, 112.dp, 64.dp, BLUE),
        ),
        left = listOf(Shoulder("Z", L2, 60.dp), Shoulder("L", L1)),
        right = listOf(Shoulder("R", R1)),
        select = null,
        dpad = false,
        stick = true,
        style = PadStyle.N64,
    )

    /** Dreamcast (et Naomi, Atomiswave) : croix et stick, A B X Y, gâchettes L et R. */
    private val DREAMCAST = PadLayout(
        buttons = diamond("Y" to X, "B" to A, "A" to B, "X" to Y, listOf(GREEN, BLUE, RED, YELLOW)),
        left = listOf(Shoulder("L", L2)),
        right = listOf(Shoulder("R", R2)),
        select = null,
        stick = true,
        style = PadStyle.DREAMCAST,
    )

    /** Neo Geo : A B C D en arc. */
    private val NEO_GEO = PadLayout(
        buttons = listOf("A" to B, "B" to A, "C" to Y, "D" to X).mapIndexed { i, (label, key) ->
            PadButton(label, key, (62 * i).dp, listOf(56, 28, 12, 0)[i].dp, size = 56.dp, color = listOf(RED, YELLOW, GREEN, BLUE)[i])
        },
        select = "COIN",
        style = PadStyle.NEO_GEO,
    )
    private val NEO_GEO_POCKET = PadLayout(buttons = diagonal("A" to B, "B" to A), select = null, start = "OPTION", style = PadStyle.NEO_GEO_POCKET)

    /** CP System : poings en haut, pieds en bas ; Select = pièce. */
    private val CPS = PadLayout(
        buttons = grid(listOf("LP" to Y, "MP" to X, "HP" to L1), listOf("LK" to B, "MK" to A, "HK" to R1)),
        select = "COIN",
        style = PadStyle.CPS,
    )
    private val ARCADE = PadLayout(
        buttons = grid(listOf("4" to X, "5" to L1, "6" to R1), listOf("1" to B, "2" to A, "3" to Y)),
        select = "COIN",
        style = PadStyle.ARCADE,
    )

    private val ATARI_2600 = PadLayout(buttons = listOf(PadButton("FIRE", B, 0.dp, 0.dp, 72.dp, RED)), start = "RESET", style = PadStyle.ATARI_2600)
    private val ATARI_7800 = PadLayout(buttons = diagonal("1" to B, "2" to A), start = "PAUSE", style = PadStyle.ATARI_7800)
    private val LYNX = PadLayout(
        buttons = diagonal("B" to B, "A" to A),
        left = listOf(Shoulder("OPT 1", L1)),
        right = listOf(Shoulder("OPT 2", R1)),
        select = null,
        start = "PAUSE",
        style = PadStyle.LYNX,
    )

    private val BY_SYSTEM: Map<String, PadLayout> = buildMap {
        put("nes", NES)
        put("fds", FDS)
        listOf("gb", "gbc", "megaduck", "gw", "pokemini", "supervision").forEach { put(it, GAME_BOY) }
        put("gba", GBA)
        listOf("snes", "snesmsu1", "satellaview").forEach { put(it, SNES) }
        put("n64", N64)
        put("psx", PLAYSTATION)
        listOf("psp", "pspminis").forEach { put(it, PSP) }
        listOf("genesis", "genesismsu", "segacd", "sega32x", "pico").forEach { put(it, GENESIS) }
        listOf("saturn", "stv").forEach { put(it, SATURN) }
        listOf("master", "gamegear", "sg1000").forEach { put(it, MASTER_SYSTEM) }
        listOf("dreamcast", "naomi", "atomiswave").forEach { put(it, DREAMCAST) }
        // PC-FX (six boutons) : disposition par défaut, qui donne tous les boutons du RetroPad.
        listOf("tg16", "tgcd", "supergrafx").forEach { put(it, PC_ENGINE) }
        listOf("neogeo", "neogeocd").forEach { put(it, NEO_GEO) }
        listOf("ngp", "ngpc").forEach { put(it, NEO_GEO_POCKET) }
        listOf("cps1", "cps2", "cps3").forEach { put(it, CPS) }
        listOf("fbneo", "mame").forEach { put(it, ARCADE) }
        put("gx4000", GX4000)
        put("atari2600", ATARI_2600)
        put("atari7800", ATARI_7800)
        put("lynx", LYNX)
    }

    /** Système inconnu (ajouté à la main sur le serveur) : d'après le cœur. */
    private val BY_CORE: List<Pair<String, PadLayout>> = listOf(
        // Préfixes les plus longs d'abord : « mesen-s » (Super Nintendo) avant « mesen » (NES).
        "mesen-s" to SNES,
        "fceumm" to NES, "nestopia" to NES, "mesen" to NES, "quicknes" to NES,
        "gambatte" to GAME_BOY, "sameboy" to GAME_BOY, "gearboy" to GAME_BOY, "tgbdual" to GAME_BOY,
        "mgba" to GBA, "gpsp" to GBA, "vba" to GBA, "mednafen_gba" to GBA,
        "snes9x" to SNES, "bsnes" to SNES,
        "mupen64plus" to N64, "parallel_n64" to N64,
        "pcsx_rearmed" to PLAYSTATION, "swanstation" to PLAYSTATION, "duckstation" to PLAYSTATION, "mednafen_psx" to PLAYSTATION,
        "ppsspp" to PSP,
        "genesis_plus_gx" to GENESIS, "picodrive" to GENESIS,
        "mednafen_saturn" to SATURN, "yabause" to SATURN, "yabasanshiro" to SATURN, "kronos" to SATURN,
        "gearsystem" to MASTER_SYSTEM, "smsplus" to MASTER_SYSTEM,
        "flycast" to DREAMCAST,
        "mednafen_pce" to PC_ENGINE, "mednafen_supergrafx" to PC_ENGINE,
        "neocd" to NEO_GEO, "geolith" to NEO_GEO,
        "mednafen_ngp" to NEO_GEO_POCKET, "race" to NEO_GEO_POCKET,
        "fbalpha2012_cps" to CPS,
        "fbneo" to ARCADE, "fbalpha" to ARCADE, "mame" to ARCADE,
        "stella" to ATARI_2600,
        "prosystem" to ATARI_7800,
        "handy" to LYNX, "mednafen_lynx" to LYNX,
    )

    fun forGame(systemId: String, core: String): PadLayout =
        BY_SYSTEM[systemId.lowercase()]
            ?: BY_CORE.firstOrNull { (prefix, _) -> core.startsWith(prefix) }?.second
            ?: DEFAULT
}
