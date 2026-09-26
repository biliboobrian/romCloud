package com.romcloud.app.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class AmStartParserTest {

    private val retroArch = """
        -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
         -e ROM {file.path}
         -e LIBRETRO /data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so
         -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
         --activity-clear-task
    """.trimIndent()

    @Test
    fun `un chemin avec espaces reste un seul argument`() {
        val path = "/storage/emulated/0/RomCloud/snes/Super Mario World (USA).sfc"
        val tokens = AmStartParser.tokenize(retroArch).map { AmStartParser.substitute(it, mapOf("file.path" to path)) }
        assertEquals(listOf("-e", "ROM", path), tokens.subList(2, 5))
        assertEquals("--activity-clear-task", tokens.last())
    }

    @Test
    fun `guillemets et echappements`() {
        assertEquals(listOf("-e", "KEY", "a b", "c\"d"), AmStartParser.tokenize("-e KEY \"a b\" c\\\"d"))
        assertEquals(listOf("-e", "EMPTY", ""), AmStartParser.tokenize("-e EMPTY ''"))
    }

    @Test
    fun `placeholders inconnus remplaces par une chaine vide`() {
        assertEquals("id=", AmStartParser.substitute("id={tags.steamappid}", emptyMap()))
        assertEquals("content://x", AmStartParser.substitute("{file.uri}", mapOf("file.uri" to "content://x")))
    }
}
