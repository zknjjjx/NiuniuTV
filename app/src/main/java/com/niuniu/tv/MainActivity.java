package com.niuniu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(16);
    private final ExecutorService probes = Executors.newFixedThreadPool(3);
    private final ExecutorService dbPool = Executors.newFixedThreadPool(6);  // 豆瓣单独排队，不被片源搜索堵住
    private long lastBack = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.init();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0C10"));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Bridge(), "NN");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                // 豆瓣图片要带 Referer 才给
                if (u.contains("doubanio.com")) {
                    try {
                        JSONObject h = new JSONObject();
                        h.put("Referer", "https://movie.douban.com/");
                        Net.Res r = Net.get(u, h, 10000, 0);
                        String t = r.type.split(";")[0];
                        if (t.length() == 0) t = "image/jpeg";
                        return new WebResourceResponse(t, null, new ByteArrayInputStream(r.body));
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl("file:///android_asset/web/index.html");
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() { if (web != null) web.evaluateJavascript(code, null); }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public void fetch(final String id, final String url, final String headers, final int timeout) {
            (url.contains("douban.com") ? dbPool : pool).execute(new Runnable() {
                public void run() {
                    int code = 0;
                    String body = "";
                    long ms = 0;
                    try {
                        JSONObject h = headers == null || headers.length() == 0 ? null : new JSONObject(headers);
                        Net.Res r = Net.get(url, h, timeout <= 0 ? 10000 : timeout, 8 * 1024 * 1024);
                        code = r.code;
                        body = Net.text(r);
                        ms = r.ms;
                    } catch (Exception e) {
                        code = 0;
                        body = e.getClass().getSimpleName();
                    }
                    js("window.__nnCb&&__nnCb(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + "," + ms + ")");
                }
            });
        }

        @JavascriptInterface
        public void probe(final String id, final String url, final int budget, final int max) {
            probes.execute(new Runnable() {
                public void run() {
                    JSONObject o = Net.probe(url, budget, max);
                    js("window.__nnCb&&__nnCb(" + JSONObject.quote(id) + ",200," + JSONObject.quote(o.toString()) + ",0)");
                }
            });
        }

        @JavascriptInterface
        public void play(final String json) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra("data", json);
                    startActivity(i);
                }
            });
        }

        @JavascriptInterface
        public String history() { return Hist.all(MainActivity.this).toString(); }

        @JavascriptInterface
        public void delHistory(String key) { Hist.remove(MainActivity.this, key); }

        @JavascriptInterface
        public String version() { return BuildConfigVersion.NAME; }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface
        public void exit() { runOnUiThread(new Runnable() { public void run() { finish(); } }); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.nnResume&&nnResume()", null);
        hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.nnBack?nnBack():false", new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        if (!"true".equals(v)) {
                            long now = System.currentTimeMillis();
                            if (now - lastBack < 2000) finish();
                            else { lastBack = now; Toast.makeText(MainActivity.this, "再按一次返回键退出", Toast.LENGTH_SHORT).show(); }
                        }
                    }
                });
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            web.evaluateJavascript("window.nnMenu&&nnMenu()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        probes.shutdownNow();
        dbPool.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
