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
    fun `reprise par disque, dernier etat de chaque disque garde`() {
        val dir = tmp.newFolder("mednafen_psx_hw", "FF7.history")
        val history = StateHistory(dir, "mednafen_psx_hw")
        val quick = File(tmp.root, "FF7.state")
        val old = history.add(byteArrayOf(1), null, "Pixel 8", "android", createdAt = 1000) // sans disque : le premier
        val second = history.add(byteArrayOf(2), null, "Pixel 8", "android", createdAt = 2000, disk = 1)
        assertEquals(history.stateFile(old.id), history.resumePoint(quick, 0)?.first)
        assertEquals(history.stateFile(second.id), history.resumePoint(quick, 1)?.first)
        assertEquals(null, history.resumePoint(quick, 2))
        assertEquals(1, history.diskOf(history.stateFile(second.id), quick))
        assertEquals(null, history.diskOf(history.stateFile(old.id), quick))
        // État de reprise écrit avec un état du disque 3 : reprise du disque 3, disque 2 inchangé.
        quick.writeBytes(byteArrayOf(3, 3))
        quick.setLastModified(5000)
        history.add(byteArrayOf(3, 3), null, "Pixel 8", "android", createdAt = 5000, disk = 2)
        assertEquals(2, history.diskOf(quick, quick))
        assertEquals(quick, history.resumePoint(quick, 2)?.first)
        assertEquals(history.stateFile(second.id), history.resumePoint(quick, 1)?.first)
        // Dix états plus récents du disque 3 : le dernier du disque 2 reste.
        repeat(10) { history.add(byteArrayOf(it.toByte()), null, "Pixel 8", "android", createdAt = 6000L + it, disk = 2) }
        assertTrue(history.list().any { it.id == second.id })
        assertFalse(history.list().any { it.id == old.id })
    }

    @Test
    fun `etat de reprise ajoute une seule fois a l'historique`() {
        val legacy = tmp.newFile("Zelda.state").apply { writeBytes(byteArrayOf(9)) }
        val history = StateHistory(File(tmp.root, "Zelda.history"), "snes9x")
        assertTrue(history.record(legacy, "Pixel 8", "android"))
        assertFalse(history.record(legacy, "Pixel 8", "android"))
        val list = history.list()
        assertEquals(1, list.size)
        assertEquals(legacy.lastModified(), list.single().createdAt)
        history.markUploaded(list.single().id)
        assertTrue(history.get(list.single().id)!!.uploaded)
    }

    @Test
    fun `etat de reprise d'un autre appareil ajoute meme avec un historique`() {
        val history = StateHistory(File(tmp.root, "Mario.history"), "snes9x")
        history.add(byteArrayOf(1, 2), null, "Shield", "androidtv", createdAt = 1_000L)
        val quick = tmp.newFile("Mario.state").apply {
            writeBytes(byteArrayOf(3, 4, 5))
            setLastModified(5_000_000L)
        }
        assertTrue(history.record(quick, "PC du salon", "windows"))
        val latest = history.list().first()
        assertEquals("PC du salon", latest.device)
        assertEquals(2, history.list().size)
    }

    @Test
    fun `etats de l'appareil et en ligne reunis`() {
        val mine = StateMeta("a", "snes9x", 30000, "Pixel 8", "android", uploaded = true)
        val notSent = StateMeta("b", "snes9x", 10000, "Pixel 8", "android")
        val online = listOf(
            OnlineState("a", 1, "snes9x", Instant.ofEpochMilli(30000).toString(), "Pixel 8", "android", pinned = true),
            OnlineState("c", 1, "snes9x", Instant.ofEpochMilli(20000).toString(), "Shield", "androidtv", thumbnail = true),
            OnlineState("d", 1, "bsnes", Instant.ofEpochMilli(40000).toString(), "PC", "windows"),
        )
        val merged = StateHistory.merge(listOf(mine, notSent), online, "snes9x")
        assertEquals(listOf("a", "c", "b"), merged.map { it.id })
        assertTrue(merged[0].pinned && merged[0].local != null && merged[0].online != null && !merged[0].canUpload)
        assertEquals("Shield", merged[1].device)
        assertTrue(merged[1].local == null && !merged[1].canUpload)
        assertTrue(merged[2].canUpload)
    }

    @Test
    fun `copie d'un etat en ligne masquee`() {
        // État de reprise du PC, téléchargé puis ajouté à l'historique du téléphone sous un autre identifiant.
        val copy = StateMeta("e", "ppsspp", 20183, "POCO F3", "android")
        val online = listOf(OnlineState("f", 1, "ppsspp", Instant.ofEpochMilli(20000).toString(), "ZEPC", "windows"))
        assertEquals(listOf("f"), StateHistory.merge(listOf(copy), online, "ppsspp").map { it.id })
        // Déjà envoyé : un état à part entière, gardé.
        assertEquals(2, StateHistory.merge(listOf(copy.copy(uploaded = true)), online, "ppsspp").size)
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
