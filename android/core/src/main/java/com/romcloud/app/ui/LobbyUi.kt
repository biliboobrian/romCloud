package com.romcloud.app.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.romcloud.app.I18n
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.Game
import com.romcloud.app.data.GameSystem
import com.romcloud.app.netplay.Lobby
import com.romcloud.app.netplay.LobbyGame
import com.romcloud.app.netplay.LobbyMember
import com.romcloud.app.netplay.LobbyPlayer
import com.romcloud.app.netplay.NetplayRules
import com.romcloud.app.netplay.Together
import com.romcloud.app.netplay.together
import com.romcloud.core.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Multijoueurs : joueurs connectés (pastille présent / absent) et lobbies (téléphone et TV).
// ---------------------------------------------------------------------------

/** Façons de jouer en réseau possibles pour ce jeu (« netplay », « link »). */
fun RomCloudApp.lobbyModes(system: GameSystem, game: Game): List<String> =
    listOfNotNull(NetplayRules.together(system, game, launcher.selectedPlayer(system, game.fileName))?.let { if (it == Together.LINK) "link" else "netplay" })

/** Jeu tel que le lobby le garde (identifiant, fichier, nombre de joueurs). */
fun lobbyGameOf(system: GameSystem, game: Game) =
    LobbyGame(game.id, system.id, game.title, game.fileName, game.size, NetplayRules.maxPlayers(game.players))

/** Jeu affiché dans un lobby : assez de joueurs pour ses membres (pas de filtre hors d'un lobby). */
fun fitsLobby(game: Game, minPlayers: Int): Boolean = minPlayers == 0 || NetplayRules.maxPlayers(game.players) >= minPlayers

/** Message d'erreur d'une action sur les lobbies. */
private fun Throwable.lobbyMessage(): String = message ?: I18n.get(R.string.lobby_error)

/**
 * « Jouer » dans le lobby : l'hôte lance la partie (le moteur la propose), les invités la rejoignent ;
 * chacun passe « en jeu » pour les autres. Renvoie un message à afficher, ou null.
 */
suspend fun RomCloudApp.playLobby(context: Context, lobby: Lobby, system: GameSystem?, game: Game?): String? {
    if (system == null || game == null) return I18n.get(R.string.netplay_game_unknown, lobby.game?.title.orEmpty())
    val host = lobbies.state.value.isHost
    val message = if (host) {
        if (!withContext(Dispatchers.IO) { library.isDownloaded(system, game) }) {
            downloader.start(system, game, withContext(Dispatchers.IO) { library.missingBios(repository.bios(system)) })
            return I18n.get(R.string.lobby_downloading, game.title)
        }
        val activity = context as? Activity ?: return I18n.get(R.string.lobby_error)
        hostNetplay(activity, system, game)
    } else {
        // Partie de l'hôte (son appareil annonce le jeu proposé) : rejointe, jeu téléchargé d'abord s'il manque.
        val peer = presence.peers.value.find { it.id == lobby.host && it.hosting != null } ?: return I18n.get(R.string.lobby_host_not_ready)
        // Jeu absent : téléchargé d'abord (message), la partie est rejointe à la fin du téléchargement.
        joinNetplay(context, peer)
    }
    if (message == null) runCatching { lobbies.markPlaying() }
    return message
}

@Composable
fun PresenceDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF34C759) else Color(0xFFFF9F0A)),
    )
}

/**
 * Fenêtre « Multijoueurs » : joueurs connectés et lobbies (côte à côte sur grand écran) ; « Créer un
 * lobby » dans la barre du titre, masqué dans un lobby. [onOpenLobby] : page du lobby.
 */
@Composable
fun MultiplayerDialog(app: RomCloudApp, onOpenLobby: () -> Unit, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state by app.lobbies.state.collectAsStateWithLifecycle()
    val account by app.account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.lobbies.refresh() }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                onDismiss()
                onOpenLobby()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(e.lobbyMessage())
            }
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.netplay_title), modifier = Modifier.weight(1f))
                if (account.signedIn && state.mine == null) {
                    Button(onClick = { act { app.lobbies.create() } }) {
                        Icon(Icons.Filled.Groups, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.lobby_create))
                    }
                }
            }
        },
        text = {
            if (!account.signedIn) {
                Text(stringResource(R.string.lobby_sign_in))
                return@AlertDialog
            }
            val players: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.lobby_players))
                    val sorted = state.players.sortedWith(compareByDescending<LobbyPlayer> { it.id == state.me }.thenByDescending { it.active })
                    if (sorted.isEmpty()) Text(stringResource(R.string.netplay_nobody), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sorted.forEach { PlayerRow(it, isMe = it.id == state.me) }
                }
            }
            val lobbies: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.lobby_lobbies))
                    if (state.lobbies.isEmpty()) Text(stringResource(R.string.lobby_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.lobbies.forEach { lobby ->
                        val mine = lobby.members.any { it.id == state.me }
                        LobbyCard(lobby, highlighted = mine) { act { app.lobbies.join(lobby.id) } }
                    }
                }
            }
            Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.weight(1f)) { players() }
                        Box(Modifier.weight(1f)) { lobbies() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        players()
                        lobbies()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        modifier = if (wide) Modifier.widthIn800() else Modifier,
    )
}

