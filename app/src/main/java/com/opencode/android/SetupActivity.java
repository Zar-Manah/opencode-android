package com.opencode.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** OpenCode: Guided initial setup (accessibility, files, unknown sources). Run once. */
public class SetupActivity extends Activity {

    private static final String BUILD = "build 26";

    static boolean needed(Context c) {
        return !DeviceBridgeService.bridgeReady(c) || !filesOk() || !unknownOk(c);
    }

    static boolean unknownOk(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean filesOk() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            || Environment.isExternalStorageManager();
    }

    static boolean restrictedAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager)
                c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            return ops.unsafeCheckOpNoThrow("android:access_restricted_settings",
                android.os.Process.myUid(), c.getPackageName())
                == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openUnknownSources() {
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e1) {
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        Intent[] candidates = new Intent[]{
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())),
            new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            new Intent(Settings.ACTION_APPLICATION_SETTINGS),
            new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent it : candidates) {
            try {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return;
            } catch (Exception ignored) {}
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent it = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e1) {
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private LinearLayout rows;
    private TextView status;
    private final android.os.Handler handler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean alive;
    private boolean launched;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("OpenCode");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("To grant OpenCode full device control (taps, keys, screen capture, and app installation), follow these steps: [" + BUILD + "]");
        sub.setTextColor(0xFF9CA3AF);
        sub.setPadding(0, 8, 0, 24);
        root.addView(sub);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        status = new TextView(this);
        status.setTextColor(0xFF9CA3AF);
        status.setPadding(0, 16, 0, 0);
        root.addView(status);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        launched = false;
        refresh();
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        if (!alive || launched) return;
        rows.removeAllViews();
        boolean live = DeviceBridgeService.isOn();
        boolean bridge = DeviceBridgeService.bridgeReady(this);
        boolean files = filesOk();
        boolean unknown = unknownOk(this);
        boolean listed = !bridge && DeviceBridgeService.isEnabledInSettings(this);

        rows.addView(brow("OpenCode",
            "Tap the button and select Downloaded apps and then OpenCode. At first you'll get a message that you can't select it, but it's important you tap it anyway so the three dots from the next step appear, then return here.",
            bridge, "Downloaded apps → OpenCode",
            v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        if (listed) {
            rows.addView(note("Shown as enabled but not connected (normal after reinstall): turn it off and on."));
        }
        if (Build.VERSION.SDK_INT >= 33 && !bridge) {
            rows.addView(brow("App info",
                "Tap the button and then tap the 3 dots (⋮) in the top right corner, and select 'Allow restricted settings'. Then go back and now it will let you select OpenCode.",
                false, "Restricted settings",
                v -> openAppDetails()));
        }
        rows.addView(brow("Allow files",
            "Tap the button to grant permission to manage all files. Then return here; it continues automatically. The first time wait a couple of minutes and OpenCode terminal interface will automatically initialize.",
            files, "Manage all files",
            v -> openAllFilesSettings()));
        rows.addView(brow("Allow installs",
            "Tap the button to allow installing apps from OpenCode. Then return here; it continues automatically.",
            unknown, "Install unknown apps",
            v -> openUnknownSources()));

        if (status != null) {
            status.setText("Bridge: " + (live ? "connected"
                    : (bridge ? "enabled (connecting…)" : "waiting…"))
                + "  ·  Files: " + (files ? "ok" : "pending")
                + "  ·  Installs: " + (unknown ? "ok" : "pending"));
        }
        if (bridge && files && unknown) {
            launched = true;
            alive = false;
            handler.removeCallbacksAndMessages(null);
            finish();
            return;
        }
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::refresh, 1000);
    }

    private View brow(String btn, String desc, boolean done, String okLabel, View.OnClickListener go) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 12, 0, 12);
        TextView t = new TextView(this);
        t.setText((done ? "✓ " + okLabel + ". " : "") + desc);
        t.setTextColor(done ? 0xFF9CA3AF : Color.WHITE);
        t.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (!done) {
            Button b = mkButton(btn);
            b.setOnClickListener(go);
            r.addView(b);
        }
        r.addView(t, lp);
        return r;
    }

    private Button mkButton(String s) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackgroundColor(0xFF1F1F1F);
        b.setPadding(32, 20, 32, 20);
        return b;
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF9CA3AF);
        t.setPadding(0, 0, 0, 4);
        return t;
    }
}
