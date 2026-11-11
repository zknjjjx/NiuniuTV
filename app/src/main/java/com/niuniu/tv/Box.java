package com.niuniu.tv;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.crawler.pyLoader;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TVBox 接口：读配置（解伪装/解密）、加载 jar/JS/Python 爬虫，按片名搜索、取详情、取播放地址。
 * 爬虫运行时（JarLoader/JsLoader/pyLoader 等）移植自 TVBoxOS（AGPL-3.0）。
 * 片源地址在网页侧记作 box://站点key/线路/剧集id，播放、测速、预下载前由 resolve() 换成真实地址，并登记它要的请求头。
 */
public class Box {
    static final String TAG = "NNBox";
    static final String UA = "okhttp/3.15";
    /** 网盘、扫码、登录、推送、直播、听书、音乐等：老人用不了或不是影视，直接不要 */
    /** 站名：只认明确的非影视词，不用「云」「球」「舞」「弹幕」这类会误伤的单字 */
    static final Pattern SKIP = Pattern.compile("网盘|云盘|阿里|夸克|天翼|迅雷|百度|PikPak|pikpak|Alist|alist|AList|扫码|登录|推送|直播|虎牙|斗鱼|听书|评书|有声|音乐|歌曲|演唱会|MV|KTV|ktv|舞曲|跳舞|戏曲|相声|广播|电台|体育|看球|配置|设置|公告|说明|DIY|diy|Emby|emby|WebDAV|webdav|哔哩|哔哔|B站|YouTube|Youtube|油管|教育|课堂|小学|初中|高中|少儿|儿歌|启蒙|漫画|小说|磁力|种子|网红|急救|壁纸|本地|商店|市场|JustLive|盘搜|四盘|易搜|DJ|BT");
    /** 爬虫类名/脚本地址：网盘、分享、搜盘、直播、B站、推送、配置等类 */
    static final Pattern SKIP_API = Pattern.compile("(?i)pan|share|aliyun|alips|quark|thunder|xunlei|p115|ucshare|pikpak|alist|webdav|samba|wogg|wobg|wo4k|wexwo|upyun|yiso|csp_jso|jiansou|tgyun|seedhub|s_zps|fishps|yisou|hdh\\b|tzfile|xiaozhang|fourkhdr|bili|kanqiu|live|push|config|market|localfile|csp_local|music|kugou|kuwo|djuu|firstaid|wallpaper|sport|(?-i:TY(Guard)?$)");
    static final Pattern ADULT = Pattern.compile("成人|福利|伦理|色|情色|AV|av|里番|写真|18\\+|🔞");

    static class Site { String key, name, api, ext, jar; int type; }

    private static Context app;
    private static final JarLoader jar = new JarLoader();
    private static final JsLoader js = new JsLoader();
    private static final pyLoader py = new pyLoader();
    private static final List<Site> sites = new ArrayList<>();
    private static final Map<String, Site> byKey = new ConcurrentHashMap<>();
    private static final ExecutorService run = Executors.newCachedThreadPool();
    private static String loadedUrl = "", state = "未加载", cmsJson = "[]";
    private static volatile boolean loading = false;
    private static String recentKey = "";

    /** JSON 解析接口（配置 parses 里 type=1 的）：地址后拼官网播放页，返回 {"url":"…m3u8"} */
    static class Jx { String name, url; List<String> flags = new ArrayList<>(); Map<String, String> headers = new HashMap<>(); }
    private static final List<Jx> jxs = Collections.synchronizedList(new ArrayList<Jx>());

    static void init(Context c) { if (app == null) { app = c.getApplicationContext(); com.github.catvod.crawler.JsLoader.MAX = lowRam() ? 6 : 16; } }

    // ---------------- 配置 ----------------

