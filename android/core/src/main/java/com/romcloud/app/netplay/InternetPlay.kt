package com.romcloud.app.netplay

import android.content.Context
import com.romcloud.app.data.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.math.ceil

/**
 * Jeu à plusieurs par Internet entre les joueurs connectés à un profil (même serveur RomCloud) :
 * chaque appareil s'annonce au serveur ([InternetPresence]) avec la partie qu'il propose ; la partie
 * passe par le relais du serveur (deux connexions sortantes, rien à ouvrir sur les box). Le moteur
 * de jeu, lui, garde sa connexion habituelle : une passerelle locale ([bridge]) la relie au relais.
 */

/** Appareil connecté à un profil, vu par le serveur. */
@Serializable
data class InternetPeer(
    val id: String,
    val name: String = "",
    val platform: String = "",
    val user: String = "",
    val hosting: NetplayProtocol.HostedGame? = null,
    val busy: Boolean = false,
    val rtt: Int = 0,
)

@Serializable
data class PlayPresenceResponse(val ttlSeconds: Int = 30, val peers: List<InternetPeer> = emptyList())

/** Connexion de relais à ouvrir : adresse (http ou https) et en-têtes (clé d'API, session du joueur). */
data class RelayRequest(val url: String, val headers: Map<String, String>)

/** Relais refusé ou injoignable : [status] (code HTTP, 0 : réseau). */
class RelayException(val status: Int, message: String) : IOException(message)

object Relay {
    /** Rôle et canal : « play » (partie, liaison par paquets), « link » (câble ouvert par le cœur). */
    const val CHANNEL_PLAY = "play"
    const val CHANNEL_LINK = "link"

    /**
     * Ouvre une connexion de relais (requête « Upgrade ») ; renvoie la socket une fois acceptée
     * (statut 101), prête à transmettre les octets tels quels.
     */
    fun open(request: RelayRequest, connectTimeoutMs: Int = 8000): Socket {
        val uri = URI(request.url)
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(uri.host, port), connectTimeoutMs)
            plain.tcpNoDelay = true
            val socket = if (secure) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, uri.host, port, true) else plain
            socket.soTimeout = 15_000
            val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = buildString {
                append("GET $path HTTP/1.1\r\nHost: ${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}\r\n")
                append("Connection: Upgrade\r\nUpgrade: romcloud-relay\r\n")
                for ((k, v) in request.headers) append("$k: ${v.replace("\r", "").replace("\n", "")}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            val status = readStatus(socket.getInputStream())
            if (status != 101) {
                socket.close()
                throw RelayException(status, "HTTP $status")
            }
            socket.soTimeout = 0
            return socket
        } catch (e: RelayException) {
            throw e
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw RelayException(0, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lit l'en-tête de la réponse octet par octet (rien de plus : la suite appartient à la partie). */
    private fun readStatus(input: InputStream): Int {
        val line = StringBuilder()
        var status = 0
        var empty = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("relay closed")
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (status == 0) status = text.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
                if (text.isEmpty()) return status
                line.clear()
                if (++empty > 64) throw IOException("relay header")
            } else {
                line.append(b.toChar())
            }
        }
    }

    /**
     * Passerelle locale : renvoie une socket de la boucle locale reliée à [remote] (octets copiés
     * dans les deux sens). Le moteur de jeu s'en sert comme d'une connexion du réseau local (son
     * descripteur peut lui être remis, contrairement à une connexion chiffrée).
     */
    fun bridge(remote: Socket): Socket {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val local = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val inner = server.accept()
            local.tcpNoDelay = true
            inner.tcpNoDelay = true
            pipe(remote, inner)
            return local
        }
    }

    /** Copie les octets de [a] vers [b] et de [b] vers [a] ; fermeture de l'une : les deux fermées. */
    fun pipe(a: Socket, b: Socket) {
        fun copy(from: InputStream, to: OutputStream) {
            Thread {
                val buffer = ByteArray(16384)
                try {
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        to.write(buffer, 0, n)
                        to.flush()
                    }
                } catch (_: Exception) {
                }
                runCatching { a.close() }
                runCatching { b.close() }
            }.apply { isDaemon = true; name = "romcloud-relay" }.start()
        }
        copy(a.getInputStream(), b.getOutputStream())
        copy(b.getInputStream(), a.getOutputStream())
    }

    /**
     * Hôte : attend un invité sur le relais (connexion ouverte d'avance ; le serveur envoie un octet
     * quand un invité arrive). Renvoie la connexion reliée à l'invité, ou null si elle s'est fermée
     * avant (relais coupé : à rouvrir).
     */
    fun awaitGuest(request: RelayRequest): Socket? {
        val socket = open(request)
        return try {
            if (socket.getInputStream().read() == 1) socket else null.also { socket.close() }
        } catch (e: IOException) {
            runCatching { socket.close() }
            null
        }
    }

    /** Nouvel identifiant de partie sur le relais (hôte). */
    fun newSession(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /**
     * Images de délai des touches sur Internet : temps d'aller-retour entre les deux appareils
     * (chacun jusqu'au serveur, plus une marge) converti en images à 60 images/s, au moins le délai du
     * réseau local, au plus 15.
     */
    fun delayFrames(ownRttMs: Int, hostRttMs: Int): Int {
        val rtt = ownRttMs.coerceAtLeast(0) + hostRttMs.coerceAtLeast(0) + 20
        return (ceil(rtt / 16.7).toInt() + 1).coerceIn(NetplayProtocol.DELAY_FRAMES, 15)
    }
}

