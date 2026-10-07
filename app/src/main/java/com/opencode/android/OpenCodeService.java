package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;
import android.util.Log;
import android.widget.RemoteViews;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OpenCode: servicio en primer plano que mantiene vivo el servidor local oculto. */
public class OpenCodeService extends Service {

    private static final String TAG = "OpenCodeService";
    static final int PORT = 4096;

    public static final String ACTION_STOP_SERVICE = "com.opencode.android.ACTION_STOP_SERVICE";
    public static final String ACTION_TOGGLE_24_7 = "com.opencode.android.ACTION_TOGGLE_24_7";
    public static final String ACTION_TOGGLE_SERVER = "com.opencode.android.ACTION_TOGGLE_SERVER";

    private Process server;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notifyManager;
    private volatile boolean serverRunning = false;
    private volatile boolean is24_7 = false;

    private void progress(String msg) {
        try {
            if (notifyManager == null) {
                notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            }
            if (notifyManager == null) return;
            if (msg == null) {
                notifyManager.notify(1, buildNotification());
                return;
            }
            String ch = "opencode";
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            b.setContentTitle("OpenCode").setContentText(msg)
                .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
            notifyManager.notify(1, b.build());
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        is24_7 = prefs.getBoolean("pref_24_7", false);
        if (is24_7) {
            acquireWakeLock();
        }
        startForeground(1, buildNotification());
    }

    private synchronized void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:24_7");
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired (24/7 mode enabled)");
        }
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released (24/7 mode disabled)");
        }
    }

    private synchronized void stopServer() {
        Log.i(TAG, "stopServer called");
        if (server != null) {
            try {
                server.destroy();
            } catch (Exception ignored) {}
            server = null;
        }
        serverRunning = false;
        updateNotification();
    }

    private synchronized boolean isServerAlive() {
        if (server != null) {
            try {
                if (server.isAlive()) return true;
            } catch (Exception ignored) {}
        }
        return serverRunning;
    }

    private void updateNotification() {
        try {
            if (notifyManager == null) {
                notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            }
            if (notifyManager != null) {
                notifyManager.notify(1, buildNotification());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_STOP_SERVICE.equals(act)) {
                Log.i(TAG, "ACTION_STOP_SERVICE intent received");
                stopServer();
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
                System.exit(0);
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE_24_7.equals(act)) {
                Log.i(TAG, "ACTION_TOGGLE_24_7 intent received");
                is24_7 = !is24_7;
                getSharedPreferences("opencode_prefs", MODE_PRIVATE)
                    .edit().putBoolean("pref_24_7", is24_7).apply();
                if (is24_7) {
                    acquireWakeLock();
                } else {
                    releaseWakeLock();
                }
                updateNotification();
                return START_STICKY;
            } else if (ACTION_TOGGLE_SERVER.equals(act)) {
                Log.i(TAG, "ACTION_TOGGLE_SERVER intent received");
                if (isServerAlive()) {
                    stopServer();
                } else {
                    new Thread(this::bootServer).start();
                }
                updateNotification();
                return START_STICKY;
            }
        }

        if (server == null && !serverRunning) {
            new Thread(this::bootServer).start();
        }
        new Thread(() -> {
            try {
                File ready = new File(new File(getFilesDir(), "server-home"), ".factory-ready");
                if (ready.exists()) return;
                for (int i = 0; i < 60; i++) {
                    if (serverUp()) break;
                    Thread.sleep(1000);
                }
                Thread.sleep(45000);
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                ensureFactory();
            } catch (Exception ignored) {}
        }).start();
        return START_STICKY;
    }

    private static boolean factoryStarted = false;

    private void ensureSymlink(File target, File link) {
        try {
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                link.delete();
            }
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            Log.i(TAG, "Symlink created: " + link + " -> " + target);
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"/system/bin/ln", "-sfn",
                    target.getAbsolutePath(), link.getAbsolutePath()}).waitFor();
            } catch (Exception ignored) {}
        }
    }

    private void wrapToolchainDirect(File tc) {
        try {
            File home = new File(getFilesDir(), "server-home");
            String homePath = home.getAbsolutePath();
            File usrBin = new File(tc, "usr/bin");
            wrapDirectBinary(new File(usrBin, "git"), homePath, "usr/bin/git.real", null);
            wrapDirectBinary(new File(usrBin, "aapt2"), homePath, "usr/bin/aapt2.real", null);
            File jvmBin = new File(tc, "usr/lib/jvm/java-21-openjdk/bin");
            for (String b : new String[]{"java", "javac", "keytool", "jar"}) {
                wrapDirectBinary(new File(jvmBin, b), homePath, "usr/lib/jvm/java-21-openjdk/bin/" + b + ".real",
                    homePath + "/toolchain/usr/lib/jvm/java-21-openjdk");
            }
        } catch (Exception e) {
            Log.e(TAG, "wrapToolchainDirect failed", e);
        }
    }

    private void wrapDirectBinary(File bin, String homePath, String relReal, String javaHome) {
        try {
            if (!bin.exists()) return;
            File real = new File(homePath, "toolchain/" + relReal);
            if (real.exists()) return;
            bin.renameTo(real);
            chmod(real, 0755);
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            sb.append("if [ -z \"${HOME:-}\" ]; then export HOME=\"").append(homePath).append("\"; fi\n");
            if (javaHome != null) {
                sb.append("export JAVA_HOME=\"").append(javaHome).append("\"\n");
            }
            sb.append("LD_LIBRARY_PATH=\"").append(homePath).append("/toolchain/usr/lib\" exec \"")
              .append(real.getAbsolutePath()).append("\" \"$@\"\n");
            try (FileOutputStream out = new FileOutputStream(bin)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
            chmod(bin, 0755);
        } catch (Exception ignored) {}
    }

    /** Factoría autosuficiente: toolchain+SDK listos tras instalar, sin ayuda externa. */
    private void ensureFactory() {
        synchronized (OpenCodeService.class) {
            if (factoryStarted) return;
            factoryStarted = true;
        }
        try {
            File home = new File(getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();
            File work = new File(android.os.Environment.getExternalStorageDirectory(), "OpenCode");
            try {
                if (!work.exists()) work.mkdirs();
            } catch (Exception ignored) {}
            if (!work.exists() || !work.canWrite()) {
                work = new File(home, "OpenCode");
                if (!work.exists()) work.mkdirs();
            } else {
                ensureSymlink(work, new File(home, "OpenCode"));
            }

            File ready = new File(home, ".factory-ready");
            if (ready.exists()) return;
            File tcJava = new File(home, "toolchain/usr/lib/jvm/java-21-openjdk/bin/java");
            File plat = new File(home, "android-sdk/platforms/android-35/android.jar");
            File bt34 = new File(home, "android-sdk/build-tools/34.0.0/d8");
            if (tcJava.canExecute() && plat.exists() && bt34.exists()) { writeMarker(ready, "ok"); return; }

            File script = new File(new File(home, "bin"), "open-factory-setup");
            for (int i = 0; i < 120 && !script.exists(); i++) Thread.sleep(1000);
            if (!script.exists()) { Log.e(TAG, "factory: no setup script"); return; }
            File tc = new File(home, "toolchain");
            File unpacked = new File(home, ".toolchain-unpacked");
            if (!tcJava.canExecute() && !unpacked.exists()) {
                boolean ok = false;
                try (InputStream a = getAssets().open("toolchain/toolchain-native.tar.gz")) {
                    extractTar(a, true, tc);
                    ok = true;
                } catch (Exception e1) {
                    try (InputStream a = getAssets().open("toolchain/toolchain-native.tar")) {
                        extractTar(a, false, tc);
                        ok = true;
                    } catch (Exception e2) { Log.e(TAG, "factory unpack", e2); }
                }
                if (ok) {
                    wrapToolchainDirect(tc);
                    chmod(new File(tc, "usr/bin/aapt2"), 0755);
                    chmod(new File(tc, "usr/bin/git"), 0755);
                    writeMarker(unpacked, "ok");
                } else {
                    return;
                }
            } else if (tc.exists()) {
                wrapToolchainDirect(tc);
            }

            File sdk = new File(home, "android-sdk");
            File androidJar = new File(sdk, "platforms/android-35/android.jar");
            if (!androidJar.exists()) {
                try {
                    copyAssetTree("sdk", sdk);
                    chmod(new File(sdk, "build-tools/35.0.0/d8"), 0755);
                    chmod(new File(sdk, "build-tools/35.0.0/apksigner"), 0755);
                    chmod(new File(sdk, "build-tools/35.0.0/aapt2"), 0755);
                    chmod(new File(sdk, "build-tools/34.0.0/d8"), 0755);
                    chmod(new File(sdk, "build-tools/34.0.0/apksigner"), 0755);
                    chmod(new File(sdk, "build-tools/34.0.0/aapt2"), 0755);
                    Log.i(TAG, "factory sdk seeded");
                } catch (Exception e) { Log.e(TAG, "factory sdk seed", e); }
            } else {
                File b34 = new File(sdk, "build-tools/34.0.0/d8");
                if (!b34.exists()) {
                    try {
                        copyAssetTree("sdk/build-tools/34.0.0", new File(sdk, "build-tools/34.0.0"));
                        chmod(new File(sdk, "build-tools/34.0.0/d8"), 0755);
                        chmod(new File(sdk, "build-tools/34.0.0/apksigner"), 0755);
                        chmod(new File(sdk, "build-tools/34.0.0/aapt2"), 0755);
                    } catch (Exception e) { Log.e(TAG, "factory b34 seed", e); }
                }
                File ptools = new File(sdk, "platform-tools/source.properties");
                if (!ptools.exists()) {
                    try {
                        copyAssetTree("sdk/platform-tools", new File(sdk, "platform-tools"));
                    } catch (Exception e) { Log.e(TAG, "factory ptools seed", e); }
                }
            }

            File gmod = new File(new File(home, ".gradle"), "caches/modules-2/files-2.1");
            if (!gmod.exists()) {
                try {
                    copyAssetTree("gradle-cache", new File(new File(home, ".gradle"), "caches/modules-2"));
                    Log.i(TAG, "factory gradle cache seeded");
                } catch (Exception e) { Log.e(TAG, "factory gradle seed", e); }
            }

            File log = new File(home, "factory-setup.log");
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath());
            Map<String, String> env = pb.environment();
            env.put("HOME", home.getAbsolutePath());
            pb.directory(home);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            pb.redirectErrorStream(true);
            progress("Initializing local toolchain…");
            int exit = pb.start().waitFor();
            Log.i(TAG, "factory setup exit=" + exit);
            if (exit == 0 && tcJava.canExecute() && plat.exists()) writeMarker(ready, "ok");
            progress(null);
        } catch (Exception e) {
            Log.e(TAG, "factory failed", e);
        }
    }

    private void bootServer() {
        Log.i(TAG, "boot: start");
        try {
            File dir = installServer();
            Log.i(TAG, "boot: engine ready at " + dir);
            if (serverUp()) {
                Log.i(TAG, "server already running, attached");
                return;
            }
            File home = new File(getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();
            File work = new File(android.os.Environment.getExternalStorageDirectory(), "OpenCode");
            try {
                if (!work.exists()) work.mkdirs();
            } catch (Exception ignored) {}
            if (!work.exists() || !work.canWrite()) {
                work = new File(home, "OpenCode");
                if (!work.exists()) work.mkdirs();
            } else {
                ensureSymlink(work, new File(home, "OpenCode"));
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(new File(dir, "ld-musl-aarch64.so.1").getAbsolutePath());
            cmd.add("--library-path");
            cmd.add(dir.getAbsolutePath());
            cmd.add(new File(dir, "opencode").getAbsolutePath());
            cmd.add("web");
            cmd.add("--port");
            cmd.add(String.valueOf(PORT));
            cmd.add("--hostname");
            cmd.add("127.0.0.1");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(work);
            File loader = new File(dir, "ld-musl-aarch64.so.1");
            File engine = new File(dir, "opencode");
            Log.i(TAG, "exec check loader=" + loader.exists() + "/" + loader.canExecute()
                + " engine=" + engine.exists() + "/" + engine.canExecute()
                + " len=" + engine.length());
            Map<String, String> env = pb.environment();
            env.put("HOME", work.getAbsolutePath());
            env.remove("LD_LIBRARY_PATH");
            env.put("SSL_CERT_FILE", new File(dir, "ca-certificates.crt").getAbsolutePath());
            env.put("SSL_CERT_DIR", "");
            env.put("ANDROID_HOME", new File(home, "android-sdk").getAbsolutePath());
            env.put("ANDROID_SDK_ROOT", new File(home, "android-sdk").getAbsolutePath());
            env.put("JAVA_HOME", new File(home, "toolchain/usr/lib/jvm/java-21-openjdk").getAbsolutePath());
            env.put("ANDROID_USER_HOME", new File(home, ".android").getAbsolutePath());
            env.put("GRADLE_USER_HOME", new File(home, ".gradle").getAbsolutePath());
            env.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
            env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
            env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
            env.put("ENV", new File(home, ".mkshrc").getAbsolutePath());
            String path = home.getAbsolutePath() + "/bin:"
                + home.getAbsolutePath() + "/toolchain/bin:"
                + home.getAbsolutePath() + "/toolchain/usr/bin:"
                + home.getAbsolutePath() + "/android-sdk/platform-tools:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/35.0.0:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/34.0.0:"
                + "/system/bin";
            env.put("PATH", path);
            env.put("SHELL", "/system/bin/sh");
            pb.redirectErrorStream(true);
            File sLog = new File(getFilesDir(), "server.log");
            server = pb.start();
            serverRunning = true;
            updateNotification();
            final Process p = server;
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                     FileOutputStream out = new FileOutputStream(sLog, true)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, line);
                        out.write((line + "\n").getBytes("UTF-8"));
                    }
                } catch (Exception ignored) {}
                serverRunning = false;
                updateNotification();
            }).start();
            Log.i(TAG, "server started");
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
        }
    }

    private File installServer() throws Exception {
        Log.i(TAG, "install: begin");
        File dir = new File(getFilesDir(), "server");
        if (!dir.exists()) dir.mkdirs();
        File bin = new File(dir, "opencode");
        File loader = new File(dir, "ld-musl-aarch64.so.1");

        String[] libs = getAssets().list("server");
        if (libs == null || libs.length == 0) throw new RuntimeException("assets/server empty");
        for (String name : libs) {
            if (name.equals("engine.version") || name.equals("opencode")) continue;
            File dest = new File(dir, name);
            if (!dest.exists()) copyAsset("server/" + name, dest);
        }
        chmod(loader, 0755);
        for (String name : libs) {
            if (name.startsWith("ld-musl")) continue;
            if (name.endsWith(".so") || name.endsWith(".so.1")) chmod(new File(dir, name), 0644);
        }

        File marker = new File(dir, ".version");
        if (!bin.exists() || bin.length() < 50000000) {
            try {
                copyAsset("server/opencode", bin);
                chmod(bin, 0755);
                writeMarker(marker, assetText("server/engine.version"));
                Log.i(TAG, "install: engine seeded from assets");
            } catch (Exception e) {
                Log.e(TAG, "engine seed missing", e);
            }
        } else {
            chmod(bin, 0755);
        }

        if (!bin.exists() || bin.length() < 1000) {
            try {
                String latest = EngineFetcher.latestTag();
                EngineFetcher.fetch(bin, (done, total) -> progress(
                    (done / 1048576) + "MB" + (total > 0 ? " / " + (total / 1048576) + "MB" : "")));
                if (!latest.isEmpty()) writeMarker(marker, latest);
                Log.i(TAG, "install: engine fallback downloaded");
            } catch (Exception e) {
                Log.e(TAG, "fetch fallback failed", e);
            }
        }
        chmod(bin, 0755);
        if (!bin.exists() || bin.length() < 1000) throw new RuntimeException("engine not available");

        installPc();
        return dir;
    }

    private void installPc() {
        try {
            File home = new File(getFilesDir(), "server-home");
            File homeBin = new File(home, "bin");
            if (!homeBin.exists()) homeBin.mkdirs();
            copyAsset("bin/pc", new File(homeBin, "pc"));
            chmod(new File(homeBin, "pc"), 0755);
            copyAsset("bin/rg", new File(homeBin, "rg"));
            chmod(new File(homeBin, "rg"), 0755);
            copyAsset("bin/open-factory-setup", new File(homeBin, "open-factory-setup"));
            chmod(new File(homeBin, "open-factory-setup"), 0755);
            copyAsset("bin/gradle-oc", new File(homeBin, "gradle-oc"));
            chmod(new File(homeBin, "gradle-oc"), 0755);
            copyAsset("bin/iconos", new File(homeBin, "iconos"));
            chmod(new File(homeBin, "iconos"), 0755);
            copyAsset("bin/iconos.jar", new File(homeBin, "iconos.jar"));

            File tcBin = new File(home, "toolchain/bin");
            if (!tcBin.exists()) tcBin.mkdirs();
            copyAsset("bin/gradle-oc", new File(tcBin, "gradle-oc"));
            chmod(new File(tcBin, "gradle-oc"), 0755);
            copyAsset("bin/iconos", new File(tcBin, "iconos"));
            chmod(new File(tcBin, "iconos"), 0755);
            copyAsset("bin/iconos.jar", new File(tcBin, "iconos.jar"));

            File tcUsrBin = new File(home, "toolchain/usr/bin");
            if (tcUsrBin.exists()) {
                copyAsset("bin/gradle-oc", new File(tcUsrBin, "gradle-oc"));
                chmod(new File(tcUsrBin, "gradle-oc"), 0755);
            }

            for (String w : new String[]{"java", "javac", "keytool", "git", "aapt2", "d8", "apksigner"}) {
                File src = new File(homeBin, "wrap/" + w);
                copyAsset("bin/wrap/" + w, new File(homeBin, w));
                chmod(new File(homeBin, w), 0755);
                copyAsset("bin/wrap/" + w, new File(tcBin, w));
                chmod(new File(tcBin, w), 0755);
            }

            // Write global gradle.properties
            File gdir = new File(home, ".gradle");
            if (!gdir.exists()) gdir.mkdirs();
            File gp = new File(gdir, "gradle.properties");
            String gpContent = "org.gradle.jvmargs=-Xmx3g\n"
                + "android.aapt2FromMavenOverride=" + home.getAbsolutePath() + "/toolchain/bin/aapt2\n"
                + "android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE\n"
                + "android.suppressUnsupportedCompileSdk=35\n";
            try (FileOutputStream out = new FileOutputStream(gp)) {
                out.write(gpContent.getBytes("UTF-8"));
            }

            // Write shell rc files
            File mkshrc = new File(home, ".mkshrc");
            String rcContent = "export HOME=\"" + home.getAbsolutePath() + "\"\n"
                + "export ANDROID_HOME=\"" + home.getAbsolutePath() + "/android-sdk\"\n"
                + "export ANDROID_SDK_ROOT=\"" + home.getAbsolutePath() + "/android-sdk\"\n"
                + "export JAVA_HOME=\"" + home.getAbsolutePath() + "/toolchain/usr/lib/jvm/java-21-openjdk\"\n"
                + "export ANDROID_USER_HOME=\"" + home.getAbsolutePath() + "/.android\"\n"
                + "export GRADLE_USER_HOME=\"" + home.getAbsolutePath() + "/.gradle\"\n"
                + "export PATH=\"" + home.getAbsolutePath() + "/bin:"
                + home.getAbsolutePath() + "/toolchain/bin:"
                + home.getAbsolutePath() + "/toolchain/usr/bin:"
                + home.getAbsolutePath() + "/android-sdk/platform-tools:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/35.0.0:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/34.0.0:/system/bin:${PATH:-}\"\n"
                + "alias gradle=\"gradle-oc\"\n";
            try (FileOutputStream out = new FileOutputStream(mkshrc)) {
                out.write(rcContent.getBytes("UTF-8"));
            }
            try (FileOutputStream out = new FileOutputStream(new File(home, ".profile"))) {
                out.write(rcContent.getBytes("UTF-8"));
            }
            try (FileOutputStream out = new FileOutputStream(new File(home, ".bashrc"))) {
                out.write(rcContent.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "bin install failed", e);
        }
        try {
            File home = new File(getFilesDir(), "server-home");
            File cfg = new File(home, ".config/opencode");
            if (!cfg.exists()) cfg.mkdirs();
            File jsonc = new File(cfg, "opencode.jsonc");
            copyAsset("config/opencode.jsonc", jsonc);
            File agents = new File(cfg, "AGENTS.md");
            copyAsset("config/AGENTS.md", agents);
            copyAsset("config/AGENTS.md", new File(home, "AGENTS.md"));
            File memDir = new File(cfg, "memory");
            if (!memDir.exists()) memDir.mkdirs();
            File memFile = new File(memDir, "memory.json");
            if (!memFile.exists()) copyAsset("config/memory.json", memFile);

            File work = new File(android.os.Environment.getExternalStorageDirectory(), "OpenCode");
            if (work.exists()) {
                File workAgents = new File(work, "AGENTS.md");
                copyAsset("config/AGENTS.md", workAgents);
            }
            ensureSymlink(work, new File(home, "OpenCode"));

            File skills = new File(cfg, "skills/android-app-factory");
            if (!skills.exists()) skills.mkdirs();
            copyAsset("skills/android-app-factory/SKILL.md", new File(skills, "SKILL.md"));
            Log.i(TAG, "OpenCode config and skills installed successfully");
        } catch (Exception e) {
            Log.e(TAG, "config and skills install failed", e);
        }
    }

    private String assetText(String path) {
        try (InputStream in = getAssets().open(path)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n > 0) return new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception ignored) {}
        return "unknown";
    }

    /** Copia un árbol de assets (directorios y ficheros). */
    private void copyAssetTree(String assetPath, File dest) throws Exception {
        if (assetPath.endsWith("platforms/android-35/data/res")) return;
        String[] list = getAssets().list(assetPath);
        if (list != null && list.length > 0) {
            dest.mkdirs();
            for (String name : list) {
                if ("res".equals(name) && assetPath.endsWith("platforms/android-35/data")) continue;
                copyAssetTree(assetPath + "/" + name, new File(dest, name));
            }
            return;
        }
        try {
            copyAsset(assetPath, dest);
        } catch (Exception e) {
            dest.mkdirs();
        }
    }

    private void copyAsset(String asset, File dest) throws Exception {
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void chmod(File f, int mode) {
        if (f == null || !f.exists()) return;
        try {
            f.setReadable(true, false);
            if ((mode & 0111) != 0) {
                f.setExecutable(true, false);
            }
        } catch (Exception ignored) {}
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (Exception e) {
            Log.e(TAG, "chmod failed " + f + ": " + e.getMessage());
            try {
                Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/chmod", (mode & 0111) != 0 ? "755" : "644", f.getAbsolutePath()});
                p.waitFor();
            } catch (Exception ignored) {}
        }
    }

    /** Descomprime el tar del toolchain (gz o pelado) tal como lo deje aapt en assets. */
    private void extractTar(InputStream raw, boolean gz, File dest) throws Exception {
        InputStream in = gz ? new java.util.zip.GZIPInputStream(raw, 262144) : raw;
        byte[] h = new byte[512];
        String pending = null;
        String destCanon = dest.getCanonicalPath() + File.separator;
        while (true) {
            if (readFully(in, h) < 512) break;
            if (isZeroBlock(h)) break;
            String name = pending != null ? pending : cstr(h, 0, 100);
            String prefix = pending != null ? "" : cstr(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            pending = null;
            long size = octal(h, 124, 12);
            char type = (char) (h[156] & 0xFF);
            int mode = (int) octal(h, 100, 8);
            if (type == 'L') {
                byte[] nb = new byte[(int) size];
                readFully(in, nb);
                pending = new String(nb, "UTF-8").trim();
                skipPad(in, size);
                continue;
            }
            String base = name.substring(name.lastIndexOf('/') + 1);
            boolean skip = base.startsWith("._");
            File f = new File(dest, name);
            boolean inside = f.getCanonicalPath().startsWith(destCanon);
            if (!skip && inside) {
                if (type == '5' || name.endsWith("/")) {
                    f.mkdirs();
                } else if (type == '2') {
                    f.getParentFile().mkdirs();
                    try { Os.symlink(cstr(h, 157, 100), f.getAbsolutePath()); }
                    catch (Exception e) { Log.e(TAG, "symlink " + f, e); }
                } else if (type == '0' || type == 0 || type == ' ') {
                    f.getParentFile().mkdirs();
                    OutputStream o = new FileOutputStream(f);
                    copyN(in, o, size);
                    o.close();
                    try { Os.chmod(f.getAbsolutePath(), mode & 0777); }
                    catch (Exception ignored) {}
                } else {
                    skipN(in, size);
                }
            } else {
                skipN(in, size);
            }
            skipPad(in, size);
        }
        in.close();
    }

    private static int readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int n = 0;
        while (n < len && b[off + n] != 0) n++;
        try { return new String(b, off, n, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c >= '0' && c <= '7') v = (v << 3) + (c - '0');
            else if (c == 0 || c == ' ') break;
        }
        return v;
    }

    private static void copyN(InputStream in, OutputStream o, long n) throws Exception {
        byte[] buf = new byte[262144];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new RuntimeException("tar truncado");
            o.write(buf, 0, r);
            n -= r;
        }
    }

    private static void skipN(InputStream in, long n) throws Exception {
        byte[] buf = new byte[262144];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) break;
            n -= r;
        }
    }

    private static void skipPad(InputStream in, long size) throws Exception {
        long pad = (512 - (size % 512)) % 512;
        skipN(in, pad);
    }

    private static String readMarker(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n > 0) return new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static void writeMarker(File f, String v) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(v.getBytes("UTF-8")); }
        catch (Exception ignored) {}
    }

    private static boolean serverUp() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + PORT + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Notification buildNotification() {
        String ch = "opencode";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(ch, "OpenCode",
                NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            nm.createNotificationChannel(chan);
        }

        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent appPending = PendingIntent.getActivity(this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent exitIntent = new Intent(this, OpenCodeService.class).setAction(ACTION_STOP_SERVICE);
        PendingIntent exitPending = PendingIntent.getService(this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent toggle247Intent = new Intent(this, OpenCodeService.class).setAction(ACTION_TOGGLE_24_7);
        PendingIntent toggle247Pending = PendingIntent.getService(this, 2, toggle247Intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        RemoteViews actionsView = new RemoteViews(getPackageName(), R.layout.ocode_notification_actions);

        // Exit button: dark neutral pill, text "Exit"
        actionsView.setInt(R.id.ocode_action_exit, "setBackgroundResource", R.drawable.open_action_pill);
        actionsView.setTextColor(R.id.ocode_action_exit, 0xFFEDEDED);
        actionsView.setTextViewText(R.id.ocode_action_exit, "Exit");
        actionsView.setOnClickPendingIntent(R.id.ocode_action_exit, exitPending);

        // 24/7 button: illuminates emerald when active, dark when inactive
        boolean active24_7 = is24_7 && (wakeLock != null && wakeLock.isHeld());
        actionsView.setInt(R.id.ocode_action_wakelock, "setBackgroundResource",
            active24_7 ? R.drawable.open_action_pill_active : R.drawable.open_action_pill);
        actionsView.setTextColor(R.id.ocode_action_wakelock,
            active24_7 ? 0xFFFFFFFF : 0xFF9CA3AF);
        actionsView.setTextViewText(R.id.ocode_action_wakelock, "24/7");
        actionsView.setOnClickPendingIntent(R.id.ocode_action_wakelock, toggle247Pending);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, ch) : new Notification.Builder(this);

        b.setContentTitle("OpenCode")
            .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()))
            .setContentIntent(appPending)
            .setOngoing(true)
            .setShowWhen(false)
            .setCustomContentView(actionsView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setStyle(new Notification.DecoratedCustomViewStyle());
            b.setCustomBigContentView(actionsView);
        }

        return b.build();
    }

    @Override
    public void onDestroy() {
        stopServer();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
