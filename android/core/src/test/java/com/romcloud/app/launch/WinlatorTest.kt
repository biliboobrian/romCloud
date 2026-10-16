package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WinlatorTest {

    private val downloads = File("/storage/emulated/0/Download")

    @Test
    fun `jeu dans Telechargements, lecteur D par defaut`() {
        val exe = File("/storage/emulated/0/Download/RomCloud/windows/Mr. Robot/Bin/Game.exe")
        assertEquals("D:\\RomCloud\\windows\\Mr. Robot\\Bin\\Game.exe" to false, Winlator.winePath(exe, downloads, File("/storage/emulated/0/Download/RomCloud")))
    }

    @Test
    fun `dossier des ROMs ailleurs, lecteur des ROMs a ajouter`() {
        val exe = File("/storage/emulated/0/RomCloud/windows/Doom/doom.exe")
        assertEquals("R:\\windows\\Doom\\doom.exe" to true, Winlator.winePath(exe, downloads, File("/storage/emulated/0/RomCloud/")))
    }

    @Test
    fun `raccourci relu par Winlator, espaces et barres obliques echappees`() {
        val text = Winlator.desktopEntry("Mr. Robot", "D:\\My Games\\Robot.exe")
        assertTrue(text.startsWith("[Desktop Entry]\n"))
        assertTrue("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine D:\\\\\\\\My\\ Games\\\\\\\\Robot.exe\n" in text)
        assertTrue("StartupWMClass=robot.exe\n" in text)
        assertTrue(text.endsWith("[Extra Data]\ncontainer_id=1\n"))
        // Chemin relu comme Winlator (StringUtils.unescape) : le chemin Windows d'origine.
        val exec = text.lines().first { it.startsWith("Exec=") }
        val raw = exec.substring(exec.lastIndexOf("wine ") + 5)
        val unescaped = raw.replace(Regex("\\\\([^\\\\]+)"), "$1").replace(Regex("\\\\([^\\\\]+)"), "$1").replace("\\\\", "\\").trim()
        assertEquals("D:\\My Games\\Robot.exe", unescaped)
    }
}
