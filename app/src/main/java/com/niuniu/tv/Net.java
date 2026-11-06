package com.niuniu.tv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONObject;

/** 简单 HTTP：给网页层用（绕过跨域）、给测速用。片源站证书五花八门，统一放行。 */
public class Net {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    static {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
            });
        } catch (Exception ignored) {}
    }

    public static void init() { /* 触发静态块 */ }

    public static class Res {
        public int code;
        public byte[] body;
        public String type = "";
        public String finalUrl;
        public long ms;
    }

    public static HttpURLConnection open(String url, JSONObject headers, int timeout) throws Exception {
        String u = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(Math.min(timeout, 8000));
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", UA);
            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, headers.optString(k)); }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("too many redirects");
    }

    public static Res get(String url, JSONObject headers, int timeout, int maxBytes) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = open(url, headers, timeout);
        Res r = new Res();
        r.code = c.getResponseCode();
        r.type = c.getContentType() == null ? "" : c.getContentType();
        r.finalUrl = c.getURL().toString();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (maxBytes > 0 && bo.size() >= maxBytes) break;
            }
            in.close();
        }
        c.disconnect();
        r.body = bo.toByteArray();
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    public static String text(Res r) {
        try { return new String(r.body, "UTF-8"); } catch (Exception e) { return ""; }
    }

    /** 测速：m3u8 → (子列表) → 第一个切片下载最多 maxBytes，返回字节/秒；不通返回 -1 */
    public static JSONObject probe(String url, int budgetMs, int maxBytes) {
        JSONObject o = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            String u = url;
            String seg = null;
            for (int depth = 0; depth < 3 && seg == null; depth++) {
                Res r = get(u, null, Math.max(2000, budgetMs), 2 * 1024 * 1024);
                if (r.code >= 400) throw new Exception("http " + r.code);
                String body = text(r);
                if (!body.contains("#EXT")) {
                    // 不是 m3u8，直接当视频文件测
                    seg = u;
                    break;
                }
                String base = r.finalUrl;
                String next = null;
                boolean variant = body.contains("#EXT-X-STREAM-INF");
                int h = 0;
                for (String line : body.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(line);
                        if (m.find()) h = Math.max(h, Integer.parseInt(m.group(1)));
                    }
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    next = new URL(new URL(base), line).toString();
                    break;
                }
                if (h > 0) o.put("h", h);
                if (next == null) throw new Exception("empty");
                if (variant) u = next; else seg = next;
            }
            if (seg == null) throw new Exception("no seg");
            long t1 = System.currentTimeMillis();
            o.put("ttfb", t1 - t0);
            int left = (int) Math.max(1500, budgetMs - (t1 - t0));
            HttpURLConnection c = open(seg, null, left);
            if (c.getResponseCode() >= 400) throw new Exception("seg " + c.getResponseCode());
            InputStream in = c.getInputStream();
            byte[] buf = new byte[32768];
            long got = 0, ts = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got >= maxBytes || System.currentTimeMillis() - ts > left) break;
            }
            in.close();
            c.disconnect();
            long dt = Math.max(1, System.currentTimeMillis() - ts);
            if (got < 4096) throw new Exception("tiny");
            o.put("bps", got * 1000 / dt);
        } catch (Exception e) {
            try { o.put("bps", -1); o.put("err", e.getClass().getSimpleName()); } catch (Exception ignored) {}
        }
        return o;
    }
}
