package com.romcloud.app.netplay

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Présence sur le réseau local (voir [NetplayProtocol]) : annonce de cet appareil, en diffusion UDP,
 * et liste des autres appareils RomCloud ouverts ([peers]). [hosting] : partie proposée (port TCP et
 * jeu), ajoutée à l'annonce. Un appareil ne se voit pas lui-même (même identifiant, quel que soit le
 * processus : application ou jeu).
 */
class LanPresence(
    private val context: Context,
    private val name: String,
    private val platform: String,
) {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    @Volatile
    var hosting: Pair<Int, NetplayProtocol.HostedGame>? = null

    /** En partie avec un autre appareil (annoncé : plus proposé comme partenaire libre). */
    @Volatile
    var busy: Boolean = false

    val deviceId: String = deviceId(context)

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private class Entry(val peer: Peer, val hostingAt: Long, val busyAt: Long = 0L)
    private val seen = HashMap<String, Entry>()

    /** Annonce et écoute (jusqu'à [stop]). */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        multicastLock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("romcloud-netplay").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        job = scope.launch(Dispatchers.IO) {
            val listener = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(NetplayProtocol.DISCOVERY_PORT))
                }
            }.getOrNull()
            socket = listener
            launch { announce() }
            if (listener != null) listen(listener)
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        synchronized(seen) { seen.clear() }
        _peers.value = emptyList()
    }

    private suspend fun CoroutineScope.announce() {
        val sender = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return
        try {
            while (isActive) {
                val host = hosting
                val beacon = NetplayProtocol.Beacon(
                    id = deviceId, name = name, platform = platform,
                    port = host?.first ?: 0, hosting = host?.second, busy = busy,
                )
                val bytes = NetplayProtocol.json.encodeToString(NetplayProtocol.Beacon.serializer(), beacon).toByteArray()
                for (address in broadcastAddresses()) {
                    runCatching { sender.send(DatagramPacket(bytes, bytes.size, address, NetplayProtocol.DISCOVERY_PORT)) }
                }
                expire()
                delay(NetplayProtocol.BEACON_MS)
            }
        } finally {
            sender.close()
        }
    }

    private fun CoroutineScope.listen(listener: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (isActive && !listener.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                listener.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val beacon = runCatching {
                NetplayProtocol.json.decodeFromString(NetplayProtocol.Beacon.serializer(), String(packet.data, 0, packet.length))
            }.getOrNull() ?: continue
            if (beacon.app != NetplayProtocol.APP || beacon.v != NetplayProtocol.VERSION || beacon.id == deviceId) continue
            val now = System.currentTimeMillis()
            synchronized(seen) {
                // Même appareil annoncé par l'application et par le jeu : la partie proposée reste tant
                // que le jeu l'annonce (heure de sa dernière annonce gardée à part).
                val previous = seen[beacon.id]
                val (port, hosting, hostingAt) = when {
                    beacon.hosting != null -> Triple(beacon.port, beacon.hosting, now)
                    previous?.peer?.hosting != null && now - previous.hostingAt < NetplayProtocol.PEER_TTL_MS ->
                        Triple(previous.peer.port, previous.peer.hosting, previous.hostingAt)
                    else -> Triple(0, null, 0L)
                }
                // Même règle pour « en partie » : annoncé par le jeu, gardé entre les annonces de l'application.
                val busyAt = if (beacon.busy) now else previous?.busyAt?.takeIf { now - it < NetplayProtocol.PEER_TTL_MS } ?: 0L
                val peer = Peer(
                    beacon.id, beacon.name, beacon.platform, packet.address.hostAddress.orEmpty(), port, hosting, now,
                    busy = busyAt > 0L,
                )
                seen[beacon.id] = Entry(peer, hostingAt, busyAt)
            }
            publish()
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen.values.removeAll { now - it.peer.seenAt > NetplayProtocol.PEER_TTL_MS }
            // Partie plus annoncée (jeu quitté) : l'appareil reste, sans partie.
            for ((id, entry) in seen.entries.toList()) {
                if (entry.peer.hosting != null && now - entry.hostingAt > NetplayProtocol.PEER_TTL_MS) {
                    seen[id] = Entry(entry.peer.copy(port = 0, hosting = null), 0L, entry.busyAt)
                }
                val current = seen[id] ?: continue
                if (current.peer.busy && now - current.busyAt > NetplayProtocol.PEER_TTL_MS) {
                    seen[id] = Entry(current.peer.copy(busy = false), current.hostingAt, 0L)
                }
            }
        }
        publish()
    }

    private fun publish() {
        _peers.value = synchronized(seen) { seen.values.map { it.peer }.sortedBy { it.name.lowercase() } }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                nif.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
            }
        }
        return addresses.distinct()
    }

    companion object {
        /**
         * Adresse IPv4 de cet appareil sur le réseau local : celle de la route par défaut (socket UDP
         * « connectée », rien n'est envoyé), sinon la première adresse privée d'une interface active.
         * Réseau : hors du thread principal.
         */
        fun localAddress(): String? {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                    val address = socket.localAddress
                    if (address is java.net.Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress) return address.hostAddress
                }
            }
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
            }.getOrNull()
        }

        /** Identifiant de l'appareil, gardé dans les réglages (le même dans tous les processus). */
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("romcloud", Context.MODE_PRIVATE)
            return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("deviceId", it).commit()
            }
        }
    }
}