private fun Modifier.widthIn800(): Modifier = this.then(Modifier.fillMaxWidth(0.85f))

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PlayerRow(player: LobbyPlayer, isMe: Boolean) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PresenceDot(player.active)
            Column(Modifier.weight(1f)) {
                Text(
                    player.displayName + if (isMe) " ${stringResource(R.string.lobby_you)}" else "",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(platformName(player.platform), stringResource(R.string.lobby_in_game).takeIf { player.busy }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun platformName(platform: String): String = stringResource(
    when (platform) {
        "androidtv" -> R.string.netplay_platform_tv
        "windows" -> R.string.netplay_platform_pc
        else -> R.string.netplay_platform_phone
    },
)

@Composable
fun lobbyTitle(lobby: Lobby): String = lobby.game?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.lobby_new)

@Composable
private fun LobbyCard(lobby: Lobby, highlighted: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (highlighted) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lobbyTitle(lobby), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    lobby.members.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${lobby.members.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Choix entre les lobbies ouverts pour un jeu (« Rejoindre un lobby » de la fiche du jeu). */
@Composable
fun LobbyChoiceDialog(lobbies: List<Lobby>, onChoose: (Lobby) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lobby_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lobbies.forEach { lobby ->
                    val host = lobby.members.find { it.host } ?: lobby.members.firstOrNull()
                    Surface(onClick = { onChoose(lobby) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(host?.displayName.orEmpty(), fontWeight = FontWeight.SemiBold)
                            Text(lobby.members.joinToString(", ") { it.displayName }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Page du lobby : jeu (jaquette), façon de jouer en réseau (choix de l'hôte si le jeu permet les
 * deux), membres (couronne de l'hôte, statut), « Jouer », choix du jeu, quitter ou dissoudre.
 */
@Composable
fun LobbyContent(app: RomCloudApp, onChooseGame: () -> Unit, onMessage: (String) -> Unit, modifier: Modifier = Modifier, coverWidth: Dp = 140.dp) {
    val state by app.lobbies.state.collectAsStateWithLifecycle()
    val lobby = state.mine ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = state.isHost
    val gameRef = lobby.game
    // Jeu et système du lobby (fiches du serveur), relus quand l'hôte change de jeu.
    val loaded by produceState<Pair<GameSystem?, Game?>>(null to null, gameRef) {
        value = if (gameRef == null) null to null else withContext(Dispatchers.IO) {
            val system = runCatching { app.repository.system(gameRef.systemId) }.getOrNull()
            val game = runCatching { app.repository.game(gameRef.systemId, gameRef.gameId) }.getOrNull()?.takeIf { it.fileName == gameRef.fileName }
                ?: runCatching { app.repository.games(gameRef.systemId).data }.getOrDefault(emptyList()).find { it.fileName == gameRef.fileName && it.size == gameRef.size }
            system to game
        }
    }
    val (system, game) = loaded
    var confirmDissolve by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun act(block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()?.let(onMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(e.lobbyMessage())
            } finally {
                busy = false
            }
        }
    }
    val self = lobby.members.find { it.id == state.me }
    val canPlay = gameRef != null && (host || lobby.started)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.width(coverWidth).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val cover = game?.let { app.api.mediaUrl(it, "boxart") }
                if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                else Icon(Icons.Filled.SportsEsports, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(lobbyTitle(lobby), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val hostMember = lobby.members.find { it.host }
                Text(
                    listOfNotNull(
                        stringResource(R.string.lobby_hosted_by, hostMember?.displayName.orEmpty()),
                        system?.name,
                        gameRef?.players?.takeIf { it > 0 }?.let { stringResource(R.string.players_count, it.toString()) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gameRef == null) {
                    Text(stringResource(if (host) R.string.lobby_choose_hint else R.string.lobby_wait_game), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (lobby.modes.size > 1 && host) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lobby_mode_label))
                        lobby.modes.forEach { mode ->
                            FilterChip(selected = mode == lobby.mode, onClick = { act { app.lobbies.setMode(mode); null } }, label = { Text(modeLabel(mode)) })
                        }
                    }
                } else if (lobby.mode != null) {
                    Text(stringResource(R.string.lobby_mode_used, modeLabel(lobby.mode)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        self?.playing == true -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.lobby_status_playing)) }
                        canPlay -> Button(onClick = { act { app.playLobby(context, lobby, system, game) } }) { Text(stringResource(R.string.action_play)) }
                        !host -> Button(onClick = {}, enabled = false) {
                            Text(stringResource(if (gameRef == null) R.string.lobby_wait_game else R.string.lobby_wait_host))
                        }
                    }
                    if (host) {
                        OutlinedButton(onClick = onChooseGame) { Text(stringResource(if (gameRef == null) R.string.lobby_choose_game else R.string.lobby_change_game)) }
                        OutlinedButton(onClick = { confirmDissolve = true }) { Text(stringResource(R.string.lobby_dissolve), color = MaterialTheme.colorScheme.error) }
                    } else {
                        OutlinedButton(onClick = { act { app.lobbies.leave(); null } }) { Text(stringResource(R.string.lobby_leave)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.lobby_members, lobby.members.size), style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lobby.members.forEach { MemberRow(it, isMe = it.id == state.me) }
        }
    }

    if (confirmDissolve) {
        AlertDialog(
            onDismissRequest = { confirmDissolve = false },
            title = { Text(stringResource(R.string.lobby_dissolve)) },
            text = { Text(stringResource(R.string.lobby_dissolve_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDissolve = false
                    act { app.lobbies.dissolve(); null }
                }) { Text(stringResource(R.string.lobby_dissolve), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDissolve = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(if (mode == "link") R.string.lobby_mode_link else R.string.lobby_mode_netplay)

@Composable
private fun MemberRow(member: LobbyMember, isMe: Boolean) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Couronne de l'hôte.
            Box(Modifier.width(22.dp)) { if (member.host) Text("👑") }
            Text(
                member.displayName + if (isMe) " ${stringResource(R.string.lobby_you)}" else "",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (member.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    stringResource(if (member.playing) R.string.lobby_status_playing else R.string.lobby_status_waiting),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (member.playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
