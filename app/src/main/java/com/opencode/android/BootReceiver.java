package com.opencode.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** OpenCode: reanuda el servidor tras reiniciar el teléfono solo si 24/7 está activado. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE);
            boolean is24_7 = prefs.getBoolean("pref_24_7", false);
            if (is24_7) {
                Intent i = new Intent(context, OpenCodeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i);
                } else {
                    context.startService(i);
                }
            }
        }
    }
}
