package com.romcloud.app.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class StreamProtocolTest {

    @Test
    fun `jeu 4-3 sur TV 4K, hauteur limitee a 1080, proportions gardees`() {
        assertEquals(1440 to 1072, StreamProtocol.encodeSize(4f / 3f, 3840, 2160))
    }

    @Test
    fun `jeu 4-3 sur TV 720p, hauteur de la TV`() {
        assertEquals(960 to 720, StreamProtocol.encodeSize(4f / 3f, 1280, 720))
    }

    @Test
    fun `jeu plus large que la TV, limite par la largeur`() {
        val (w, h) = StreamProtocol.encodeSize(3f, 1920, 1080)
        assertEquals(1920, w)
        assertEquals(640, h)
    }

    @Test
    fun `TV inconnue, ecran 1080p, multiples de 16`() {
        val (w, h) = StreamProtocol.encodeSize(8f / 7f, 0, 0)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(1072, h)
    }

    @Test
    fun `zone du jeu centree dans la vue du telephone, sans bandes noires`() {
        assertEquals(StreamProtocol.Area(480, 0, 1920, 1080), StreamProtocol.gameArea(2400, 1080, 4f / 3f))
        // Proportions inconnues : toute la vue.
        assertEquals(StreamProtocol.Area(0, 0, 2400, 1080), StreamProtocol.gameArea(2400, 1080, 0f))
        // Écran étroit (tablette 4:3) et jeu 16:9 : bandes en haut et en bas.
        assertEquals(StreamProtocol.Area(0, 192, 2048, 1344), StreamProtocol.gameArea(2048, 1536, 16f / 9f))
    }

    @Test
    fun `paquet relu tel quel`() {
        val bytes = ByteArrayOutputStream()
        StreamProtocol.writePacket(DataOutputStream(bytes), StreamProtocol.AUDIO, 123_456L, byteArrayOf(1, 2, 3, 4, 5), length = 4)
        val packet = StreamProtocol.readPacket(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(StreamProtocol.AUDIO, packet.type)
        assertEquals(123_456L, packet.ptsUs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packet.data)
    }
}
