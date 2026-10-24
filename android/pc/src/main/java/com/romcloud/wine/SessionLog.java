package com.romcloud.wine;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Journal de la dernière partie, lu par RomCloud ([LogsProvider]) : pendant la partie, le journal
 * Android du processus (sortie de Wine comprise, étiquette « Wine ») est recopié dans un fichier, et
 * la session est notée dans [MARKER]. Plantage du jeu sous Wine (exception non gérée), ou du
 * processus de RomCloud PC (session restée « en cours », confirmée depuis Android 11 par la raison
 * de fin du processus) : rapport rendu une seule fois à RomCloud, qui l'envoie au serveur.
 */
public final class SessionLog {
    private static final String MARKER = "session.txt";
    private static final String LOG = "session.log";
    private static final int MAX_LOG_BYTES = 1024 * 1024;
    /**
     * Fin du journal jointe au rapport d'un plantage, et à celui de l'utilisateur (avec le journal de
     * RomCloud) : le serveur garde 200 000 caractères au plus.
     */
    private static final int CRASH_CHARS = 150_000;
    private static final int REPORT_CHARS = 60_000;
    /** Lignes de Wine qui signalent un plantage du jeu. */
    private static final Pattern WINE_CRASH = Pattern.compile("Unhandled (exception|page fault|stack overflow|illegal instruction)|wine: .*crash", Pattern.CASE_INSENSITIVE);

    private static File dir;
    private static java.lang.Process logcat;
    private static volatile boolean crashNoted;

    private SessionLog() {}

    private static synchronized File dir(Context context) {
        if (dir == null) {
            dir = new File(context.getApplicationContext().getFilesDir(), "romcloud");
            dir.mkdirs();
        }
        return dir;
    }

