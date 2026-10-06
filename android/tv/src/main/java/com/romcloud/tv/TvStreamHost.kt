package com.romcloud.tv

import android.app.Activity
import android.content.Intent
import com.romcloud.app.RomCloudApp
import com.romcloud.app.stream.StreamProtocol
import com.romcloud.core.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Réception d'un jeu diffusé depuis un téléphone du même profil (voir StreamProtocol). Tant que
 * [run] tourne (application au premier plan) et que le profil est connecté, la TV écoute sur le
 * réseau local et s'annonce au serveur : adresses, port et clé, que le serveur ne remet qu'aux
 * appareils du profil. Une connexion qui présente la bonne clé ouvre [TvStreamActivity].
 */
class TvStreamHost(private val activity: Activity, private val app: RomCloudApp) {

    suspend fun run() {
        app.account.state.distinctUntilChangedBy { it.signedIn }.collectLatest { state ->
            if (state.signedIn) serve()
        }
    }

    private suspend fun serve() = withContext(Dispatchers.IO) {
        val server = bind()
        port = server.localPort
        val (width, height) = screenSize()
        try {
            coroutineScope {
                launch {
                    while (isActive) {
                        val addresses = localAddresses()
                        if (addresses.isNotEmpty()) {
                            runCatching { app.account.announceReceiver(addresses, server.localPort, key, width, height) }
                        }
                        delay(ANNOUNCE_INTERVAL_MS)
                    }
                }
                // accept() bloquant : débloqué par la fermeture du port quand la TV quitte l'application.
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { server.close() }
                    }
                }
                thread(name = "stream-accept") {
                    while (true) {
                        val socket = try {
                            server.accept()
                        } catch (_: IOException) {
                            break
                        }
                        thread(name = "stream-handshake") { handshake(socket, key, width, height) }
                    }
                }
            }
        } finally {
            runCatching { server.close() }
            withContext(NonCancellable) { app.account.withdrawReceiver() }
        }
    }

    /** Vérifie la clé du téléphone, puis ouvre l'écran de réception (une diffusion à la fois). */
    private fun handshake(socket: Socket, key: String, width: Int, height: Int) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val json = StreamProtocol.json
            fun reply(welcome: StreamProtocol.Welcome) {
                output.writeUTF(json.encodeToString(StreamProtocol.Welcome.serializer(), welcome))
                output.flush()
            }
            val hello = json.decodeFromString(StreamProtocol.Hello.serializer(), input.readUTF())
            if (hello.version != StreamProtocol.VERSION || !MessageDigest.isEqual(hello.key.toByteArray(), key.toByteArray())) {
                app.account.reportError("stream-tv", "refused ${hello.device}: " + if (hello.version != StreamProtocol.VERSION) "version ${hello.version}" else "key")
                reply(StreamProtocol.Welcome(ok = false))
                socket.close()
                return
            }
            val session = TvStreamActivity.Session(socket, input, hello.device, hello.title)
            if (!TvStreamActivity.offer(session)) {
                reply(StreamProtocol.Welcome(ok = false, error = activity.getString(R.string.stream_tv_busy)))
                socket.close()
                return
            }
            reply(StreamProtocol.Welcome(ok = true, width = width, height = height))
            socket.soTimeout = 0
            activity.runOnUiThread { activity.startActivity(Intent(activity, TvStreamActivity::class.java)) }
        } catch (e: Exception) {
            runCatching { socket.close() }
            app.account.reportError("stream-tv", e.message ?: e.javaClass.name, e.stackTraceToString().take(6000))
        }
    }

    /**
     * Port d'écoute : le même tant que l'application tourne (le téléphone a pu le lire sur le serveur
     * juste avant que la TV ne quitte puis ne retrouve le premier plan), sinon un port libre.
     */
    private fun bind(): ServerSocket {
        if (port > 0) {
            runCatching { return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }
        }
        return ServerSocket(0)
    }

    /** Définition de l'écran de la TV (mode d'affichage physique, 4K comprise). */
    private fun screenSize(): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val mode = activity.windowManager.defaultDisplay.mode
        val w = maxOf(mode.physicalWidth, mode.physicalHeight)
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        return w to h
    }

    /** Adresses IPv4 de la TV sur le réseau local (Wi-Fi, Ethernet). */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        /** Clé à présenter par le téléphone : la même tant que l'application tourne. */
        val key: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        @Volatile var port = 0
        const val ANNOUNCE_INTERVAL_MS = 20_000L
        const val HANDSHAKE_TIMEOUT_MS = 8000
    }
}
