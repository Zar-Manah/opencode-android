package com.opencode.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OpenCode: Device control bridge. HTTP server at 127.0.0.1:4399 for `pc` CLI. */
public class DeviceBridgeService extends AccessibilityService {

    private static final String TAG = "DeviceBridge";
    private static final int PORT = 4399;

    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private static volatile DeviceBridgeService instance;

    static boolean isOn() { return instance != null; }

    static boolean isEnabledInSettings(Context c) {
        try {
            String flat = Settings.Secure.getString(
                c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String me = c.getPackageName() + "/" + DeviceBridgeService.class.getName();
            String shortMe = c.getPackageName() + "/.DeviceBridgeService";
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)
                    || s.endsWith("/com.opencode.android.DeviceBridgeService")) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean bridgeReady(Context c) {
        return isOn() || isEnabledInSettings(c);
    }

    @Override
    public void onServiceConnected() {
        instance = this;
        new Thread(this::serve).start();
        Log.i(TAG, "Bridge listening on port " + PORT);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null) {
            String p = pkg.toString().toLowerCase();
            if (p.contains("packageinstaller") || p.contains("installer") || p.contains("systemui")) {
                tryClickInstallButton();
            }
        }
    }

    @Override
    public void onInterrupt() {}

    private void serve() {
        try {
            server = new ServerSocket(PORT, 5,
                java.net.InetAddress.getByName("127.0.0.1"));
            while (server != null && !server.isClosed()) {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            Log.e(TAG, "Bridge server stopped", e);
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s;
             InputStream in = sock.getInputStream();
             OutputStream out = sock.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return;
            String req = new String(buf, 0, n, StandardCharsets.UTF_8);
            String line = req.split("\r\n")[0];
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            Map<String, String> q = query(path);
            byte[] body;
            String type = "application/json";
            int code = 200;
            try {
                switch (route) {
                    case "/ping": body = ok("bridge"); break;
                    case "/dump": body = dump().getBytes(StandardCharsets.UTF_8); type = "text/xml"; break;
                    case "/tap": tap(num(q, "x"), num(q, "y")); body = ok("tap"); break;
                    case "/swipe": swipe(num(q, "x1"), num(q, "y1"), num(q, "x2"), num(q, "y2"), num(q, "ms", 300)); body = ok("swipe"); break;
                    case "/text": typeText(str(q, "t")); body = ok("text"); break;
                    case "/key": key(str(q, "k")); body = ok("key"); break;
                    case "/open": open(str(q, "p")); body = ok("open"); break;
                    case "/notify": notify(str(q, "m"), str(q, "t")); body = ok("notify"); break;
                    case "/install": install(str(q, "p")); body = installResult().getBytes(StandardCharsets.UTF_8); break;
                    case "/shell": body = shell(str(q, "c")).getBytes(StandardCharsets.UTF_8); type = "text/plain"; break;
                    case "/list": body = apps(str(q, "f")).getBytes(StandardCharsets.UTF_8); break;
                    case "/shot": body = shot(); type = "image/png"; break;
                    default: code = 404; body = err("unknown route"); break;
                }
            } catch (Exception e) {
                code = 500; body = err(e.getMessage());
            }
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
        } catch (Exception ignored) {}
    }

    private static byte[] ok(String s) {
        return ("{\"ok\":true,\"r\":\"" + s + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] err(String s) {
        if (s == null) s = "?";
        return ("{\"ok\":false,\"error\":\"" + s.replace("\"", "'") + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String path) {
        Map<String, String> m = new HashMap<>();
        int i = path.indexOf('?');
        if (i < 0) return m;
        for (String kv : path.substring(i + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) {
                try {
                    m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private static int num(Map<String, String> q, String k) { return num(q, k, 0); }

    private static int num(Map<String, String> q, String k, int d) {
        try { return Integer.parseInt(q.get(k)); } catch (Exception e) { return d; }
    }

    private static String str(Map<String, String> q, String k) {
        String v = q.get(k);
        return v == null ? "" : v;
    }

    private void tap(int x, int y) throws Exception {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(p, 0, 80);
    }

    private void swipe(int x1, int y1, int x2, int y2, int ms) throws Exception {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        dispatch(p, 0, Math.max(ms, 80));
    }

    private void dispatch(Path path, long start, long dur) throws Exception {
        final Exception[] err = new Exception[1];
        final boolean[] done = new boolean[1];
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            try {
                GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, start, dur))
                    .build();
                dispatchGesture(g, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { done[0] = true; }
                    @Override public void onCancelled(GestureDescription d) { done[0] = true; }
                }, null);
            } catch (Exception e) { err[0] = e; done[0] = true; }
        });
        long t = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - t < 5000) Thread.sleep(50);
        if (err[0] != null) throw err[0];
    }

    private void typeText(final String text) throws Exception {
        final Exception[] err = new Exception[1];
        final boolean[] done = new boolean[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo target = focusedEditable(root);
                if (target == null) throw new RuntimeException("no focused field");
                android.os.Bundle b = new android.os.Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("pc", text));
                    target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                }
            } catch (Exception e) { err[0] = e; }
            done[0] = true;
        });
        long t = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - t < 5000) Thread.sleep(50);
        if (err[0] != null) throw err[0];
    }

    private AccessibilityNodeInfo focusedEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && (n.isFocused() || n.isAccessibilityFocused())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = focusedEditable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    private void key(String k) {
        int a = -1;
        switch (k.toUpperCase()) {
            case "BACK": a = GLOBAL_ACTION_BACK; break;
            case "HOME": a = GLOBAL_ACTION_HOME; break;
            case "RECENTS": a = GLOBAL_ACTION_RECENTS; break;
            case "NOTIFICATIONS": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "POWER": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "LOCK": a = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "SPLIT": a = GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN; break;
            default: throw new RuntimeException("Unknown key: " + k);
        }
        if (!performGlobalAction(a)) throw new RuntimeException("key action failed");
    }

    private void open(String p) {
        Intent i;
        if (p.startsWith("http") || p.contains("://")) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(p));
        } else {
            i = getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) throw new RuntimeException("no app " + p);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private String shell(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "(empty)";
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1 && total < 200000) {
                o.write(buf, 0, n);
                total += n;
            }
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "(timeout)\n" + o.toString("UTF-8"); }
            return "exit=" + p.exitValue() + "\n" + o.toString("UTF-8");
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private void install(String apkPath) {
        File apk = new File(apkPath);
        if (!apk.exists()) throw new RuntimeException("no apk found at " + apkPath);

        // Check REQUEST_INSTALL_PACKAGES permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                throw new RuntimeException("REQUEST_INSTALL_PACKAGES permission not granted. Opened Settings.");
            }
        }

        // Delete previous install result marker
        try {
            File res = new File(getFilesDir(), "server-home/last-install.txt");
            if (res.exists()) res.delete();
        } catch (Exception ignored) {}

        android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams params =
            new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setRequireUserAction(
                    android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            } catch (Throwable ignored) {}
        }

        android.content.pm.PackageInstaller.Session s = null;
        try {
            int id = pi.createSession(params);
            s = pi.openSession(id);
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("apk", 0, apk.length());
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            s.fsync(out);
            in.close();
            out.close();

            Intent intent = new Intent(this, InstallResultReceiver.class);
            intent.setAction("com.opencode.android.ACTION_INSTALL_COMMIT");
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= android.app.PendingIntent.FLAG_MUTABLE;
            }
            android.app.PendingIntent pi2 = android.app.PendingIntent.getBroadcast(this, id, intent, flags);
            s.commit(pi2.getIntentSender());
            s.close();
            s = null;
            Log.i(TAG, "Install session " + id + " committed for " + apkPath);
        } catch (Exception e) {
            if (s != null) {
                try { s.abandon(); } catch (Exception ignored) {}
            }
            Log.e(TAG, "PackageInstaller session error: " + e.getMessage(), e);
            throw new RuntimeException("install: " + e.getMessage());
        }
    }

    /** Background watcher that automatically confirms package installation dialogs. */
    public static void autoClickPackageInstaller() {
        new Thread(() -> {
            Log.i(TAG, "autoClickPackageInstaller watcher started");
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 15000) {
                DeviceBridgeService svc = instance;
                if (svc != null) {
                    try {
                        if (svc.tryClickInstallButton()) {
                            Log.i(TAG, "autoClickPackageInstaller: Successfully clicked install button!");
                            break;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "autoClickPackageInstaller check error: " + e.getMessage());
                    }
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
            Log.i(TAG, "autoClickPackageInstaller watcher finished");
        }).start();
    }

    private boolean tryClickInstallButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence pkg = root.getPackageName();
            String pkgStr = pkg != null ? pkg.toString().toLowerCase() : "";
            if (pkgStr.contains("packageinstaller") || pkgStr.contains("installer")
                || pkgStr.contains("systemui") || pkgStr.contains("android")) {

                // 1. By resource IDs common to Android PackageInstaller
                String[] candidateIds = new String[]{
                    "android:id/button1",
                    "com.android.packageinstaller:id/ok_button",
                    "com.google.android.packageinstaller:id/ok_button",
                    "com.android.packageinstaller:id/install_confirm_button",
                    "com.google.android.packageinstaller:id/install_confirm_button",
                    "com.android.packageinstaller:id/install_button",
                    "com.google.android.packageinstaller:id/install_button"
                };
                for (String cid : candidateIds) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(cid);
                    if (nodes != null && !nodes.isEmpty()) {
                        for (AccessibilityNodeInfo n : nodes) {
                            if (n.isEnabled() && clickNodeOrParent(n)) {
                                return true;
                            }
                        }
                    }
                }

                // 2. By common localized text
                String[] candidateTexts = new String[]{
                    "Install", "Instalar", "Update", "Actualizar", "Aceptar", "OK"
                };
                for (String txt : candidateTexts) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(txt);
                    if (nodes != null && !nodes.isEmpty()) {
                        for (AccessibilityNodeInfo n : nodes) {
                            if (n.isEnabled() && clickNodeOrParent(n)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isClickable()) {
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo parent = n.getParent();
        if (parent != null) {
            boolean clicked = clickNodeOrParent(parent);
            try { parent.recycle(); } catch (Exception ignored) {}
            return clicked;
        }
        return false;
    }

    private String installResult() {
        try {
            File res = new File(getFilesDir(), "server-home/last-install.txt");
            long t = System.currentTimeMillis();
            while (!res.exists() && System.currentTimeMillis() - t < 120000) {
                Thread.sleep(500);
            }
            if (!res.exists()) {
                return "{\"ok\":false,\"error\":\"install-timeout\"}";
            }
            InputStream in = new FileInputStream(res);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            in.close();
            String raw = o.toString("UTF-8").trim();

            int status = -999;
            String pkg = null;
            String msg = null;
            for (String part : raw.split(" ")) {
                if (part.startsWith("status=")) {
                    try { status = Integer.parseInt(part.substring(7)); } catch (Exception ignored) {}
                } else if (part.startsWith("pkg=")) {
                    pkg = part.substring(4);
                } else if (part.startsWith("msg=")) {
                    msg = part.substring(4);
                }
            }
            boolean ok = (status == 0); // PackageInstaller.STATUS_SUCCESS = 0
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("status", status);
            if (pkg != null) json.put("pkg", pkg);
            if (msg != null) json.put("msg", msg);
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void notify(String msg, String title) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String ch = "pc";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(ch, "OpenCode",
                NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info);
        if (title != null && !title.isEmpty()) b.setContentTitle(title);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    private String apps(String filter) {
        StringBuilder sb = new StringBuilder("[");
        List<ApplicationInfo> list = getPackageManager()
            .getInstalledApplications(PackageManager.GET_META_DATA);
        boolean first = true;
        for (ApplicationInfo a : list) {
            String label = String.valueOf(getPackageManager().getApplicationLabel(a));
            if (filter != null && !filter.isEmpty()
                && !a.packageName.contains(filter) && !label.contains(filter)) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"pkg\":\"").append(a.packageName).append("\",\"label\":\"")
                .append(label.replace("\"", "'")).append("\"}");
        }
        return sb.append("]").toString();
    }

    private String dump() {
        final String[] out = new String[1];
        final boolean[] done = new boolean[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                StringBuilder sb = new StringBuilder("<hierarchy>");
                xml(getRootInActiveWindow(), sb, 0);
                out[0] = sb.append("</hierarchy>").toString();
            } catch (Exception e) { out[0] = "<hierarchy/>"; }
            done[0] = true;
        });
        long t = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - t < 8000) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
        return out[0] == null ? "<hierarchy/>" : out[0];
    }

    private void xml(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 12) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        CharSequence txt = n.getText();
        CharSequence desc = n.getContentDescription();
        sb.append("<node class=\"").append(cls(n)).append("\"")
            .append(" bounds=\"").append(r.flattenToString()).append("\"");
        if (txt != null) sb.append(" text=\"").append(esc(txt)).append("\"");
        if (desc != null) sb.append(" desc=\"").append(esc(desc)).append("\"");
        if (n.isClickable()) sb.append(" clickable=\"true\"");
        sb.append(">");
        for (int i = 0; i < n.getChildCount(); i++) xml(n.getChild(i), sb, depth + 1);
        sb.append("</node>");
    }

    private static String cls(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        String s = c == null ? "?" : c.toString();
        int d = s.lastIndexOf('.');
        return d >= 0 ? s.substring(d + 1) : s;
    }

    private static String esc(CharSequence s) {
        return s.toString().replace("&", "&amp;").replace("<", "&lt;")
            .replace("\"", "&quot;").replace("\n", " ");
    }

    private byte[] shot() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new RuntimeException("shot requires Android 11+");
        }
        final byte[][] out = new byte[1][];
        final Exception[] err = new Exception[1];
        final boolean[] done = new boolean[1];
        takeScreenshot(Display.DEFAULT_DISPLAY,
            pool, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        Bitmap bm = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(),
                            r.getColorSpace());
                        Bitmap copy = bm.copy(Bitmap.Config.ARGB_8888, false);
                        r.getHardwareBuffer().close();
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        copy.compress(Bitmap.CompressFormat.PNG, 90, o);
                        copy.recycle();
                        out[0] = o.toByteArray();
                    } catch (Exception e) { err[0] = e; }
                    done[0] = true;
                }
                @Override public void onFailure(int code) {
                    err[0] = new RuntimeException("Screenshot failed with code " + code);
                    done[0] = true;
                }
            });
        long t = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - t < 10000) Thread.sleep(50);
        if (err[0] != null) throw err[0];
        if (out[0] == null) throw new RuntimeException("Screenshot returned empty buffer");
        return out[0];
    }
}
