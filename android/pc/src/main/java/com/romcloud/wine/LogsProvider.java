package com.romcloud.wine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Journaux de RomCloud PC pour RomCloud (content://com.romcloud.wine.logs, méthode [call]) ; seul un
 * appelant signé avec la même clé est accepté (comme pour le lancement d'un jeu, [LaunchActivity]).
 * « pending » : { running } partie en cours, ou { title, text } rapport du plantage de la dernière
 * partie (rendu une seule fois), ou rien ; « log » : { text } journal de la dernière partie.
 */
public class LogsProvider extends ContentProvider {
    public static final String METHOD_PENDING = "pending";
    public static final String METHOD_LOG = "log";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage();
        if (caller == null || getContext().getPackageManager().checkSignatures(caller, getContext().getPackageName()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("caller " + caller);
        }
        Bundle result = new Bundle();
        if (METHOD_PENDING.equals(method)) {
            if (SessionLog.running(getContext())) {
                result.putBoolean("running", true);
            } else {
                String[] report = SessionLog.takePending(getContext());
                if (report != null) {
                    result.putString("title", report[0]);
                    result.putString("text", report[1]);
                }
            }
        } else if (METHOD_LOG.equals(method)) {
            result.putString("text", SessionLog.lastLog(getContext()));
        } else {
            throw new IllegalArgumentException("method " + method);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
