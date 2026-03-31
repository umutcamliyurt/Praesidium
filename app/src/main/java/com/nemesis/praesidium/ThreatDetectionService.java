package com.nikolas-trey.praesidium;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

public class ThreatDetectionService {

    private static final String TAG = "Praesidium";
    private static final String PREFS_NAME = "praesidium_prefs";

    private static final int BASELINE_SAMPLES = 5;
    private static final int BASELINE_SAMPLE_DELAY_MS = 500;

    // How many FDs above the baseline before we call it a spike.
    // Raised slightly from 10 to 15 to reduce false positives from normal
    // post-boot adbd activity settling.
    private static final int FD_SPIKE_THRESHOLD = 15;

    // Minimum number of baseline samples that must succeed for calibration to
    // be considered valid. If fewer samples succeed, FD spike detection is
    // disabled for this boot to avoid a false-positive → reboot loop.
    private static final int MIN_SUCCESSFUL_BASELINE_SAMPLES = 3;

    // How long after boot we refuse to flag an FD spike even if calibration
    // succeeded. adbd opens/closes FDs during early init, so the baseline
    // measured in calibrateBaselines() may be lower than steady-state.
    private static final long FD_SPIKE_BOOT_GRACE_MS = 120_000; // 2 minutes

    private final Context context;
    private final long serviceStartTime;

    private int baselineAdbdFds = -1;
    private boolean baselineReady = false;

    public ThreatDetectionService(Context context) {
        this.context = context;
        this.serviceStartTime = SystemClock.elapsedRealtime();
    }

    /**
     * Samples adbd's FD count several times to establish a baseline.
     * Must be called from a background thread (it sleeps between samples).
     */
    public void calibrateBaselines() {
        int initial = getAdbdFdCount();
        if (initial < 0) {
            Log.d(TAG, "adbd not running — FD spike detection disabled until next calibration");
            baselineAdbdFds = -1;
            baselineReady = false;
            return;
        }

        int total = 0;
        int successful = 0;
        for (int i = 0; i < BASELINE_SAMPLES; i++) {
            int sample = getAdbdFdCount();
            if (sample >= 0) {
                total += sample;
                successful++;
            }
            try {
                Thread.sleep(BASELINE_SAMPLE_DELAY_MS);
            } catch (InterruptedException ignored) {}
        }

        // Require a minimum number of good samples before trusting the baseline.
        // If too many samples failed, the baseline would be artificially low,
        // making normal adbd activity look like a spike.
        if (successful >= MIN_SUCCESSFUL_BASELINE_SAMPLES) {
            baselineAdbdFds = total / successful;
            baselineReady = true;
            Log.d(TAG, "Baseline calibrated: adbdFds=" + baselineAdbdFds
                    + " (averaged over " + successful + "/" + BASELINE_SAMPLES + " samples)");
        } else {
            baselineAdbdFds = -1;
            baselineReady = false;
            Log.w(TAG, "Baseline calibration failed (only " + successful
                    + " valid samples) — FD spike detection disabled");
        }
    }

    /**
     * Returns the set of currently active threat identifiers.
     * Safe to call from the main thread.
     */
    public Set<String> detectThreats() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> active = new HashSet<>();

        if (prefs.getBoolean("detect_adb", true) && isAdbEstablished())
            active.add("adb_connected");

        if (prefs.getBoolean("detect_brute_force", true) && isExcessiveFailedUnlocks(prefs))
            active.add("brute_force");

        if (prefs.getBoolean("detect_fd_spike", true) && isAdbdFdSpike())
            active.add("adb_fd_spike");

        if (!active.isEmpty()) Log.d(TAG, "Active threats: " + active);
        return active;
    }

    // -------------------------------------------------------------------------
    // Individual detectors
    // -------------------------------------------------------------------------

    private boolean isAdbEstablished() {
        // Check /proc/net/tcp and /proc/net/tcp6 for an ESTABLISHED (state=01)
        // connection on the ADB port (5555 = 0x15B3) or the ADB-over-USB port
        // used by some kernels (5037 = 0x13AD).
        for (String file : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 3) {
                        String localAddr = parts[1];
                        String state = parts[3];
                        if ((localAddr.endsWith(":15B3") || localAddr.endsWith(":13AD"))
                                && "01".equals(state)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isExcessiveFailedUnlocks(SharedPreferences prefs) {
        int cumulative = prefs.getInt("failed_unlock_cumulative", 0);
        // Enforce a minimum threshold of 3 regardless of what the user set,
        // so a threshold of 1 or 2 can't cause a wipe loop on any mistype.
        int threshold = Math.max(3, prefs.getInt("failed_unlock_threshold", 5));
        Log.d(TAG, "Failed unlocks cumulative=" + cumulative + " threshold=" + threshold);
        return cumulative >= threshold;
    }

    private boolean isAdbdFdSpike() {
        if (!baselineReady || baselineAdbdFds < 0) return false;

        // Suppress spike detection for a grace period after the service started
        // to let adbd settle. Early-boot FD counts are often higher than steady
        // state, so a low calibration baseline would produce instant false positives.
        long elapsed = SystemClock.elapsedRealtime() - serviceStartTime;
        if (elapsed < FD_SPIKE_BOOT_GRACE_MS) {
            Log.d(TAG, "FD spike check suppressed during boot grace period (elapsed=" + elapsed + "ms)");
            return false;
        }

        int current = getAdbdFdCount();
        if (current < 0) return false;

        boolean spike = current > baselineAdbdFds + FD_SPIKE_THRESHOLD;
        if (spike) {
            Log.w(TAG, "FD spike detected: current=" + current
                    + " baseline=" + baselineAdbdFds
                    + " threshold=" + FD_SPIKE_THRESHOLD);
        }
        return spike;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getAdbdFdCount() {
        try {
            java.io.File proc = new java.io.File("/proc");
            String[] pids = proc.list();
            if (pids == null) return -1;
            for (String pid : pids) {
                if (!pid.matches("\\d+")) continue;
                try (BufferedReader r = new BufferedReader(
                        new FileReader("/proc/" + pid + "/comm"))) {
                    String comm = r.readLine();
                    if ("adbd".equals(comm != null ? comm.trim() : "")) {
                        java.io.File fdDir = new java.io.File("/proc/" + pid + "/fd");
                        String[] fds = fdDir.list();
                        return fds != null ? fds.length : 0;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "getAdbdFdCount error: " + e.getMessage());
        }
        return -1;
    }
}