package com.romcloud.app.libretro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PadArtTest {

    @Test
    fun `chaque bouton de la Super Nintendo a son repere sur l'illustration`() {
        val layout = PadLayouts.forGame("snes", "snes9x")
        assertEquals("snes", padArtName(layout.style))
        for (step in mappingSteps(layout)) assertNotNull(step.label ?: step.retroKey.toString(), spotOf("snes", step))
        // B de la Super Nintendo : bouton du bas du losange.
        assertEquals(Spot(206, 167, 8), spotOf("snes", mappingSteps(layout).first { it.retroKey == KeyEvent.KEYCODE_BUTTON_B }))
    }

    @Test
    fun `boutons C de la Nintendo 64 sur le stick droit, X Y Z absents de la Mega Drive a trois boutons`() {
        val n64 = mappingSteps(PadLayouts.forGame("n64", "mupen64plus_next"))
        assertEquals(PAD_SPOTS.getValue("n64").getValue("rs"), spotOf("n64", n64.first { it.stick == StickAxis.X }))
        assertEquals(PAD_SPOTS.getValue("n64").getValue("ls"), spotOf("n64", n64.first { it.stick == StickAxis.LEFT_X }))
        val genesis = mappingSteps(PadLayouts.forGame("genesis", "genesis_plus_gx"))
        assertNotNull(spotOf("genesis", genesis.first { it.label == "C" }))
        assertNull(spotOf("genesis", genesis.first { it.label == "Z" }))
    }
}
