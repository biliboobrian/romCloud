package com.romcloud.app.launch

import com.romcloud.app.data.GameSystem
import com.romcloud.app.data.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibretroPlayersTest {

    private val ra = "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n -e ROM {file.path}\n"

    private fun retroArch(id: String, core: String, regex: String? = "^(.*)\\.(?:sfc|zip)$") =
        Player(name = id, uniqueId = id, acceptedFilenameRegex = regex, amStartArguments = "$ra -e LIBRETRO $core")

    private fun system(vararg players: Player) =
        GameSystem(id = "snes", name = "Super Nintendo", shortname = "snes", folder = "snes", players = players.toList())

    @Test
    fun `nom du coeur extrait du modele`() {
        assertEquals("snes9x", LibretroPlayers.coreOf(retroArch("a", "snes9x")))
        assertEquals(
            "mupen64plus_next_gles3",
            LibretroPlayers.coreOf(retroArch("a", "/data/data/com.retroarch.aarch64/cores/mupen64plus_next_gles3_libretro_android.so")),
        )
        assertNull(LibretroPlayers.coreOf(Player(name = "x", uniqueId = "x", amStartArguments = "-n a/.B")))
    }

    @Test
    fun `un emulateur par coeur ajoute en tete`() {
        val other = Player(name = "Snes9x EX+", uniqueId = "snes.ex", amStartArguments = "-n com.explusalpha.Snes9xPlus/.Main")
        val result = LibretroPlayers.addTo(
            system(retroArch("ra64.snes9x", "snes9x"), other, retroArch("ra32.snes9x", "snes9x"), retroArch("ra64.bsnes", "bsnes", regex = null)),
        )
        // Modèles RetroArch des mêmes cœurs retirés ; autres émulateurs gardés.
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
            system(retroArch("ra.play", "play"), retroArch("ra.pcee2", "pcee2"), retroArch("ra.lrps2", "pcsx2")).copy(id = "ps2", shortname = "ps2"),
        )
        assertEquals(listOf("libretrodroid.pcsx2", "libretrodroid.pcee2", "libretrodroid.play"), ps2.players.take(3).map { it.uniqueId })
        val gba = LibretroPlayers.addTo(
            system(retroArch("a", "vba_next"), retroArch("b", "inconnu"), retroArch("c", "mgba")).copy(id = "gba", shortname = "gba"),
        )
        assertEquals(listOf("libretrodroid.mgba", "libretrodroid.vba_next", "libretrodroid.inconnu"), gba.players.take(3).map { it.uniqueId })
        // Cœur absent du buildbot Android : en dernier.
        val n3ds = LibretroPlayers.addTo(
            system(retroArch("a", "azahar"), retroArch("b", "citra"), retroArch("c", "panda3ds")).copy(id = "3ds", shortname = "3ds"),
        )
        assertEquals(listOf("libretrodroid.citra", "libretrodroid.panda3ds", "libretrodroid.azahar"), n3ds.players.take(3).map { it.uniqueId })
        // RetroArch gardé seulement pour le cœur absent du buildbot Android (installé à la main).
        assertEquals(listOf("a"), n3ds.players.drop(3).map { it.uniqueId })
    }

    @Test
    fun `systeme sans RetroArch ou deja complete inchange`() {
        val plain = system(Player(name = "x", uniqueId = "x", amStartArguments = "-n a/.B"))
        assertSame(plain, LibretroPlayers.addTo(plain))
        val once = LibretroPlayers.addTo(system(retroArch("a", "snes9x")))
        assertSame(once, LibretroPlayers.addTo(once))
    }
}