    /** 加载（或复用已加载的）接口；返回 {ok,n,state} */
    static synchronized JSONObject load(String url, boolean force) {
        JSONObject r = new JSONObject();
        try {
            if (url == null || url.trim().isEmpty()) { clear(); state = "未设置接口"; r.put("ok", false); r.put("state", state); return r; }
            url = url.trim();
            if (!force && url.equals(loadedUrl) && !sites.isEmpty()) { r.put("ok", true); r.put("n", sites.size()); r.put("state", state); return r; }
            loading = true;
            File cache = new File(dir("cfg"), MD5.string2MD5(url) + ".json");
            String text = null, base = url;
            if (!force && cache.exists()) {  // 先用上次成功的配置马上开用，后台再更新缓存（下次生效）
                try {
                    String all = new String(read(cache), "UTF-8");
                    int i = all.indexOf('\n');
                    parse(all.substring(0, i), all.substring(i + 1));
                    loadedUrl = url;
                    state = sites.size() + " 个爬虫源";
                    final String u = url; final File cf = cache;
                    new Thread(() -> {
                        try { String[] g = fetchConfig(u, 0); if (g[0] != null) write(cf, (g[1] + "\n" + g[0]).getBytes("UTF-8")); }
                        catch (Throwable e) { Log.w(TAG, "config refresh: " + e); }
                    }).start();
                    r.put("ok", true); r.put("n", sites.size()); r.put("state", state); return r;
                } catch (Throwable e) { Log.w(TAG, "config cache: " + e); clear(); }
            }
            try {
                String[] got = fetchConfig(url, 0);
                text = got[0]; base = got[1];
                if (text != null) write(cache, (base + "\n" + text).getBytes("UTF-8"));
            } catch (Throwable e) { Log.w(TAG, "config fetch: " + e); }
            if (text == null && cache.exists()) {  // 网络不通：用上次成功的
                String all = new String(read(cache), "UTF-8");
                int i = all.indexOf('\n');
                base = all.substring(0, i); text = all.substring(i + 1);
            }
            if (text == null) { state = "接口打不开"; r.put("ok", false); r.put("state", state); return r; }
            parse(base, text);
            loadedUrl = url;
            state = sites.size() + " 个爬虫源";
            r.put("ok", true); r.put("n", sites.size()); r.put("state", state);
        } catch (Throwable e) {
            Log.e(TAG, "load", e);
            state = "接口解析失败";
            try { r.put("ok", false); r.put("state", state); } catch (Exception ignored) {}
        } finally { loading = false; }
        return r;
    }

    static void clear() {
        sites.clear(); byKey.clear(); jxs.clear(); loadedUrl = ""; cmsJson = "[]";
        try { jar.clear(); js.clear(); py.clear(); } catch (Throwable ignored) {}
    }

