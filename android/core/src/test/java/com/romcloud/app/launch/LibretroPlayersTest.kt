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
        assertEquals(
            listOf("libretrodroid.snes9x", "libretrodroid.bsnes", "ra64.snes9x", "snes.ex", "ra32.snes9x", "ra64.bsnes"),
            result.players.map { it.uniqueId },
        )
        assertEquals("snes9x", result.players[0].libretroCore)
        assertEquals("^(.*)\\.(?:sfc|zip)$", result.players[0].acceptedFilenameRegex)
        assertNull(result.players[1].acceptedFilenameRegex)
    }

    @Test
    fun `systeme sans RetroArch ou deja complete inchange`() {
        val plain = system(Player(name = "x", uniqueId = "x", amStartArguments = "-n a/.B"))
        assertSame(plain, LibretroPlayers.addTo(plain))
        val once = LibretroPlayers.addTo(system(retroArch("a", "snes9x")))
        assertSame(once, LibretroPlayers.addTo(once))
    }
}
