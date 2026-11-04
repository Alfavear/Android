package com.alvear.psl;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Abre otra app a partir de un enlace psl://open?pkg=com.ejemplo.app. El mando del teléfono lo envía
 * al TV; no muestra pantalla propia.
 */
public class OpenActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri u = getIntent().getData();
        String pkg = u == null ? null : u.getQueryParameter("pkg");
        if (pkg != null) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                getSharedPreferences("recent", MODE_PRIVATE).edit()
                        .putLong(pkg, System.currentTimeMillis()).apply();
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }
        finish();
    }
}
