package com.romcloud.wine;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Chemins du système Linux de Winlator (imagefs, Proton) : ses programmes sont construits pour le
 * dossier de l'application Winlator Cmod (/data/data/com.winlator.cmod/…). Les fichiers extraits
 * sont réécrits pour celui de RomCloud PC, nom de même longueur : aucun décalage dans les programmes
 * (chemins des bibliothèques, de l'interpréteur, des fichiers de configuration).
 */
public final class PackagePath {
    public static final String ORIGINAL = "com.winlator.cmod";
    public static final String CURRENT = "com.romcloud.wine";

    private static final byte[] FROM = ORIGINAL.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TO = CURRENT.getBytes(StandardCharsets.US_ASCII);

    static {
        if (FROM.length != TO.length) throw new AssertionError("noms de paquet de longueurs différentes");
    }

    private PackagePath() {}

    /** Texte (cible d'un lien symbolique…) avec le nom de paquet de RomCloud PC. */
    public static String rewrite(String text) {
        return text == null ? null : text.replace(ORIGINAL, CURRENT);
    }

    /** Remplace le nom de paquet dans [bytes] (en place), de [from] à [to] exclu. */
    static void rewrite(byte[] bytes, int from, int to) {
        outer:
        for (int i = from; i <= to - FROM.length; i++) {
            for (int j = 0; j < FROM.length; j++) {
                if (bytes[i + j] != FROM[j]) continue outer;
            }
            System.arraycopy(TO, 0, bytes, i, TO.length);
            i += FROM.length - 1;
        }
    }

    /** Flux d'écriture qui remplace le nom de paquet au passage (occurrences à cheval sur deux écritures comprises). */
    public static OutputStream rewriting(OutputStream out) {
        return new FilterOutputStream(out) {
            // Fin des données reçues gardée pour une occurrence coupée entre deux écritures.
            private final byte[] pending = new byte[1 << 16];
            private int count;

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    int n = Math.min(len, pending.length - count);
                    System.arraycopy(b, off, pending, count, n);
                    count += n;
                    off += n;
                    len -= n;
                    if (count == pending.length) drain(FROM.length - 1);
                }
            }

            /** Écrit les données reçues sauf les [keep] derniers octets (début possible d'une occurrence). */
            private void drain(int keep) throws IOException {
                rewrite(pending, 0, count);
                int n = Math.max(0, count - keep);
                out.write(pending, 0, n);
                System.arraycopy(pending, n, pending, 0, count - n);
                count -= n;
            }

            @Override
            public void flush() throws IOException {
                drain(0);
                out.flush();
            }

            @Override
            public void close() throws IOException {
                flush();
                out.close();
            }
        };
    }
}
