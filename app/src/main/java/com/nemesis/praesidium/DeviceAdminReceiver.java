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

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt("failed_unlock_cumulative", 0) + 1;

        prefs.edit().putInt("failed_unlock_cumulative", count).commit();
        Log.d(TAG, "onPasswordFailed fired — cumulative=" + count);
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        Log.d(TAG, "onPasswordSucceeded fired — resetting counter");
        resetCount(context);
    }

    private void resetCount(Context context) {

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("failed_unlock_cumulative", 0).commit();
    }
}