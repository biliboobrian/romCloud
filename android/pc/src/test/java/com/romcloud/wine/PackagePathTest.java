package com.romcloud.wine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class PackagePathTest {

    private static byte[] rewrite(byte[] data, int chunk) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (OutputStream out = PackagePath.rewriting(result)) {
            for (int i = 0; i < data.length; i += chunk) out.write(data, i, Math.min(chunk, data.length - i));
        }
        return result.toByteArray();
    }

    @Test
    public void cheminsReecritsSansDecalage() throws IOException {
        String text = "RPATH=/data/data/com.winlator.cmod/files/imagefs/usr/lib\0/data/data/com.winlator.cmod/files/imagefs/usr/bin/sh com.winlator.cmodcom.winlator.cmod";
        String expected = text.replace("com.winlator.cmod", "com.romcloud.wine");
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        // Écritures de toutes tailles : occurrences coupées entre deux écritures comprises.
        for (int chunk = 1; chunk <= data.length; chunk++) {
            assertEquals("chunk " + chunk, expected, new String(rewrite(data, chunk), StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    public void donneesSansOccurrenceInchangees() throws IOException {
        byte[] data = new byte[300_000];
        new Random(1).nextBytes(data);
        assertArrayEquals(data, rewrite(data, 4096));
        assertArrayEquals(data, rewrite(data, 70_000));
    }

    @Test
    public void occurrenceAuBordDuTamponInterne() throws IOException {
        // Tampon interne de 64 Kio : occurrence à cheval sur sa limite.
        byte[] data = new byte[(1 << 16) + 100];
        byte[] name = PackagePath.ORIGINAL.getBytes(StandardCharsets.US_ASCII);
        int at = (1 << 16) - 5;
        System.arraycopy(name, 0, data, at, name.length);
        byte[] out = rewrite(data, data.length);
        assertEquals(PackagePath.CURRENT, new String(out, at, name.length, StandardCharsets.US_ASCII));
        assertEquals(data.length, out.length);
        assertEquals("/x/" + PackagePath.CURRENT + "/y", PackagePath.rewrite("/x/" + PackagePath.ORIGINAL + "/y"));
    }
}
