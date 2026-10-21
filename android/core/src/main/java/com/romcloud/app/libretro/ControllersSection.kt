package com.romcloud.app.libretro

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.romcloud.core.R

/**
 * Manettes lues par la fiche du jeu (choix des joueurs, configuration d'une manette) : l'activité
 * (téléphone, TV) lui passe d'abord ses évènements, qui ne vont pas plus loin s'ils sont pris.
 */
object ControllerCapture {
    internal var onKey: ((KeyEvent) -> Boolean)? = null
    internal var onMotion: ((MotionEvent) -> Boolean)? = null

    fun dispatchKey(event: KeyEvent): Boolean = onKey?.invoke(event) == true
    fun dispatchMotion(event: MotionEvent): Boolean = onMotion?.invoke(event) == true
}

/** Famille d'une manette branchée : noms de ses boutons. */
internal enum class PadFamily { XBOX, PLAYSTATION, NINTENDO }

/** Image (illustration de @rachels_ham) et famille d'une manette branchée, d'après son fabricant et son modèle. */
internal fun physicalPad(device: InputDevice): Pair<Int, PadFamily> {
    val name = device.name.orEmpty()
    return when (device.vendorId) {
        0x054c -> when {
            device.productId == 0x0268 -> R.drawable.pad_ps3
            device.productId in setOf(0x0ce6, 0x0df2) || name.contains("DualSense", true) -> R.drawable.pad_ps5
            else -> R.drawable.pad_ps4
        } to PadFamily.PLAYSTATION
        0x057e -> R.drawable.pad_joycon to PadFamily.NINTENDO
        0x045e -> when {
            device.productId in setOf(0x028e, 0x028f, 0x0291, 0x02a1, 0x0719) -> R.drawable.pad_xbox360
            device.productId in setOf(0x0b12, 0x0b13) || name.contains("Series", true) -> R.drawable.pad_xboxseries
            else -> R.drawable.pad_xboxone
        } to PadFamily.XBOX
        else -> R.drawable.pad_xboxseries to PadFamily.XBOX
    }
}

/** Manette de la console du système (illustrations de @rachels_ham), null s'il n'y en a pas. */
@DrawableRes
internal fun consolePadImage(systemId: String): Int? = when (systemId.lowercase()) {
    "nes" -> R.drawable.pad_nes
    "fds" -> R.drawable.pad_famicom
    "snes", "snesmsu1", "satellaview" -> R.drawable.pad_snes
    "n64" -> R.drawable.pad_n64
    "gc", "ngc" -> R.drawable.pad_gamecube
    "wii" -> R.drawable.pad_wii
    "psx" -> R.drawable.pad_psx
    "ps2" -> R.drawable.pad_ps2
    "genesis", "genesismsu", "segacd", "sega32x", "pico" -> R.drawable.pad_genesis
    "master", "sg1000" -> R.drawable.pad_master
    "saturn" -> R.drawable.pad_saturn
    "dreamcast", "naomi", "atomiswave" -> R.drawable.pad_dreamcast
    "tg16", "tgcd", "supergrafx" -> R.drawable.pad_pcengine
    "neogeo", "neogeocd" -> R.drawable.pad_neogeo
    "atari2600" -> R.drawable.pad_atari2600
    else -> null
}

/**
 * Élément de la manette qui commande l'étape [step] avec la configuration [mapping] (null : celle
 * d'Android) : « a » (bouton du bas), « dpup », « lefttrigger », « leftx »… comme les noms de SDL de
 * l'application Windows ; nom brut d'une touche ou d'un axe inhabituel ; null : aucun.
 */
