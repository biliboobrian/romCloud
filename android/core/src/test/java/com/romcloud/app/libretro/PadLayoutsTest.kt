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
