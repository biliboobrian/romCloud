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
    fun `versions de developpement ignorees`() {
        assertNull(AppUpdater.parseVersion("1.0.0-dev"))
        assertFalse(AppUpdater.isNewer("1.12.0", "1.0.0-dev"))
        assertEquals(listOf(1, 12, 0), AppUpdater.parseVersion("v1.12.0"))
    }

    @Test
    fun `nom de l'APK selon l'application`() {
        assertEquals("RomCloud-1.12.0.apk", AppUpdater.apkName("1.12.0", tv = false))
        assertEquals("RomCloud-TV-1.12.0.apk", AppUpdater.apkName("1.12.0", tv = true))
    }
}
