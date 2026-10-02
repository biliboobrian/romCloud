package com.romcloud.app.libretro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLayoutsTest {

    private fun labels(layout: PadLayout) = layout.buttons.map { it.label }

    @Test
    fun `disposition choisie d'apres le systeme`() {
        val nes = PadLayouts.forGame("nes", "fceumm")
        assertEquals(listOf("B", "A"), labels(nes))
        assertTrue(nes.left.isEmpty() && nes.right.isEmpty())

        val psx = PadLayouts.forGame("psx", "pcsx_rearmed")
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, psx.buttons.first { it.label == "✕" }.key)
        assertEquals(listOf("L2", "L1"), psx.left.map { it.label })

        val genesis = PadLayouts.forGame("genesis", "genesis_plus_gx")
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, genesis.buttons.first { it.label == "A" }.key)
        assertNull(genesis.select)
    }

    @Test
    fun `nintendo 64 avec stick et boutons C sur le stick droit`() {
        val n64 = PadLayouts.forGame("n64", "mupen64plus_next_gles3")
        assertTrue(n64.stick)
        val c = n64.buttons.filter { it.label.startsWith("C") }
        assertEquals(4, c.size)
        assertTrue(c.all { it.rightStick != null })
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, n64.left.first { it.label == "Z" }.key)
        assertFalse(n64.dpad)
    }

    @Test
    fun `croix et stick ensemble pour la dreamcast et la psp`() {
        listOf(PadLayouts.forGame("dreamcast", "flycast"), PadLayouts.forGame("psp", "ppsspp")).forEach {
            assertTrue(it.dpad && it.stick)
        }
    }

    @Test
    fun `saturn avec les boutons lus par ses coeurs`() {
        val saturn = PadLayouts.forGame("saturn", "mednafen_saturn")
        fun key(label: String) = saturn.buttons.first { it.label == label }.key
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, key("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, key("B"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, key("C"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, key("X"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, key("Y"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1, key("Z"))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_L2), saturn.left.map { it.key })
    }

    @Test
    fun `gx4000 avec deux boutons et une croix`() {
        val gx = PadLayouts.forGame("gx4000", "cap32")
        assertEquals(listOf("1", "2"), labels(gx))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A), gx.buttons.map { it.key })
        assertTrue(gx.dpad && !gx.stick && gx.left.isEmpty() && gx.right.isEmpty())
        assertNull(gx.select)
        assertNull(gx.start)
    }

    @Test
    fun `famicom disk system avec changement de face`() {
        val fds = PadLayouts.forGame("fds", "fceumm")
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_L1), fds.left.map { it.key })
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_R1), fds.right.map { it.key })
        assertSame(PadLayouts.DEFAULT, PadLayouts.forGame("pcfx", "mednafen_pcfx"))
    }

    @Test
    fun `systeme inconnu d'apres le coeur, sinon disposition par defaut`() {
        assertEquals(listOf("B", "A"), labels(PadLayouts.forGame("perso", "nestopia")))
        // « mesen-s » est un cœur Super Nintendo, pas NES (« mesen »).
        assertEquals(4, PadLayouts.forGame("perso", "mesen-s").buttons.size)
        assertSame(PadLayouts.DEFAULT, PadLayouts.forGame("perso", "inconnu"))
        assertFalse(PadLayouts.DEFAULT.stick)
    }
}
