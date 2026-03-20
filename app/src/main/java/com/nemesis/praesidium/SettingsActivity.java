package com.nemesis.praesidium;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "praesidium_prefs";

    private static final int MIN_FAILED_UNLOCK_THRESHOLD = 3;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadSettings();
        setupSaveButton();
    }

    private void loadSettings() {
        ((Switch) findViewById(R.id.switchAdb)).setChecked(
                prefs.getBoolean("detect_adb", true));

        ((Switch) findViewById(R.id.switchBruteForce)).setChecked(
                prefs.getBoolean("detect_brute_force", true));
        ((EditText) findViewById(R.id.etFailedAttempts)).setText(
                String.valueOf(prefs.getInt("failed_unlock_threshold", 5)));

        ((Switch) findViewById(R.id.switchFdSpike)).setChecked(
                prefs.getBoolean("detect_fd_spike", true));

        loadThreatAction("action_adb",         R.id.spinnerAdbAction);
        loadThreatAction("action_brute_force", R.id.spinnerBruteForceAction);
        loadThreatAction("action_fd_spike",    R.id.spinnerFdSpikeAction);
    }

    private void loadThreatAction(String key, int spinnerId) {
        String action = prefs.getString(key, PraesidiumService.ACTION_LOCK);
        android.widget.Spinner spinner = findViewById(spinnerId);
        String[] actions = {
                PraesidiumService.ACTION_LOCK,
                PraesidiumService.ACTION_REBOOT,
                PraesidiumService.ACTION_WIPE
        };
        for (int i = 0; i < actions.length; i++) {
            if (actions[i].equals(action)) {
                spinner.setSelection(i);
                break;
            }
        }
    }

    private void setupSaveButton() {
        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> {

            int threshold;
            try {
                threshold = Integer.parseInt(
                        ((EditText) findViewById(R.id.etFailedAttempts))
                                .getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid number entered", Toast.LENGTH_SHORT).show();
                return;
            }

            if (threshold < MIN_FAILED_UNLOCK_THRESHOLD) {
                Toast.makeText(this,
                        "Minimum threshold is " + MIN_FAILED_UNLOCK_THRESHOLD
                                + " — value adjusted",
                        Toast.LENGTH_SHORT).show();
                threshold = MIN_FAILED_UNLOCK_THRESHOLD;
                ((EditText) findViewById(R.id.etFailedAttempts))
                        .setText(String.valueOf(threshold));
            }

            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean("detect_adb",
                    ((Switch) findViewById(R.id.switchAdb)).isChecked());
            editor.putBoolean("detect_brute_force",
                    ((Switch) findViewById(R.id.switchBruteForce)).isChecked());
            editor.putBoolean("detect_fd_spike",
                    ((Switch) findViewById(R.id.switchFdSpike)).isChecked());

            editor.putInt("failed_unlock_threshold", threshold);

            saveThreatAction("action_adb",         R.id.spinnerAdbAction,         editor);
            saveThreatAction("action_brute_force",  R.id.spinnerBruteForceAction,  editor);
            saveThreatAction("action_fd_spike",     R.id.spinnerFdSpikeAction,     editor);

            editor.apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void saveThreatAction(String key, int spinnerId,
                                  SharedPreferences.Editor editor) {
        String[] actions = {
                PraesidiumService.ACTION_LOCK,
                PraesidiumService.ACTION_REBOOT,
                PraesidiumService.ACTION_WIPE
        };
        int pos = ((android.widget.Spinner) findViewById(spinnerId))
                .getSelectedItemPosition();

        if (pos >= 0 && pos < actions.length) {
            editor.putString(key, actions[pos]);
        } else {
            editor.putString(key, PraesidiumService.ACTION_LOCK);
        }
    }
}