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

/** 局域网推送安装包 / 升级自己 */
public class Upd {
    static File target(Context c) {
        File dir = new File(Build.VERSION.SDK_INT >= 24 || c.getExternalCacheDir() == null ? c.getCacheDir() : c.getExternalCacheDir(), "apk");
        dir.mkdirs();
        File f = new File(dir, "push.apk");
        if (f.exists()) f.delete();
        return f;
    }

    static String download(Context c, String url, Lan.Js js) {
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
                if (code != 200) return err("下载失败 HTTP " + code);
                break;
            }
            InputStream in = con.getInputStream();
            OutputStream o = new FileOutputStream(f);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.close(); in.close();
            return install(c, f, js);
        } catch (Exception e) {
            return err("下载失败：" + e.getClass().getSimpleName());
        }
    }

    static String install(Context c, File f, Lan.Js js) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) return err("不是有效的安装包");
            String label = pi.packageName + " " + (pi.versionName == null ? "" : pi.versionName);
            if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(s);
                js.run("window.nnRemote&&nnRemote('toast','请在电视上允许“安装未知应用”，然后再推送一次')");
                return err("电视需要先允许安装未知应用，允许后请再推送一次");
            }
            f.setReadable(true, false);
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
