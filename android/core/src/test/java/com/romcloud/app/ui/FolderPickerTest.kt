package com.romcloud.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderPickerTest {

    @Test
    fun `chemin du dossier choisi dans le selecteur d'Android`() {
        assertEquals("/storage/emulated/0/RomCloud", folderPath("primary:RomCloud"))
        assertEquals("/storage/emulated/0", folderPath("primary:"))
        assertEquals("/storage/1234-ABCD/Jeux/Roms", folderPath("1234-ABCD:Jeux/Roms/"))
        assertEquals("/storage/emulated/0/Documents/Bios", folderPath("home:Bios"))
        // Hors du stockage de l'appareil (Drive, téléchargements…) : refusé.
        assertNull(folderPath("raw:/storage/emulated/0/Download"))
        assertNull(folderPath("msf:12"))
    }
}
