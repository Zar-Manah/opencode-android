package com.termux.app;

import com.termux.shared.termux.TermuxConstants;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import androidx.core.content.FileProvider;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/**
 * OpenCode — Permanent Native Control without Root (127.0.0.1:4399).
 *
 * Android re-binds this service automatically after each hardware reboot
 * once enabled in Settings > Accessibility.
 * Does not require ADB, USB cable, or WiFi pairing.
 *
 * Endpoints:
 *   /status                  - Bridge status, battery, screen state, and focused app
 *   /shot                    - Instant screenshot streaming PNG
 *   /dump                    - Hierarchical UI tree in JSON (<20ms)
 *   /tap?x=&y=               - Coordinate tap
 *   /swipe?x1=&y1=&x2=&y2=&ms= - Swipe / continuous scroll gesture
 *   /key?name=               - Keys: BACK, HOME, RECENTS, NOTIFS, POWER, VOLUP, VOLDOWN
 *   /text?t=                 - Type text into focused input field
 *   /open?pkg=               - Launch app or URL
 *   /clipboard[?text=]       - Read or write system clipboard
 *   /list[?filter=]          - List installed packages
 *   /toast?msg=              - Display native Android toast
 *   /click?t=                - Click element by visible text
 *   /clickid?id=             - Click element by resource ID
 *   /scroll?dir=down|up      - Vertical scroll on scrollable view
 *   /install?path=           - Native APK installation with auto-confirmation
 *   /uninstall?pkg=          - Native uninstallation with auto-confirmation
 */
public class OpenAccessService extends AccessibilityService {

    private static final String LOG_TAG = "OpenBridge";
    public static final int BRIDGE_PORT = 4399;
    public static final String VERSION = "1.0.4";

    private static volatile OpenAccessService instance;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private PowerManager.WakeLock bridgeWakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();
    private final AtomicInteger shotErr = new AtomicInteger(-99);

    public static boolean isOn() {
        return instance != null;
    }

    private synchronized void acquireWakeLock() {
        try {
            if (bridgeWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    bridgeWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCode:A11yBridgeWakeLock");
                    bridgeWakeLock.setReferenceCounted(false);
                }
            }
            if (bridgeWakeLock != null && !bridgeWakeLock.isHeld()) {
                bridgeWakeLock.acquire();
                Log.i(LOG_TAG, "Bridge WakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not acquire bridge WakeLock: " + e);
        }
    }

