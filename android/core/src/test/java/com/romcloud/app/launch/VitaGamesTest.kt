package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VitaGamesTest {

    @Test
    fun `identifiant lu dans le nom du fichier`() {
        assertEquals("PCSB00061", VitaGames.titleIdFromName("EP0001-PCSB00061_00-LUMINESELECTROSY.pkg"))
        assertEquals("PCSB00061", VitaGames.titleIdFromName("Lumines [PCSB-00061].vpk"))
        assertEquals("PCSF00008", VitaGames.titleIdFromName("Gravity Rush (Europe) PCSF00008.vpk"))
        assertNull(VitaGames.titleIdFromName("Lumines (Europe).vpk"))
        assertNull(VitaGames.titleIdFromName("PCSB000612.vpk"))
    }

    @Test
    fun `identifiant lu dans le param sfo`() {
        // En-tête PSF, une entrée TITLE_ID (texte, 10 octets) : clés à 0x24, valeurs à 0x30.
        val header = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), 1, 1, 0, 0))
            .putInt(0x24).putInt(0x30).putInt(1)
            .putShort(0).putShort(0x0204).putInt(10).putInt(12).putInt(0)
        val sfo = ByteArrayOutputStream().apply {
            write(header.array())
            write("TITLE_ID\u0000\u0000\u0000\u0000".toByteArray())
            write("PCSB00061\u0000\u0000\u0000".toByteArray())
        }.toByteArray()
        assertEquals("PCSB00061", VitaGames.titleIdFromSfo(sfo))
        assertNull(VitaGames.titleIdFromSfo("pas un sfo".toByteArray()))
    }
}
