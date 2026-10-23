package com.winlator.cmod.xenvironment;

import com.romcloud.wine.ContentPack;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 24;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    public static void installWineFromAssets(final MainActivity activity) {
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        File rootDir = ImageFs.find(activity).getRootDir();
        for (String version : versions) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile);
        }
    }

    public static void installFromAssets(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.txz") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, "imagefs.txz", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                installWineFromAssets(activity);
//                installGuestLibs(activity); // If evshim.tzst ends up being used
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });
    }

    public static void installFromAssets(final MainActivity activity, final Runnable onCompletion) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 24;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.txz") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, "imagefs.txz", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });



            if (success) {
                installWineFromAssets(activity);
//                installGuestLibs(activity); // If evshim.tzst ends up being used
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
            if (onCompletion != null) {
                activity.runOnUiThread(onCompletion);
            }
        });
    }

    /** Système Linux à installer (absent ou d'une version précédente) : RomCloud PC, sans écran de Winlator. */
    public static boolean isInstallNeeded(Context context) {
        ImageFs imageFs = ImageFs.find(context);
        return !imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION;
    }

    /**
     * Installe le système Linux et Proton (assets téléchargés par ContentPack) sur le fil appelant ;
     * [progress] : pourcentage. RomCloud PC (LaunchActivity), sans écran de Winlator.
     */
    public static boolean installSync(Context context, java.util.function.IntConsumer progress) {
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        android.content.SharedPreferences.Editor editor = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.remove("current_box64_version").remove("current_wowbox64_version").remove("current_fexcore_version").apply();
        clearRootDir(rootDir);
        final long contentLength = (long)(FileUtils.getSize(context, "imagefs.txz") * (100.0f / 24));
        AtomicLong totalSizeRef = new AtomicLong();
        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, "imagefs.txz", rootDir, (file, size) -> {
            if (size > 0) progress.accept((int) Math.min(99, totalSizeRef.addAndGet(size) * 100 / Math.max(1, contentLength)));
            return file;
        });
        if (!success) return false;
        for (String version : context.getResources().getStringArray(R.array.wine_entries)) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, version + ".txz", outFile)) return false;
        }
        imageFs.createImgVersionFile(LATEST_VERSION);
        resetContainerImgVersions(context);
        progress.accept(100);
        return true;
    }

    public static void installIfNeeded(final MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) installFromAssets(activity);
    }

    public static void installIfNeeded(final MainActivity activity, final Runnable onCompletion) {
        ImageFs imageFs = ImageFs.find(activity);

        // Fresh Install. The imagefs is not valid/doesn't exist.
        if (!imageFs.isValid()) {
            // Silently install without showing a warning dialog.
            installFromAssets(activity, onCompletion);
        }
        // Update. The imagefs exists but is an old version.
        else if (imageFs.getVersion() < LATEST_VERSION) {
            // Show the warning dialog because the user is updating.
            String htmlMessageString = activity.getString(R.string.system_update_warning);
            CharSequence formattedMessage;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                formattedMessage = Html.fromHtml(htmlMessageString, Html.FROM_HTML_MODE_LEGACY);
            } else {
                //noinspection deprecation
                formattedMessage = Html.fromHtml(htmlMessageString);
            }
            new AlertDialog.Builder(activity)
                    .setTitle("System Files Update Required")
                    .setMessage(formattedMessage)
                    .setCancelable(false)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        installFromAssets(activity, onCompletion);
                    })
                    .show();
        }
        // Already Up-to-Date.
        else if (onCompletion != null) {
            onCompletion.run();
        }
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    private static void installGuestLibs(Context ctx) {
        final String ASSET_TAR = "evshim.tzst";          // ➊  add this to assets/
        File imagefs = new File(ctx.getFilesDir(), "imagefs");
        // ➋  Unpack straight into imagefs, preserving relative paths.
        try (InputStream in  = ContentPack.open(ctx, ASSET_TAR)) {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,      // you said .tzst
                    in, imagefs);                      // helper already exists in the project
        } catch (IOException e) {
            Log.e("ImageFsInstaller", "evshim deploy failed", e);
            return;
        }

        // ➌  Make sure the new libs are world-readable / executable
        chmod(new File(imagefs, "lib/libevshim.so"));
        chmod(new File(imagefs, "lib/libSDL2.so"));
        chmod(new File(imagefs, "lib/libSDL2-2.0.so"));
        chmod(new File(imagefs, "lib/libSDL2-2.0.so.0"));
    }
    private static void chmod(File f) { if (f.exists()) FileUtils.chmod(f, 0755);}
}