package com.nemesis.praesidium;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    private static final String TAG = "Praesidium";
    private static final String PREFS_NAME = "praesidium_prefs";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.d(TAG, "Device admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.d(TAG, "Device admin disabled");
    }

    public static final String ACTION_PASSWORD_FAILED    = "com.nemesis.praesidium.PASSWORD_FAILED";
    public static final String ACTION_PASSWORD_SUCCEEDED = "com.nemesis.praesidium.PASSWORD_SUCCEEDED";
    public static final String EXTRA_FAILED_COUNT        = "failed_count";

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt("failed_unlock_cumulative", 0) + 1;

        prefs.edit().putInt("failed_unlock_cumulative", count).commit();
        Log.d(TAG, "onPasswordFailed fired — cumulative=" + count);

        Intent notify = new Intent(ACTION_PASSWORD_FAILED);
        notify.putExtra(EXTRA_FAILED_COUNT, count);
        notify.setPackage(context.getPackageName());

        context.sendBroadcast(notify);
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        Log.d(TAG, "onPasswordSucceeded fired — resetting counter");
        resetCount(context);

        Intent notify = new Intent(ACTION_PASSWORD_SUCCEEDED);
        notify.setPackage(context.getPackageName());
        context.sendBroadcast(notify);
    }

    private void resetCount(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("failed_unlock_cumulative", 0).commit();
    }
}