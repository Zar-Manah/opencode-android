package com.termux.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.util.Calendar;

/**
 * OpenCode: Native wake-up alarm via Android AlarmManager.
 * Executes scheduled tasks (nightly consolidation, memory maintenance, crons)
 * waking the device briefly without keeping a persistent 24/7 service or preventing deep sleep.
 */
public class OpenAlarmReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "OpenAlarmReceiver";
    public static final String ACTION_RUN_DREAM = "com.termux.app.ACTION_RUN_DREAM";
    public static final String ACTION_RUN_CRON = "com.termux.app.ACTION_RUN_CRON";
    private static final int REQUEST_CODE_DREAM = 43991;

    public static void scheduleDailyDream(Context context) {
        if (context == null) return;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, OpenAlarmReceiver.class);
            intent.setAction(ACTION_RUN_DREAM);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE_DREAM, intent, flags);

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 3);
            cal.set(Calendar.MINUTE, 30);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
            Log.i(LOG_TAG, "Daily dream maintenance scheduled for: " + cal.getTime());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Error scheduling daily dream: " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        Log.i(LOG_TAG, "Alarm received: " + action);

        // Reschedule immediately for the next day
        scheduleDailyDream(context);

        if (ACTION_RUN_DREAM.equals(action) || ACTION_RUN_CRON.equals(action)) {
            final PendingResult pendingResult = goAsync();
            new Thread(() -> {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = null;
                if (pm != null) {
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCode:DreamWakeLock");
                    // Safety timeout of 2 minutes to prevent battery drain if anything hangs
                    wl.acquire(120_000);
                }

                try {
                    File prootBin = new File(com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro");
                    String dreamExecPath = "/root/.opencode/dream.sh";
                    File dreamScript = null;
                    String[] possibleDebianRoots = new String[]{
                        com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian",
                        com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs",
                        com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian"
                    };
                    for (String root : possibleDebianRoots) {
                        File candidate = new File(root, "root/.opencode/dream.sh");
                        if (candidate.exists()) {
                            dreamScript = candidate;
                            break;
                        }
                    }

                    if (prootBin.exists() && dreamScript != null) {
                        Log.i(LOG_TAG, "Executing OpenCode dream consolidation in background...");
                        ProcessBuilder pb = new ProcessBuilder(
                            prootBin.getAbsolutePath(),
                            "login",
                            "debian",
                            "--",
                            "/bin/bash",
                            dreamExecPath
                        );
                        pb.environment().put("PATH", com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/bin:/usr/bin");
                        pb.environment().put("HOME", com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH);
                        pb.redirectErrorStream(true);

                        Process p = pb.start();
                        // Maximum wait of 90 seconds
                        boolean finished = p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
                        if (!finished) {
                            p.destroyForcibly();
                            Log.w(LOG_TAG, "Dream script exceeded time limit and was terminated.");
                        } else {
                            Log.i(LOG_TAG, "Dream completed with exit code: " + p.exitValue());
                        }
                    } else {
                        Log.w(LOG_TAG, "Debian environment or dream script not found.");
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error executing dream: " + e.getMessage(), e);
                } finally {
                    if (wl != null && wl.isHeld()) {
                        try {
                            wl.release();
                        } catch (Exception ignored) {}
                    }
                    pendingResult.finish();
                    Log.i(LOG_TAG, "Maintenance job finished. Returning device to sleep.");
                }
            }).start();
        }
    }
}
