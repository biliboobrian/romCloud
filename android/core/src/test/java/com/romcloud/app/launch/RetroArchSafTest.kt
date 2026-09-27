package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetroArchSafTest {

    @Test
    fun `chemin identique a celui affiche par RetroArch`() {
        assertEquals(
            "saf://content:%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ARomCloud" +
                "/n64/Super Mario 64 (Europe) (En,Fr,De) (2).zip",
            RetroArchSaf.path(
                "/storage/emulated/0/RomCloud",
                "/storage/emulated/0/RomCloud/n64/Super Mario 64 (Europe) (En,Fr,De) (2).zip",
            ),
        )
    }

    @Test
    fun `sous-dossier et carte SD`() {
        assertEquals(
            "saf://content:%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253AJeux%252FRomCloud/snes/a.sfc",
            RetroArchSaf.path("/storage/emulated/0/Jeux/RomCloud/", "/storage/emulated/0/Jeux/RomCloud/snes/a.sfc"),
        )
        assertEquals(
            "saf://content:%2F%2Fcom.android.externalstorage.documents%2Ftree%2F1234-ABCD%253ARoms/gba/b.gba",
            RetroArchSaf.path("/storage/1234-ABCD/Roms", "/storage/1234-ABCD/Roms/gba/b.gba"),
        )
    }

    @Test
    fun `hors du dossier ou volume inconnu`() {
        assertNull(RetroArchSaf.path("/storage/emulated/0/RomCloud", "/storage/emulated/0/Autre/a.sfc"))
        assertNull(RetroArchSaf.path("/data/user/0/app/files", "/data/user/0/app/files/a.sfc"))
    }

    @Test
    fun `encodage comme Uri encode`() {
        assertEquals("primary%3AJeux%20r%C3%A9tro%2F(1)", RetroArchSaf.uriEncode("primary:Jeux rétro/(1)"))
    }
}
