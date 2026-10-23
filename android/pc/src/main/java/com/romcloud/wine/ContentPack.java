package com.romcloud.wine;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Gros fichiers de Winlator Cmod (système Linux, Proton, DXVK, pilotes graphiques, composants
 * Windows : dossier « assets » de son APK, 700 Mo) : téléchargés au premier lancement depuis la
 * version publiée qui correspond au code repris, entrée par entrée (requêtes Range sur le zip de
 * l'APK, reprise après interruption), dans le dossier de l'application. Lus à la place des assets
 * de l'APK de RomCloud PC ([open], [list]).
 */
public final class ContentPack {
    /** Version de Winlator Cmod dont le code est repris (android/pc), et son APK. */
    public static final String VERSION = "cmod_v13.1.1";
    public static final String APK_URL = "https://github.com/coffincolors/winlator/releases/download/cmod_v13.1/Winlator-Cmod-v13.1.1.apk";

    /** Fichiers inutiles à RomCloud PC (musique du mode Big Picture). */
    private static final List<String> SKIPPED = Arrays.asList("default_music.mp3");

    public interface Progress {
        /** Octets téléchargés sur le total (compressés, tels que dans l'APK). */
        void onProgress(long done, long total);
    }

    private ContentPack() {}

    public static File dir(Context context) {
        return new File(context.getFilesDir(), "content/assets");
    }

    private static File marker(Context context) {
        return new File(context.getFilesDir(), "content/" + VERSION + ".complete");
    }

    public static boolean isInstalled(Context context) {
        return marker(context).isFile();
    }

    /** Fichier [name] téléchargé et toujours présent. */
    public static boolean has(Context context, String name) {
        return new File(dir(context), name).isFile();
    }

    /**
     * Supprime des fichiers téléchargés devenus inutiles (archives du système Linux et de Proton, une
     * fois installées : 300 Mo) ; retéléchargés par [download] s'il faut les réinstaller.
     */
    public static void discard(Context context, String... names) {
        for (String name : names) new File(dir(context), name).delete();
    }

    /** Fichier [name] des assets : téléchargé, sinon dans l'APK. */
    public static InputStream open(Context context, String name) throws IOException {
        File file = new File(dir(context), name);
        if (file.isFile()) return new FileInputStream(file);
        return context.getAssets().open(name);
    }

    /** Contenu du dossier [path] des assets : téléchargé, sinon dans l'APK. */
    public static String[] list(Context context, String path) throws IOException {
        File folder = new File(dir(context), path);
        String[] names = folder.isDirectory() ? folder.list() : null;
        if (names != null && names.length > 0) {
            Arrays.sort(names);
            return names;
        }
        return context.getAssets().list(path);
    }

    /** Entrée du zip de l'APK : nom, taille compressée, méthode, position de l'en-tête local. */
    private static final class Entry {
        final String name;
        final long compressed;
        final long size;
        final int method;
        final long offset;

        Entry(String name, long compressed, long size, int method, long offset) {
            this.name = name;
            this.compressed = compressed;
            this.size = size;
            this.method = method;
            this.offset = offset;
        }
    }

    /**
     * Télécharge les fichiers manquants (ceux déjà complets sont gardés) ; exception en cas d'échec.
     * Un seul téléchargement à la fois : un second appel attend la fin du premier (mêmes fichiers).
     */
    public static synchronized void download(Context context, Progress progress) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().followRedirects(true).build();
        long length = contentLength(client);
        List<Entry> entries = centralDirectory(client, length);
        long total = 0;
        for (Entry entry : entries) total += entry.compressed;
        long done = 0;
        File root = dir(context);
        for (Entry entry : entries) {
            File file = new File(root, entry.name.substring("assets/".length()));
            if (file.isFile() && file.length() == entry.size) {
                done += entry.compressed;
                progress.onProgress(done, total);
                continue;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            File partial = new File(file.getPath() + ".part");
            byte[] header = range(client, entry.offset, entry.offset + 29);
            ByteBuffer local = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int start = 30 + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff);
            long from = entry.offset + start;
            Request request = new Request.Builder().url(APK_URL)
                    .header("Range", "bytes=" + from + "-" + (from + entry.compressed - 1)).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() != 206 || response.body() == null) throw new IOException("HTTP " + response.code() + " " + entry.name);
                InputStream raw = response.body().byteStream();
                InputStream in = entry.method == 8 ? new InflaterInputStream(raw, new Inflater(true), 1 << 16) : raw;
                try (OutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[1 << 16];
                    long counted = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        // Avancement d'après la taille compressée (proportion des octets écrits).
                        counted += n;
                        long part = entry.size > 0 ? entry.compressed * Math.min(counted, entry.size) / entry.size : 0;
                        progress.onProgress(done + part, total);
                    }
                }
            }
            if (partial.length() != entry.size || !partial.renameTo(file)) throw new IOException("fichier incomplet " + entry.name);
            done += entry.compressed;
            progress.onProgress(done, total);
        }
        File marker = marker(context);
        if (!marker.createNewFile() && !marker.isFile()) throw new IOException("marker");
    }

    private static long contentLength(OkHttpClient client) throws IOException {
        Request request = new Request.Builder().url(APK_URL).head().build();
        try (Response response = client.newCall(request).execute()) {
            String length = response.header("Content-Length");
            if (!response.isSuccessful() || length == null) throw new IOException("HTTP " + response.code());
            return Long.parseLong(length);
        }
    }

    private static byte[] range(OkHttpClient client, long from, long to) throws IOException {
        Request request = new Request.Builder().url(APK_URL).header("Range", "bytes=" + from + "-" + to).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 206 || response.body() == null) throw new IOException("HTTP " + response.code());
            return response.body().bytes();
        }
    }

    /** Entrées « assets/… » du répertoire central du zip (fin de l'APK). */
    private static List<Entry> centralDirectory(OkHttpClient client, long length) throws IOException {
        byte[] tail = range(client, Math.max(0, length - 65536), length - 1);
        int end = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                end = i;
                break;
            }
        }
        if (end < 0) throw new IOException("fin du zip introuvable");
        ByteBuffer eocd = ByteBuffer.wrap(tail, end, 22).order(ByteOrder.LITTLE_ENDIAN);
        long size = eocd.getInt(end + 12) & 0xffffffffL;
        long offset = eocd.getInt(end + 16) & 0xffffffffL;
        ByteBuffer cd = ByteBuffer.wrap(range(client, offset, offset + size - 1)).order(ByteOrder.LITTLE_ENDIAN);
        List<Entry> entries = new ArrayList<>();
        int p = 0;
        while (p + 46 <= cd.limit() && cd.getInt(p) == 0x02014b50) {
            int method = cd.getShort(p + 10) & 0xffff;
            long compressed = cd.getInt(p + 20) & 0xffffffffL;
            long uncompressed = cd.getInt(p + 24) & 0xffffffffL;
            int nameLength = cd.getShort(p + 28) & 0xffff;
            int extraLength = cd.getShort(p + 30) & 0xffff;
            int commentLength = cd.getShort(p + 32) & 0xffff;
            long local = cd.getInt(p + 42) & 0xffffffffL;
            String name = new String(cd.array(), p + 46, nameLength, StandardCharsets.UTF_8);
            boolean file = !name.endsWith("/");
            if (file && name.startsWith("assets/") && !SKIPPED.contains(name.substring(7))) {
                entries.add(new Entry(name, compressed, uncompressed, method, local));
            }
            p += 46 + nameLength + extraLength + commentLength;
        }
        if (entries.isEmpty()) throw new IOException("aucun fichier dans l'APK");
        return entries;
    }
}
