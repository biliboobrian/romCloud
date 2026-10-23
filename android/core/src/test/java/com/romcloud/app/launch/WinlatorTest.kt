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
    fun `reglages du jeu selon l'appareil`() {
        // Téléphone 20:9 (portrait ou paysage), TV 16:9, tablette 4:3, écran très allongé.
        assertEquals("1600x720", Winlator.screenSize(1080, 2400))
        assertEquals("1280x720", Winlator.screenSize(3840, 2160))
        assertEquals("960x720", Winlator.screenSize(2048, 1536))
        assertEquals("1680x720", Winlator.screenSize(4000, 1000))

        val adreno = Winlator.gameSettings(adreno = true, screenWidth = 2400, screenHeight = 1080, locale = java.util.Locale.FRANCE)
        assertEquals("1600x720", adreno["screenSize"])
        assertEquals("1", adreno["forceFullscreen"])
        assertEquals("wrapper", adreno["graphicsDriver"])
        assertTrue("adrenotoolsTurnip=1" in adreno.getValue("graphicsDriverConfig"))
        assertTrue(adreno.getValue("graphicsDriverConfig").startsWith("version=turnip"))
        assertEquals("fr_FR.UTF-8", adreno["lc_all"])
        assertEquals("DXVK_HUD=0 MANGOHUD=0", adreno["envVars"])
        // Mali, Xclipse… : pilote Vulkan de l'appareil.
        val mali = Winlator.gameSettings(adreno = false, screenWidth = 1920, screenHeight = 1080, locale = java.util.Locale.US)
        assertTrue("adrenotoolsTurnip=0" in mali.getValue("graphicsDriverConfig"))
        assertTrue(mali.getValue("graphicsDriverConfig").startsWith("version=System"))
        assertEquals("en_US.UTF-8", mali["lc_all"])
    }

    @Test
    fun `raccourci relu par Winlator, espaces et barres obliques echappees`() {
        val text = Winlator.desktopEntry("Mr. Robot", "D:\\My Games\\Robot.exe")
        assertTrue(text.startsWith("[Desktop Entry]\n"))
        assertTrue("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine D:\\\\\\\\My\\ Games\\\\\\\\Robot.exe\n" in text)
        assertTrue("StartupWMClass=robot.exe\n" in text)
        assertTrue(text.endsWith("[Extra Data]\ncontainer_id=1\n"))
        // Réglages du jeu : une ligne « clé=valeur » chacun (valeur avec « = » relue entière).
        val withSettings = Winlator.desktopEntry("Doom", "D:\\Doom\\doom.exe", mapOf("screenSize" to "1600x720", "envVars" to "DXVK_HUD=0 MANGOHUD=0"))
        assertTrue(withSettings.endsWith("[Extra Data]\ncontainer_id=1\nscreenSize=1600x720\nenvVars=DXVK_HUD=0 MANGOHUD=0\n"))
        // Chemin relu comme Winlator (StringUtils.unescape) : le chemin Windows d'origine.
        val exec = text.lines().first { it.startsWith("Exec=") }
        val raw = exec.substring(exec.lastIndexOf("wine ") + 5)
        val unescaped = raw.replace(Regex("\\\\([^\\\\]+)"), "$1").replace(Regex("\\\\([^\\\\]+)"), "$1").replace("\\\\", "\\").trim()
        assertEquals("D:\\My Games\\Robot.exe", unescaped)
    }

    @Test
    fun `lecteurs du conteneur lus et modifies comme Winlator`() {
        val defaults = "D:/storage/emulated/0/DownloadE:/data/data/com.winlator.cmod/storage"
        assertEquals(listOf('D' to "/storage/emulated/0/Download", 'E' to "/data/data/com.winlator.cmod/storage"), Winlator.parseDrives(defaults))
        // Lecteur des ROMs ajouté à la fin, puis remplacé (nouveau dossier) sans doublon.
        val added = Winlator.withDrive(defaults, 'R', "/storage/emulated/0/RomCloud")
        assertEquals("$defaults" + "R:/storage/emulated/0/RomCloud", added)
        assertEquals(
            listOf('D' to "/storage/emulated/0/Download", 'E' to "/data/data/com.winlator.cmod/storage", 'R' to "/sdcard/Jeux"),
            Winlator.parseDrives(Winlator.withDrive(added, 'r', "/sdcard/Jeux")).map { (l, p) -> l.uppercaseChar() to p },
        )
        assertEquals(emptyList<Pair<Char, String>>(), Winlator.parseDrives(""))
        assertEquals("R:/x", Winlator.withDrive("", 'R', "/x"))
        assertEquals("files/imagefs/home/xuser-1/.container", Winlator.containerConfigPath())
    }
}
