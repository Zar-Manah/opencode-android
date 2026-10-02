package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * OpenCode: System boot receiver.
 * The native OpenAccessService is automatically resumed by Android on each boot.
 * This receiver handles silent background scheduling.
 */
public class OpenBootReceiver extends BroadcastReceiver {

    public static boolean tryEnableAdbWifi(Context context) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            android.content.ContentResolver cr = context.getContentResolver();
            android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            OpenAlarmReceiver.scheduleDailyDream(context);
            tryEnableAdbWifi(context);
        } catch (Exception ignored) {}
    }
}
