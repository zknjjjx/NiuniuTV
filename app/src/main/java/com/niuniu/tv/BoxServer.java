package com.niuniu.tv;

import android.net.Uri;
import android.util.Log;

import com.github.tvbox.osc.server.RemoteServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 爬虫本地代理：只监听 127.0.0.1，处理 /proxy?do=...（爬虫返回 [状态码, 类型, 数据流, 头]） */
public class BoxServer {
    static volatile int port = 0;
    private static ServerSocket server;
    private static final ExecutorService pool = Executors.newCachedThreadPool();

    static synchronized void start() {
        if (server != null) return;
        for (int p = 9966; p < 9976 && server == null; p++) {
            try { server = new ServerSocket(p, 50, InetAddress.getByName("127.0.0.1")); port = p; } catch (Exception ignored) {}
        }
        if (server == null) return;
        RemoteServer.serverPort = port;
        com.github.catvod.Proxy.set(port);
        new Thread(new Runnable() { public void run() {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    pool.execute(new Runnable() { public void run() { handle(s); } });
                } catch (Exception e) { if (server.isClosed()) break; }
            }
        } }, "boxsrv").start();
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] p = line.split(" ");
            String path = p.length > 1 ? p[1] : "/";
            Map<String, String> params = new HashMap<>();
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int i = h.indexOf(':');
                if (i > 0) params.put(h.substring(0, i).trim().toLowerCase(), h.substring(i + 1).trim());
            }
            Uri u = Uri.parse("http://127.0.0.1" + path);
            for (String k : u.getQueryParameterNames()) params.put(k, u.getQueryParameter(k));
            OutputStream out = s.getOutputStream();
            Object[] rs = u.getPath() != null && (u.getPath().equals("/proxy") || u.getPath().equals("/")) && params.containsKey("do") ? Box.proxy(params) : null;
            if (rs == null || rs.length < 3) {
                write(out, 404, "text/plain", null, new ByteArrayInputStream("not found".getBytes()));
            } else {
                int code = rs[0] instanceof Integer ? (Integer) rs[0] : 200;
                String mime = rs[1] == null ? "application/octet-stream" : String.valueOf(rs[1]);
                InputStream body = rs[2] instanceof InputStream ? (InputStream) rs[2] : rs[2] instanceof byte[] ? new ByteArrayInputStream((byte[]) rs[2]) : rs[2] instanceof String ? new ByteArrayInputStream(((String) rs[2]).getBytes("UTF-8")) : new ByteArrayInputStream(new byte[0]);
                @SuppressWarnings("unchecked") Map<String, String> hd = rs.length > 3 && rs[3] instanceof Map ? (Map<String, String>) rs[3] : null;
                write(out, code, mime, hd, body);
            }
            s.close();
        } catch (Throwable e) {
            Log.w("NNBox", "proxy srv " + e);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void write(OutputStream out, int code, String mime, Map<String, String> hd, InputStream body) throws Exception {
        StringBuilder b = new StringBuilder("HTTP/1.1 ").append(code).append(code < 300 ? " OK" : code < 400 ? " Found" : " Error").append("\r\n");
        b.append("Content-Type: ").append(mime).append("\r\n");
        if (hd != null) for (Map.Entry<String, String> e : hd.entrySet()) {
            if (e.getKey() == null || e.getKey().equalsIgnoreCase("content-type") || e.getKey().equalsIgnoreCase("transfer-encoding") || e.getKey().equalsIgnoreCase("connection")) continue;
            b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        b.append("Connection: close\r\n\r\n");
        out.write(b.toString().getBytes("UTF-8"));
        byte[] buf = new byte[32768]; int n;
        try { while ((n = body.read(buf)) > 0) out.write(buf, 0, n); } finally { try { body.close(); } catch (Exception ignored) {} }
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) break;
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }
}