/**
 * Présence par Internet (profil connecté) : annonce de cet appareil au serveur toutes les 10 s
 * (et tout de suite quand la partie proposée change), avec en retour les autres appareils connectés.
 */
class InternetPresence(private val account: Account, private val deviceId: String, private val name: String, private val platform: String) {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Partie proposée par Internet (avec son identifiant de relais), ou null. */
    @Volatile
    var hosting: NetplayProtocol.HostedGame? = null
        set(value) {
            field = value
            wake.trySend(Unit)
        }

    @Volatile
    var busy: Boolean = false
        set(value) {
            field = value
            wake.trySend(Unit)
        }

    /** Dernier aller-retour mesuré jusqu'au serveur (ms). */
    @Volatile
    var rtt: Int = 0
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val started = System.nanoTime()
                val response = runCatching {
                    account.playPresence(deviceId, name, platform, hosting, busy, rtt)
                }.getOrNull()
                if (response != null) {
                    rtt = ((System.nanoTime() - started) / 1_000_000).toInt()
                    _peers.value = response.peers.map {
                        Peer(it.id, it.name, it.platform, "", 0, it.hosting, System.currentTimeMillis(), busy = it.busy, user = it.user, internet = true, rtt = it.rtt)
                    }
                } else {
                    _peers.value = emptyList()
                }
                withTimeoutOrNull(NetplayProtocol.INTERNET_ANNOUNCE_MS) { wake.receive() }
            }
        }
    }

    /** Arrêt : l'appareil est retiré tout de suite (sans attendre l'expiration). */
    @Synchronized
    fun stop(scope: CoroutineScope) {
        job?.cancel()
        job = null
        _peers.value = emptyList()
        scope.launch(Dispatchers.IO) { runCatching { account.withdrawPlayPresence(deviceId) } }
    }
}

/**
 * Appareils joignables : réseau local ([LanPresence]) et Internet ([InternetPresence], profil
 * connecté). Un appareil vu des deux façons est gardé une fois, en local (plus rapide).
 */
class PlayPresence(context: Context, account: Account, name: String, platform: String) {
    val lan = LanPresence(context, name, platform)
    val internet = InternetPresence(account, lan.deviceId, name, platform)
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    /** Partie proposée (port local et jeu) : annoncée en local, et par Internet si elle a un identifiant de relais. */
    var hosting: Pair<Int, NetplayProtocol.HostedGame>?
        get() = lan.hosting
        set(value) {
            lan.hosting = value
            internet.hosting = value?.second?.takeIf { it.session != null }
        }

    var busy: Boolean
        get() = lan.busy
        set(value) {
            lan.busy = value
            internet.busy = value
        }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        this.scope = scope
        lan.start(scope)
        internet.start(scope)
        job = scope.launch {
            combine(lan.peers, internet.peers) { local, remote -> local + remote.filter { r -> local.none { it.id == r.id } } }
                .collect { _peers.value = it }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        lan.stop()
        scope?.let { internet.stop(it) }
        _peers.value = emptyList()
    }
}

/**
 * Câble ouvert par le cœur lui-même (Game Boy, Gambatte) par Internet : sa connexion passe par un
 * second canal du relais. Hôte : chaque invité relié au serveur du cœur ([localPort] de cet appareil).
 */
fun CoroutineScope.tunnelHost(request: () -> RelayRequest?, localPort: Int): Job = launch(Dispatchers.IO) {
    while (isActive) {
        val remote = request()?.let { runCatching { Relay.awaitGuest(it) }.getOrNull() }
        if (remote == null) {
            delay(3_000)
            continue
        }
        val local = runCatching { Socket(InetAddress.getLoopbackAddress(), localPort).apply { tcpNoDelay = true } }.getOrNull()
        if (local == null) runCatching { remote.close() } else Relay.pipe(remote, local)
    }
}

/**
 * Invité : le cœur se connecte à [localPort] de cet appareil (adresse 127.0.0.1 dans ses options) ;
 * chaque connexion est reliée à l'hôte par le relais.
 */
fun CoroutineScope.tunnelGuest(request: () -> RelayRequest?, localPort: Int): Job = launch(Dispatchers.IO) {
    val server = runCatching { ServerSocket(localPort, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return@launch
    try {
        while (isActive) {
            val local = runCatching { server.accept().apply { tcpNoDelay = true } }.getOrNull() ?: break
            val remote = request()?.let { runCatching { Relay.open(it) }.getOrNull() }
            if (remote == null) runCatching { local.close() } else Relay.pipe(remote, local)
        }
    } finally {
        runCatching { server.close() }
    }
}
