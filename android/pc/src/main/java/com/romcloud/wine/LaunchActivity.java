package com.romcloud.wine;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Point d'entrée de RomCloud (lancé pour un résultat, appelant signé avec la même clé) : lance le jeu PC [EXTRA_EXE] avec le moteur
 * de Winlator, sans ses écrans. Au premier lancement : accès aux fichiers (dossier des ROMs),
 * téléchargement des gros fichiers (ContentPack), installation du système Linux et de Proton,
 * création du conteneur. À chaque lancement : lecteurs du conteneur (D: Téléchargements, R: dossier
 * des ROMs), raccourci du jeu avec ses réglages ([EXTRA_SETTINGS]), puis écran du jeu.
 */
public class LaunchActivity extends Activity {
    /** Exécutable du jeu (chemin Android). */
    public static final String EXTRA_EXE = "exe";
    /** Nom du jeu. */
    public static final String EXTRA_TITLE = "title";
    /** Dossier des ROMs de RomCloud (lecteur R:). */
    public static final String EXTRA_ROMS_DIR = "roms_dir";
    /** Réglages du jeu (Bundle de chaînes) : section « Extra Data » du raccourci (screenSize, graphicsDriver…). */
    public static final String EXTRA_SETTINGS = "settings";

    private static final int REQUEST_STORAGE = 1;

    /**
     * Préparation en cours (téléchargement, installation) : poursuivie même si l'écran est fermé ; un
     * nouveau lancement attend sa fin au lieu d'en démarrer une seconde sur les mêmes fichiers.
     */
    private static final ReentrantLock PREPARING = new ReentrantLock();
    private static final char ROMS_DRIVE = 'R';

    /**
     * Version de Proton (réglage « wineVersion » de [EXTRA_SETTINGS], un conteneur par version) ; par
     * défaut arm64ec : Wine natif ARM, seul le code x86 du jeu traduit par FEX (jeux .NET compris,
     * qui plantent sous Box64 avec Proton x86_64).
     */
    public static final String SETTING_WINE = "wineVersion";

    /** Profil de commandes à l'écran « RomCloud Xbox » (assets/romcloud/xbox.icp) : manette Xbox tactile. */
    private static final int XBOX_PROFILE = 100;
    private static final String DEFAULT_WINE = "proton-9.0-arm64ec";

    private TextView status;
    private ProgressBar bar;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControllerManager.getInstance().init(getApplicationContext());
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(18);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        layout.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
        status.setText(R.string.romcloud_preparing);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Au retour des réglages d'Android (accès aux fichiers), la préparation reprend.
        if (started) return;
        if (getIntent().getStringExtra(EXTRA_EXE) == null || !trustedCaller()) {
            finish();
            return;
        }
        if (!PackagePath.CURRENT.equals(getPackageName())) {
            fail("package " + getPackageName());
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        started = true;
        Executors.newSingleThreadExecutor().execute(this::prepareAndLaunch);
    }

