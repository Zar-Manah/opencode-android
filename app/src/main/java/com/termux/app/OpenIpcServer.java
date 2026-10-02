package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.view.TerminalView;
import com.termux.terminal.TerminalSession;
import com.termux.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OpenIpcServer {

    private static final String LOG_TAG = "OpenIpcServer";
    public static final int PORT = 19876;

    private static final AtomicReference<CountDownLatch> sVideoLatch = new AtomicReference<>(null);
    private static volatile boolean sIsRunning = false;

    private final TermuxActivity mActivity;
    private final Handler mMainHandler;
    private ServerSocket mServerSocket;
    private Thread mHttpThread;
    private Thread mFileWatcherThread;

    public OpenIpcServer(TermuxActivity activity) {
        this.mActivity = activity;
        this.mMainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start() {
        if (sIsRunning) return;
        sIsRunning = true;

        startHttpServer();
        startFileWatcher();
        Logger.logInfo(LOG_TAG, "OpenCode IPC Server started on port " + PORT);
    }

    public synchronized void stop() {
        sIsRunning = false;
        if (mServerSocket != null) {
            try { mServerSocket.close(); } catch (Exception ignored) {}
            mServerSocket = null;
        }
        notifyVideoCompleted();
    }

    public static void notifyVideoCompleted() {
        CountDownLatch latch = sVideoLatch.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }
    }

    private void startHttpServer() {
        mHttpThread = new Thread(() -> {
            try {
                mServerSocket = new ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"));
                while (sIsRunning && !mServerSocket.isClosed()) {
                    try {
                        Socket client = mServerSocket.accept();
                        handleHttpClient(client);
                    } catch (Exception e) {
                        if (!sIsRunning) break;
                    }
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to start HTTP server on port " + PORT + ": " + e.getMessage());
            }
        }, "OpenIpcHttpThread");
        mHttpThread.setDaemon(true);
        mHttpThread.start();
    }

    private void handleHttpClient(Socket socket) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {

                String line = reader.readLine();
                if (line == null) return;

                String[] parts = line.split(" ");
                if (parts.length < 2) return;

                String uriStr = parts[1];
                Uri uri = Uri.parse(uriStr);
                String path = uri.getPath();

                boolean waitVideo = false;

                if ("/typing_start".equals(path) || "/typing_stop".equals(path) ||
                    "/scifi_enter".equals(path) || "/hide_keyboard".equals(path) ||
                    "/restore_keyboard".equals(path)) {
                    // No-op in OpenCode
                } else if ("/dismiss_splash".equals(path) || "/ready".equals(path)) {
                    mActivity.dismissSplash();
                } else if ("/play_video".equals(path) || "/launch_video_async".equals(path)) {
                    String doneFile = uri.getQueryParameter("done");
                    launchVideo(null, doneFile);
                } else if ("/dump_screen".equals(path)) {
                    TerminalView tv = mActivity.findViewById(R.id.terminal_view);
                    String screen = "";
                    if (tv != null && tv.getCurrentSession() != null && tv.getCurrentSession().getEmulator() != null) {
                        screen = tv.getCurrentSession().getEmulator().getScreen().getTranscriptText();
                    }
                    byte[] data = screen.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
                    out.write(data);
                    out.flush();
                    return;
                } else if ("/exec".equals(path)) {
                    String cmd = uri.getQueryParameter("cmd");
                    if (cmd == null) cmd = "id";
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/zsh", "-c", cmd});
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        java.io.InputStream is = p.getInputStream();
                        while ((n = is.read(b)) != -1) baos.write(b, 0, n);
                        p.waitFor();
                        byte[] data = baos.toByteArray();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
                        out.write(data);
                        out.flush();
                        return;
                    } catch (Exception e) {
                        byte[] data = ("Error: " + e.getMessage()).getBytes();
                        out.write(("HTTP/1.1 500 Error\r\nContent-Type: text/plain\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
                        out.write(data);
                        out.flush();
                        return;
                    }
                }

                if (waitVideo) {
                    CountDownLatch latch = sVideoLatch.get();
                    if (latch != null) {
                        try {
                            latch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {}
                    }
                }

                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
                out.write(response.getBytes());
                out.flush();

            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void startFileWatcher() {
        mFileWatcherThread = new Thread(() -> {
            File cmdDir = new File(mActivity.getFilesDir(), "home/.open-cmd");
            while (sIsRunning) {
                try {
                    if (cmdDir.exists() && cmdDir.isDirectory()) {
                        File[] files = cmdDir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                String name = file.getName();
                                if ("typing_start".equals(name) || "typing_stop".equals(name) ||
                                    "scifi_enter".equals(name) || "hide_keyboard".equals(name) ||
                                    "restore_keyboard".equals(name)) {
                                    file.delete();
                                } else if (name.startsWith("play_video")) {
                                    String content = "";
                                    try {
                                        content = new String(java.nio.file.Files.readAllBytes(file.toPath())).trim();
                                    } catch (Exception ignored) {}
                                    file.delete();

                                    String video = "openintro";
                                    String done = null;
                                    if (!content.isEmpty()) {
                                        String[] p = content.split(":");
                                        video = p[0];
                                        if (p.length > 1) done = p[1];
                                    }
                                    launchVideo(video, done);
                                }
                            }
                        }
                    }
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        }, "OpenFileWatcherThread");
        mFileWatcherThread.setDaemon(true);
        mFileWatcherThread.start();
    }

    public void launchVideo(String videoName, String doneFile) {
        if (doneFile != null && !doneFile.isEmpty()) {
            try {
                File f = new File(doneFile);
                if (!f.exists()) f.createNewFile();
            } catch (Exception ignored) {}
        }
        notifyVideoCompleted();
    }
}