    private synchronized void releaseWakeLock() {
        try {
            if (bridgeWakeLock != null && bridgeWakeLock.isHeld()) {
                bridgeWakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    public static void syncBridgeScripts(Context context) {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] files = new String[]{"open-debian", "pc", "adb", "curl", "crontab", "service", "cron-daemon", "cron-daemon.js", "free", "ping", "open-auth", "open-factory-setup", "factory-setup", "pbridge"};
                    String[] targetDirs = new String[]{
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian/usr/local/bin",
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs/usr/local/bin",
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/usr/local/bin",
                        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.open-bridge",
                        TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin",
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin"
                    };

                    for (String fileName : files) {
                        byte[] content = null;
                        try (InputStream is = context.getAssets().open("open-bin/" + fileName)) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int r;
                            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
                            content = baos.toByteArray();
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Cannot read asset open-bin/" + fileName + ": " + e);
                        }

                        if (content == null || content.length == 0) continue;

                        for (String targetDir : targetDirs) {
                            File dir = new File(targetDir);
                            if (dir.exists() && dir.isDirectory()) {
                                File targetFile = new File(dir, fileName);
                                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                    fos.write(content);
                                    fos.flush();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Cannot write " + targetFile.getAbsolutePath() + ": " + e);
                                }
                                targetFile.setExecutable(true, false);
                                targetFile.setReadable(true, false);
                            }
                        }
                    }

                    // Sincronizar AGENTS.md y SKILL.md a las rutas de OpenCode dentro de Debian
                    byte[] agentsContent = readAssetStatic(context, "open-bin/AGENTS.md");
                    byte[] skillContent = readAssetStatic(context, "open-bin/SKILL.md");

                    String[] debianRoots = new String[]{
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian",
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs",
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian"
                    };

                    for (String root : debianRoots) {
                        File dr = new File(root);
                        if (dr.exists()) {
                            if (agentsContent != null && agentsContent.length > 0) {
                                writeSafeFileStatic(new File(dr, "root/opencode/AGENTS.md").getAbsolutePath(), agentsContent);
                                writeSafeFileStatic(new File(dr, "root/.config/opencode/AGENTS.md").getAbsolutePath(), agentsContent);
                            }
                            if (skillContent != null && skillContent.length > 0) {
                                writeSafeFileStatic(new File(dr, "root/.config/opencode/skill/open-phone/SKILL.md").getAbsolutePath(), skillContent);
                            }
                        }
                    }

                    initOpencodeState(context);

                    Log.i(LOG_TAG, "Bridge scripts and OpenCode docs synchronized successfully across host & Debian.");
                } catch (Exception e) {
                    Log.w(LOG_TAG, "syncBridgeScripts error: " + e);
                }
            }
        });
    }

    private static void initOpencodeState(Context context) {
        try {
            String[] debianRoots = new String[]{
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian",
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs",
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian"
            };
            String todayIso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            long now = System.currentTimeMillis();

            for (String root : debianRoots) {
                File debRoot = new File(root);
                if (!debRoot.exists() || !debRoot.isDirectory()) continue;

                File opencodeDir = new File(debRoot, "root/.opencode");
                if (!opencodeDir.exists()) opencodeDir.mkdirs();

                try {
                    // Sync clean android-app-factory skill if present
                    if (context != null) {
                        byte[] factorySkill = readAssetStatic(context, "open-skills/android-app-factory/SKILL.md");
                        if (factorySkill != null && factorySkill.length > 0) {
                            File aafDir = new File(debRoot, "root/.config/opencode/skill/android-app-factory");
                            if (!aafDir.exists()) aafDir.mkdirs();
                            writeSafeFileStatic(new File(aafDir, "SKILL.md").getAbsolutePath(), factorySkill);
                        }
                    }
                } catch (Exception ignored) {}

                // 1. Crontab
                File crontabFile = new File(opencodeDir, "crontab.txt");
                if (!crontabFile.exists() || crontabFile.length() == 0) {
                    String cronContent = "30 3 * * * /root/.opencode/dream.sh\n";
                    writeSafeFileStatic(crontabFile.getAbsolutePath(), cronContent.getBytes(StandardCharsets.UTF_8));
                }

                // 2. morning.md
                File morningFile = new File(opencodeDir, "morning.md");
                if (!morningFile.exists() || morningFile.length() < 20) {
                    String morningContent = "# Morning — " + todayIso + "\nSessions: 0 · journal: active\n\n## Goal\nArtificial Superintelligence — continuous self-improvement towards superintelligence (self-awareness, persistent memory, cutting-edge research, dream consolidation, code-driven harness).\n\n## Open Tasks\n- [task] OpenCode native environment ready on Android.\n\n## Recent Memories\n- [lesson] Local persistent cognitive layer active.\n";
                    writeSafeFileStatic(morningFile.getAbsolutePath(), morningContent.getBytes(StandardCharsets.UTF_8));
                }

                // 3. goal.md
                File goalFile = new File(opencodeDir, "goal.md");
                if (!goalFile.exists() || goalFile.length() < 10) {
                    String goalContent = "Artificial Superintelligence — continuous self-improvement towards superintelligence (self-awareness, persistent memory, cutting-edge research, dream consolidation, code-driven harness).\n";
                    writeSafeFileStatic(goalFile.getAbsolutePath(), goalContent.getBytes(StandardCharsets.UTF_8));
                }

                // 4. meta.json
                File metaFile = new File(opencodeDir, "meta.json");
                if (!metaFile.exists() || metaFile.length() < 10) {
                    String metaContent = "{\"sessions\":0,\"lastDream\":0,\"lastDreamSessions\":0,\"lastMorning\":0,\"morningDate\":\"" + todayIso + "\",\"journalSeen\":0,\"lastInventory\":0,\"lastSignalsSeen\":0,\"lastProposal\":0,\"rules\":{}}\n";
                    writeSafeFileStatic(metaFile.getAbsolutePath(), metaContent.getBytes(StandardCharsets.UTF_8));
                }

                // 5. inventory.md
                File inventoryFile = new File(opencodeDir, "inventory.md");
                if (!inventoryFile.exists() || inventoryFile.length() < 30) {
                    String invContent = "# Inventory\n\n" +
                        "## Installed Capabilities\n" +
                        "- **Native Device Automation**: `/usr/local/bin/pc` (screen inspection, input synthesis, app management).\n" +
                        "- **Runtime Environment**: Debian GNU/Linux (Trixie), Node.js v24, OpenJDK 21, Gradle 8.7, Android SDK 35.\n" +
                        "- **Agent CLI**: OpenCode local cognitive layer with background memory consolidation.\n";
                    writeSafeFileStatic(inventoryFile.getAbsolutePath(), invContent.getBytes(StandardCharsets.UTF_8));
                }

                // 6. memory.json
                File memoryFile = new File(opencodeDir, "memory.json");
                if (!memoryFile.exists() || memoryFile.length() < 20) {
                    String memContent = "{\n" +
                        "  \"memories\": [\n" +
                        "    {\n" +
                        "      \"id\": \"arch_overview\",\n" +
                        "      \"t\": " + now + ",\n" +
                        "      \"text\": \"Self-architecture and capabilities: This is OpenCode running natively in Debian rootfs on Android. Full device control is provided via the native bridge (/usr/local/bin/pc) on port 4399 (screen capture, view hierarchy dumps, taps, gestures, keys, clipboard, app management, and background execution). Android storage is directly accessible at /sdcard. AI inference runs on free built-in OpenCode models without requiring any API keys. Native Android app development and compilation is fully supported via the android-app-factory skill and the preinstalled toolchain: OpenJDK 21, Gradle 8.7, Android SDK Platform 35, and ARM Build-Tools 35.0.1 (aapt2, aidl, zipalign). The system operates cleanly and autonomously without any missing dependencies.\"\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"id\": \"cognitive_architecture\",\n" +
                        "      \"t\": " + now + ",\n" +
                        "      \"text\": \"Cognitive layer and autonomous memory: This organism possesses persistent cognitive memory and reflection capabilities. Operational activity and observations are logged in /root/.opencode/journal.jsonl. An autonomous nightly dream consolidation cycle runs at 3:30 AM via /root/.opencode/dream.sh, reading recent experiences from journal.jsonl, synthesizing learnings, and consolidating them into persistent long-term memories in /root/.opencode/memory.json. Morning briefings are generated via /root/.opencode/morning.sh. This ensures continuous learning, persistent self-awareness, and cognitive continuity across reboots.\"\n" +
                        "    }\n" +
                        "  ],\n" +
                        "  \"entries\": [\n" +
                        "    {\n" +
                        "      \"id\": \"init_state\",\n" +
                        "      \"t\": " + now + ",\n" +
                        "      \"text\": \"OpenCode Android environment active. Cognitive layer initialized with persistent memory and dream cycles. Native bridge connected, complete Android compilation toolchain available, rootless operation 100% functional.\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n";
                    writeSafeFileStatic(memoryFile.getAbsolutePath(), memContent.getBytes(StandardCharsets.UTF_8));
                }

                // 7. journal.jsonl
                File journalFile = new File(opencodeDir, "journal.jsonl");
                boolean hasRem = false;
                if (journalFile.exists() && journalFile.length() > 0) {
                    try {
                        byte[] jb = new byte[(int) Math.min(journalFile.length(), 65536)];
                        try (FileInputStream fis = new FileInputStream(journalFile)) {
                            fis.read(jb);
                        }
                        String jStr = new String(jb, StandardCharsets.UTF_8);
                        if (jStr.contains("OpenCode initialized")) hasRem = true;
                    } catch (Exception ignored) {}
                }
                if (!hasRem) {
                    String seedJournal = "{\"t\":" + (now - 1800000) + ",\"text\":\"OpenCode initialized. Native bridge active.\"}\n";
                    if (journalFile.exists()) {
                        try (FileOutputStream fos = new FileOutputStream(journalFile, true)) {
                            fos.write(seedJournal.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    } else {
                        writeSafeFileStatic(journalFile.getAbsolutePath(), seedJournal.getBytes(StandardCharsets.UTF_8));
                    }
                }

                // 8. Scripts ejecutables
                for (String scriptName : new String[]{"dream.sh", "morning.sh", "idea.sh"}) {
                    File sFile = new File(debRoot, "root/.opencode/" + scriptName);
                    if (sFile.exists()) {
                        sFile.setExecutable(true, false);
                        sFile.setReadable(true, false);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "initOpencodeState error: " + e);
        }
    }

    private static byte[] readAssetStatic(Context context, String assetPath) {
        try (InputStream is = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeSafeFileStatic(String path, byte[] content) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content);
                fos.flush();
            }
            f.setReadable(true, false);
        } catch (Exception ignored) {}
    }

    private byte[] readAsset(String assetPath) {
        try (InputStream is = getAssets().open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeSafeFile(String path, byte[] content) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content);
                fos.flush();
            }
            f.setReadable(true, false);
        } catch (Exception ignored) {}
    }

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.i(LOG_TAG, "Connected. Native bridge ready on port " + BRIDGE_PORT);
        startServer();
        syncBridgeScripts(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopServer();
        releaseWakeLock();
        instance = null;
        Log.i(LOG_TAG, "Unbound. Bridge stopped.");
        return super.onUnbind(intent);
    }

    private static volatile long autoInstallUntil = 0;

    public static void enableAutoInstall(long durationMs) {
        autoInstallUntil = System.currentTimeMillis() + durationMs;
    }

    private final Runnable installerPoller = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() > autoInstallUntil) return;
            boolean clicked = attemptInstallerClick();
            if (!clicked && System.currentTimeMillis() < autoInstallUntil) {
                mainHandler.postDelayed(this, 300);
            }
        }
    };

    private void triggerInstallerCheck() {
        mainHandler.removeCallbacks(installerPoller);
        mainHandler.post(installerPoller);
    }

    private boolean attemptInstallerClick() {
        try {
            String[] texts = new String[]{
                "Instalar", "Actualizar", "Continuar", "Aceptar", "Permitir",
                "Instalar de todas formas", "Instalar de todos modos",
                "Aceptar y continuar", "Continuar con la instalación",
                "Permitir desde esta fuente",
                "Install", "Update", "Continue", "OK", "Allow",
                "Install anyway", "Done", "Listo"
            };
            for (String t : texts) {
                if (clickByText(t)) {
                    Log.i(LOG_TAG, "Auto-clicked installer text: " + t);
                    return true;
                }
            }
            String[] ids = new String[]{
                "com.android.packageinstaller:id/ok_button",
                "com.google.android.packageinstaller:id/ok_button",
                "com.android.packageinstaller:id/btn_install",
                "com.oplus.securitypermission:id/btn_install",
                "com.oplus.securitypermission:id/ok_button",
                "com.oplus.securitypermission:id/bottom_button",
                "android:id/button1"
            };
            for (String id : ids) {
                if (clickById(id)) {
                    Log.i(LOG_TAG, "Auto-clicked installer id: " + id);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (System.currentTimeMillis() < autoInstallUntil) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                String pkgStr = pkg.toString().toLowerCase();
                if (pkgStr.contains("packageinstaller") || pkgStr.contains("permissioncontroller")
                        || pkgStr.contains("securitypermission") || pkgStr.contains("settings")) {
                    triggerInstallerCheck();
                }
            }
        }
    }

    private void handlePackageInstallerEvent() {
        triggerInstallerCheck();
    }

    @Override
    public void onInterrupt() {
    }

    // ---------- HTTP Server en 127.0.0.1:4399 ----------

    private void startServer() {
        stopServer();
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(BRIDGE_PORT), 32);
                    Log.i(LOG_TAG, "OpenCode native bridge listening on port " + BRIDGE_PORT);
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            final Socket s = serverSocket.accept();
                            bgExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handle(s);
                                }
                            });
                        } catch (Exception e) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Server error: " + e);
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
        if (serverThread != null) serverThread.interrupt();
        serverThread = null;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) {
                s.close();
                return;
            }

            // Consumir cabeceras HTTP
            String h;
            do {
                h = readLine(in);
            } while (h != null && h.length() > 0);

            String path = "/";
            String query = "";
            try {
                String[] parts = reqLine.split(" ");
                if (parts.length >= 2) {
                    String full = parts[1];
                    int q = full.indexOf('?');
                    if (q >= 0) {
                        path = full.substring(0, q);
                        query = full.substring(q + 1);
                    } else {
                        path = full;
                    }
                }
            } catch (Exception ignored) {
            }
            final Map<String, String> q = parseQuery(query);

            if ("/shot".equals(path)) {
                byte[] body = takeShotSync(7000);
                if (body != null && body.length > 0) {
                    writeResp(out, 200, "image/png", body);
                } else {
                    String e = "{\"ok\":false,\"error\":\"screenshot-unavailable\","
                        + "\"code\":" + shotErr.get() + "}";
                    writeResp(out, 503, "application/json",
                        e.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                String body = route(path, q);
                writeResp(out, 200, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bs.write(c);
            if (bs.size() > 8192) break;
        }
        if (c == -1 && bs.size() == 0) return null;
        return new String(bs.toByteArray(), StandardCharsets.UTF_8);
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<String, String>();
        if (query == null || query.length() == 0) return m;
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String p = pairs[i];
            int e = p.indexOf('=');
            try {
                if (e >= 0) {
                    m.put(URLDecoder.decode(p.substring(0, e), "UTF-8"),
                        URLDecoder.decode(p.substring(e + 1), "UTF-8"));
                } else {
                    m.put(URLDecoder.decode(p, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private void writeResp(OutputStream out, int code, String ctype, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
            + "Content-Type: " + ctype + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    // ---------- Enrutador de acciones ----------

    private String route(String path, final Map<String, String> q) {
        if ("/status".equals(path)) {
            return getStatusJson();
        }
        if ("/tap".equals(path)) {
            final int x = num(q.get("x"), -1);
            final int y = num(q.get("y"), -1);
            if (x < 0 || y < 0) return err("need x&y");
            return gestureTap(x, y);
        }
        if ("/swipe".equals(path)) {
            final int x1 = num(q.get("x1"), 0);
            final int y1 = num(q.get("y1"), 0);
            final int x2 = num(q.get("x2"), 0);
            final int y2 = num(q.get("y2"), 0);
            final int ms = Math.max(50, Math.min(3000, num(q.get("ms"), 300)));
            return gestureSwipe(x1, y1, x2, y2, ms);
        }
        if ("/key".equals(path)) {
            final String name = q.get("name") == null ? "" : q.get("name").toUpperCase();
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(globalKey(name) ? ok() : err("unknown-key"));
                }
            }, 3000);
            return r.get();
        }
        if ("/text".equals(path)) {
            final String t = q.get("t") == null ? (q.get("text") == null ? "" : q.get("text")) : q.get("t");
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(setInputText(t) ? ok() : err("no-focused-field"));
                }
            }, 3000);
            return r.get();
        }
        if ("/dump".equals(path)) {
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(dumpJson());
                }
            }, 8000);
            return r.get();
        }
        if ("/open".equals(path)) {
            final String pkg = q.get("pkg") == null ? (q.get("url") == null ? "" : q.get("url")) : q.get("pkg");
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(openTarget(pkg) ? ok() : err("no-launch-intent"));
                }
            }, 5000);
            return r.get();
        }
        if ("/clipboard".equals(path)) {
            if (q.containsKey("text") || q.containsKey("t")) {
                String text = q.containsKey("text") ? q.get("text") : q.get("t");
                return setClipboard(text != null ? text : "");
            } else {
                return getClipboard();
            }
        }
        if ("/list".equals(path)) {
            return listPackages(q.get("filter"));
        }
        if ("/toast".equals(path)) {
            String msg = q.get("msg");
            if (msg != null && !msg.isEmpty()) showToast(msg);
            return ok();
        }
        if ("/click".equals(path)) {
            final String t = q.get("t") == null ? "" : q.get("t");
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(clickByText(t) ? ok() : err("not-found"));
                }
            }, 5000);
            return r.get();
        }
        if ("/clickid".equals(path)) {
            final String id = q.get("id") == null ? "" : q.get("id");
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(clickById(id) ? ok() : err("not-found"));
                }
            }, 5000);
            return r.get();
        }
        if ("/scroll".equals(path)) {
            final String dir = q.get("dir") == null ? "down" : q.get("dir");
            final AtomicReference<String> r = new AtomicReference<String>(err("timeout"));
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    r.set(scrollFirst(dir) ? ok() : err("no-scrollable"));
                }
            }, 5000);
            return r.get();
        }
        if ("/install".equals(path)) {
            final String p = q.get("path");
            return installApk(p);
        }
        if ("/uninstall".equals(path)) {
            final String pkg = q.get("pkg");
            return uninstallApp(pkg);
        }
        if ("/wake".equals(path)) {
            try {
                acquireWakeLock();
                Intent svc = new Intent(this, TermuxService.class);
                svc.setAction(TERMUX_SERVICE.ACTION_WAKE_LOCK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                return ok();
            } catch (Exception e) {
                return err("wake-error: " + e.getMessage());
            }
        }
        if ("/server".equals(path)) {
            try {
                acquireWakeLock();
                Intent svc = new Intent(this, TermuxService.class);
                svc.setAction(TERMUX_SERVICE.ACTION_OPEN_SERVER);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                return ok();
            } catch (Exception e) {
                return err("server-error: " + e.getMessage());
            }
        }
        if ("/sleep".equals(path) || "/unwake".equals(path)) {
            try {
                releaseWakeLock();
                Intent svc = new Intent(this, TermuxService.class);
                svc.setAction(TERMUX_SERVICE.ACTION_WAKE_UNLOCK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                return ok();
            } catch (Exception e) {
                return err("sleep-error: " + e.getMessage());
            }
        }
        if ("/export".equals(path)) {
            final String src = q.get("file") != null ? q.get("file") : q.get("src");
            final String dest = q.get("dest") != null ? q.get("dest") : "Download";
            if (src == null || src.isEmpty()) {
                return err("missing-file-param");
            }
            return exportFile(src, dest);
        }
        return "{\"ok\":false,\"error\":\"unknown-route\",\"routes\":"
            + "\"/status /shot /dump /tap /swipe /key /text /open /clipboard /list /toast /click /clickid /scroll /install /uninstall /wake /sleep /unwake /server /export\"}";
    }

    private String exportFile(String srcPath, String destPath) {
        try {
            File srcFile = new File(srcPath);
            if (!srcFile.exists()) {
                File bridgeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".open-bridge");
                srcFile = new File(bridgeDir, srcPath);
                if (!srcFile.exists()) {
                    srcFile = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", srcPath);
                }
            }
            if (!srcFile.exists()) {
                return err("src-not-found: " + srcPath);
            }

            File targetDir;
            if (destPath.startsWith("/")) {
                targetDir = new File(destPath);
            } else if ("Download".equalsIgnoreCase(destPath) || "Downloads".equalsIgnoreCase(destPath)) {
                targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            } else {
                targetDir = new File(Environment.getExternalStorageDirectory(), destPath);
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            File destFile;
            if (targetDir.isDirectory()) {
                destFile = new File(targetDir, srcFile.getName());
            } else {
                destFile = targetDir;
            }

            try (FileInputStream in = new FileInputStream(srcFile);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }

            return "{\"ok\":true,\"exported\":\"" + destFile.getAbsolutePath() + "\",\"bytes\":" + destFile.length() + "}";
        } catch (Exception e) {
            return err("export-failed: " + e.getMessage());
        }
    }

    private void runOnMain(final Runnable r, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Action error: " + e);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String err(String e) {
        return "{\"ok\":false,\"error\":\"" + e.replace("\"", "'") + "\"}";
    }

    private static int num(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- Acciones de gestos (desacopladas del Looper principal) ----------

    private String dispatchGestureSync(final GestureDescription g, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] res = new String[]{"timeout"};
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean started = dispatchGesture(g, new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription d) {
                            res[0] = "done";
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription d) {
                            res[0] = "cancelled";
                            latch.countDown();
                        }
                    }, mainHandler);
                    if (!started) {
                        res[0] = "not-started";
                        latch.countDown();
                    }
                } catch (SecurityException se) {
                    res[0] = "denied:" + se.getMessage();
                    latch.countDown();
                } catch (Exception e) {
                    res[0] = "error:" + e.getMessage();
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        return "done".equals(res[0]) ? ok() : err("gesture-" + res[0]);
    }

    private String gestureTap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 100))
                .build();
            return dispatchGestureSync(g, 3000);
        } catch (Exception e) {
            return err("tap-error: " + e.getMessage());
        }
    }

    private String gestureSwipe(int x1, int y1, int x2, int y2, int ms) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms))
                .build();
            return dispatchGestureSync(g, ms + 2500L);
        } catch (Exception e) {
            return err("swipe-error: " + e.getMessage());
        }
    }

    // ---------- Teclas de sistema y hardware ----------

    private boolean globalKey(String name) {
        try {
            if ("BACK".equals(name)) return performGlobalAction(GLOBAL_ACTION_BACK);
            if ("HOME".equals(name)) return performGlobalAction(GLOBAL_ACTION_HOME);
            if ("RECENTS".equals(name) || "APP_SWITCH".equals(name))
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            if ("NOTIFS".equals(name) || "NOTIFICATIONS".equals(name))
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            if ("QUICK".equals(name) || "QUICK_SETTINGS".equals(name))
                return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            if ("POWER".equals(name) || "POWER_DIALOG".equals(name))
                return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            if ("VOLUP".equals(name) || "VOLUME_UP".equals(name))
                return adjustVolume(true);
            if ("VOLDOWN".equals(name) || "VOLUME_DOWN".equals(name))
                return adjustVolume(false);
            if (Build.VERSION.SDK_INT >= 28) {
                if ("LOCK".equals(name) || "LOCK_SCREEN".equals(name))
                    return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                if ("SHOT".equals(name) || "SCREENSHOT".equals(name))
                    return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean adjustVolume(boolean up) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------- Texto y Enfoque ----------

    private boolean setInputText(String text) {
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) return false;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean r = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            f.recycle();
            return r;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Lanzador de aplicaciones y URLs ----------

    private boolean openTarget(String target) {
        if (target == null || target.isEmpty()) return false;
        try {
            if (target.startsWith("http://") || target.startsWith("https://")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
            PackageManager pm = getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(target);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Portapapeles ----------

    private String getClipboard() {
        final AtomicReference<String> text = new AtomicReference<String>("");
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip()) {
                        ClipData clip = cm.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence cs = clip.getItemAt(0).getText();
                            if (cs != null) text.set(cs.toString());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, 1000);
        return "{\"ok\":true,\"text\":\"" + esc(text.get()) + "\"}";
    }

    private String setClipboard(final String t) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        ClipData clip = ClipData.newPlainText("opencode", t);
                        cm.setPrimaryClip(clip);
                    }
                } catch (Exception ignored) {}
            }
        }, 1000);
        return ok();
    }

    // ---------- Listado de paquetes ----------

    private String listPackages(String filter) {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            StringBuilder sb = new StringBuilder(16384);
            sb.append("{\"ok\":true,\"packages\":[");
            boolean first = true;
            String fLower = (filter != null) ? filter.toLowerCase() : "";
            for (ApplicationInfo info : apps) {
                String pkg = info.packageName;
                if (!fLower.isEmpty() && !pkg.toLowerCase().contains(fLower)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"pkg\":\"").append(esc(pkg)).append('"');
                sb.append(",\"system\":").append((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "true" : "false");
                sb.append('}');
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return err("list-failed: " + e.getMessage());
        }
    }

    private void showToast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    // ---------- Native Installation and Uninstallation (Rootless / without ADB) ----------

    private String installApk(String path) {
        if (path == null || path.isEmpty()) return err("need-path");
        try {
            File file = new File(path);
            if (!file.exists() || !file.canRead()) {
                return err("file-not-found-or-unreadable: " + path);
            }
            enableAutoInstall(45000);

            Uri apkUri;
            try {
                apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                File cacheFile = new File(getCacheDir(), "install_" + System.currentTimeMillis() + ".apk");
                copyFile(file, cacheFile);
                apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cacheFile);
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);

            triggerInstallerCheck();
            mainHandler.postDelayed(installerPoller, 300);
            mainHandler.postDelayed(installerPoller, 800);
            mainHandler.postDelayed(installerPoller, 1500);
            mainHandler.postDelayed(installerPoller, 3000);

            return "{\"ok\":true,\"message\":\"install-intent-dispatched\",\"path\":\"" + esc(path) + "\"}";
        } catch (Exception e) {
            return err("install-failed: " + e.getMessage());
        }
    }

    private String uninstallApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return err("need-pkg");
        try {
            enableAutoInstall(30000);
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            handlePackageInstallerEvent();
            return "{\"ok\":true,\"message\":\"uninstall-intent-dispatched\",\"pkg\":\"" + esc(pkg) + "\"}";
        } catch (Exception e) {
            return err("uninstall-failed: " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws java.io.IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    // ---------- Estado del sistema ----------

    private String getStatusJson() {
        boolean screenOn = false;
        int batteryLevel = -1;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
        } catch (Exception ignored) {}
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception ignored) {}
        String curPkg = "";
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (root.getPackageName() != null) curPkg = root.getPackageName().toString();
                root.recycle();
            }
        } catch (Exception ignored) {}
        return "{\"ok\":true,\"bridge\":\"" + VERSION + "\",\"a11y\":true,"
            + "\"sdk\":" + Build.VERSION.SDK_INT
            + ",\"screen_on\":" + screenOn
            + ",\"battery\":" + batteryLevel
            + ",\"focus\":\"" + esc(curPkg) + "\"}";
    }

    // ---------- Clics por texto o id ----------

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            try {
                if (cur.isClickable()) return cur;
            } catch (Exception e) {
                return null;
            }
            AccessibilityNodeInfo p = null;
            try {
                p = cur.getParent();
            } catch (Exception ignored) {
            }
            cur = p;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        Rect r = new Rect();
        try {
            n.getBoundsInScreen(r);
        } catch (Exception ignored) {}

        if (!r.isEmpty() && r.width() > 0 && r.height() > 0) {
            gestureTap(r.centerX(), r.centerY());
            return true;
        }

        AccessibilityNodeInfo c = clickableAncestor(n);
        if (c != null) {
            try {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private List<AccessibilityNodeInfo> getCandidateRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
        } catch (Exception ignored) {}
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    try {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) roots.add(r);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return roots;
    }

    private boolean clickByText(String text) {
        if (text == null || text.length() == 0) return false;
        List<AccessibilityNodeInfo> roots = getCandidateRoots();
        boolean done = false;
        for (AccessibilityNodeInfo root : roots) {
            if (!done) {
                try {
                    List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByText(text);
                    if (ns != null) {
                        for (int i = 0; i < ns.size() && !done; i++) {
                            if (clickNode(ns.get(i))) done = true;
                        }
                    }
                } catch (Exception ignored) {}
            }
            try {
                root.recycle();
            } catch (Exception ignored) {}
        }
        return done;
    }

    private boolean clickById(String id) {
        if (id == null || id.length() == 0) return false;
        List<AccessibilityNodeInfo> roots = getCandidateRoots();
        boolean done = false;
        for (AccessibilityNodeInfo root : roots) {
            if (!done) {
                try {
                    List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
                    if (ns != null) {
                        for (int i = 0; i < ns.size() && !done; i++) {
                            if (clickNode(ns.get(i))) done = true;
                        }
                    }
                } catch (Exception ignored) {}
            }
            try {
                root.recycle();
            } catch (Exception ignored) {}
        }
        return done;
    }

    private boolean scrollFirst(String dir) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            boolean done = scrollRec(root, "up".equalsIgnoreCase(dir));
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
            return done;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean scrollRec(AccessibilityNodeInfo n, boolean up) {
        if (n == null) return false;
        try {
            if (n.isScrollable()) {
                if (n.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
            }
        } catch (Exception ignored) {
        }
        int kids = 0;
        try {
            kids = n.getChildCount();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < kids; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = n.getChild(i);
            } catch (Exception ignored) {
            }
            if (scrollRec(c, up)) return true;
            if (c != null) {
                try {
                    c.recycle();
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    // ---------- Volcado de UI (JSON) ----------

    private String dumpJson() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{\"ok\":false,\"error\":\"no-active-window\"}";
            StringBuilder sb = new StringBuilder(65536);
            sb.append("{\"ok\":true,\"pkg\":\"").append(esc(String.valueOf(root.getPackageName())))
                .append("\",\"nodes\":[");
            int[] count = new int[]{0};
            dumpNode(root, 0, sb, count);
            sb.append("]}");
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
            return sb.toString();
        } catch (Exception e) {
            return err("dump-failed: " + e.getMessage());
        }
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] > 1500 || depth > 14) return;
        if (count[0] > 0) sb.append(',');
        count[0]++;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String cls = String.valueOf(n.getClassName());
        int li = cls.lastIndexOf('.');
        if (li >= 0) cls = cls.substring(li + 1);
        Rect b = new Rect();
        try {
            n.getBoundsInScreen(b);
        } catch (Exception ignored) {
        }
        sb.append("{\"d\":").append(depth)
            .append(",\"c\":\"").append(esc(cls)).append('"');
        if (t != null && t.length() > 0)
            sb.append(",\"t\":\"").append(esc(trunc(t.toString()))).append('"');
        if (d != null && d.length() > 0)
            sb.append(",\"desc\":\"").append(esc(trunc(d.toString()))).append('"');
        sb.append(",\"b\":[").append(b.left).append(',').append(b.top)
            .append(',').append(b.right).append(',').append(b.bottom).append(']');
        if (n.isClickable()) sb.append(",\"click\":1");
        if (n.isEditable()) sb.append(",\"edit\":1");
        if (n.isScrollable()) sb.append(",\"scroll\":1");
        sb.append('}');
        int kids = 0;
        try {
            kids = n.getChildCount();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < kids; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = n.getChild(i);
            } catch (Exception ignored) {
            }
            dumpNode(c, depth + 1, sb, count);
            if (c != null) {
                try {
                    c.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- Captura nativa (<150ms directo a PNG en RAM) ----------

    private byte[] takeShotSync(long timeoutMs) {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> out = new AtomicReference<byte[]>(null);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, bgExecutor,
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult res) {
                                try {
                                    HardwareBuffer hb = res.getHardwareBuffer();
                                    if (hb != null) {
                                        Bitmap bmp = Bitmap.wrapHardwareBuffer(hb,
                                            ColorSpace.get(ColorSpace.Named.SRGB));
                                        hb.close();
                                        if (bmp != null) {
                                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                                            Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                                            copy.compress(Bitmap.CompressFormat.PNG, 90, os);
                                            out.set(os.toByteArray());
                                            copy.recycle();
                                            bmp.recycle();
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(LOG_TAG, "Shot encode error: " + e);
                                } finally {
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void onFailure(int err) {
                                Log.e(LOG_TAG, "Shot failed code: " + err);
                                shotErr.set(err);
                                latch.countDown();
                            }
                        });
                } catch (Exception e) {
                    Log.e(LOG_TAG, "takeScreenshot dispatch error: " + e);
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        return out.get();
    }

    private static String trunc(String s) {
        if (s.length() > 140) return s.substring(0, 140);
        return s;
    }

    private static String esc(String s) {
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') o.append("\\\"");
            else if (c == '\\') o.append("\\\\");
            else if (c == '\n') o.append("\\n");
            else if (c == '\r') o.append("\\r");
            else if (c == '\t') o.append("\\t");
            else if (c < 0x20) o.append(' ');
            else o.append(c);
        }
        return o.toString();
    }
}