    /** 拉配置：处理多仓（urls）、图片伪装、AES 加密、相对路径。返回 [正文, 基准地址] */
    private static String[] fetchConfig(String url, int depth) throws Exception {
        String key = null;
        if (url.contains(";pk;")) { String[] a = url.split(";pk;"); url = a[0]; key = a[1]; }
        if (!url.startsWith("http")) url = "http://" + url;
        String raw = httpText(url, 15000);
        String text = decode(raw, key);
        text = text.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        // 多仓：{"urls":[{"url":..,"name":..}]} 或 {"storeHouse":[{"sourceUrl":..}]}，取第一个能用的
        if (depth < 2 && text.startsWith("{")) {
            JSONObject o = lenient(text);
            JSONArray arr = o == null ? null : o.optJSONArray("urls");
            if (arr == null && o != null) arr = o.optJSONArray("storeHouse");
            if (arr != null && o.optJSONArray("sites") == null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i); if (e == null) continue;
                    String u = e.optString("url", e.optString("sourceUrl", ""));
                    if (u.isEmpty()) continue;
                    try { return fetchConfig(abs(url, u), depth + 1); } catch (Throwable ignored) {}
                }
            }
        }
        return new String[]{fixPath(url, text), url};
    }

    /** 与 TVBox 相同的解码：xxxxxxxx** 后跟 base64（藏在图片里），2423 开头为 AES-CBC，;pk; 为 AES-ECB */
    static String decode(String content, String key) {
        String json = content;
        try {
            if (AES.isJson(content)) return content;
            Matcher m = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(content);
            if (m.find()) {
                content = content.substring(content.indexOf(m.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT), "UTF-8");
            }
            content = content.trim();
            if (content.startsWith("2423")) {
                content = content.replaceAll("\\s+", "");
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String k = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, k, iv);
            } else if (key != null && !AES.isJson(content)) json = AES.ECB(content, key);
            else json = content;
        } catch (Throwable e) { Log.w(TAG, "decode " + e); }
        return json;
    }

    private static String fixPath(String url, String content) {
        if (!content.contains("\"./") && !content.contains("\"../")) return content;
        String b1 = abs(url, "../"), b0 = abs(url, "./");
        return content.replace("\"../", "\"" + b1).replace("\"./", "\"" + b0);
    }

    static String abs(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    /** 配置常带 // 注释、结尾逗号，宽松解析 */
    static JSONObject lenient(String s) {
        try { return new JSONObject(s); } catch (Exception ignored) {}
        try {
            StringBuilder b = new StringBuilder();
            for (String line : s.split("\n")) { String t = line.trim(); if (t.startsWith("//") || t.startsWith("#")) continue; b.append(line).append('\n'); }
            String t = b.toString().replaceAll(",\\s*([}\\]])", "$1");
            return new JSONObject(t);
        } catch (Exception e) { return null; }
    }

    private static void parse(String base, String text) throws Exception {
        JSONObject o = lenient(text);
        if (o == null) throw new Exception("bad json");
        clear();
        // 主爬虫 jar
        String spider = o.optString("spider", "");
        if (!spider.isEmpty()) {
            try { loadMainJar(abs(base, spider)); } catch (Throwable e) { Log.w(TAG, "main jar " + e); }
        }
        boolean hasPy = false;
        JSONArray a = o.optJSONArray("sites");
        JSONArray cms = new JSONArray();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i); if (s == null) continue;
            String name = s.optString("name", ""), api = s.optString("api", "");
            int type = s.optInt("type", 0);
            if (ADULT.matcher(name).find()) continue;
            if ((type == 0 || type == 1) && api.startsWith("http")) {  // 配置里顺带的采集站：交给网页侧和采集源一起用
                JSONObject c = new JSONObject(); c.put("name", clean(name)); c.put("api", api); c.put("type", type); cms.put(c);
                continue;
            }
            if (type != 3) continue;
            if (s.optInt("searchable", 1) == 0) continue;
            if (SKIP.matcher(name).find() || SKIP_API.matcher(api).find()) continue;
            Site t = new Site();
            t.key = s.optString("key", api + i); t.name = clean(name); t.api = api; t.type = type;
            Object ext = s.opt("ext");
            t.ext = ext == null ? "" : ext instanceof JSONObject || ext instanceof JSONArray ? ext.toString() : String.valueOf(ext);
            t.jar = s.optString("jar", "");
            if (!t.jar.isEmpty()) t.jar = abs(base, t.jar);
            // 老写法：api 写 py_xxx，脚本地址放在 ext
            if (api.startsWith("py_") && t.ext.contains(".py")) { t.api = abs(base, t.ext); t.ext = ""; }
            if (t.name.isEmpty()) t.name = t.key;
            if (t.api.contains(".py")) hasPy = true;
            sites.add(t); byKey.put(t.key, t);
        }
        cmsJson = cms.toString();
        JSONArray ps = o.optJSONArray("parses");
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            JSONObject q = ps.optJSONObject(i);
            if (q == null || q.optInt("type", 0) != 1 || !q.optString("url").startsWith("http")) continue;
            Jx j = new Jx(); j.name = q.optString("name"); j.url = q.optString("url");
            Object ex = q.opt("ext");
            if (ex instanceof String && ((String) ex).startsWith("{")) { try { ex = new JSONObject((String) ex); } catch (Exception ignored) {} }
            if (ex instanceof JSONObject) {
                JSONArray fl = ((JSONObject) ex).optJSONArray("flag");
                if (fl != null) for (int k = 0; k < fl.length(); k++) j.flags.add(fl.optString(k));
                JSONObject h = ((JSONObject) ex).optJSONObject("header");
                if (h != null) { Iterator<String> it = h.keys(); while (it.hasNext()) { String k = it.next(); j.headers.put(k, h.optString(k)); } }
            }
            jxs.add(j);
        }
        pyCfg = hasPy ? o.toString() : null;  // Python 解释器很占内存：真用到 Python 源时才启动
    }

    /** 站名去掉表情和装饰：「💓‍玩偶┃4K💓‍」→「玩偶」 */
    static String clean(String n) {
        String s = n.replaceAll("[┃|丨│].*$", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i); i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '·' || cp == '-' || cp == ' ') b.appendCodePoint(cp);
        }
        String r = b.toString().trim();
        return r.isEmpty() ? n.trim() : r;
    }

    private static void loadMainJar(String spider) throws Exception {
        String src = spider, md5 = "";
        if (spider.contains(";md5;")) { String[] p = spider.split(";md5;"); src = p[0]; md5 = p[1].trim(); }
        boolean img = src.startsWith("img+");
        if (img) src = src.substring(4);
        File f = new File(dir("jar"), MD5.string2MD5(src) + ".jar");
        boolean ok = f.exists() && f.length() > 0 && (md5.isEmpty() ? System.currentTimeMillis() - f.lastModified() < 24 * 3600e3 : md5.equalsIgnoreCase(MD5.getFileMd5(f)));
        if (!ok) {
            try {
                byte[] b = httpBytes(src, 30000);
                if (img) {
                    String t = new String(b, "UTF-8");
                    Matcher m = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(t);
                    if (m.find()) b = Base64.decode(t.substring(t.indexOf(m.group()) + 10), Base64.DEFAULT);
                }
                int pk = indexOf(b, new byte[]{'P', 'K', 3, 4});
                if (pk > 0) { byte[] c = new byte[b.length - pk]; System.arraycopy(b, pk, c, 0, c.length); b = c; }
                if (b.length > 1000) { if (f.exists()) f.delete(); write(f, b); }
            } catch (Throwable e) { Log.w(TAG, "jar download " + e); }
        }
        if (f.exists() && f.length() > 0) {
            File ro = new File(dir("jar"), "main_" + f.getName());
            if (!ro.exists() || ro.length() != f.length()) { ro.delete(); write(ro, read(f)); }
            jar.load(ro.getAbsolutePath());
        }
    }

    // ---------------- 爬虫调用 ----------------

    static String sitesJson() {
        JSONArray a = new JSONArray();
        for (Site s : new ArrayList<>(sites)) {
            try {
                JSONObject o = new JSONObject();
                o.put("key", s.key); o.put("name", s.name);
                o.put("kind", s.api.contains(".py") ? "py" : (s.api.contains(".js") ? "js" : "jar"));
                a.put(o);
            } catch (Exception ignored) {}
        }
        return a.toString();
    }

    static String cmsJson() { return cmsJson; }
    static String state() { return loading ? "加载中…" : state; }

    private static final Map<String, Object> locks = new ConcurrentHashMap<>();
    private static volatile String pyCfg = null;

    /** 低内存设备（老电视）：同时搜的爬虫少一些、保留的 JS 引擎少一些 */
    static boolean lowRam() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return am.isLowRamDevice() || mi.totalMem < 1600L * 1024 * 1024 || am.getLargeMemoryClass() < 256;
        } catch (Throwable e) { return false; }
    }

    static Spider spider(Site s) {
        Object lk = locks.get(s.key);
        if (lk == null) { locks.put(s.key, new Object()); lk = locks.get(s.key); }
        synchronized (lk) {
            String api = s.api;
            if (api.contains(".js")) return js.getSpider(s.key, api, s.ext, s.jar);
            if (api.contains(".py")) {
                String c = pyCfg; if (c != null) { try { py.setConfig(c); } catch (Throwable e) { Log.w(TAG, "py cfg " + e); } }
                py.setRecentPyKey(s.key); return py.getSpider(s.key, api, s.ext);
            }
            return jar.getSpider(s.key, api, s.ext, s.jar);
        }
    }

    private static String call(final Site s, int timeoutMs, final Callable<String> c) {
        Future<String> f = run.submit(c);
        try { String r = f.get(timeoutMs, TimeUnit.MILLISECONDS); return r == null ? "" : r; }
        catch (Throwable e) { f.cancel(true); Log.w(TAG, s.name + " " + e); return ""; }
    }

    static String search(String key, final String wd, int timeoutMs) {
        final Site s = byKey.get(key); if (s == null) return "";
        return call(s, timeoutMs, new Callable<String>() { public String call() {
            Spider sp = spider(s); if (sp instanceof SpiderNull) return "";
            return sp.searchContent(wd, false);
        } });
    }

    static String detail(String key, final String id, int timeoutMs) {
        final Site s = byKey.get(key); if (s == null) return "";
        return call(s, timeoutMs, new Callable<String>() { public String call() {
            Spider sp = spider(s); if (sp instanceof SpiderNull) return "";
            return sp.detailContent(Collections.singletonList(id));
        } });
    }

    static String player(String key, final String flag, final String id, int timeoutMs) {
        final Site s = byKey.get(key); if (s == null) return "";
        recentKey = key;
        return call(s, timeoutMs, new Callable<String>() { public String call() {
            Spider sp = spider(s); if (sp instanceof SpiderNull) return "";
            return sp.playerContent(flag, id, new ArrayList<String>());
        } });
    }

    /** 爬虫本地代理（/proxy?do=...）：先交给当前站点的爬虫，再交给 jar 的 Proxy 类 */
    static Object[] proxy(Map<String, String> p) {
        try {
            String k = p.get("siteKey");
            Site s = k == null ? null : byKey.get(k);
            if (s == null) s = byKey.get(recentKey);
            String act = p.get("do");
            if ("js".equals(act)) return js.proxyInvoke(p);
            if ("py".equals(act) || (s != null && s.api.contains(".py"))) return py.proxyInvoke(p, s != null ? s.key : recentKey);
            if (s != null && !s.api.contains(".js")) { Object[] r = spider(s).proxy(p); if (r != null) return r; }
            return jar.proxyInvoke(p);
        } catch (Throwable e) { Log.w(TAG, "proxy " + e); return null; }
    }

    // ---------------- 地址解析 ----------------

    static class Res { String url; Map<String, String> headers = new HashMap<>(); long at; String err; }
    private static final Map<String, Res> resolved = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> hostHeaders = new ConcurrentHashMap<>();

    /** 上次把 box:// 换真实地址失败的原因（给测速显示） */
    static String errOf(String boxUrl) { Res r = resolved.get(boxUrl); return r == null || r.err == null ? "爬虫超时" : r.err; }

    static boolean isBox(String u) { return u != null && u.startsWith("box://"); }

    static String boxUrl(String key, String flag, String id) {
        return "box://" + Uri.encode(key) + "/" + Uri.encode(flag) + "/" + Uri.encode(id);
    }

    /** box:// → 真实地址（缓存 15 分钟）；需要网页解析（parse=1）的线路 3.0 暂不支持，返回 null */
    static Res resolve(String boxUrl, int timeoutMs) {
        Res c = resolved.get(boxUrl);
        if (c != null && System.currentTimeMillis() - c.at < 15 * 60_000L) return c.url == null ? null : c;
        Res r = new Res(); r.at = System.currentTimeMillis();
        try {
            String[] p = boxUrl.substring(6).split("/", 3);
            String key = Uri.decode(p[0]), flag = Uri.decode(p[1]), id = Uri.decode(p[2]);
            String out = player(key, flag, id, timeoutMs);
            JSONObject o = out.startsWith("{") ? new JSONObject(out) : new JSONObject();
            Object uo = o.opt("url");
            String url = "";
            if (uo instanceof JSONArray) { JSONArray ua = (JSONArray) uo; for (int i = 1; i < ua.length(); i += 2) { url = ua.optString(i); if (!url.isEmpty()) break; } }
            else if (uo != null) url = String.valueOf(uo);
            int parse = o.optInt("parse", 0), jx = o.optInt("jx", 0);
            String pre = o.optString("playUrl", "");
            if (url.startsWith("proxy://")) url = url.replace("proxy://", "http://127.0.0.1:" + BoxServer.port + "/proxy?");
            if (!pre.isEmpty() && parse == 0 && jx == 0 && url.startsWith("http")) url = pre + url;
            else if ((parse == 1 || jx == 1) && url.startsWith("http")) {
                r.err = "要解析";
                // 官源：官网播放页 → 只用接口自己配置的 JSON 解析拿直链（App 不内置解析；网页解析/嗅探留到 3.1）
                Res j = jsonJx(flag, url, Math.min(9000, Math.max(5000, timeoutMs)));
                if (j == null) { r.err = jxs.isEmpty() ? "要解析·接口没配" : "解析失败"; resolved.put(boxUrl, r); return null; }
                j.at = r.at; resolved.put(boxUrl, j);
                String host = host(j.url); if (host != null && !j.headers.isEmpty()) hostHeaders.put(host, j.headers);
                return j;
            }
            if (!url.startsWith("http")) { r.err = out.isEmpty() ? "爬虫超时" : url.isEmpty() ? "爬虫无地址" : "非直链 " + url.split(":")[0]; Log.w(TAG, "resolve " + key + " " + flag + " -> " + out.substring(0, Math.min(200, out.length()))); resolved.put(boxUrl, r); return null; }
            Object h = o.opt("header");
            if (h instanceof String && ((String) h).startsWith("{")) h = new JSONObject((String) h);
            if (h instanceof JSONObject) {
                Iterator<String> it = ((JSONObject) h).keys();
                while (it.hasNext()) { String k = it.next(); r.headers.put(k, ((JSONObject) h).optString(k)); }
            }
            r.url = url;
            if (!r.headers.isEmpty()) { String host = host(url); if (host != null) hostHeaders.put(host, r.headers); }
        } catch (Throwable e) { r.err = e.getClass().getSimpleName(); Log.w(TAG, "resolve " + e); }
        resolved.put(boxUrl, r);
        return r.url == null ? null : r;
    }

    /** 同时问所有匹配线路的 JSON 解析，谁先给出 http 直链用谁 */
    static Res jsonJx(String flag, final String page, int timeoutMs) {
        List<Jx> all = new ArrayList<>(jxs), use = new ArrayList<>();
        for (Jx j : all) if (j.flags.isEmpty() || j.flags.contains(flag)) use.add(j);
        if (use.isEmpty()) use = all;
        if (use.isEmpty()) return null;
        if (use.size() > 8) use = use.subList(0, 8);
        final java.util.concurrent.BlockingQueue<Res> q = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(use.size());
        final int to = timeoutMs;
        List<Future<?>> fs = new ArrayList<>();
        for (final Jx j : use) fs.add(run.submit(new Runnable() { public void run() {
            Res r = null;
            try {
                okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(j.url + page).header("User-Agent", UA);
                for (Map.Entry<String, String> e : j.headers.entrySet()) b.header(e.getKey(), e.getValue());
                okhttp3.Response res = com.github.catvod.net.OkHttp.client(to).newCall(b.build()).execute();
                String t; try { t = res.body() == null ? "" : res.body().string(); } finally { res.close(); }
                int a = t.indexOf('{'); JSONObject o = a >= 0 ? lenient(t.substring(a)) : null;
                if (o != null && o.has("data") && !o.has("url") && o.optJSONObject("data") != null) o = o.optJSONObject("data");
                String u = o == null ? "" : o.optString("url", "");
                if (u.startsWith("http") && !u.contains("?url=http")) {
                    r = new Res(); r.url = u;
                    String ua = o.optString("user-agent", o.optString("UA", ""));
                    if (!ua.isEmpty()) r.headers.put("User-Agent", ua);
                    String rf = o.optString("referer", o.optString("Referer", ""));
                    if (!rf.isEmpty()) r.headers.put("Referer", rf);
                    JSONObject h = o.optJSONObject("header"); if (h == null) h = o.optJSONObject("headers");
                    if (h != null) { Iterator<String> it = h.keys(); while (it.hasNext()) { String k = it.next(); r.headers.put(k, h.optString(k)); } }
                }
            } catch (Throwable e) { Log.w(TAG, "jx " + j.name + " " + e); }
            if (r != null) q.offer(r);
            if (left.decrementAndGet() == 0) { Res none = new Res(); q.offer(none); }
        } }));
        Res got = null;
        try { got = q.poll(timeoutMs + 500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        for (Future<?> f : fs) f.cancel(true);
        return got == null || got.url == null ? null : got;
    }

    /** 播放器、预下载、测速发请求时补上爬虫要求的请求头（按域名） */
    static Map<String, String> headersFor(String url) {
        if (hostHeaders.isEmpty()) return null;
        String h = host(url);
        return h == null ? null : hostHeaders.get(h);
    }

    static String host(String u) {
        try { return Uri.parse(u).getHost(); } catch (Exception e) { return null; }
    }

    // ---------------- 小工具 ----------------

    static File dir(String n) { File d = new File(app.getFilesDir(), "box/" + n); if (!d.exists()) d.mkdirs(); return d; }

    static String httpText(String url, int timeout) throws Exception {
        byte[] b = httpBytes(url, timeout);
        return new String(b, "UTF-8");
    }

    static byte[] httpBytes(String url, int timeout) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("User-Agent", UA).build();
        okhttp3.Response res = com.github.catvod.net.OkHttp.client(timeout).newCall(req).execute();
        try {
            if (!res.isSuccessful() || res.body() == null) throw new Exception("http " + res.code());
            return res.body().bytes();
        } finally { res.close(); }
    }

    private static int indexOf(byte[] d, byte[] p) {
        int lim = Math.min(d.length - p.length, 4 * 1024 * 1024);
        outer: for (int i = 0; i <= lim; i++) { for (int j = 0; j < p.length; j++) if (d[i + j] != p[j]) continue outer; return i; }
        return -1;
    }

    static byte[] read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    static void write(File f, byte[] b) throws Exception { FileOutputStream o = new FileOutputStream(f); o.write(b); o.close(); }
}