internal fun stepElement(step: MappingStep, mapping: GamepadMapping?): String? {
    step.stick?.let { stick ->
        val (axis, standard, name) = when (stick) {
            StickAxis.LEFT_X -> Triple(mapping?.leftStickX, MotionEvent.AXIS_X, "leftx")
            StickAxis.LEFT_Y -> Triple(mapping?.leftStickY, MotionEvent.AXIS_Y, "lefty")
            StickAxis.X -> Triple(mapping?.rightStickX, MotionEvent.AXIS_Z, "rightx")
            StickAxis.Y -> Triple(mapping?.rightStickY, MotionEvent.AXIS_RZ, "righty")
        }
        return if (axis == null || axis == standard) name else axisElement(axis, null)
    }
    mapping?.axes?.firstOrNull { it.retroKey == step.retroKey }?.let { return axisElement(it.axis, it.direction) }
    val key = mapping?.keys?.entries?.firstOrNull { it.value == step.retroKey }?.key
        ?: STANDARD_KEYS.firstOrNull { code -> (mapping?.retroKey(code) ?: GamepadInput.retroKey(code)) == step.retroKey }
        ?: return null
    return KEY_ELEMENTS[key] ?: KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
}

private fun axisElement(axis: Int, direction: Float?): String = when (axis) {
    MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE -> "lefttrigger"
    MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS -> "righttrigger"
    else -> MotionEvent.axisToString(axis).removePrefix("AXIS_") + when {
        direction == null -> ""
        direction > 0 -> " +"
        else -> " −"
    }
}

private val KEY_ELEMENTS = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to "a", KeyEvent.KEYCODE_BUTTON_B to "b", KeyEvent.KEYCODE_BUTTON_X to "x", KeyEvent.KEYCODE_BUTTON_Y to "y",
    KeyEvent.KEYCODE_BUTTON_L1 to "leftshoulder", KeyEvent.KEYCODE_BUTTON_R1 to "rightshoulder",
    KeyEvent.KEYCODE_BUTTON_L2 to "lefttrigger", KeyEvent.KEYCODE_BUTTON_R2 to "righttrigger",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "leftstick", KeyEvent.KEYCODE_BUTTON_THUMBR to "rightstick",
    KeyEvent.KEYCODE_BUTTON_START to "start", KeyEvent.KEYCODE_BUTTON_SELECT to "back", KeyEvent.KEYCODE_BUTTON_MODE to "guide",
    KeyEvent.KEYCODE_DPAD_UP to "dpup", KeyEvent.KEYCODE_DPAD_DOWN to "dpdown", KeyEvent.KEYCODE_DPAD_LEFT to "dpleft",
    KeyEvent.KEYCODE_DPAD_RIGHT to "dpright",
)

/** Touches d'une manette standard, dans l'ordre où chercher celle d'un bouton RetroPad. */
private val STANDARD_KEYS = KEY_ELEMENTS.keys.filter { it != KeyEvent.KEYCODE_BUTTON_MODE }

private val BUTTON_NAMES = mapOf(
    PadFamily.XBOX to mapOf("a" to "A", "b" to "B", "x" to "X", "y" to "Y", "leftshoulder" to "LB", "rightshoulder" to "RB",
        "lefttrigger" to "LT", "righttrigger" to "RT", "leftstick" to "LS", "rightstick" to "RS", "start" to "Menu", "back" to "View", "guide" to "Xbox"),
    PadFamily.PLAYSTATION to mapOf("a" to "✕", "b" to "○", "x" to "□", "y" to "△", "leftshoulder" to "L1", "rightshoulder" to "R1",
        "lefttrigger" to "L2", "righttrigger" to "R2", "leftstick" to "L3", "rightstick" to "R3", "start" to "Options", "back" to "Share", "guide" to "PS"),
    PadFamily.NINTENDO to mapOf("a" to "B", "b" to "A", "x" to "Y", "y" to "X", "leftshoulder" to "L", "rightshoulder" to "R",
        "lefttrigger" to "ZL", "righttrigger" to "ZR", "leftstick" to "L3", "rightstick" to "R3", "start" to "+", "back" to "−", "guide" to "Home"),
)

/** Nom affiché d'un élément de la manette pour sa famille (« A », « ✕ », « Croix ↑ », « Stick gauche ↔ »). */
@Composable
private fun elementLabel(element: String?, family: PadFamily): String {
    if (element == null) return "—"
    val arrows = mapOf("up" to "↑", "down" to "↓", "left" to "←", "right" to "→")
    if (element.startsWith("dp")) arrows[element.removePrefix("dp")]?.let { return stringResource(R.string.pads_dpad, it) }
    Regex("^(left|right)([xy])$").find(element)?.let { m ->
        val dir = if (m.groupValues[2] == "x") "↔" else "↕"
        return stringResource(if (m.groupValues[1] == "left") R.string.pads_left_stick else R.string.pads_right_stick, dir)
    }
    return BUTTON_NAMES.getValue(family)[element] ?: element
}

