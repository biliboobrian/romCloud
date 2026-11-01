package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class AmStartParserTest {

    private val template = """
        -n com.example.emulator/.GameActivity
         -e ROM {file.path}
         -e LIBRETRO /data/data/com.example.emulator/cores/snes9x_libretro_android.so
         -e CONFIG /storage/emulated/0/Android/data/com.example.emulator/files/emulator.cfg
         --activity-clear-task
    """.trimIndent()

    @Test
    fun `un chemin avec espaces reste un seul argument`() {
        val path = "/storage/emulated/0/RomCloud/snes/Super Mario World (USA).sfc"
        val tokens = AmStartParser.tokenize(template).map { AmStartParser.substitute(it, mapOf("file.path" to path)) }
        assertEquals(listOf("-e", "ROM", path), tokens.subList(2, 5))
        assertEquals("--activity-clear-task", tokens.last())
    }

    @Test
    fun `guillemets et echappements`() {
        assertEquals(listOf("-e", "KEY", "a b", "c\"d"), AmStartParser.tokenize("-e KEY \"a b\" c\\\"d"))
        assertEquals(listOf("-e", "EMPTY", ""), AmStartParser.tokenize("-e EMPTY ''"))
    }

    @Test
    fun `lecture d'un extra du modele`() {
        assertEquals("snes9x", AmStartParser.stringExtra(template.replace(Regex("/data/\\S+/(snes9x)_libretro_android.so"), "$1"), "LIBRETRO"))
        assertEquals(
            "/storage/emulated/0/Android/data/com.example.emulator/files/emulator.cfg",
            AmStartParser.stringExtra(template, "CONFIG"),
        )
        assertEquals("{file.path}", AmStartParser.stringExtra(template, "ROM"))
        assertEquals(null, AmStartParser.stringExtra(template, "ABSENT"))
    }

    @Test
    fun `placeholders inconnus remplaces par une chaine vide`() {
        assertEquals("id=", AmStartParser.substitute("id={tags.steamappid}", emptyMap()))
        assertEquals("content://x", AmStartParser.substitute("{file.uri}", mapOf("file.uri" to "content://x")))
    }
}
