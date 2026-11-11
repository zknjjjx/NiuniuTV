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

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(16);
    private final ExecutorService probes = Executors.newFixedThreadPool(6);
    private final ExecutorService dbPool = Executors.newFixedThreadPool(6);  // 豆瓣单独排队，不被片源搜索堵住
    private long lastBack = 0;
    private Lan lan;
    private boolean phone;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.init();
        new Thread(new Runnable() { public void run() { Pre.cache(MainActivity.this); } }).start();   // 每次开 App 清空播放缓存
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        // 电视：网页画出来之前透明，露出窗口背景里的启动狗狗，避免闪一下
        web.setBackgroundColor(Ui.phone(this) ? Color.parseColor("#0B0C10") : Color.TRANSPARENT);
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
        Upd.cleanup(this, true);  // 升级装完重启、或上次推送的安装包，一律清掉
        phone = Ui.phone(this);
        if (phone) {  // 手机：保留状态栏，不开局域网推送
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 21) { getWindow().setStatusBarColor(Color.parseColor("#0B0C10")); getWindow().setNavigationBarColor(Color.parseColor("#0B0C10")); }
        } else {
            lan = new Lan(this, new Lan.Js() { public void run(String code) { js(code); } });
            lan.start();
        }
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
        /** 网页启动动画结束：WebView 恢复不透明，去掉窗口背景里的狗狗 */
        @JavascriptInterface
        public void splashDone() {
            runOnUiThread(new Runnable() { public void run() {
                if (web != null) web.setBackgroundColor(Color.parseColor("#0B0C10"));
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.parseColor("#0B0C10")));
            } });
        }

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
            try { searching = new org.json.JSONObject(json).optBoolean("searching", false); } catch (Exception e) { searching = false; }
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra("data", json);
                    startActivity(i);
                }
            });
        }

        /** 网页搜源结束：播放中就让网页停下来 */
        @JavascriptInterface
        public void searchDone() {
            searching = false;
            PlayerActivity.searchEnded();
            runOnUiThread(new Runnable() { public void run() { if (paused && web != null) web.pauseTimers(); } });
        }

        /** 起播后网页还在搜源：新找到的片源送给正在播的播放器，加进换源列表并在后台测速 */
        @JavascriptInterface
        public void addSrc(final String key, final String json) {
            runOnUiThread(new Runnable() { public void run() { PlayerActivity.addSource(key, json); } });
        }

        /** 详情页测速定下片源后预载开头几分钟；地址变了就删旧缓存换新的 */
        @JavascriptInterface
        public void prefetch(final String url, final double posMs) {
            pool.execute(new Runnable() { public void run() { Pre.start(MainActivity.this, url, (long) posMs, Pre.PRELOAD_MS); } });
        }

        @JavascriptInterface
        public String history() { return Hist.all(MainActivity.this).toString(); }

        @JavascriptInterface
        public void delHistory(String key) { Hist.remove(MainActivity.this, key); }

        @JavascriptInterface
        public String cfg(String k) { return Lan.cfg(MainActivity.this).getString(k, ""); }

        @JavascriptInterface
        public void setCfg(String k, String v) { Lan.cfg(MainActivity.this).edit().putString(k, v == null ? "" : v).apply(); }

        @JavascriptInterface
        public String lan() { return lan == null ? "{}" : lan.info(); }

        @JavascriptInterface
        public String apkInfo() { return Upd.info(MainActivity.this).toString(); }

        @JavascriptInterface
        public int apkClean() { return Upd.cleanup(MainActivity.this, true); }

        @JavascriptInterface
        public boolean phone() { return phone; }

        @JavascriptInterface
        public String version() { return BuildConfigVersion.NAME; }

        /** 在线升级：urls 为 JSON 数组，按顺序尝试；进度和失败通过 nnUpg 回调 */
        @JavascriptInterface
        public void upgrade(final String urls) {
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        org.json.JSONArray a = new org.json.JSONArray(urls);
                        String[] u = new String[a.length()];
                        for (int i = 0; i < u.length; i++) u[i] = a.getString(i);
                        Upd.upgrade(MainActivity.this, u, new Lan.Js() { public void run(String code) { js(code); } });
                    } catch (Exception e) { js("window.nnUpg&&nnUpg('升级失败')"); }
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface
        public void exit() { runOnUiThread(new Runnable() { public void run() { finish(); } }); }
    }

    private volatile boolean searching, paused;
    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        // 播放时让界面完全停下（动画、定时器），把性能全留给视频；还在搜源就等搜完（最多 90 秒）再停
        if (web != null) {
            web.onPause();
            if (!searching) web.pauseTimers();
            else web.postDelayed(new Runnable() { public void run() { if (paused && web != null) { searching = false; web.pauseTimers(); } } }, 90000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        if (web != null) { web.onResume(); web.resumeTimers(); }
        Upd.cleanup(this, false);  // 从安装界面回来（装好或取消）即删安装包
        if (web != null) web.evaluateJavascript("window.nnResume&&nnResume()", null);
        if (!phone) hideBars();
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
        if (lan != null) lan.stop();
        probes.shutdownNow();
        dbPool.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