private enum class Capture { NONE, ORDER, WIZARD }

/**
 * Manettes du moteur intégré dans la fiche d'un jeu (téléphone et TV) : manette de chaque joueur et
 * choix des joueurs (chacun appuie à son tour sur un bouton de sa manette), boutons de la console
 * avec ceux de la manette qui les commandent, configuration d'une manette étape par étape. Mêmes
 * réglages que le menu du jeu (« Joueurs des manettes », « Configurer la manette ») : joueurs pour
 * tous les jeux, configuration par système et modèle de manette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllersSection(systemId: String, systemName: String, core: String, modifier: Modifier = Modifier, imageSize: Dp = 140.dp) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    // Manettes branchées ou débranchées : affichage mis à jour.
    DisposableEffect(Unit) {
        val input = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { revision++ }
            override fun onInputDeviceRemoved(deviceId: Int) { revision++ }
            override fun onInputDeviceChanged(deviceId: Int) { revision++ }
        }
        input.registerInputDeviceListener(listener, null)
        onDispose { input.unregisterInputDeviceListener(listener) }
    }
    // Retour d'une partie : joueurs ou configuration peut-être changés depuis le menu du jeu.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!first) revision++
            first = false
        }
    }
    val players = remember(revision) { GamepadPlayers(context, fromDisk = true) }
    val mappings = remember(systemId, revision) { GamepadMappings(context, systemId, fromDisk = true) }
    val steps = remember(systemId, core) { mappingSteps(PadLayouts.forGame(systemId, core)) }
    val byPort = remember(players) { (0 until GamepadPlayers.MAX_PLAYERS).map(players::deviceOf) }
    val connected = byPort.filterNotNull()

    var capture by remember { mutableStateOf(Capture.NONE) }
    val order = remember { mutableStateListOf<InputDevice>() }
    var session by remember { mutableStateOf<MappingSession?>(null) }
    var shownPort by remember { mutableIntStateOf(-1) }

    DisposableEffect(capture, session) {
        when (capture) {
            Capture.ORDER -> {
                ControllerCapture.onKey = onKey@{ event ->
                    val device = event.device?.takeIf(GamepadInput::isGamepad) ?: return@onKey false
                    if (event.action == KeyEvent.ACTION_DOWN && order.none { it.id == device.id }) {
                        order += device
                        // Toutes les manettes branchées ont un joueur : enregistré.
                        if (order.size >= GamepadPlayers.connected().size) {
                            players.setOrder(order.toList())
                            Toast.makeText(context, R.string.pads_order_saved, Toast.LENGTH_SHORT).show()
                            capture = Capture.NONE
                            revision++
                        }
                    }
                    true
                }
                ControllerCapture.onMotion = { event -> event.device?.let(GamepadInput::isGamepad) == true }
            }
            Capture.WIZARD -> session?.let { s ->
                ControllerCapture.onKey = s::onKey
                ControllerCapture.onMotion = s::onMotion
            }
            Capture.NONE -> Unit
        }
        onDispose {
            ControllerCapture.onKey = null
            ControllerCapture.onMotion = null
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pads_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        // Joueurs : manette de chacun (choix en cours : dans l'ordre des appuis).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (port in 0 until GamepadPlayers.MAX_PLAYERS) {
                val device = if (capture == Capture.ORDER) order.getOrNull(port) else byPort[port]
                val current = capture == Capture.ORDER && port == order.size
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadTile(device?.let { physicalPad(it).first }, Modifier.fillMaxWidth(), highlighted = current, touch = port == 0 && device == null && capture == Capture.NONE)
                    Text(stringResource(R.string.pads_player, port + 1), style = MaterialTheme.typography.labelLarge)
                    Text(
                        device?.name ?: stringResource(if (port == 0 && capture == Capture.NONE) R.string.pads_touch else R.string.pads_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        when (capture) {
            Capture.ORDER -> {
                Text(stringResource(R.string.pads_order_prompt, order.size + 1), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { capture = Capture.NONE }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(
                        onClick = {
                            players.setOrder(order.toList())
                            Toast.makeText(context, R.string.pads_order_saved, Toast.LENGTH_SHORT).show()
                            capture = Capture.NONE
                            revision++
                        },
                        enabled = order.isNotEmpty(),
                    ) { Text(stringResource(R.string.pads_order_done)) }
                }
            }
            Capture.WIZARD -> session?.let { s -> Wizard(s, systemId, imageSize, onCancel = { capture = Capture.NONE }) }
            Capture.NONE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            order.clear()
                            capture = Capture.ORDER
                        },
                        enabled = connected.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Groups, null)
                        Text(stringResource(R.string.pads_order), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            session = MappingSession(mappings, steps) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                capture = Capture.NONE
                                revision++
                            }
                            capture = Capture.WIZARD
                        },
                        enabled = connected.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.SportsEsports, null)
                        Text(stringResource(R.string.pads_configure), Modifier.padding(start = 8.dp))
                    }
                }
                val shown = byPort.getOrNull(shownPort) ?: connected.firstOrNull()
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    consolePadImage(systemId)?.let { PadTile(it, Modifier.width(imageSize)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (connected.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                byPort.forEachIndexed { port, device ->
                                    if (device != null) {
                                        FilterChip(
                                            selected = device.id == shown?.id,
                                            onClick = { shownPort = port },
                                            label = { Text(stringResource(R.string.pads_player, port + 1)) },
                                        )
                                    }
                                }
                            }
                        }
                        if (shown == null) {
                            Text(stringResource(R.string.pads_no_pad), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val family = physicalPad(shown).second
                            val mapping = mappings.get(shown)
                            Row {
                                Text(systemName, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(shown.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            for (step in steps) {
                                Row {
                                    Text(stepLabel(step), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(elementLabel(stepElement(step, mapping), family), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.pads_hint, systemName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.pads_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Libellé d'une étape : bouton de la console (« B », « C haut ») ou consigne (croix, stick). */
