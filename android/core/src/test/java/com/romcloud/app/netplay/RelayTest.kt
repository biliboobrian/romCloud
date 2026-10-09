package com.romcloud.app.netplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RelayTest {

    /**
     * Faux relais (même échange que le serveur) : l'hôte attend, reçoit 1 octet à l'arrivée de
     * l'invité, puis les octets passent tels quels ; un invité sans hôte reçoit 404.
     */
    private class FakeRelay : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        @Volatile var waitingHost: Socket? = null
        val headers = mutableListOf<String>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            val input = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            synchronized(headers) { headers += head.toString() }
            val host = head.contains("role=host")
            if (host) {
                s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: romcloud-relay\r\n\r\n".toByteArray())
                waitingHost = s
                return
            }
            val h = waitingHost
            if (h == null) {
                s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                s.close()
                return
            }
            waitingHost = null
            s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
            h.getOutputStream().write(1)
            Relay.pipe(h, s)
        }

        fun request(role: String) = RelayRequest(
            "http://127.0.0.1:${server.localPort}/api/play/relay?session=abc&channel=play&role=$role",
            mapOf("X-RomCloud-Session" to "jeton"),
        )

        override fun close() = server.close()
    }

    @Test
    fun `l'invite sans hote est refuse, puis rejoint l'hote par la passerelle locale`() {
        FakeRelay().use { relay ->
            val refused = runCatching { Relay.open(relay.request("guest")) }.exceptionOrNull()
            assertTrue(refused is RelayException && refused.status == 404)

            var hostSide: Socket? = null
            val waiter = thread { hostSide = Relay.awaitGuest(relay.request("host"))?.let(Relay::bridge) }
            while (relay.waitingHost == null) Thread.sleep(10)
            val guestSide = Relay.bridge(Relay.open(relay.request("guest")))
            waiter.join(5000)
            val host = hostSide!!

            // Même échange que la demande et la réponse (writeUTF / readUTF), à travers le relais.
            DataOutputStream(guestSide.getOutputStream()).apply { writeUTF("demande"); flush() }
            assertEquals("demande", DataInputStream(host.getInputStream()).readUTF())
            DataOutputStream(host.getOutputStream()).apply { writeUTF("réponse"); flush() }
            assertEquals("réponse", DataInputStream(guestSide.getInputStream()).readUTF())
            assertTrue(relay.headers.any { it.contains("X-RomCloud-Session: jeton") && it.contains("Upgrade: romcloud-relay") })
            host.close()
            guestSide.close()
        }
    }

    @Test
    fun `delai des touches d'apres les allers-retours`() {
        assertEquals(NetplayProtocol.DELAY_FRAMES, Relay.delayFrames(5, 5))
        assertEquals(9, Relay.delayFrames(60, 40))  // (60 + 40 + 20 ms) / 16,7 -> 8 images, +1
        assertEquals(NetplayProtocol.MAX_DELAY_FRAMES, Relay.delayFrames(2000, 2000))
    }

    /**
     * Contre un vrai serveur RomCloud (variable ROMCLOUD_RELAY_TEST = « adresse|jeton hôte|jeton
     * invité|partie », partie annoncée par l'hôte) ; sans elle, rien n'est fait.
     */
    @Test
    fun `relais du vrai serveur`() {
        val spec = System.getenv("ROMCLOUD_RELAY_TEST") ?: return
        val (server, hostToken, guestToken, session) = spec.split('|')
        fun request(token: String, role: String) =
            RelayRequest("$server/api/play/relay?session=$session&channel=play&role=$role", mapOf("X-RomCloud-Session" to token))
        var host: Socket? = null
        val waiter = thread { host = Relay.awaitGuest(request(hostToken, "host"))?.let(Relay::bridge) }
        Thread.sleep(500)
        val guest = Relay.bridge(Relay.open(request(guestToken, "guest")))
        waiter.join(5000)
        DataOutputStream(guest.getOutputStream()).apply { writeUTF("demande"); flush() }
        assertEquals("demande", DataInputStream(host!!.getInputStream()).readUTF())
        DataOutputStream(host!!.getOutputStream()).apply { writeUTF("réponse"); flush() }
        assertEquals("réponse", DataInputStream(guest.getInputStream()).readUTF())
        guest.close()
        host!!.close()
    }
}
