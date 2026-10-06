package com.opencode.android;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** OpenCode: descarga la última versión oficial (linux-arm64-musl) en el primer arranque. */
final class EngineFetcher {

    private static final String TAG = "EngineFetcher";
    private static final String API = "https://api.github.com/repos/anomalyco/opencode/releases/latest";
    private static final String WANT_ASSET = "opencode-linux-arm64-musl.tar.gz";

    interface Progress { void on(long done, long total); }

    static String latestTag() throws Exception {
        String json = get(API, null);
        int i = json.indexOf("\"tag_name\"");
        int a = json.indexOf('"', i + 11) + 1;
        int b = json.indexOf('"', a);
        return json.substring(a, b);
    }

    static String assetUrl() throws Exception {
        String json = get(API, null);
        String key = "\"name\":\"" + WANT_ASSET + "\"";
        int i = json.indexOf(key);
        if (i < 0) throw new RuntimeException("asset missing");
        int u = json.indexOf("\"browser_download_url\":\"", i);
        int a = u + 24;
        int e = json.indexOf('"', a);
        return json.substring(a, e).replace("\\/", "/");
    }

    static void fetch(File dest, Progress progress) throws Exception {
        String url = assetUrl();
        Log.i(TAG, "downloading " + url);
        File tmp = new File(dest.getParent(), "opencode.tar.gz.tmp");
        download(url, tmp, progress);
        extractOpencode(tmp, dest);
        tmp.delete();
        try { Runtime.getRuntime().exec(new String[]{"chmod", "755", dest.getAbsolutePath()}).waitFor(); }
        catch (Exception ignored) {}
        if (!dest.canExecute()) {
            try { android.system.Os.chmod(dest.getAbsolutePath(), 0755); }
            catch (Exception ignored) {}
        }
    }

    private static void download(String url, File out, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.connect();
        if (c.getResponseCode() / 100 != 2 && c.getResponseCode() != 302) {
            throw new RuntimeException("http " + c.getResponseCode());
        }
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream();
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[262144];
            long done = 0;
            int n;
            long last = 0;
            while ((n = in.read(buf)) != -1) {
                o.write(buf, 0, n);
                done += n;
                if (progress != null && done - last > 1048576) {
                    last = done;
                    progress.on(done, total);
                }
            }
            if (progress != null) progress.on(done, total);
        } finally {
            c.disconnect();
        }
    }

    private static void extractOpencode(File tarGz, File dest) throws Exception {
        try (InputStream f = new java.io.FileInputStream(tarGz);
             GZIPInputStream gz = new GZIPInputStream(f)) {
            byte[] head = new byte[512];
            while (true) {
                if (!fill(gz, head)) break;
                if (isZero(head)) break;
                String name = cstr(head, 0, 100);
                long size = Long.parseLong(cstr(head, 124, 12).trim(), 8);
                if (name.equals("opencode") || name.endsWith("/opencode")) {
                    try (OutputStream o = new FileOutputStream(dest)) {
                        copyN(gz, o, size);
                    }
                    return;
                }
                skip(gz, (size + 511) / 512 * 512);
            }
        }
        throw new RuntimeException("opencode not in tarball");
    }

    private static boolean fill(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return off > 0;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off;
        while (e < off + len && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.UTF_8);
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws Exception {
        byte[] buf = new byte[262144];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new RuntimeException("truncated");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static void skip(InputStream in, long n) throws Exception {
        byte[] buf = new byte[262144];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) return;
            n -= r;
        }
    }

    private static String get(String url, Progress unused) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
