package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class StateHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dix etats gardes, plus les etats epingles`() {
        val history = StateHistory(tmp.newFolder("snes9x", "Zelda.history"), "snes9x")
        val first = history.add(byteArrayOf(1), null, "Pixel 8", "android", createdAt = 1000)
        history.setPinned(first.id, true)
        repeat(11) { history.add(byteArrayOf(it.toByte()), null, "Pixel 8", "android", createdAt = 2000L + it) }

        val list = history.list()
        assertEquals(11, list.size)
        assertTrue(list.any { it.id == first.id && it.pinned })
        assertEquals(2010L, list.first().createdAt)
        assertFalse(list.any { it.createdAt == 2000L }) // le plus ancien non épinglé

        history.setPinned(first.id, false)
        assertEquals(10, history.list().size)
        assertFalse(history.stateFile(first.id).exists())
    }

    @Test
    fun `ancien etat unique repris une seule fois`() {
        val legacy = tmp.newFile("Zelda.state").apply { writeBytes(byteArrayOf(9)) }
        val history = StateHistory(File(tmp.root, "Zelda.history"), "snes9x")
        history.adopt(legacy, "Pixel 8", "android")
        history.adopt(legacy, "Pixel 8", "android")
        val list = history.list()
        assertEquals(1, list.size)
        assertEquals(legacy.lastModified(), list.single().createdAt)
        history.markUploaded(list.single().id)
        assertTrue(history.get(list.single().id)!!.uploaded)
    }

    @Test
    fun `etats de l'appareil et en ligne reunis`() {
        val mine = StateMeta("a", "snes9x", 3000, "Pixel 8", "android", uploaded = true)
        val notSent = StateMeta("b", "snes9x", 1000, "Pixel 8", "android")
        val online = listOf(
            OnlineState("a", 1, "snes9x", Instant.ofEpochMilli(3000).toString(), "Pixel 8", "android", pinned = true),
            OnlineState("c", 1, "snes9x", Instant.ofEpochMilli(2000).toString(), "Shield", "androidtv", thumbnail = true),
            OnlineState("d", 1, "bsnes", Instant.ofEpochMilli(4000).toString(), "PC", "windows"),
        )
        val merged = StateHistory.merge(listOf(mine, notSent), online, "snes9x")
        assertEquals(listOf("a", "c", "b"), merged.map { it.id })
        assertTrue(merged[0].pinned && merged[0].local != null && merged[0].online != null && !merged[0].canUpload)
        assertEquals("Shield", merged[1].device)
        assertTrue(merged[1].local == null && !merged[1].canUpload)
        assertTrue(merged[2].canUpload)
    }

    @Test
    fun `fiche du jeu, etats de tous les coeurs du systeme`() {
        val online = listOf(
            OnlineState("w", 1, "yabause", Instant.ofEpochMilli(5000).toString(), "ZEPC", "windows"),
            OnlineState("x", 1, "snes9x", Instant.ofEpochMilli(6000).toString(), "PC", "windows"),
        )
        val local = listOf(StateMeta("m", "mednafen_saturn", 1000, "Pixel 8", "android"))
        val merged = StateHistory.merge(local, online, listOf("mednafen_saturn", "yabause", "ymir"))
        assertEquals(listOf("w", "m"), merged.map { it.id })
        assertEquals("yabause", merged[0].core)
        // Cœur seul (menu du jeu) : les états des autres cœurs ne s'y chargent pas.
        assertEquals(listOf("m"), StateHistory.merge(local, online, "mednafen_saturn").map { it.id })
    }

    private fun File(parent: java.io.File, name: String) = java.io.File(parent, name)
}
