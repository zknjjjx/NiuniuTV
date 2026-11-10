package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 TVBox 精简：JS 爬虫加载模块与缓存 */
public class FileUtils {
    public static boolean isWeekAgo(File file) { return System.currentTimeMillis() - file.lastModified() > 3L * 24 * 3600 * 1000; }

    private static final Pattern URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public static String loadModule(String name) {
        try {
            if (name.contains("gbk.js")) name = "gbk.js";
            else if (name.contains("模板.js")) name = "模板.js";
            else if (name.contains("cat.js")) name = "cat.js";
            Matcher m = URL_JOIN.matcher(name);
            if (m.find()) {
                String cache = getCache(MD5.encode(name));
                if (!TextUtils.isEmpty(cache)) return cache;
                String net = get(name);
                if (!TextUtils.isEmpty(net)) setCache(604800, MD5.encode(name), net);
                return net;
            } else if (name.startsWith("assets://")) return getAsOpen(name.substring(9));
            else if (isAsFile(name, "js/lib")) return getAsOpen("js/lib/" + name);
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private static final Map<String, Set<String>> dirs = new HashMap<>();
    public static synchronized boolean isAsFile(String name, String dir) {
        Set<String> f = dirs.get(dir);
        if (f == null) {
            try { f = new HashSet<>(Arrays.asList(App.getInstance().getAssets().list(dir))); } catch (Exception e) { f = Collections.emptySet(); }
            dirs.put(dir, f);
        }
        return f.contains(name.trim());
    }

    public static String getAsOpen(String name) {
        try {
            InputStream is = App.getInstance().getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    public static String getCache(String name) {
        try {
            File f = open(name);
            if (!f.exists()) return "";
            JSONObject o = new JSONObject(new String(read(f), "UTF-8"));
            if (o.optLong("expires") <= System.currentTimeMillis() / 1000) { f.delete(); return ""; }
            return o.optString("data");
        } catch (Exception e) { return ""; }
    }

    public static void setCache(int time, String name, String data) {
        try {
            JSONObject o = new JSONObject();
            o.put("expires", time + System.currentTimeMillis() / 1000);
            o.put("data", data);
            write(o.toString().getBytes("UTF-8"), open(name));
        } catch (Exception ignored) {}
    }

    public static void setCacheByte(String name, byte[] data) {
        try {
            byte[] h = "//DRPY".getBytes(), b = Base64.encode(data, Base64.URL_SAFE), all = new byte[h.length + b.length];
            System.arraycopy(h, 0, all, 0, h.length); System.arraycopy(b, 0, all, h.length, b.length);
            write(all, open("B_" + name));
        } catch (Exception ignored) {}
    }

    public static String get(String url) { return get(url, null); }
    public static String get(String url, Map<String, String> h) {
        if (h == null) { h = new HashMap<>(); h.put("User-Agent", "okhttp/3.15"); }
        return OkHttp.string(url, h);
    }

    public static File open(String name) { return new File(App.getInstance().getCacheDir(), "qjscache_" + name + ".js"); }

    private static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()]; int o = 0, n;
        while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
        in.close();
        return b;
    }
    private static void write(byte[] b, File f) throws Exception { FileOutputStream o = new FileOutputStream(f); o.write(b); o.close(); }
}
