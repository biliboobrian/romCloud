package com.romcloud.wine;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copie des fichiers téléchargés par RomCloud PC dans le dossier RomCloud (system/windows, donné par
 * RomCloud : [LaunchActivity.EXTRA_CACHE_DIR]), gardée si RomCloud PC est désinstallé ou ses données
 * effacées : reprise au lieu d'être retéléchargée.
 *  - winlator/&lt;version&gt;/… : gros fichiers de Winlator Cmod (système Linux, Proton, DXVK…, [ContentPack]) ;
 *  - components/&lt;type&gt;-&lt;version&gt;.wcp : composants choisis dans RomCloud (DXVK, VKD3D, Box64, FEXCore) ;
 *  - drivers/&lt;pilote&gt;.zip : pilotes Vulkan des puces Adreno (AdrenoTools).
 * Les programmes restent installés dans le dossier de l'application : le stockage partagé ne permet
 * ni de les exécuter ni leurs liens symboliques. Sans dossier (RomCloud plus ancien) ou en cas
 * d'erreur d'écriture : rien n'est gardé, le téléchargement se fait comme avant.
 */
final class SharedCache {
    private final File dir;

    /** [dir] : dossier system/windows du dossier RomCloud, ou null (pas de copie). */
    SharedCache(String dir) {
        this.dir = dir == null || dir.isEmpty() ? null : new File(dir);
    }

    File contentPack(String name) {
        return dir == null ? null : new File(dir, "winlator/" + ContentPack.VERSION + "/" + name);
    }

    File component(String type, String version) {
        return dir == null ? null : new File(dir, "components/" + type + "-" + version + ".wcp");
    }

    File driver(String id) {
        return dir == null ? null : new File(dir, "drivers/" + id + ".zip");
    }

    /** Copie gardée [cached] présente (et de taille [size], sauf -1). */
    static boolean has(File cached, long size) {
        return cached != null && cached.isFile() && cached.length() > 0 && (size < 0 || cached.length() == size);
    }

    /** Garde une copie de [file] en [cached] (fichier partiel renommé à la fin) ; une erreur est ignorée. */
    static void keep(File file, File cached) {
        if (cached == null || has(cached, file.length())) return;
        File partial = new File(cached.getPath() + ".part");
        try {
            copy(file, partial);
            if (!partial.renameTo(cached)) throw new IOException("rename " + cached);
        } catch (IOException e) {
            partial.delete();
            Log.w("RomCloud", "Copie de " + file.getName() + " non gardée dans le dossier RomCloud : " + e.getMessage());
        }
    }

    /** Copie [from] sur [to] (dossiers créés). */
    static void copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }
}
