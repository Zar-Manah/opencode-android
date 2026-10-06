package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** OpenCode: Package installation broadcast receiver with user confirmation handling. */
public class InstallResultReceiver extends BroadcastReceiver {
    private static final String TAG = "InstallResult";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        Log.i(TAG, "Install broadcast received: status=" + status + " pkg=" + pkg + " msg=" + msg);

        // 1. User confirmation required by Android PackageInstaller
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = null;
            if (Build.VERSION.SDK_INT >= 33) {
                confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                confirmIntent = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmIntent);
                    Log.i(TAG, "Successfully launched user confirmation activity");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch user confirmation activity", e);
                }
            } else {
                Log.w(TAG, "STATUS_PENDING_USER_ACTION received without EXTRA_INTENT");
            }

            // Launch accessibility watcher to automatically click "Install" / "Update"
            DeviceBridgeService.autoClickPackageInstaller();
            return;
        }

        // 2. Terminal state: write result for DeviceBridgeService
        String line = "status=" + status + " pkg=" + (pkg != null ? pkg : "")
            + " msg=" + (msg != null ? msg.replace("\n", " ") : (status == PackageInstaller.STATUS_SUCCESS ? "ok" : "err")) + "\n";
        try {
            File home = new File(context.getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(home, "last-install.txt"));
            o.write(line.getBytes("UTF-8"));
            o.close();
            Log.i(TAG, "Saved last-install.txt: " + line.trim());
        } catch (Exception e) {
            Log.e(TAG, "Write last-install.txt failed", e);
        }

        // 3. User notification
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "install";
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(ch, "OpenCode",
                    NotificationManager.IMPORTANCE_DEFAULT));
            }
            if (nm != null) {
                Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(context, ch) : new Notification.Builder(context);
                boolean ok = (status == PackageInstaller.STATUS_SUCCESS);
                b.setContentTitle(ok ? "App installed successfully" : "Installation failed (" + status + ")")
                    .setContentText(msg == null ? (pkg == null ? "" : pkg) : msg)
                    .setSmallIcon(android.R.drawable.ic_dialog_info);
                nm.notify(2, b.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification failed", e);
        }
    }
}
