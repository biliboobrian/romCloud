package com.romcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun `comparaison des versions`() {
        assertTrue(AppUpdater.isNewer("1.12.0", "1.11.0"))
        assertTrue(AppUpdater.isNewer("v1.11.1", "1.11.0"))
        assertTrue(AppUpdater.isNewer("2.0", "1.99.99"))
        assertFalse(AppUpdater.isNewer("1.11.0", "1.11.0"))
        assertFalse(AppUpdater.isNewer("1.11", "1.11.0"))
        assertFalse(AppUpdater.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `versions compilees sur un PC`() {
        assertNull(AppUpdater.parseVersion("1.0.0-dev"))
        assertTrue(AppUpdater.isNewer("1.64.0", "1.63.0-dev"))
        assertTrue(AppUpdater.isNewer("1.63.0", "1.63.0-dev"))
        assertFalse(AppUpdater.isNewer("1.62.0", "1.63.0-dev"))
        assertFalse(AppUpdater.isNewer("1.63.0-dev", "1.62.0"))
        assertEquals(listOf(1, 12, 0), AppUpdater.parseVersion("v1.12.0"))
    }

    @Test
    fun `modifications regroupees par version`() {
        val body = """{"commits": [
            {"commit": {"message": "Couleur des systèmes ; Android : fond ; v1.35.6\r\n\r\nCo-Authored-By: x"}},
            {"commit": {"message": "Interface web sur mobile ; scraping Vita"}},
            {"commit": {"message": "Merge branch 'x'"}},
            {"commit": {"message": "Fond de l'image ; v1.35.7"}}
        ]}"""
        assertEquals(
            listOf(
                AppUpdater.VersionChanges("1.35.7", listOf("Fond de l'image", "Interface web sur mobile", "scraping Vita")),
                AppUpdater.VersionChanges("1.35.6", listOf("Couleur des systèmes", "Android : fond")),
            ),
            AppUpdater.changesFromCompare(body, "1.35.7"),
        )
        assertEquals(emptyList<AppUpdater.VersionChanges>(), AppUpdater.changesFromCompare("{}", "1.35.7"))
    }

    @Test
    fun `nom de l'APK selon l'application`() {
        assertEquals("RomCloud-1.12.0.apk", AppUpdater.apkName("1.12.0", tv = false))
        assertEquals("RomCloud-TV-1.12.0.apk", AppUpdater.apkName("1.12.0", tv = true))
    }
}