    /** Appelant (startActivityForResult) signé avec la même clé que RomCloud PC : RomCloud. */
    private boolean trustedCaller() {
        String caller = getCallingPackage();
        return caller != null && getPackageManager().checkSignatures(caller, getPackageName()) == PackageManager.SIGNATURE_MATCH;
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        status.setText(R.string.romcloud_storage_access);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && !hasStorageAccess()) fail(getString(R.string.romcloud_storage_denied));
    }

    private void show(int text, int percent) {
        runOnUiThread(() -> {
            status.setText(text);
            bar.setIndeterminate(percent < 0);
            if (percent >= 0) bar.setProgress(percent);
        });
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            status.setText(message);
            bar.setIndeterminate(false);
            bar.setProgress(0);
        });
    }

    private void prepareAndLaunch() {
        PREPARING.lock();
        try {
            // Archives du système supprimées après installation : retéléchargées pour une mise à jour.
            boolean systemNeeded = ImageFsInstaller.isInstallNeeded(this);
            if (!ContentPack.isInstalled(this) || (systemNeeded && !ContentPack.has(this, "imagefs.txz"))) {
                show(R.string.romcloud_downloading, 0);
                ContentPack.download(this, (done, total) -> show(R.string.romcloud_downloading, (int) (done * 100 / Math.max(1, total))));
            }
            if (systemNeeded) {
                show(R.string.romcloud_installing, 0);
                if (!ImageFsInstaller.installSync(this, percent -> show(R.string.romcloud_installing, percent))) {
                    fail(getString(R.string.romcloud_install_failed));
                    return;
                }
                String[] wines = getResources().getStringArray(R.array.wine_entries);
                String[] archives = new String[wines.length + 1];
                archives[0] = "imagefs.txz";
                for (int i = 0; i < wines.length; i++) archives[i + 1] = wines[i] + ".txz";
                ContentPack.discard(this, archives);
            }
            show(R.string.romcloud_preparing, -1);
            installXboxProfile();
            Intent launch = getIntent();
            Bundle requested = launch.getBundleExtra(EXTRA_SETTINGS);
            String wineVersion = requested != null && requested.getString(SETTING_WINE) != null ? requested.getString(SETTING_WINE) : DEFAULT_WINE;
            Container container = container(wineVersion);
            if (container == null) {
                fail(getString(R.string.romcloud_container_failed));
                return;
            }
            Intent intent = getIntent();
            File exe = new File(intent.getStringExtra(EXTRA_EXE));
            File romsDir = new File(intent.getStringExtra(EXTRA_ROMS_DIR) != null ? intent.getStringExtra(EXTRA_ROMS_DIR) : exe.getParent());
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            container.setDrives(drives(container.getDrives(), ROMS_DRIVE, romsDir.getAbsolutePath()));
            container.saveData();
            File shortcut = writeShortcut(container, intent.getStringExtra(EXTRA_TITLE), winePath(exe, downloads, romsDir), intent.getBundleExtra(EXTRA_SETTINGS));
            runOnUiThread(() -> {
                Intent game = new Intent(this, XServerDisplayActivity.class)
                        .putExtra("container_id", container.id)
                        .putExtra("shortcut_path", shortcut.getAbsolutePath());
                startActivity(game);
                finish();
            });
        } catch (Exception e) {
            fail(getString(R.string.romcloud_download_failed, e.getMessage()));
        } finally {
            PREPARING.unlock();
        }
    }

    /**
     * Conteneur de la version de Proton [wineVersion], créé s'il n'existe pas : réglages par défaut de
     * l'écran de création de Winlator (configuration de DXVK, émulateur selon l'architecture de Proton).
     */
    private Container container(String wineVersion) throws Exception {
        ContainerManager manager = new ContainerManager(this);
        ContentsManager contents = new ContentsManager(this);
        Container container = null;
        for (Container existing : manager.getContainers()) {
            if (wineVersion.equals(existing.getWineVersion())) {
                container = existing;
                break;
            }
        }
        if (container == null) {
            JSONObject data = new JSONObject();
            data.put("name", "RomCloud");
            data.put("wineVersion", wineVersion);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            data.put("emulator", emulatorFor(contents, wineVersion));
            container = manager.createContainer(data, contents);
            if (container == null) return null;
        }
        // Conteneur créé sans ces réglages, ou configuration de DXVK écrasée par celle de DirectDraw
        // (défaut de Winlator corrigé dans Container.loadData) : complété.
        String dxConfig = container.getDXWrapperConfig();
        if (dxConfig == null || !dxConfig.contains("version=")) {
            container.setDXWrapperConfig(Container.DEFAULT_DXWRAPPERCONFIG);
        }
        if (container.getEmulator() == null || container.getEmulator().isEmpty()) {
            container.setEmulator(emulatorFor(contents, container.getWineVersion()));
        }
        container.saveData();
        return container;
    }

    /** Émulateur des programmes x86 : FEXCore pour Proton arm64ec, Box64 sinon (comme l'écran de Winlator). */
    private String emulatorFor(ContentsManager contents, String wineVersion) {
        return WineInfo.fromIdentifier(this, contents, wineVersion).isArm64EC() ? "fexcore" : "box64";
    }

    /** Profil « RomCloud Xbox » installé (ou mis à jour) parmi les profils de Winlator, après ceux d'origine. */
    private void installXboxProfile() throws IOException {
        new InputControlsManager(this).getProfiles();
        File target = ControlsProfile.getProfileFile(this, XBOX_PROFILE);
        try (InputStream in = getAssets().open("romcloud/xbox.icp"); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    /**
     * Manette physique branchée : sticks (joystick), ou boutons de manette sur un périphérique externe.
     * Les boutons intégrés qui se déclarent manette (capteur d'empreintes « uinput-fpc » des Xiaomi)
     * ne comptent pas.
     */
    private static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            boolean external = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && device.isExternal();
            if (joystick || (gamepad && external)) return true;
        }
        return false;
    }

    /** Lecteurs [drives] (format de Winlator « D:/chemin E:/chemin ») avec [letter] vers [path]. */
    static String drives(String drives, char letter, String path) {
        StringBuilder result = new StringBuilder();
        int index = drives.indexOf(':');
        while (index > 0) {
            int next = drives.indexOf(':', index + 1);
            char current = drives.charAt(index - 1);
            String target = drives.substring(index + 1, next != -1 ? next - 1 : drives.length());
            if (Character.toUpperCase(current) != Character.toUpperCase(letter)) result.append(current).append(':').append(target);
            index = next;
        }
        return result.append(letter).append(':').append(path).toString();
    }

    /** Chemin Windows de [file] : D: s'il est dans Téléchargements, sinon lecteur des ROMs. */
    static String winePath(File file, File downloads, File romsDir) {
        String path = file.getAbsolutePath();
        String inDownloads = relative(path, downloads.getAbsolutePath());
        if (inDownloads != null) return "D:\\" + inDownloads.replace('/', '\\');
        String inRoms = relative(path, romsDir.getAbsolutePath());
        return ROMS_DRIVE + ":\\" + (inRoms != null ? inRoms : file.getName()).replace('/', '\\');
    }

    private static String relative(String path, String root) {
        String base = root.endsWith("/") ? root : root + "/";
        return path.startsWith(base) ? path.substring(base.length()) : null;
    }

    /** Raccourci du jeu (format des raccourcis de Wine relu par Winlator) avec ses réglages. */
    private File writeShortcut(Container container, String title, String winePath, Bundle settings) throws IOException {
        String name = title != null && !title.isEmpty() ? title.replace('\n', ' ') : new File(winePath.replace('\\', '/')).getName();
        String escaped = winePath.replace("\\", "\\\\\\\\").replace(" ", "\\ ");
        String exeName = winePath.substring(winePath.lastIndexOf('\\') + 1);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("container_id", String.valueOf(container.id));
        // Manette Xbox à l'écran : écran tactile sans manette branchée (une manette branchée reste le joueur 1).
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) && !hasGamepad()) {
            extra.put("controlsProfile", String.valueOf(XBOX_PROFILE));
        }
        if (settings != null) for (String key : settings.keySet()) {
            String value = settings.getString(key);
            if (value != null) extra.put(key, value.replace('\n', ' '));
        }
        File dir = new File(getFilesDir(), "shortcuts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File file = new File(dir, Integer.toHexString(winePath.hashCode()) + ".desktop");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write("[Desktop Entry]\n");
            out.write("Name=" + name + "\n");
            out.write("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine " + escaped + "\n");
            out.write("Type=Application\n");
            out.write("StartupNotify=true\n");
            out.write("StartupWMClass=" + exeName.toLowerCase() + "\n\n");
            out.write("[Extra Data]\n");
            for (Map.Entry<String, String> entry : extra.entrySet()) out.write(entry.getKey() + "=" + entry.getValue() + "\n");
        }
        return file;
    }
}
