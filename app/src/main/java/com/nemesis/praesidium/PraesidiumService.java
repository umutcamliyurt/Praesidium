package com.nemesis.praesidium;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PraesidiumService extends Service {

    private static final String TAG = "Praesidium";
    private static final String CHANNEL_ID = "praesidium_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "praesidium_prefs";
    private static final String KEY_RESPONSE_ENABLED = "response_enabled";
    private static final String KEY_DEFAULT_ACTION = "default_action";
    private static final int POLL_INTERVAL_MS = 1000;

    private static final long MIN_UPTIME_BEFORE_ACTION_MS = 180_000;

    private static final long BRUTE_FORCE_GRACE_PERIOD_MS = 300_000;

    private static final int WIPE_CONFIRMATION_COUNT = 10;
    private static final int REBOOT_CONFIRMATION_COUNT = 5;
    private static final int INITIAL_EVALUATION_DELAY_MS = 5000;

    public static final String ACTION_LOCK   = "lock";
    public static final String ACTION_REBOOT = "reboot";
    public static final String ACTION_WIPE   = "wipe";

    private Handler handler;
    private EmergencyActions emergencyActions;
    private ThreatDetectionService threatDetection;

    private Set<String> previousThreats = Collections.emptySet();
    private boolean calibrationComplete = false;

    private final Map<String, Integer> threatConfirmationCount = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        emergencyActions = new EmergencyActions(this);
        threatDetection = new ThreatDetectionService(this);
        handler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            threatDetection.calibrateBaselines();
            calibrationComplete = true;
            Log.d(TAG, "Calibration complete — polling starts in " + INITIAL_EVALUATION_DELAY_MS + "ms");
            handler.postDelayed(this::startPolling, INITIAL_EVALUATION_DELAY_MS);
        }, "praesidium-calibration").start();
    }

    private void startPolling() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                evaluateThreats();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        });
    }

    private void evaluateThreats() {
        if (!calibrationComplete) return;

        long uptime = SystemClock.elapsedRealtime();
        if (uptime < MIN_UPTIME_BEFORE_ACTION_MS) {
            Log.d(TAG, "Uptime " + uptime + "ms < minimum " + MIN_UPTIME_BEFORE_ACTION_MS + "ms — skipping");
            return;
        }

        Set<String> current = threatDetection.detectThreats();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_RESPONSE_ENABLED, true);

        for (String threat : previousThreats) {
            if (!current.contains(threat)) {
                threatConfirmationCount.remove(threat);
                Log.d(TAG, "Threat cleared: " + threat + " — confirmation counter reset");
            }
        }

        for (String threat : current) {

            if ("brute_force".equals(threat) && uptime < BRUTE_FORCE_GRACE_PERIOD_MS) {
                Log.d(TAG, "Brute-force threat suppressed during grace period (uptime=" + uptime + "ms)");
                continue;
            }

            int count = threatConfirmationCount.getOrDefault(threat, 0) + 1;
            threatConfirmationCount.put(threat, count);

            if (!previousThreats.contains(threat)) {
                Log.d(TAG, "Threat onset: " + threat + " (confirmation 1)");
            } else {
                Log.d(TAG, "Threat persisting: " + threat + " (confirmation " + count + ")");
            }

            if (enabled) {
                String actionKey = threatToActionKey(threat);
                String action = safeGetAction(prefs, actionKey);

                if ("adb_connected".equals(threat) && !ACTION_LOCK.equals(action)) {
                    Log.w(TAG, "ADB threat action '" + action
                            + "' demoted to 'lock' to prevent boot loop");
                    action = ACTION_LOCK;
                }

                if (shouldExecuteAction(action, count)) {
                    Log.w(TAG, "Executing action '" + action + "' for threat '" + threat
                            + "' after " + count + " confirmations");

                    if ("brute_force".equals(threat)) {
                        resetBruteForceCounter(true );
                    }

                    executeAction(action);
                    threatConfirmationCount.put(threat, 0);
                } else {
                    Log.d(TAG, "Action '" + action + "' for threat '" + threat
                            + "' pending confirmation (" + count + "/" + requiredCount(action) + ")");
                }
            }
        }

        previousThreats = current;
    }

    private boolean shouldExecuteAction(String action, int confirmationCount) {
        return confirmationCount >= requiredCount(action);
    }

    private int requiredCount(String action) {
        switch (action) {
            case ACTION_REBOOT: return REBOOT_CONFIRMATION_COUNT;
            case ACTION_WIPE:   return WIPE_CONFIRMATION_COUNT;
            default:            return 1;
        }
    }

    private String safeGetAction(SharedPreferences prefs, String actionKey) {
        String action = prefs.getString(actionKey,
                prefs.getString(KEY_DEFAULT_ACTION, ACTION_LOCK));
        if (action == null || action.isEmpty()) return ACTION_LOCK;
        switch (action) {
            case ACTION_LOCK:
            case ACTION_REBOOT:
            case ACTION_WIPE:
                return action;
            default:
                Log.w(TAG, "Unrecognised action '" + action + "' — defaulting to lock");
                return ACTION_LOCK;
        }
    }

    private void executeAction(String action) {
        switch (action) {
            case ACTION_LOCK:
                Log.d(TAG, "Action: lock");
                emergencyActions.lockDevice();
                break;
            case ACTION_REBOOT:
                Log.d(TAG, "Action: reboot");
                emergencyActions.rebootDevice();
                break;
            case ACTION_WIPE:
                Log.d(TAG, "Action: wipe");
                emergencyActions.wipeDevice();
                break;
            default:
                Log.w(TAG, "Unknown action in executeAction — locking instead");
                emergencyActions.lockDevice();
                break;
        }
    }

    private String threatToActionKey(String threat) {
        switch (threat) {
            case "adb_connected": return "action_adb";
            case "brute_force":   return "action_brute_force";
            case "adb_fd_spike":  return "action_fd_spike";
            default:              return "action_" + threat;
        }
    }

    private void resetBruteForceCounter(boolean synchronous) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt("failed_unlock_cumulative", 0);
        if (synchronous) {
            editor.commit();
        } else {
            editor.apply();
        }
        Log.d(TAG, "Brute-force counter reset (synchronous=" + synchronous + ")");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "com.nemesis.praesidium.ACTION_LOCK":
                    emergencyActions.lockDevice();
                    break;
                case "com.nemesis.praesidium.ACTION_REBOOT":
                    emergencyActions.rebootDevice();
                    break;
                case "com.nemesis.praesidium.ACTION_WIPE":
                    emergencyActions.wipeDevice();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Praesidium")
                .setContentText("Guardian active — monitoring threats")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Praesidium Service", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}