package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun `vitesse mesuree par seconde et lissee`() {
        val speed = SpeedMeter()
        assertEquals(0, speed.update(0, 0))
        assertEquals(0, speed.update(500_000, 500)) // fenêtre pas encore écoulée
        assertEquals(1_000_000, speed.update(1_000_000, 1000))
        assertEquals(2_000_000, speed.update(4_000_000, 2000)) // 3 Mo/s lissés avec 1 Mo/s
        assertEquals(1_000_000, speed.update(4_000_000, 3000)) // plus rien reçu : ralentit
        assertEquals(1_000_000, speed.update(0, 3500)) // compteur revenu en arrière : nouvelle fenêtre
        assertEquals(1_500_000, speed.update(2_000_000, 4500))
    }
}