@Composable
private fun stepLabel(step: MappingStep): String = step.label ?: stringResource(step.labelRes)

/** Configuration d'une manette en cours : manette de la console, consigne, étapes et attributions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Wizard(session: MappingSession, systemId: String, imageSize: Dp, onCancel: () -> Unit) {
    val device = session.deviceName
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        consolePadImage(systemId)?.let { PadTile(it, Modifier.width(imageSize)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (device == null) stringResource(R.string.pads_press_any)
                else stringResource(R.string.pads_press, session.step?.let { stepLabel(it) }.orEmpty()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            device?.let { Text(stringResource(R.string.pads_configuring, it), style = MaterialTheme.typography.bodySmall) }
            session.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                session.steps.forEachIndexed { i, step ->
                    val current = device != null && i == session.stepIndex
                    val assigned = session.assigned[step]
                    Text(
                        (if (assigned != null) "✓ " else "") + stepLabel(step) + (assigned?.let { " : $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    Text(stringResource(R.string.pads_wizard_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        TextButton(onClick = session::resetDevice, enabled = device != null) { Text(stringResource(R.string.pads_reset)) }
        TextButton(onClick = session::skip, enabled = device != null) { Text(stringResource(R.string.pads_skip)) }
    }
}

/** Case d'une manette : illustration, ou icône (écran tactile, aucune manette). */
@Composable
private fun PadTile(@DrawableRes image: Int?, modifier: Modifier, highlighted: Boolean = false, touch: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(292f / 307f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (highlighted) 3.dp else 0.dp, if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            // Pixel art : agrandi sans lissage.
            Image(ImageBitmap.imageResource(image), null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.None, modifier = Modifier.matchParentSize())
        } else {
            Icon(
                if (touch) Icons.Filled.TouchApp else Icons.Filled.SportsEsports,
                null,
                Modifier.size(32.dp).alpha(0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
