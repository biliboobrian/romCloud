package com.romcloud.app.libretro

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadPlayersTest {

    private val xbox = Pad(id = 11, descriptor = "xbox", controllerNumber = 1)
    private val ds4 = Pad(id = 12, descriptor = "ds4", controllerNumber = 2)
    private val switch = Pad(id = 13, descriptor = "switch", controllerNumber = 3)

    @Test
    fun sansChoixLeNumeroDAndroidDecide() {
        assertEquals(mapOf(11 to 0, 12 to 1), GamepadPlayers.assign(listOf(xbox, ds4), List(4) { "" }))
    }

    @Test
    fun chaqueManetteRetrouveSonJoueur() {
        val saved = listOf("ds4", "xbox", "", "")
        assertEquals(mapOf(12 to 0, 11 to 1), GamepadPlayers.assign(listOf(xbox, ds4), saved))
    }

    @Test
    fun placeDUneManetteMemoriseeAbsenteGardee() {
        // Joueur 1 mémorisé (Xbox) débranché : la DualShock (numéro 1 pour Android) prend une autre place.
        val ds4First = ds4.copy(controllerNumber = 1)
        val saved = listOf("xbox", "", "", "")
        assertEquals(mapOf(12 to 1), GamepadPlayers.assign(listOf(ds4First), saved))
    }

    @Test
    fun nouvelleManetteApresLesManettesMemorisees() {
        val saved = listOf("switch", "xbox", "", "")
        assertEquals(mapOf(13 to 0, 11 to 1, 12 to 2), GamepadPlayers.assign(listOf(xbox, ds4, switch), saved))
    }

    @Test
    fun auDelaDeQuatreManettesPasDePlace() {
        val pads = (1..5).map { Pad(id = it, descriptor = "pad$it", controllerNumber = it) }
        val ports = GamepadPlayers.assign(pads, List(4) { "" })
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3), ports)
    }
}
