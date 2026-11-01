package com.romcloud.wine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class SharedCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emplacementsDansSystemWindows() {
        SharedCache cache = new SharedCache("/storage/emulated/0/RomCloud/system/windows");
        assertEquals("/storage/emulated/0/RomCloud/system/windows/components/dxvk-2.4.1.wcp", cache.component("dxvk", "2.4.1").getPath().replace('\\', '/'));
        assertEquals("/storage/emulated/0/RomCloud/system/windows/drivers/turnip-25.zip", cache.driver("turnip-25").getPath().replace('\\', '/'));
        assertEquals("/storage/emulated/0/RomCloud/system/windows/winlator/" + ContentPack.VERSION + "/imagefs.txz",
                cache.contentPack("imagefs.txz").getPath().replace('\\', '/'));
        // Sans dossier (RomCloud plus ancien) : aucune copie.
        assertNull(new SharedCache(null).component("dxvk", "2.4.1"));
        assertFalse(SharedCache.has(null, -1));
    }

    @Test
    public void copieGardeeUneFoisEtReprise() throws IOException {
        File file = tmp.newFile("content.wcp");
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        File cached = new File(tmp.getRoot(), "windows/components/dxvk-2.4.1.wcp");
        assertFalse(SharedCache.has(cached, -1));
        SharedCache.keep(file, cached);
        assertTrue(SharedCache.has(cached, 3));
        assertFalse(SharedCache.has(cached, 4)); // taille attendue différente
        assertFalse(new File(cached.getPath() + ".part").exists());
        long kept = cached.lastModified();
        SharedCache.keep(file, cached); // déjà gardée : pas recopiée
        assertEquals(kept, cached.lastModified());
    }
}
