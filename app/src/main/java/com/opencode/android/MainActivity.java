package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** OpenCode: pantalla única. WebView a pantalla completa contra el servidor local oculto. */
public class MainActivity extends Activity {

    static final String SERVER_URL = "http://127.0.0.1:4096/";

    private WebView web;
    private View splash;
    private volatile boolean loaded;
    private ValueCallback<Uri[]> fileCallback;

    /** CSS móvil: la barra del composer puede envolverse; el pill de variante
     * nunca tapa el botón de enviar. Solo pantallas estrechas. */
    private static final String MOBILE_CSS =
        "@media (max-width:600px){"
        + "form[data-component=\"prompt-input-v2\"]>div.flex.h-11"
        + "{height:auto!important;min-height:44px;padding-top:6px;padding-bottom:6px}"
        + "form[data-component=\"prompt-input-v2\"] div.min-w-0.flex-1"
        + "{flex-wrap:wrap!important;row-gap:6px}"
        + "form[data-component=\"prompt-input-v2\"] [data-action=\"prompt-submit\"]"
        + "{flex-shrink:0!important}"
        + "}";

    private void injectMobileCss() {
        if (web == null) return;
        String js = "(function(){var i=document.getElementById('oc-android-css');"
            + "if(!i){i=document.createElement('style');i.id='oc-android-css';"
            + "i.textContent=" + JSONObject.quote(MOBILE_CSS) + ";"
            + "document.head.appendChild(i);}})();";
        web.evaluateJavascript(js, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Sin puerta: la app abre siempre; el asistente va encima y se quita solo.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OpenCode-Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectMobileCss();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                injectMobileCss();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(i, null), 1001);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        splash = new View(this);
        splash.setBackgroundColor(Color.BLACK);
        ImageView icon = new ImageView(this);
        icon.setImageResource(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
        FrameLayout splashWrap = new FrameLayout(this);
        splashWrap.setBackgroundColor(Color.BLACK);
        int pad = (int) (96 * getResources().getDisplayMetrics().density);
        splashWrap.setPadding(pad, pad, pad, pad);
        splashWrap.addView(icon, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(splashWrap, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        splash = splashWrap;

        setContentView(root);

        ensureStorage();
        startService(new Intent(this, OpenCodeService.class));
        waitForServer();
    }

    private void ensureStorage() {
        // No auto-abrir ajustes: el asistente (SetupActivity) lo guía.
        // Auto-abrir aquí era lo que lanzaba menús solos.
    }

    private void waitForServer() {
        new Thread(() -> {
            for (int i = 0; i < 120 && !loaded; i++) {
                if (serverUp()) {
                    loaded = true;
                    final String url = resolveChatUrl();
                    runOnUiThread(() -> {
                        web.loadUrl(url);
                        web.postDelayed(() -> {
                            if (splash != null) splash.setVisibility(View.GONE);
                        }, 1500);
                        if (SetupActivity.needed(MainActivity.this)) {
                            startActivity(new Intent(MainActivity.this,
                                SetupActivity.class));
                        }
                    });
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }).start();
    }

    /** Abre directo en un chat: reutiliza la última sesión o crea una. */
    private static String resolveChatUrl() {
        try {
            String list = http("http://127.0.0.1:4096/session", null);
            org.json.JSONArray arr = new org.json.JSONArray(list);
            String bestId = null, bestProj = "global";
            long bestTime = -1;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject s = arr.getJSONObject(i);
                long t = s.optJSONObject("time") != null
                    ? s.optJSONObject("time").optLong("updated", 0) : 0;
                if (t >= bestTime) {
                    bestTime = t;
                    bestId = s.optString("id", null);
                    bestProj = s.optString("projectID", "global");
                }
            }
            if (bestId == null) {
                String created = http("http://127.0.0.1:4096/session", "{\"title\":\"Chat\"}");
                org.json.JSONObject s = new org.json.JSONObject(created);
                bestId = s.getString("id");
                bestProj = s.optString("projectID", "global");
            }
            return "http://127.0.0.1:4096/" + bestProj + "/session/" + bestId;
        } catch (Exception ignored) {
            return SERVER_URL;
        }
    }

    private static String http(String url, String body) throws Exception {
        java.net.HttpURLConnection c =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes("UTF-8"));
        }
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(o.toByteArray(), "UTF-8");
    }

    private static boolean serverUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(SERVER_URL).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && fileCallback != null) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        uris[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
