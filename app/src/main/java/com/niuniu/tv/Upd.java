package com.niuniu.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 局域网推送安装包 / 在线升级自己。安装包只放在 App 缓存目录，装完（或取消）回到 App 即删除 */
public class Upd {
    static long lastInstall = 0;

    static File dir(Context c) {
        File d = new File(Build.VERSION.SDK_INT >= 24 || c.getExternalCacheDir() == null ? c.getCacheDir() : c.getExternalCacheDir(), "apk");
        d.mkdirs();
        return d;
    }

    static File target(Context c) {
        cleanup(c, true);
        return new File(dir(c), "push-" + System.currentTimeMillis() + ".apk");
    }

    /** 删掉所有残留安装包（两个缓存目录都清）。force=false 时，刚发起安装的 5 秒内不删，免得安装器还没读完 */
    static int cleanup(Context c, boolean force) {
        if (!force && System.currentTimeMillis() - lastInstall < 5000) return 0;
        int n = 0;
        File[] roots = { c.getCacheDir(), c.getExternalCacheDir() };
        for (File r : roots) {
            if (r == null) continue;
            File[] fs = new File(r, "apk").listFiles();
            if (fs != null) for (File f : fs) if (f.delete()) n++;
            File[] top = r.listFiles();
            if (top != null) for (File f : top) if (f.isFile() && f.getName().endsWith(".apk") && f.delete()) n++;
        }
        return n;
    }

    /** 在线升级：按顺序试几个下载地址（加速镜像在前），下完校验是本 App 再安装 */
    static String upgrade(Context c, String[] urls, Lan.Js js) {
        String last = "没有下载地址";
        for (String u : urls) {
            String r = download(c, u, js, true);
            try { if (new JSONObject(r).optBoolean("ok")) return r; last = new JSONObject(r).optString("msg"); } catch (Exception e) { last = r; }
        }
        cleanup(c, true);
        js.run("window.nnUpg&&nnUpg(" + JSONObject.quote(last) + ")");
        return err(last);
    }

    static String download(Context c, String url, Lan.Js js) { return download(c, url, js, false); }

    static String download(Context c, String url, Lan.Js js, boolean self) {
        try {
            File f = target(c);
            String u = url;
            HttpURLConnection con = null;
            for (int i = 0; i < 6; i++) {
                con = (HttpURLConnection) new URL(u).openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", "Mozilla/5.0 NiuniuTV");
                int code = con.getResponseCode();
                if (code / 100 == 3) { u = new URL(new URL(u), con.getHeaderField("Location")).toString(); con.disconnect(); continue; }
                if (code != 200) { f.delete(); return err("下载失败 HTTP " + code); }
                break;
            }
            long total = con.getContentLength(), got = 0; int step = -1;
            InputStream in = con.getInputStream();
            OutputStream o = new FileOutputStream(f);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n); got += n;
                if (self && total > 0 && (int) (got * 10 / total) != step) { step = (int) (got * 10 / total); js.run("window.nnUpg&&nnUpg(" + (step * 10) + ")"); }
            }
            o.close(); in.close();
            if (total > 0 && got != total) { f.delete(); return err("下载不完整"); }
            if (self) {
                PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !c.getPackageName().equals(pi.packageName)) { f.delete(); return err("下载的不是妞妞影视安装包"); }
            }
            return install(c, f, js);
        } catch (Exception e) {
            cleanup(c, true);
            return err("下载失败：" + e.getClass().getSimpleName());
        }
    }

    static String install(Context c, File f, Lan.Js js) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) { f.delete(); return err("不是有效的安装包"); }
            String label = pi.packageName + " " + (pi.versionName == null ? "" : pi.versionName);
            if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(s);
                js.run("window.nnRemote&&nnRemote('toast','请在电视上允许“安装未知应用”，然后再推送一次')");
                return err("电视需要先允许安装未知应用，允许后请再推送一次");
            }
            f.setReadable(true, false);
            lastInstall = System.currentTimeMillis();
            Uri uri = Build.VERSION.SDK_INT >= 24 ? FileProvider.getUriForFile(c, c.getPackageName() + ".fp", f) : Uri.fromFile(f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(i);
            js.run("window.nnRemote&&nnRemote('toast'," + JSONObject.quote("收到安装包 " + label + "，请在电视上确认安装") + ")");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", "已发到电视：" + label + "，请在电视上点“安装”");
            return o.toString();
        } catch (Exception e) {
            return err("安装失败：" + e.getClass().getSimpleName());
        }
    }

    private static String err(String m) {
        try { JSONObject o = new JSONObject(); o.put("ok", false); o.put("msg", m); return o.toString(); } catch (Exception e) { return "{\"ok\":false}"; }
    }
}
