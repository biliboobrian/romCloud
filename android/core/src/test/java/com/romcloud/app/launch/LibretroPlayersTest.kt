package com.romcloud.app.launch

import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibretroPlayersTest {

    private val ra = "-n com.example.frontend/.GameActivity\n -e ROM {file.path}\n"

    private fun coreTemplate(id: String, core: String, regex: String? = "^(.*)\\.(?:sfc|zip)$") =
        Player(name = id, uniqueId = id, acceptedFilenameRegex = regex, amStartArguments = "$ra -e LIBRETRO $core")

    private fun system(vararg players: Player) =
        GameSystem(id = "snes", name = "Super Nintendo", shortname = "snes", folder = "snes", players = players.toList())

    @Test
    fun `nom du coeur extrait du modele`() {
        assertEquals("snes9x", LibretroPlayers.coreOf(coreTemplate("a", "snes9x")))
        assertEquals(
            "mupen64plus_next_gles3",
            LibretroPlayers.coreOf(coreTemplate("a", "/data/data/com.example.frontend/cores/mupen64plus_next_gles3_libretro_android.so")),
        )
        assertNull(LibretroPlayers.coreOf(Player(name = "x", uniqueId = "x", amStartArguments = "-n a/.B")))
    }

    @Test
    fun `un emulateur par coeur ajoute en tete`() {
        val other = Player(name = "Snes9x EX+", uniqueId = "snes.ex", amStartArguments = "-n com.explusalpha.Snes9xPlus/.Main")
        val result = LibretroPlayers.addTo(
            system(coreTemplate("ra64.snes9x", "snes9x"), other, coreTemplate("ra32.snes9x", "snes9x"), coreTemplate("ra64.bsnes", "bsnes", regex = null)),
        )
        // Modèles de cœur retirés ; autres émulateurs gardés.
        assertEquals(
            listOf("libretrodroid.bsnes", "libretrodroid.snes9x", "snes.ex"),
            result.players.map { it.uniqueId },
        )
        assertEquals("bsnes", result.players[0].libretroCore)
        assertNull(result.players[0].acceptedFilenameRegex)
        assertEquals("^(.*)\\.(?:sfc|zip)$", result.players[1].acceptedFilenameRegex)
    }

    @Test
    fun `coeurs du plus abouti au moins abouti, inconnus ensuite`() {
        val ps2 = LibretroPlayers.addTo(
            system(coreTemplate("ra.play", "play"), coreTemplate("ra.pcee2", "pcee2"), coreTemplate("ra.lrps2", "pcsx2")).copy(id = "ps2", shortname = "ps2"),
        )
        assertEquals(listOf("libretrodroid.pcsx2", "libretrodroid.pcee2", "libretrodroid.play"), ps2.players.take(3).map { it.uniqueId })
        val gba = LibretroPlayers.addTo(
            system(coreTemplate("a", "vba_next"), coreTemplate("b", "inconnu"), coreTemplate("c", "mgba")).copy(id = "gba", shortname = "gba"),
        )
        assertEquals(listOf("libretrodroid.mgba", "libretrodroid.vba_next", "libretrodroid.inconnu"), gba.players.take(3).map { it.uniqueId })
        // Cœur absent du buildbot Android : en dernier.
        val n3ds = LibretroPlayers.addTo(
            system(coreTemplate("a", "azahar"), coreTemplate("b", "citra"), coreTemplate("c", "panda3ds")).copy(id = "3ds", shortname = "3ds"),
        )
        assertEquals(listOf("libretrodroid.citra", "libretrodroid.panda3ds", "libretrodroid.azahar"), n3ds.players.take(3).map { it.uniqueId })
        // Aucun modèle de cœur gardé, même pour le cœur absent du buildbot Android.
        assertEquals(emptyList<String>(), n3ds.players.drop(3).map { it.uniqueId })
    }

    @Test
    fun `systeme sans modele de coeur ou deja complete inchange`() {
        val plain = system(Player(name = "x", uniqueId = "x", amStartArguments = "-n a/.B"))
        assertSame(plain, LibretroPlayers.addTo(plain))
        val once = LibretroPlayers.addTo(system(coreTemplate("a", "snes9x")))
        assertSame(once, LibretroPlayers.addTo(once))
    }
}
