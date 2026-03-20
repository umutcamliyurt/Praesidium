package com.nemesis.praesidium;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Praesidium";

    private static final int BOOT_DELAY_MS = 60_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed — starting service in " + BOOT_DELAY_MS + "ms");
            final Context appContext = context.getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent serviceIntent = new Intent(appContext, PraesidiumService.class);
                appContext.startForegroundService(serviceIntent);
                Log.d(TAG, "PraesidiumService started after boot delay");
            }, BOOT_DELAY_MS);
        }
    }
}