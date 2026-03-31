package com.nikolas-trey.praesidium;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public class EmergencyActions {

    private static final String TAG = "Praesidium";
    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName adminComponent;

    public EmergencyActions(Context context) {
        this.context = context;
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(context, DeviceAdminReceiver.class);
    }

    public boolean isAdminActive() {
        return dpm != null && dpm.isAdminActive(adminComponent);
    }

    public boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public void lockDevice() {
        if (isAdminActive()) {
            Log.d(TAG, "Locking device");
            dpm.lockNow();
        } else {
            Log.e(TAG, "lockDevice: admin not active — cannot lock");
        }
    }

    public void rebootDevice() {
        if (isDeviceOwner()) {
            Log.d(TAG, "Rebooting device");
            dpm.reboot(adminComponent);
        } else {
            Log.e(TAG, "rebootDevice: device owner required — falling back to lock");
            lockDevice();
        }
    }

    public void wipeDevice() {
        if (isDeviceOwner()) {
            Log.d(TAG, "Wiping device");
            dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE |
                    DevicePolicyManager.WIPE_RESET_PROTECTION_DATA);
        } else {
            Log.e(TAG, "wipeDevice: device owner required — falling back to lock");
            lockDevice();
        }
    }
}