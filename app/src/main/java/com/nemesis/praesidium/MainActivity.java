package com.nikolas-trey.praesidium;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQUEST_ENABLE_ADMIN = 1;
    private static final String TAG = "Praesidium";

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private EmergencyActions emergencyActions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdminReceiver.class);
        emergencyActions = new EmergencyActions(this);

        startForegroundService(new Intent(this, PraesidiumService.class));

        if (!dpm.isAdminActive(adminComponent)) {
            requestAdminPermission();
        }

        setupEmergencyButtons();
        updateStatus();

        findViewById(R.id.btnActivate).setOnClickListener(v -> {
            if (!dpm.isAdminActive(adminComponent)) {
                requestAdminPermission();
            } else {
                confirmDeactivate();
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void setupEmergencyButtons() {
        findViewById(R.id.btnManualLock).setOnClickListener(v ->
                emergencyActions.lockDevice());

        findViewById(R.id.btnManualReboot).setOnClickListener(v ->
                confirmAction("Reboot", "Reboot the device now?",
                        () -> emergencyActions.rebootDevice()));

        findViewById(R.id.btnManualWipe).setOnClickListener(v ->
                confirmAction("⚠️ WIPE DEVICE",
                        "This will permanently erase ALL data. This cannot be undone.",
                        () -> emergencyActions.wipeDevice()));
    }

    private void confirmAction(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Confirm", (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeactivate() {
        String message = dpm.isDeviceOwnerApp(getPackageName())
                ? "This will remove Device Owner status AND admin privileges. " +
                "All security policies will be cleared and the service will stop protecting this device."
                : "This will remove admin privileges. " +
                "The service will no longer be able to lock, reboot, or wipe the device.";

        new AlertDialog.Builder(this)
                .setTitle("⚠️ Deactivate Praesidium")
                .setMessage(message)
                .setPositiveButton("Deactivate", (d, w) -> deactivate())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deactivate() {

        if (dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.clearDeviceOwnerApp(getPackageName());
                Log.d(TAG, "Device owner cleared");
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear device owner: " + e.getMessage());
            }
        }

        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.removeActiveAdmin(adminComponent);
                Log.d(TAG, "Admin removed");
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove admin: " + e.getMessage());
            }
        }

        stopService(new Intent(this, PraesidiumService.class));

        updateStatus();
    }

    private void requestAdminPermission() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Praesidium requires device administrator access for emergency actions.");
        startActivityForResult(intent, REQUEST_ENABLE_ADMIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_ADMIN) {
            if (dpm.isAdminActive(adminComponent)) {
                applyPasswordPolicy();
            }
            updateStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dpm.isAdminActive(adminComponent)) {
            applyPasswordPolicy();
        }
        updateStatus();
    }

    private void applyPasswordPolicy() {
        try {
            dpm.setPasswordQuality(adminComponent,
                    DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
            Log.d(TAG, "Password quality policy set");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set password quality: " + e.getMessage());
        }
    }

    private void updateStatus() {
        TextView tvStatus = findViewById(R.id.tvStatus);
        View statusDot = findViewById(R.id.statusDot);
        boolean adminActive = dpm.isAdminActive(adminComponent);
        boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());

        findViewById(R.id.btnManualLock).setEnabled(adminActive);
        findViewById(R.id.btnManualReboot).setEnabled(adminActive);
        findViewById(R.id.btnManualWipe).setEnabled(adminActive);

        ((android.widget.Button) findViewById(R.id.btnActivate))
                .setText(adminActive ? "Deactivate" : "Activate");

        if (!adminActive) {
            tvStatus.setText("Admin permission required");
            statusDot.setBackgroundResource(R.drawable.status_dot_red);
        } else if (!isDeviceOwner) {
            tvStatus.setText("Active — reboot/wipe requires device owner");
            statusDot.setBackgroundResource(R.drawable.status_dot_yellow);
        } else {
            tvStatus.setText("Fully armed — monitoring active");
            statusDot.setBackgroundResource(R.drawable.status_dot);
        }
    }
}