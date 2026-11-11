package com.niuniu.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 网页嗅探：爬虫给的是播放页（parse=1）时，用隐藏的 WebView 打开它，
 * 抓页面自己请求的第一个视频地址（m3u8/mp4/flv…），连同 Referer、UA 一起交给播放器。
 * 同时最多 2 个，免得老电视内存吃不消。
 */
final class Sniff {
    private static final String TAG = "Sniff";
    private static final Semaphore slots = new Semaphore(2);
    private static final Handler ui = new Handler(Looper.getMainLooper());
    static final Pattern VIDEO = Pattern.compile("(?i)\\.(m3u8|mp4|flv|m4a|mkv|ts)(\\?|#|$)|\\.m3u8|/m3u8|video/|mime=video|type=m3u8|\\.mp4\\?");
    static final Pattern NOT = Pattern.compile("(?i)\\.(js|css|png|jpe?g|gif|webp|ico|svg|woff2?|ttf|html?)(\\?|$)|google|baidu\\.com/|cnzz|umeng|hm\\.baidu|/ad[s/]|doubleclick|\\.php\\?url=http|[?&]url=http");

    static final String CHROME = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final class Hit { String url; Map<String, String> headers = new HashMap<>(); }

    @SuppressLint("SetJavaScriptEnabled")
    static Hit run(final Context ctx, final String page, final Map<String, String> reqHeaders, int timeoutMs) {
        try { if (!slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return null; } catch (InterruptedException e) { return null; }
        final CountDownLatch done = new CountDownLatch(1);
        final Hit hit = new Hit();
        final WebView[] holder = new WebView[1];
        try {
            ui.post(new Runnable() { public void run() {
                try {
                    WebView w = new WebView(ctx);
                    holder[0] = w;
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setMediaPlaybackRequiresUserGesture(false);
                    s.setBlockNetworkImage(true);
                    s.setLoadsImagesAutomatically(false);
                    if (android.os.Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    final String ua = reqHeaders != null && reqHeaders.containsKey("User-Agent") ? reqHeaders.get("User-Agent") : CHROME;
                    s.setUserAgentString(ua);
                    w.setWebViewClient(new WebViewClient() {
                        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                            check(r.getUrl().toString(), r.getRequestHeaders());
                            return null;
                        }
                        @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                            check(url, null);
                            return null;
                        }
                        private void check(String u, Map<String, String> h) {
                            if (done.getCount() == 0 || u == null || !u.startsWith("http")) return;
                            if (u.equals(page) || NOT.matcher(u).find() || !VIDEO.matcher(u).find()) return;
                            synchronized (hit) {
                                if (hit.url != null) return;
                                hit.url = u;
                                if (h != null) for (Map.Entry<String, String> e : h.entrySet()) {
                                    String k = e.getKey();
                                    if ("Referer".equalsIgnoreCase(k) || "Origin".equalsIgnoreCase(k) || "Cookie".equalsIgnoreCase(k)) hit.headers.put(k, e.getValue());
                                }
                                if (!hit.headers.containsKey("Referer")) hit.headers.put("Referer", page);
                                hit.headers.put("User-Agent", ua);
                            }
                            done.countDown();
                        }
                    });
                    Map<String, String> h = new HashMap<>();
                    if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet()) if (!"User-Agent".equalsIgnoreCase(e.getKey())) h.put(e.getKey(), e.getValue());
                    w.loadUrl(page, h);
                } catch (Throwable e) { Log.w(TAG, "start " + e); done.countDown(); }
            } });
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            Log.w(TAG, "run " + e);
        } finally {
            ui.post(new Runnable() { public void run() {
                WebView w = holder[0];
                if (w != null) { try { w.stopLoading(); w.loadUrl("about:blank"); w.destroy(); } catch (Throwable ignore) {} }
            } });
            slots.release();
        }
        Log.i(TAG, page + " -> " + hit.url);
        return hit.url == null ? null : hit;
    }

    private Sniff() {}
}
