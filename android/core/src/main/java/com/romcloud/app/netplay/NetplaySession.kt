package com.romcloud.app.netplay

import android.os.ParcelFileDescriptor
import com.romcloud.app.libretro.Netplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Partie à plusieurs demandée au lancement du jeu : proposée (hôte) ou rejointe (invité, adresse de
 * l'hôte). Liaison entre consoles ([NetplayProtocol.HostedGame.link]) : [game] est le jeu de cet
 * appareil ; [saveCore] : émulateur choisi pour le jeu, dont la sauvegarde est reprise par le cœur de la liaison.
 */
@Serializable
data class NetplayLaunch(
    val host: Boolean,
    val address: String = "",
    val port: Int = 0,
    val peerName: String = "",
    val game: NetplayProtocol.HostedGame,
    val saveCore: String? = null,
    /** Invité par Internet : partie rejointe par le relais du serveur (identifiant de la partie). */
    val relay: String? = null,
    /** Images de délai souhaitées (Internet), 0 : celui de l'hôte. */
    val delay: Int = 0,
) {
    val link: LinkKind? get() = LinkRules.kind(game.link)
}

/**
 * Connexion acceptée : remise à l'adaptateur natif (la socket Java est fermée, son descripteur gardé),
 * touches échangées ou, liaison par paquets ([packets]), paquets du cœur. Liaison ouverte par le cœur
 * lui-même : la connexion ne sert plus.
 */
private fun handOver(socket: Socket, host: Boolean, delay: Int, link: LinkKind?) {
    if (link != null && !link.packets) {
        socket.close()
        return
    }
    val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
    socket.close()
    Netplay.start(fd, host, if (host) 0 else 1, delay, packets = link != null)
}

/**
 * Hôte : propose la partie sur le réseau local ([presence] : annonce avec le jeu et le port), attend
 * un invité et demande à l'utilisateur ([ask] : nom de l'invité, vrai pour accepter). Un seul invité
 * (deux joueurs) ; ensuite la partie n'est plus annoncée.
 */
class NetplayHost(
    private val presence: PlayPresence,
    private val game: NetplayProtocol.HostedGame,
    private val coreFile: File,
    private val ask: suspend (NetplayProtocol.Join) -> Boolean,
    /** Invité accepté : son nom, et s'il est arrivé par le relais (Internet). */
    private val onStarted: (String, Boolean) -> Unit,
    /** Partie aussi proposée par Internet ([game] avec un identifiant de relais) : connexion d'attente. */
    private val relay: ((channel: String) -> RelayRequest?)? = null,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    @Volatile private var playing = false

    fun start(scope: CoroutineScope) {
        val socket = ServerSocket(0)
        server = socket
        presence.hosting = socket.localPort to game
        presence.start(scope)
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(client, viaRelay = false) }
            }
        }
        // Par Internet : une connexion attend sur le relais du serveur ; un invité arrivé, une autre
        // est ouverte (relais coupé, hors ligne : nouvel essai un peu plus tard).
        if (relay != null && game.session != null) {
            scope.launch(Dispatchers.IO) {
                while (isActive && !socket.isClosed) {
                    val request = relay.invoke(Relay.CHANNEL_PLAY)
                    val guest = request?.let { runCatching { Relay.awaitGuest(it) }.getOrNull() }
                    if (guest == null) {
                        delay(if (request == null) 10_000 else 2_000)
                        continue
                    }
                    val local = runCatching { Relay.bridge(guest) }.getOrNull() ?: continue
                    launch { handle(local, viaRelay = true) }
                }
            }
        }
    }

    private suspend fun handle(client: Socket, viaRelay: Boolean) {
        try {
            client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            val join = NetplayProtocol.json.decodeFromString(NetplayProtocol.Join.serializer(), input.readUTF())
            fun answer(answer: NetplayProtocol.Answer) {
                output.writeUTF(NetplayProtocol.json.encodeToString(NetplayProtocol.Answer.serializer(), answer))
                output.flush()
            }
            val refusal = when {
                join.v != NetplayProtocol.VERSION -> NetplayProtocol.OTHER_VERSION
                playing -> NetplayProtocol.BUSY
                // Liaison : chacun son jeu, même liaison (même cœur).
                game.link != null && join.link == null -> NetplayProtocol.OTHER_VERSION
                join.link != game.link -> NetplayProtocol.OTHER_GAME
                game.link == null && (join.fileName != game.fileName || join.size != game.size) -> NetplayProtocol.OTHER_GAME
                join.core != game.core -> NetplayProtocol.OTHER_CORE
                else -> null
            }
            if (refusal != null) {
                answer(NetplayProtocol.Answer(ok = false, reason = refusal))
                client.close()
                return
            }
            // Réponse de l'utilisateur, au plus 60 s (l'invité attend 90 s).
            val accepted = withContext(Dispatchers.Main) { withTimeoutOrNull(60_000) { ask(join) } } == true
            if (!accepted || playing) {
                answer(NetplayProtocol.Answer(ok = false, reason = if (playing) NetplayProtocol.BUSY else NetplayProtocol.REFUSED))
                client.close()
                return
            }
            // Délai demandé par l'invité (Internet : allers-retours mesurés), au moins celui du réseau local.
            val delay = join.delay.coerceIn(NetplayProtocol.DELAY_FRAMES, NetplayProtocol.MAX_DELAY_FRAMES)
            answer(NetplayProtocol.Answer(ok = true, delay = delay, coreSize = coreFile.length()))
            // Ad hoc de la PSP : d'autres consoles peuvent encore se joindre (serveur du cœur).
            val link = LinkRules.kind(game.link)
            if (link?.multi != true) {
                playing = true
                presence.hosting = null
            }
            handOver(client, host = true, delay = delay, link = link)
            withContext(Dispatchers.Main) { onStarted(join.name, viaRelay) }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    /** Partie terminée côté jeu : de nouveau proposée sur le réseau local. */
    fun reopen() {
        playing = false
        server?.takeIf { !it.isClosed }?.let { presence.hosting = it.localPort to game }
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
        presence.hosting = null
        presence.stop()
        Netplay.stop()
    }
}

/** Refus de l'hôte ou connexion impossible : [reason] (raison de [NetplayProtocol], ou null : réseau). */
class NetplayRefused(val reason: String?, message: String) : Exception(message)

/**
 * Invité : rejoint la partie de l'hôte ([launch]) ; l'hôte doit accepter (attente au plus 90 s).
 * Par Internet ([NetplayLaunch.relay]) : par le relais du serveur ([relay] : connexion à ouvrir).
 * Renvoie la réponse de l'hôte (taille de son cœur comprise) ; la partie commence à l'image suivante.
 */
suspend fun joinNetplay(launch: NetplayLaunch, join: NetplayProtocol.Join, relay: RelayRequest? = null): NetplayProtocol.Answer = withContext(Dispatchers.IO) {
    val socket = if (launch.relay != null) {
        val request = relay ?: throw NetplayRefused(null, "signed out")
        try {
            Relay.bridge(Relay.open(request))
        } catch (e: RelayException) {
            throw NetplayRefused(if (e.status == 404) NetplayProtocol.BUSY else null, e.message ?: "relay")
        }
    } else {
        Socket()
    }
    try {
        if (launch.relay == null) socket.connect(InetSocketAddress(launch.address, launch.port), 5_000)
        socket.soTimeout = 90_000
        val output = DataOutputStream(socket.getOutputStream())
        output.writeUTF(NetplayProtocol.json.encodeToString(NetplayProtocol.Join.serializer(), join))
        output.flush()
        val answer = NetplayProtocol.json.decodeFromString(
            NetplayProtocol.Answer.serializer(),
            DataInputStream(socket.getInputStream()).readUTF(),
        )
        if (!answer.ok) throw NetplayRefused(answer.reason, answer.reason ?: "refused")
        socket.soTimeout = 0
        handOver(socket, host = false, delay = answer.delay, link = launch.link)
        answer
    } catch (e: NetplayRefused) {
        runCatching { socket.close() }
        throw e
    } catch (e: Exception) {
        runCatching { socket.close() }
        throw NetplayRefused(null, e.message ?: e.javaClass.simpleName)
    }
}
