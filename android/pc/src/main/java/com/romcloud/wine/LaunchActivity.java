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
import android.util.Log;
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
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.xenvironment.ImageFs;
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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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
     * qui plantent sous Box64 avec Proton x86_64). Jeux Unity IL2CPP : Proton x86_64 ([UNITY_WINE]).
     */
    public static final String SETTING_WINE = "wineVersion";

    /**
     * Proton des jeux Unity compilés en IL2CPP (UnityPlayer.dll et GameAssembly.dll) : x86_64, tout
     * traduit par Box64 (BOX64_UNITYPLAYER). En arm64ec, ils se bloquent avant ou sur l'écran titre
     * (Castlevania: Belmont's Curse, Unity 2022.3 : rendu ou chargement qui ne s'achève jamais),
     * quels que soient le pilote Vulkan et la version de DXVK.
     */
    private static final String UNITY_WINE = "proton-9.0-x86_64";

    /** Qualité graphique au démarrage choisie dans RomCloud ([QUALITIES] : [qualityArgs]). */
    static final String SETTING_QUALITY = "romcloudQuality";
    /** Images par seconde au plus choisies dans RomCloud (limite de DXVK, dans sa configuration). */
    static final String SETTING_FRAMERATE = "romcloudFramerate";
    /** Shaders compilés en arrière-plan (« async ») : DXVK async ou gplasync ([asyncDxvk]). */
    static final String SETTING_SHADERS = "romcloudShaders";
    /** Émulation du processeur : « intermediate » ou « performance » ([applyCpu]) ; absent : compatibilité. */
    static final String SETTING_CPU = "romcloudCpu";
    /**
     * DirectX 12 choisi dans RomCloud : « vkd3d » (VKD3D-Proton), « wine » (celui de Wine, avec DXVK) ;
     * absent : VKD3D-Proton pour les jeux DirectX 12 reconnus ([isDx12]).
     */
    static final String SETTING_DX12 = "romcloudDx12";
    /** VKD3D-Proton de RomCloud PC (archive « dxwrapper/vkd3d-<version>.tzst » de Winlator Cmod). */
    static final String VKD3D_VERSION = "2.14.1-0";
    /** Paquet AdrenoTools du pilote choisi dans RomCloud, s'il n'est pas inclus ([installDriver]). */
    static final String SETTING_DRIVER_URL = "adrenotoolsDriverUrl";
    /** Pilote par défaut sur Adreno (celui de RomCloud), si le pilote choisi n'a pas pu être installé. */
    private static final String DEFAULT_DRIVER_CONFIG = "version=turnip25.3.0_R3_Auto;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal";

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
            String wineVersion = requested != null && requested.getString(SETTING_WINE) != null ? requested.getString(SETTING_WINE)
                    : isUnityIl2cpp(new File(launch.getStringExtra(EXTRA_EXE)).getParentFile()) ? UNITY_WINE : DEFAULT_WINE;
            Container container = container(wineVersion);
            if (container == null) {
                fail(getString(R.string.romcloud_container_failed));
                return;
            }
            Intent intent = getIntent();
            File chosen = new File(intent.getStringExtra(EXTRA_EXE));
            File exe = unrealShipping(chosen);
            File romsDir = new File(intent.getStringExtra(EXTRA_ROMS_DIR) != null ? intent.getStringExtra(EXTRA_ROMS_DIR) : chosen.getParent());
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            container.setDrives(drives(container.getDrives(), ROMS_DRIVE, romsDir.getAbsolutePath()));
            container.saveData();
            // Réglages du jeu choisis dans RomCloud : pilote téléchargé installé, qualité graphique en
            // arguments du jeu selon son moteur (réglages propres à RomCloud retirés du raccourci).
            Bundle settings = requested != null ? new Bundle(requested) : new Bundle();
            installDriver(settings);
            String quality = qualityArgs(exe, settings.getString(SETTING_QUALITY));
            String framerate = settings.getString(SETTING_FRAMERATE);
            boolean asyncShaders = "async".equals(settings.getString(SETTING_SHADERS));
            String cpu = settings.getString(SETTING_CPU);
            boolean vkd3d = useVkd3d(exe, settings.getString(SETTING_DX12));
            settings.remove(SETTING_DX12);
            settings.remove(SETTING_QUALITY);
            settings.remove(SETTING_DRIVER_URL);
            settings.remove(SETTING_FRAMERATE);
            settings.remove(SETTING_SHADERS);
            settings.remove(SETTING_CPU);
            boolean arm64ec = container.getWineVersion().contains("arm64ec");
            // Limite d'images par seconde, shaders en arrière-plan : dans la configuration de DXVK du
            // jeu (choisie dans RomCloud), sinon dans celle du conteneur.
            boolean limit = framerate != null && framerate.matches("[1-9][0-9]{0,2}");
            if (limit || asyncShaders || vkd3d) {
                String config = settings.getString("dxwrapperConfig");
                if (config == null || config.isEmpty()) config = container.getDXWrapperConfig();
                if (limit) config = withConfig(config, "framerate", framerate);
                if (asyncShaders && !vkd3d) {
                    config = withConfig(config, "version", asyncDxvk(configValue(config, "version"), arm64ec));
                    config = withConfig(withConfig(config, "async", "1"), "asyncCache", "1");
                    settings.putString("dxwrapper", "dxvk");
                }
                if (vkd3d) {
                    // DirectX 12 par VKD3D-Proton (DirectX 9 à 11 : DXVK 2.4.1, fourni avec lui).
                    config = withConfig(withConfig(config, "vkd3dVersion", VKD3D_VERSION), "vkd3dLevel", "12_1");
                    settings.putString("dxwrapper", "vkd3d");
                    settings.putString("envVars", withEnv(settings.getString("envVars"), vkd3dEnv(limit ? framerate : null)));
                }
                settings.putString("dxwrapperConfig", config);
            }
            applyCpu(container, exe.getName(), arm64ec, cpu, settings);
            if (quality != null) {
                String args = settings.getString("execArgs");
                settings.putString("execArgs", args != null && !args.isEmpty() ? args + " " + quality : quality);
            }
            File shortcut = writeShortcut(container, intent.getStringExtra(EXTRA_TITLE), winePath(exe, downloads, romsDir), settings);
            // Journal de la partie (lu par RomCloud : plantage signalé au serveur, rapport d'erreur).
            SessionLog.start(this, intent.getStringExtra(EXTRA_TITLE), exe.getAbsolutePath(), container.getWineVersion());
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
     * Pilote Vulkan choisi dans RomCloud qui n'est pas inclus (réglage [SETTING_DRIVER_URL] : paquet
     * AdrenoTools) : téléchargé et installé au premier usage. Échec : pilote par défaut, le jeu se lance.
     */
    private void installDriver(Bundle settings) {
        String url = settings.getString(SETTING_DRIVER_URL);
        String id = driverId(settings.getString("graphicsDriverConfig"));
        if (url == null || id == null) return;
        AdrenotoolsManager drivers = new AdrenotoolsManager(this);
        if (drivers.enumarateInstalledDrivers().contains(id)) return;
        show(R.string.romcloud_driver_downloading, -1);
        File zip = new File(getCacheDir(), "driver.zip");
        try {
            OkHttpClient client = new OkHttpClient.Builder().followRedirects(true).build();
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                }
            }
            String installed = drivers.installDriver(Uri.fromFile(zip));
            if (!id.equals(installed)) throw new IOException("pilote " + installed + " au lieu de " + id);
        } catch (IOException e) {
            Log.w("RomCloud", "Pilote " + id + " non installé, pilote par défaut : " + e.getMessage());
            settings.putString("graphicsDriverConfig", DEFAULT_DRIVER_CONFIG);
        } finally {
            zip.delete();
        }
        show(R.string.romcloud_preparing, -1);
    }

    /** Pilote de « graphicsDriverConfig » (« version=…;… ») ; null sans pilote. */
    static String driverId(String config) {
        if (config == null) return null;
        for (String part : config.split(";")) {
            if (part.startsWith("version=")) return part.substring("version=".length());
        }
        return null;
    }

    /**
     * Arguments du jeu [exe] pour la qualité graphique [quality] ([QUALITIES]) selon son moteur : Unity
     * (niveau de qualité nommé Very Low, Low, Medium ou High, ignoré si le jeu n'en a pas de ce nom ;
     * minimum et verylow : Very Low), Unreal Engine (réglages sg.* de GameUserSettings.ini remplacés au
     * démarrage, au plus bas niveau sous low, avec une résolution de rendu réduite) ; null sinon.
     */
    static String qualityArgs(File exe, String quality) {
        int index = java.util.Arrays.asList(QUALITIES).indexOf(quality);
        if (index < 0 || exe == null) return null;
        File dir = exe.getParentFile();
        if (dir != null && new File(dir, "UnityPlayer.dll").isFile()) return "-screen-quality " + UNITY_QUALITIES[index];
        if (isUnreal(exe)) {
            int level = Math.max(index - 2, 0);
            StringBuilder args = new StringBuilder("-ini:GameUserSettings:[ScalabilityGroups]:sg.ResolutionQuality=" + UNREAL_RESOLUTIONS[index]);
            for (String group : UNREAL_GROUPS) args.append(",[ScalabilityGroups]:").append(group).append('=').append(level);
            return args.toString();
        }
        return null;
    }

    /** Qualités graphiques de RomCloud ([SETTING_QUALITY]), de la plus basse à la plus haute. */
    static final String[] QUALITIES = {"minimum", "verylow", "low", "medium", "high"};
    private static final String[] UNITY_QUALITIES = {"\"Very Low\"", "\"Very Low\"", "Low", "Medium", "High"};
    /** Résolution de rendu (%) des jeux Unreal Engine pour chaque qualité (interface toujours nette). */
    private static final int[] UNREAL_RESOLUTIONS = {33, 50, 75, 90, 100};

    /** Configuration de DXVK [config] (« version=…,framerate=0,… ») avec [key] à [value]. */
    static String withConfig(String config, String key, String value) {
        if (config == null || config.isEmpty()) return key + "=" + value;
        String pattern = "(^|,)" + java.util.regex.Pattern.quote(key) + "=[^,]*";
        if (java.util.regex.Pattern.compile(pattern).matcher(config).find()) {
            return config.replaceFirst(pattern, "$1" + java.util.regex.Matcher.quoteReplacement(key + "=" + value));
        }
        return config + "," + key + "=" + value;
    }

    /** Valeur de [key] dans la configuration de DXVK [config], ou null. */
    static String configValue(String config, String key) {
        if (config == null) return null;
        for (String part : config.split(",")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        }
        return null;
    }

    /**
     * Version de DXVK qui compile les shaders en arrière-plan, de la même famille que [version] :
     * gardée si elle le fait déjà ; 1.x (celle par défaut, la plus compatible) : dxvk-async 1.10.3 ;
     * 2.x : gplasync (arm64ec : celle compilée pour arm64ec).
     */
    static String asyncDxvk(String version, boolean arm64ec) {
        if (version != null && version.contains("async")) return version;
        if (version == null || version.startsWith("1.")) return "async-1.10.3";
        return arm64ec ? "2.3.1-arm64ec-gplasync" : "2.4.1-gplasync";
    }

    /**
     * Émulation du processeur choisie dans RomCloud ([cpu] : « intermediate », « performance » ; autre :
     * compatibilité, le réglage de Winlator). Proton x86_64 : préréglage de Box64 du raccourci. Proton
     * arm64ec : réglage de FEX propre à l'exécutable [exeName] (AppConfig), retiré en compatibilité.
     */
    private void applyCpu(Container container, String exeName, boolean arm64ec, String cpu, Bundle settings) {
        boolean performance = "performance".equals(cpu);
        boolean faster = performance || "intermediate".equals(cpu);
        if (!arm64ec) {
            if (faster) settings.putString("box64Preset", performance ? Box86_64Preset.PERFORMANCE : Box86_64Preset.INTERMEDIATE);
            return;
        }
        ImageFs imageFs = ImageFs.find(new File(getFilesDir(), "imagefs"));
        File config = new File(imageFs.home_path + "-" + container.id + "/.fex-emu/AppConfig/" + exeName + ".json");
        if (!faster) {
            config.delete();
            return;
        }
        File parent = config.getParentFile();
        if (parent != null) parent.mkdirs();
        // TSO : ordre des accès à la mémoire comme sur x86 (lent) ; « Fast » : seulement le nécessaire.
        FEXCoreManager.writeToConfigFile(config, performance ? "Fastest" : "Fast", "Enabled", "Fast");
    }

    private static final String[] UNREAL_GROUPS = {"sg.ViewDistanceQuality", "sg.AntiAliasingQuality", "sg.ShadowQuality", "sg.PostProcessQuality",
            "sg.TextureQuality", "sg.EffectsQuality", "sg.FoliageQuality", "sg.ShadingQuality"};

    /**
     * Jeu Unreal Engine : exécutable « …-Win64-Shipping.exe » (dossier Binaries/Win64), ou lanceur à la
     * racine du jeu, à côté du dossier Engine.
     */
    static boolean isUnreal(File exe) {
        String name = exe.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith("-shipping.exe")) return true;
        File dir = exe.getParentFile();
        return dir != null && new File(dir, "Engine/Binaries").isDirectory();
    }

    /**
     * Exécutable à lancer pour [exe] : lanceur Unreal Engine à la racine du jeu (à côté du dossier
     * Engine) remplacé par le jeu lui-même, « <Projet>/Binaries/Win64/…-Shipping.exe » ; le lanceur
     * exige le Visual C++ Redistributable installé, alors que les DLL de Wine suffisent au jeu. Sinon [exe].
     */
    static File unrealShipping(File exe) {
        File dir = exe.getParentFile();
        if (dir == null || exe.getName().toLowerCase(java.util.Locale.ROOT).endsWith("-shipping.exe")
                || !new File(dir, "Engine/Binaries").isDirectory()) return exe;
        String base = exe.getName().replaceFirst("(?i)\\.exe$", "").toLowerCase(java.util.Locale.ROOT);
        File found = null;
        File[] projects = dir.listFiles(File::isDirectory);
        if (projects == null) return exe;
        java.util.Arrays.sort(projects);
        for (File project : projects) {
            if (project.getName().equalsIgnoreCase("Engine")) continue;
            File[] shipping = new File(project, "Binaries/Win64").listFiles((d, name) ->
                    name.toLowerCase(java.util.Locale.ROOT).endsWith("-shipping.exe"));
            if (shipping == null) continue;
            java.util.Arrays.sort(shipping);
            for (File candidate : shipping) {
                // Celui du même nom que le lanceur d'abord (« Jeu.exe » : « Jeu-Win64-Shipping.exe »).
                if (candidate.getName().toLowerCase(java.util.Locale.ROOT).startsWith(base + "-")) return candidate;
                if (found == null) found = candidate;
            }
        }
        return found != null ? found : exe;
    }

    /**
     * DirectX 12 par VKD3D-Proton : choisi dans RomCloud ([dx12] « vkd3d »), ou automatique (absent)
     * pour un jeu DirectX 12 ([isDx12]) ; « wine » : DirectX 12 de Wine (DXVK pour le reste).
     */
    static boolean useVkd3d(File exe, String dx12) {
        if (dx12 != null) return "vkd3d".equals(dx12);
        return isDx12(exe);
    }

    /**
     * Jeu DirectX 12 : DirectX 12 Agility SDK livré avec le jeu (« D3D12/D3D12Core.dll » à côté de
     * l'exécutable, jeux Unreal Engine 5 compris). Celui de Wine (vkd3d d'origine) n'annonce pas le
     * Shader Model 6.6 qu'ils exigent (Frostpunk 2 : « feature level >= 12.0, shader model >= 6.6 »).
     */
    static boolean isDx12(File exe) {
        File dir = exe.getParentFile();
        return dir != null && new File(dir, "D3D12/D3D12Core.dll").isFile();
    }

    /**
     * Variables de VKD3D-Proton : Shader Model 6.6 annoncé (les pilotes Turnip des Adreno 6xx n'ont pas
     * toutes les fonctions qu'il demande, les jeux qui l'exigent refusent sinon de démarrer) ; limite
     * d'images par seconde [framerate] (null : aucune).
     */
    static String vkd3dEnv(String framerate) {
        return "VKD3D_SHADER_MODEL=6_6" + (framerate != null ? " VKD3D_FRAME_RATE=" + framerate : "");
    }

    /** Variables d'environnement du raccourci [env] (« A=1 B=2 ») avec celles de [added] (remplacées). */
    static String withEnv(String env, String added) {
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
        for (String list : new String[]{env, added}) {
            if (list == null) continue;
            for (String part : list.trim().split(" +")) {
                int eq = part.indexOf('=');
                if (eq > 0) vars.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
            if (out.length() > 0) out.append(' ');
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    /** Jeu Unity compilé en IL2CPP : UnityPlayer.dll et GameAssembly.dll à côté de l'exécutable. */
    static boolean isUnityIl2cpp(File gameDir) {
        return gameDir != null && new File(gameDir, "UnityPlayer.dll").isFile() && new File(gameDir, "GameAssembly.dll").isFile();
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
