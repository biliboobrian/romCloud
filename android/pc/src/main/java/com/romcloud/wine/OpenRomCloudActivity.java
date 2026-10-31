package com.romcloud.wine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.winlator.cmod.R;

/**
 * Icône de RomCloud PC dans le lanceur : sans elle, les espaces jeux des constructeurs (Game Turbo de
 * Xiaomi, Game Booster de Samsung…) ne la proposent pas, et le mode performances ne s'applique pas aux
 * jeux PC. Les jeux se lancent depuis RomCloud : l'icône ouvre RomCloud (téléphone, sinon TV).
 */
public class OpenRomCloudActivity extends Activity {
    private static final String[] APPS = {"com.romcloud.app", "com.romcloud.app.tv"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent open = null;
        for (String app : APPS) {
            open = getPackageManager().getLaunchIntentForPackage(app);
            if (open == null) open = getPackageManager().getLeanbackLaunchIntentForPackage(app);
            if (open != null) break;
        }
        if (open != null) {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            Toast.makeText(this, R.string.romcloud_open_missing, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