    /** Début de partie (processus du jeu) : session notée, journal du processus recopié. */
    public static synchronized void start(Context context, String title, String exe, String wine) {
        File base = dir(context);
        crashNoted = false;
        Map<String, String> info = new LinkedHashMap<>();
        info.put("title", title != null ? title : "");
        info.put("exe", exe);
        info.put("wine", wine);
        info.put("pid", String.valueOf(Process.myPid()));
        info.put("started", String.valueOf(System.currentTimeMillis()));
        info.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", " + String.join(", ", Build.SUPPORTED_ABIS) + ")");
        writeInfo(new File(base, MARKER), info);
        // Partie précédente du même processus (jeu fermé par Wine) : sa copie du journal s'arrête.
        if (logcat != null) logcat.destroy();
        File log = new File(base, LOG);
        try {
            logcat = new ProcessBuilder("logcat", "-v", "time", "--pid=" + Process.myPid()).redirectErrorStream(true).start();
        } catch (IOException e) {
            logcat = null;
            return;
        }
        java.lang.Process source = logcat;
        Thread copy = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(log), StandardCharsets.UTF_8))) {
                int written = 0;
                String line;
                while ((line = in.readLine()) != null) {
                    if (written >= MAX_LOG_BYTES) continue;
                    out.write(line);
                    out.newLine();
                    out.flush();
                    written += line.length() + 1;
                }
            } catch (IOException ignored) {
            }
        }, "romcloud-log");
        copy.setDaemon(true);
        copy.start();
    }

    /** Ligne de sortie de Wine (ProcessHelper) : une exception non gérée marque la session comme plantée. */
    public static void onWineLine(String line) {
        if (crashNoted || dir == null || !WINE_CRASH.matcher(line).find()) return;
        crashNoted = true;
        synchronized (SessionLog.class) {
            File marker = new File(dir, MARKER);
            Map<String, String> info = readInfo(marker);
            if (info.isEmpty()) return;
            info.put("crash", line.trim());
            writeInfo(marker, info);
        }
    }

    /**
     * Fin de la partie (Wine terminé, ou « Quitter » du menu) : session close ; gardée si Wine a
     * signalé un plantage, pour le rapport. [status] : code de fin de Wine (-1 : inconnu).
     */
    public static synchronized void end(Context context, int status) {
        File marker = new File(dir(context), MARKER);
        Map<String, String> info = readInfo(marker);
        if (info.isEmpty() || info.containsKey("ended")) return;
        if (!info.containsKey("crash")) {
            marker.delete();
            return;
        }
        info.put("ended", String.valueOf(System.currentTimeMillis()));
        if (status >= 0) info.put("status", String.valueOf(status));
        writeInfo(marker, info);
    }

    /** Partie en cours dans ce processus. */
    public static synchronized boolean running(Context context) {
        Map<String, String> info = readInfo(new File(dir(context), MARKER));
        return !info.isEmpty() && !info.containsKey("ended") && String.valueOf(Process.myPid()).equals(info.get("pid"));
    }

    /**
     * Rapport de la dernière partie si elle a planté (titre et texte), consommé : renvoyé une seule
     * fois ; null sinon (partie en cours, terminée normalement, processus arrêté par le système).
     */
    public static synchronized String[] takePending(Context context) {
        File marker = new File(dir(context), MARKER);
        Map<String, String> info = readInfo(marker);
        if (info.isEmpty() || running(context)) return null;
        marker.delete();
        String reason = info.get("crash");
        String exit = null;
        String trace = null;
        if (reason == null) {
            // Processus disparu en pleine partie : plantage, sauf arrêt par le système ou l'utilisateur.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ApplicationExitInfo exitInfo = exitInfo(context, info.get("pid"));
                if (exitInfo == null || !isCrash(exitInfo)) return null;
                exit = describe(exitInfo);
                trace = trace(exitInfo);
            } else {
                exit = "arrêt inattendu du processus";
            }
            reason = exit;
        }
        String title = info.get("title");
        if (title == null || title.isEmpty()) title = new File(String.valueOf(info.get("exe"))).getName();
        StringBuilder text = new StringBuilder();
        text.append("RomCloud PC — plantage d'un jeu PC (Wine)\n");
        appendInfo(text, info);
        text.append("Cause : ").append(reason).append('\n');
        if (info.containsKey("status")) text.append("Code de fin de Wine : ").append(info.get("status")).append('\n');
        if (trace != null) text.append("\n--- Trace native ---\n").append(trace).append('\n');
        text.append("\n--- Journal de la partie (dernières lignes) ---\n").append(tail(new File(dir(context), LOG), CRASH_CHARS));
        return new String[]{title + " — " + reason, text.toString()};
    }

    /** Journal de la dernière partie (rapport envoyé par l'utilisateur), même sans plantage. */
    public static synchronized String lastLog(Context context) {
        StringBuilder text = new StringBuilder();
        text.append("RomCloud PC ").append(versionName(context)).append('\n');
        Map<String, String> info = readInfo(new File(dir(context), MARKER));
        if (!info.isEmpty()) {
            appendInfo(text, info);
            if (info.containsKey("crash")) text.append("Plantage signalé par Wine : ").append(info.get("crash")).append('\n');
        }
        File log = new File(dir(context), LOG);
        if (log.isFile()) {
            text.append("Partie du ").append(date(log.lastModified())).append('\n');
            text.append("\n--- Journal de la dernière partie ---\n").append(tail(log, REPORT_CHARS));
        } else {
            text.append("(aucune partie jouée)\n");
        }
        return text.toString();
    }

    private static void appendInfo(StringBuilder text, Map<String, String> info) {
        String started = info.get("started");
        if (started != null) {
            try {
                text.append("Date : ").append(date(Long.parseLong(started))).append('\n');
            } catch (NumberFormatException ignored) {
            }
        }
        text.append("Jeu : ").append(info.get("title")).append('\n');
        text.append("Exécutable : ").append(info.get("exe")).append('\n');
        text.append("Proton / Wine : ").append(info.get("wine")).append('\n');
        text.append("Appareil : ").append(info.get("device")).append('\n');
    }

    private static String date(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static ApplicationExitInfo exitInfo(Context context, String pid) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pid == null) return null;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return null;
        try {
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), Integer.parseInt(pid), 1);
            return list.isEmpty() ? null : list.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isCrash(ApplicationExitInfo exit) {
        int reason = exit.getReason();
        return reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR;
    }

    private static String describe(ApplicationExitInfo exit) {
        String reason;
        switch (exit.getReason()) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE: reason = "plantage natif (signal " + exit.getStatus() + ")"; break;
            case ApplicationExitInfo.REASON_CRASH: reason = "exception Java"; break;
            case ApplicationExitInfo.REASON_ANR: reason = "application figée (ANR)"; break;
            default: reason = "raison " + exit.getReason();
        }
        return exit.getDescription() != null ? reason + " — " + exit.getDescription() : reason;
    }

    /**
     * Trace d'un plantage natif (« tombstone », protobuf depuis Android 12) : textes lisibles seulement
     * (message, bibliothèques et fonctions de la pile d'appels) ; trace d'une exception Java telle quelle.
     */
    private static String trace(ApplicationExitInfo exit) {
        if (exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) return null;
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(buffer, 0, n);
            java.util.regex.Matcher m = Pattern.compile("[\\x20-\\x7E]{6,}").matcher(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1));
            java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
            while (m.find() && kept.size() < 80) {
                String s = m.group();
                if (s.contains(".so") || s.toLowerCase(Locale.ROOT).contains("signal") || s.toLowerCase(Locale.ROOT).contains("abort") || s.startsWith("_Z")) kept.add(s);
            }
            return kept.isEmpty() ? null : String.join("\n", kept);
        } catch (IOException e) {
            return null;
        }
    }

    /** Fin du journal [file] ([max] caractères au plus). */
    private static String tail(File file, int max) {
        if (!file.isFile()) return "(aucun)\n";
        ArrayDeque<String> lines = new ArrayDeque<>();
        int size = 0;
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.addLast(line);
                size += line.length() + 1;
                while (size > max && !lines.isEmpty()) size -= lines.removeFirst().length() + 1;
            }
        } catch (IOException e) {
            return "(journal illisible : " + e.getMessage() + ")\n";
        }
        StringBuilder text = new StringBuilder(size);
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    private static Map<String, String> readInfo(File file) {
        Map<String, String> info = new LinkedHashMap<>();
        if (!file.isFile()) return info;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) info.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException ignored) {
        }
        return info;
    }

    private static void writeInfo(File file, Map<String, String> info) {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                out.write(entry.getKey() + "=" + String.valueOf(entry.getValue()).replace('\n', ' '));
                out.newLine();
            }
        } catch (IOException ignored) {
        }
    }
}
