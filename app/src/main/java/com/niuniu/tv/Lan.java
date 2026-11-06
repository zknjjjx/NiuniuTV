package com.niuniu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 局域网设置：手机/电脑浏览器打开 http://电视IP:9978 修改设置（仿 TVBox） */
public class Lan {
    public interface Js { void run(String code); }

    private final Context ctx;
    private final Js js;
    private ServerSocket server;
    private int port = 0;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public Lan(Context c, Js js) { this.ctx = c.getApplicationContext(); this.js = js; }

    public static SharedPreferences cfg(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    public void start() {
        new Thread(new Runnable() {
            public void run() {
                for (int p = 9978; p < 9990 && server == null; p++) {
                    try { server = new ServerSocket(p); port = p; } catch (Exception ignored) {}
                }
                if (server == null) return;
                while (!server.isClosed()) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() { public void run() { handle(s); } });
                    } catch (Exception e) { if (server.isClosed()) break; }
                }
            }
        }, "lan").start();
    }

    public void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    public static String ip() {
        String best = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String h = a.getHostAddress();
                    if (a.isSiteLocalAddress()) {
                        if (h.startsWith("192.168.")) return h;
                        if (best.length() == 0) best = h;
                    }
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    public String info() {
        JSONObject o = new JSONObject();
        try {
            String ip = ip();
            if (ip.length() > 0 && port > 0) {
                String url = "http://" + ip + ":" + port;
                o.put("url", url);
                o.put("qr", qr(url));
            }
        } catch (Exception ignored) {}
        return o.toString();
    }

    private static String qr(String text) throws Exception {
        Map<EncodeHintType, Object> h = new HashMap<>();
        h.put(EncodeHintType.MARGIN, 1);
        h.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320, h);
        int w = m.getWidth(), ht = m.getHeight();
        int[] px = new int[w * ht];
        for (int y = 0; y < ht; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        Bitmap b = Bitmap.createBitmap(px, w, ht, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] p = line.split(" ");
            String method = p[0], path = p.length > 1 ? p[1] : "/";
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int i = h.indexOf(':');
                if (i > 0 && h.substring(0, i).trim().equalsIgnoreCase("content-length")) len = Integer.parseInt(h.substring(i + 1).trim());
            }
            if ("POST".equals(method) && path.startsWith("/api/apk")) {  // 推送安装包：边收边写文件
                File f = Upd.target(ctx);
                OutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[65536]; long left = len; int r;
                while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { fo.write(buf, 0, r); left -= r; }
                fo.close();
                String res = left == 0 && len > 0 ? Upd.install(ctx, f, js) : "{\"ok\":false,\"msg\":\"上传不完整\"}";
                send(s.getOutputStream(), 200, "application/json; charset=utf-8", res.getBytes("UTF-8"));
                s.close();
                return;
            }
            byte[] body = new byte[Math.min(len, 65536)];
            int got = 0;
            while (got < body.length) { int r = in.read(body, got, body.length - got); if (r < 0) break; got += r; }
            String req = new String(body, 0, got, "UTF-8");
            OutputStream out = s.getOutputStream();
            if ("GET".equals(method) && (path.equals("/") || path.startsWith("/?") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", asset("web/remote.html"));
            } else if (path.startsWith("/api/state")) {
                JSONObject o = new JSONObject();
                o.put("version", BuildConfigVersion.NAME);
                o.put("srcUrl", cfg(ctx).getString("srcUrl", ""));
                o.put("hist", Hist.all(ctx).length());
                o.put("src", cfg(ctx).getString("srcState", "{}"));
                send(out, 200, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
            } else if ("POST".equals(method) && path.startsWith("/api/")) {
                JSONObject j = req.length() > 0 ? new JSONObject(req) : new JSONObject();
                String act = path.substring(5);
                if (act.equals("src")) {
                    cfg(ctx).edit().putString("srcUrl", j.optString("url", "").trim()).apply();
                    js.run("window.nnRemote&&nnRemote('src')");
                } else if (act.equals("search")) {
                    js.run("window.nnRemote&&nnRemote('search'," + JSONObject.quote(j.optString("q", "")) + ")");
                } else if (act.equals("apkurl")) {
                    final String u = j.optString("url", "").trim();
                    js.run("window.nnRemote&&nnRemote('toast','正在下载安装包…')");
                    String res = Upd.download(ctx, u, js);
                    send(out, 200, "application/json; charset=utf-8", res.getBytes("UTF-8"));
                    s.close();
                    return;
                } else if (act.equals("hist")) {
                    Hist.clear(ctx);
                    js.run("window.nnRemote&&nnRemote('hist')");
                } else { send(out, 404, "text/plain", "no".getBytes()); s.close(); return; }
                send(out, 200, "application/json", "{\"ok\":true}".getBytes());
            } else {
                send(out, 404, "text/plain", "not found".getBytes());
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] asset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }
}
