package com.niuniu.tv;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 磁盘缓存 + 后台预下载（只针对 m3u8 点播）。
 * - 进程启动（每次开 App）时清空缓存目录。
 * - 详情页选好片源就先下载开头 3 分钟；进播放页后领先播放位置 10 分钟一直下载。
 * - 换源 / 换集 / 换节目：停掉旧任务、删除旧缓存，立刻缓存新的。
 */
@UnstableApi
public class Pre {
    static final long PRELOAD_MS = 3 * 60_000L;   // 详情页预载
    static final long AHEAD_MS = 10 * 60_000L;    // 播放时领先
    static final int WORKERS = 5;                  // 同时下几个分片：采集站多是按单条连接限速，多路能快好几倍
    static final long NEAR_US = 30_000_000L;       // 缓冲中只帮播放器下眼前 30 秒

    private static SimpleCache cache;
    private static long capBytes;
    private static Context app;
    private static Job job;

    static synchronized SimpleCache cache(Context c) {
        if (cache == null) {
            app = c.getApplicationContext();
            File dir = new File(app.getCacheDir(), "media");
            StandaloneDatabaseProvider db = new StandaloneDatabaseProvider(app);
            // 先改名再后台删，不让清缓存拖慢启动或起播
            if (dir.exists()) {
                final File old = new File(app.getCacheDir(), "media-old-" + System.currentTimeMillis());
                if (!dir.renameTo(old)) deleteTree(dir);
            }
            try { SimpleCache.delete(dir, db); } catch (Throwable ignore) {}
            dir.mkdirs();
            new Thread(new Runnable() { public void run() {
                File[] l = app.getCacheDir().listFiles();
                if (l != null) for (File f : l) if (f.getName().startsWith("media-old-")) deleteTree(f);
            } }, "nn-clean").start();
            long free = 0;
            try { free = new StatFs(app.getCacheDir().getPath()).getAvailableBytes(); } catch (Throwable ignore) {}
            capBytes = Math.max(100L << 20, Math.min(1L << 30, free / 5));
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(capBytes), db);
        }
        return cache;
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }

    static DefaultHttpDataSource.Factory http() {
        return new DefaultHttpDataSource.Factory().setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
    }

    static boolean isList(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath().toLowerCase();
        return p.endsWith(".m3u8") || lists.contains(u.toString());
    }
    /** 地址不带 .m3u8、但内容是播放列表的（看开头 #EXTM3U 认出来） */
    static final Set<String> lists = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** mp4 等整文件：支持分段下载就记总字节数，不支持记 -1 */
    static final java.util.concurrent.ConcurrentHashMap<String, Long> fileLen = new java.util.concurrent.ConcurrentHashMap<>();
    static final long CHUNK = 2L << 20;   // mp4 按 2 MB 一段，后台 5 路各下一段，播放器也按同样的段读，彼此不卡

    /** 播放器报片长（mp4 要靠它把字节换算成时间） */
    static synchronized void duration(String url, long ms) {
        if (job != null && job.url.equals(url) && ms > 0 && job.durUs <= 0) job.durUs = ms * 1000;
    }

    /** 等后台认出这个地址是不是能分段的整文件，最多 3 秒；能分段返回总长，否则 -1 */
    static long waitLen(String url) {
        for (int i = 0; i < 30; i++) {
            Long l = fileLen.get(url);
            if (l != null) return l;
            Job j = job;
            if (j == null || j.dead || !j.url.equals(url)) return -1;
            try { Thread.sleep(100); } catch (InterruptedException e) { return -1; }
        }
        return -1;
    }

    /** 整文件按 CHUNK 对齐分段读：播放器一次只占一段，后台在别的段上下载互不阻塞 */
    static class Chunked implements DataSource {
        final DataSource in; final long total; DataSpec base; long pos, left; boolean eof, open;
        Chunked(DataSource in, long total) { this.in = in; this.total = total; }
        @Override public void addTransferListener(@NonNull TransferListener t) { in.addTransferListener(t); }
        @Override public long open(@NonNull DataSpec s) throws IOException {
            base = s; pos = s.position; left = s.length; eof = false;
            next();
            return s.length != C.LENGTH_UNSET ? s.length : Math.max(0, total - s.position);
        }
        void next() throws IOException {
            long len = (pos / CHUNK + 1) * CHUNK - pos;
            len = Math.min(len, total - pos);
            if (left != C.LENGTH_UNSET) len = Math.min(len, left);
            if (len <= 0) { eof = true; return; }
            in.open(base.buildUpon().setPosition(pos).setLength(len).build());
            open = true;
        }
        @Override public int read(@NonNull byte[] b, int o, int l) throws IOException {
            while (true) {
                if (eof) return C.RESULT_END_OF_INPUT;
                int n = in.read(b, o, l);
                if (n == C.RESULT_END_OF_INPUT) {
                    in.close(); open = false;
                    next();
                    continue;
                }
                pos += n;
                if (left != C.LENGTH_UNSET) left -= n;
                return n;
            }
        }
        @Nullable @Override public Uri getUri() { return in.getUri(); }
        @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return in.getResponseHeaders(); }
        @Override public void close() throws IOException { if (open) { open = false; in.close(); } }
    }

    /** 播放器用：m3u8 列表直连（交给去广告），分片走磁盘缓存 */
    static DataSource.Factory playerFactory(Context c) {
        final SimpleCache sc = cache(c);
        final DefaultHttpDataSource.Factory http = http();
        return new AdFilter.Factory(new DataSource.Factory() {
            @NonNull @Override public DataSource createDataSource() {
                return new Router(http.createDataSource(), new CacheDataSource(sc, http.createDataSource(),
                        CacheDataSource.FLAG_BLOCK_ON_CACHE | CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR));
            }
        });
    }

    static class Router implements DataSource {
        final DataSource direct, cached; DataSource cur;
        Router(DataSource d, DataSource c) { direct = d; cached = c; }
        @Override public void addTransferListener(@NonNull TransferListener t) { direct.addTransferListener(t); cached.addTransferListener(t); }
        @Override public long open(@NonNull DataSpec s) throws IOException {
            if (isList(s.uri)) cur = direct;
            else {
                long t = s.uri.getScheme() != null && s.uri.getScheme().startsWith("http") ? waitLen(s.uri.toString()) : -1;
                DataSource g = new Gate(cached);
                cur = t > 0 ? new Chunked(g, t) : g;
            }
            return cur.open(s);
        }
        @Override public int read(@NonNull byte[] b, int o, int l) throws IOException { int n = cur.read(b, o, l); if (n > 0) playerData = SystemClock.uptimeMillis(); return n; }
        @Nullable @Override public Uri getUri() { return cur == null ? null : cur.getUri(); }
        @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return cur == null ? java.util.Collections.<String, List<String>>emptyMap() : cur.getResponseHeaders(); }
        @Override public void close() throws IOException { if (cur != null) cur.close(); }
    }

    /** 播放器读分片前先过这道门：后台在管这一片，就让它插队下载，等它整片落盘再从磁盘读，播放器自己不连片源 */
    static class Gate implements DataSource {
        final DataSource in;
        Gate(DataSource in) { this.in = in; }
        @Override public void addTransferListener(@NonNull TransferListener t) { in.addTransferListener(t); }
        @Override public long open(@NonNull DataSpec s) throws IOException { await(s); return in.open(s); }
        @Override public int read(@NonNull byte[] b, int o, int l) throws IOException { return in.read(b, o, l); }
        @Nullable @Override public Uri getUri() { return in.getUri(); }
        @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return in.getResponseHeaders(); }
        @Override public void close() throws IOException { in.close(); }
    }

    /**
     * 等后台把播放器要的这一片下到磁盘。后台不管这片（直播、不能分段的整文件、列表还没解析出来）就直接放行，
     * 照旧由播放器自己拉。片源真坏了这里只会一直等，由播放页的「没数据就换源」兜底；最多等 2 分钟。
     */
    static void await(DataSpec s) throws IOException {
        String key = s.key != null ? s.key : s.uri.toString();
        long t0 = SystemClock.uptimeMillis();
        Seg g = null; Job j0 = null;
        try {
            while (true) {
                Job j = job;
                if (j == null || j.dead || (j0 != null && j != j0)) return;
                long now = SystemClock.uptimeMillis();
                if (g == null) {
                    if (!j.keys.contains(key)) {
                        if (j.segs.isEmpty() && !j.url.equals(key) && now - t0 < 4000) { Thread.sleep(50); continue; }
                        return;
                    }
                    g = j.find(key, s.position);
                    if (g == null) return;
                    j0 = j;
                }
                if (onDisk(g)) return;
                if (now - t0 > 120_000L) return;
                j.want(g);
                Thread.sleep(40);
            }
        } catch (InterruptedException e) {
            throw new java.io.InterruptedIOException();
        } finally { if (g != null && j0 != null) j0.wants.remove(g); }
    }

    /** 开始（或继续）缓存某一集。同一地址只更新位置与领先量，不重来。 */
    static synchronized void start(Context c, String url, long posMs, long aheadMs) {
        if (url == null || url.isEmpty()) return;
        cache(c);
        boolean player = aheadMs >= AHEAD_MS;
        if (job != null && job.url.equals(url) && !job.dead) {
            job.posUs = Math.max(0, posMs) * 1000;
            job.aheadUs = Math.max(job.aheadUs, aheadMs * 1000);
            if (player) { job.gated = true; job.ready = false; }
            return;
        }
        if (job != null) job.halt();
        job = new Job(url, posMs, aheadMs);
        job.gated = player;
        job.start();
    }

    /**
     * 播放器每秒报一次进度。播放器没起播、在缓冲、或手里缓冲不足 15 秒时，后台预下载让路，
     * 网速全部留给播放器自己拉眼前的分片——缓存绝不拖慢起播和卡顿恢复。
     */
    static synchronized void position(String url, long posMs, long bufMs, boolean ready) {
        if (job == null || !job.url.equals(url)) return;
        long p = Math.max(0, posMs) * 1000;
        job.posUs = p; job.bufUs = Math.max(p, bufMs * 1000); job.ready = ready;
        if (ready) job.everReady = true;
        if (!ready) {
            // 正在下的分片不是眼前要用的（比如刚快进），立刻停下让路
            for (Map.Entry<Seg, CacheWriter> e : job.active.entrySet()) {
                Seg c = e.getKey();
                if (job.wants.containsKey(c)) continue;
                if (c.endUs <= p || c.startUs > p + NEAR_US) e.getValue().cancel();
            }
        }
    }

    /** 播放器遇到 HTTP 错误 / 连不上：可能是对方嫌连接多，这一集预下载退回单路 */
    static synchronized void backoff(String url) {
        if (job != null && job.url.equals(url)) {
            if (job.lanes > 1 && job.everReady) { job.crowded = true; markCrowd(url); }
            job.lanes = 1;
        }
    }

    /** 这一集的片源嫌连接多、已退到 1~2 路 */
    static synchronized boolean crowded(String url) {
        return job != null && job.url.equals(url) && job.crowded;
    }

    /** 片源服务器拒绝了我们（像是嫌连接多）：403/429/503/509、连接被拒或被重置 */
    static boolean rejected(Throwable e) {
        for (int i = 0; e != null && i < 6; i++, e = e.getCause()) {
            if (e instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int c = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) e).responseCode;
                return c == 403 || c == 429 || c == 503 || c == 509;
            }
            if (e instanceof java.net.ConnectException) return true;
            if (e instanceof java.net.SocketException && String.valueOf(e.getMessage()).toLowerCase().contains("reset")) return true;
        }
        return false;
    }

    /** 记住嫌连接多的片源域名（7 天），选源时这类 1080 要单路 ≥0.9 MB/s 才排在 720 前 */
    static void markCrowd(String url) {
        try {
            if (app == null) return;
            String host = Uri.parse(url).getHost();
            if (host == null || host.isEmpty()) return;
            android.content.SharedPreferences sp = Lan.cfg(app);
            org.json.JSONObject o;
            try { o = new org.json.JSONObject(sp.getString("crowd", "{}")); } catch (Exception e) { o = new org.json.JSONObject(); }
            long now = System.currentTimeMillis();
            org.json.JSONObject n = new org.json.JSONObject();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); long t = o.optLong(k); if (now - t < 7 * 86400_000L) n.put(k, t); }
            n.put(host.toLowerCase(java.util.Locale.US), now);
            sp.edit().putString("crowd", n.toString()).apply();
        } catch (Throwable ignore) {}
    }

    static boolean isCrowdHost(Context c, String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            long t = new org.json.JSONObject(Lan.cfg(c).getString("crowd", "{}")).optLong(host.toLowerCase(java.util.Locale.US), 0);
            return t > 0 && System.currentTimeMillis() - t < 7 * 86400_000L;
        } catch (Throwable e) { return false; }
    }

    /** 播放器最近一次读到数据的时间（uptime） */
    static volatile long playerData;

    /** 后台预下载最近一次进账的时间（uptime），不是这一集返回 0 */
    static synchronized long lastData(String url) {
        return job != null && job.url.equals(url) && !job.dead ? job.lastData : 0;
    }

    /** 最近 ms 毫秒内后台下载还有进账 */
    static synchronized boolean progressing(String url, long ms) {
        return job != null && job.url.equals(url) && !job.dead && SystemClock.uptimeMillis() - job.lastData < ms;
    }

    /** 从播放位置往后、连续已存在磁盘上的时长（毫秒） */
    static synchronized long cachedAheadMs(String url, long posMs) {
        if (job == null || !job.url.equals(url) || cache == null) return 0;
        long p = Math.max(0, posMs) * 1000, end = p;
        int n = 0;
        for (Seg s : job.segs) {
            if (s.startUs < 0 || s.endUs <= p) continue;
            if (++n > 600) break;
            if (!(s.done || onDisk(s))) break;
            end = s.endUs;
        }
        return (end - p) / 1000;
    }

    static boolean onDisk(Seg s) {
        try {
            long len = s.spec.length;
            if (len == C.LENGTH_UNSET) len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(s.key));
            return len > 0 && cache.isCached(s.key, s.spec.position, len);
        } catch (Throwable e) { return false; }
    }

    static class Seg { DataSpec spec; String key; long startUs, endUs; volatile long size; volatile boolean done, busy; int fails; }

    static class Job extends Thread {
        final String url;
        volatile long posUs, aheadUs, lastData, bufUs, durUs;
        volatile boolean dead, gated, ready, everReady, crowded;
        int rejects; long rejectAt;
        volatile int lanes = WORKERS;
        /** 播放器正在等的分片，最先下 */
        final java.util.concurrent.ConcurrentHashMap<Seg, Boolean> wants = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<Seg, CacheWriter> active = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Thread> helpers = new ArrayList<>();
        volatile List<Seg> segs = new ArrayList<>();
        volatile Set<String> keys = new HashSet<>();

        Job(String url, long posMs, long aheadMs) {
            super("nn-pre");
            this.url = url; posUs = Math.max(0, posMs) * 1000; aheadUs = aheadMs * 1000;
            setPriority(Thread.MIN_PRIORITY + 1);
            if (app != null && isCrowdHost(app, url)) lanes = 2;   // 记过嫌连接多的站，一开始就少开几路
        }

        /** 找播放器要读的那一片：同地址、起点落在这一片里 */
        Seg find(String key, long pos) {
            for (Seg s : segs) {
                if (!s.key.equals(key)) continue;
                long st = s.spec.position, len = s.spec.length;
                if (len == C.LENGTH_UNSET || (pos >= st && pos < st + len)) return s;
            }
            return null;
        }

        /** 播放器在等这一片：插到最前；之前因失败跳过的，重新下 */
        synchronized void want(Seg g) {
            if (wants.put(g, Boolean.TRUE) != null) return;
            if (g.done && !g.busy && !onDisk(g)) { g.done = false; g.fails = 0; }
            // 各路都在忙后面的分片：停掉最靠后的那路，先给播放器下这一片
            if (g.busy || active.size() < lanes) return;
            Seg far = null;
            for (Seg c : active.keySet()) if (!wants.containsKey(c) && (far == null || c.startUs > far.startUs)) far = c;
            CacheWriter w = far == null ? null : active.get(far);
            if (w != null) { far.fails--; w.cancel(); }
        }

        void halt() {
            dead = true;
            for (CacheWriter w : active.values()) w.cancel();
            interrupt();
            synchronized (helpers) { for (Thread t : helpers) t.interrupt(); }
        }

        HlsPlaylist fetch(String u) throws IOException {
            DataSource s = new AdFilter.Factory(http()).createDataSource();
            DataSpec spec = new DataSpec(Uri.parse(u));
            DataSourceInputStream in = new DataSourceInputStream(s, spec);
            try {
                in.open();
                Uri real = s.getUri() != null ? s.getUri() : spec.uri;
                return new HlsPlaylistParser().parse(real, in);
            } finally { try { in.close(); } catch (Exception ignore) {} }
        }

        @Override public void run() {
            try {
                if (!isList(Uri.parse(url)) && !sniff()) { runFile(); return; }
                HlsPlaylist pl = null;
                for (int i = 0; i < 3 && pl == null && !dead; i++) { try { pl = fetch(url); } catch (Exception e) { sleepQ(1500); } }
                if (pl instanceof HlsMultivariantPlaylist) {
                    HlsMultivariantPlaylist m = (HlsMultivariantPlaylist) pl;
                    HlsMultivariantPlaylist.Variant best = null;
                    for (HlsMultivariantPlaylist.Variant v : m.variants) if (best == null || v.format.bitrate > best.format.bitrate) best = v;
                    pl = null;
                    if (best != null) for (int i = 0; i < 3 && pl == null && !dead; i++) { try { pl = fetch(best.url.toString()); } catch (Exception e) { sleepQ(1500); } }
                }
                if (!(pl instanceof HlsMediaPlaylist) || dead) { dead = true; return; }
                HlsMediaPlaylist mp = (HlsMediaPlaylist) pl;
                if (!mp.hasEndTag && !vodLike(mp)) { dead = true; return; }   // 真直播不预下载；漏写结束标记的点播照样下
                Set<String> keys = new HashSet<>();
                Set<String> inits = new HashSet<>();
                List<Seg> segs = new ArrayList<>();
                for (HlsMediaPlaylist.Segment s : mp.segments) {
                    if (s.initializationSegment != null) {
                        HlsMediaPlaylist.Segment is = s.initializationSegment;
                        Uri iu = UriUtil.resolveToUri(mp.baseUri, is.url);
                        String ik = iu + "#" + is.byteRangeOffset;
                        if (inits.add(ik)) segs.add(seg(iu, is.byteRangeOffset, is.byteRangeLength, -1, -1));
                    }
                    Uri u = UriUtil.resolveToUri(mp.baseUri, s.url);
                    segs.add(seg(u, s.byteRangeOffset, s.byteRangeLength, s.relativeStartTimeUs, s.relativeStartTimeUs + s.durationUs));
                }
                for (Seg s : segs) keys.add(s.key);
                this.keys = keys;
                this.segs = segs;
                clearOthers(keys);
                lanesGo();
            } catch (Throwable ignore) {
            } finally { dead = true; }
        }

        void lanesGo() {
            for (int i = 1; i < WORKERS && !dead; i++) {
                final int lane = i;
                Thread t = new Thread(new Runnable() { public void run() { work(lane); } }, "nn-pre" + i);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                synchronized (helpers) { helpers.add(t); }
                t.start();
            }
            work(0);
        }

        /** 地址不带 .m3u8：读开头 2 KB。是播放列表返回 true；否则记下整文件能不能分段（总长或 -1） */
        boolean sniff() {
            byte[] b = new byte[2048]; int n = 0; long total = -1;
            DefaultHttpDataSource d = http().createDataSource();
            try {
                d.open(new DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(b.length).build());
                for (int r; n < b.length && (r = d.read(b, n, b.length - n)) > 0; ) n += r;
                for (Map.Entry<String, List<String>> e : d.getResponseHeaders().entrySet()) {
                    if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Content-Range") || e.getValue().isEmpty()) continue;
                    String v = e.getValue().get(0); int k = v.lastIndexOf('/');
                    if (k > 0) try { total = Long.parseLong(v.substring(k + 1).trim()); } catch (Exception ignore) {}
                }
            } catch (Throwable e) { fileLen.put(url, -1L); return false; }
            finally { try { d.close(); } catch (Exception ignore) {} }
            String head = new String(b, 0, Math.min(n, 64), java.nio.charset.Charset.forName("ISO-8859-1")).replace("\uFEFF", "").replace("\u00EF\u00BB\u00BF", "").trim();
            if (head.startsWith("#EXTM3U")) { lists.add(url); return true; }
            boolean mp4 = n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            fileLen.put(url, mp4 && total > CHUNK ? total : -1L);
            return false;
        }

        /** 整文件（mp4）：能分段就按 2 MB 一段 5 路往前下，字节按片长折算成时间；不能分段就交给播放器单路 */
        void runFile() {
            try {
                Long t = fileLen.get(url);
                if (t == null || t <= 0) { clearOthers(new HashSet<String>()); return; }
                long total = t;
                while (!dead && durUs <= 0) sleepQ(500);   // 等播放器报片长
                if (dead) return;
                Set<String> keys = new HashSet<>(); keys.add(url);
                List<Seg> segs = new ArrayList<>();
                Uri u = Uri.parse(url);
                for (long off = 0; off < total; off += CHUNK) {
                    long len = Math.min(CHUNK, total - off);
                    segs.add(seg(u, off, len, (long) ((double) off / total * durUs), (long) ((double) (off + len) / total * durUs)));
                }
                this.keys = keys; this.segs = segs;
                clearOthers(keys);
                lanesGo();
            } catch (Throwable ignore) {
            } finally { dead = true; }
        }

        /** 没有结束标记：标了 VOD 的直接算；否则 4 秒后再拉一次，列表没变且总长超过 5 分钟就当点播 */
        boolean vodLike(HlsMediaPlaylist mp) {
            if (mp.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD) return true;
            if (mp.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_EVENT || mp.segments.isEmpty() || mp.durationUs < 300_000_000L) return false;
            sleepQ(4000);
            if (dead) return false;
            try {
                HlsPlaylist p2 = fetch(mp.baseUri);
                if (!(p2 instanceof HlsMediaPlaylist)) return false;
                HlsMediaPlaylist m2 = (HlsMediaPlaylist) p2;
                return m2.segments.size() == mp.segments.size() && m2.mediaSequence == mp.mediaSequence
                        && m2.segments.get(m2.segments.size() - 1).url.equals(mp.segments.get(mp.segments.size() - 1).url);
            } catch (Exception e) { return false; }
        }

        /** 一个下载线程：反复挑一个没人在下的分片下载。main=true 的那个负责定时清理旧缓存 */
        void work(int lane) {
            boolean main = lane == 0;
            long lastClean = SystemClock.uptimeMillis();
            byte[] tmp = new byte[128 * 1024];
            while (!dead) {
                if (main && SystemClock.uptimeMillis() - lastClean > 30000) { clearOthers(keys); lastClean = SystemClock.uptimeMillis(); }
                if (lane >= lanes) { sleepQ(1000); continue; }
                final Seg cur = pick();
                if (cur == null) { sleepQ(500); continue; }
                try {
                    CacheWriter w = new CacheWriter(new CacheDataSource(cache, http().createDataSource(), CacheDataSource.FLAG_BLOCK_ON_CACHE),
                            cur.spec, tmp, new CacheWriter.ProgressListener() {
                        public void onProgress(long len, long done, long nb) { if (nb > 0) lastData = SystemClock.uptimeMillis(); cur.size = done; }
                    });
                    active.put(cur, w);
                    if (dead) break;
                    w.cache();
                    cur.done = true;
                } catch (Exception e) {
                    if (dead) break;
                    if (rejected(e)) onReject(cur);
                    else if (gated && !ready) { sleepQ(300); continue; }   // 被播放器让路取消的，不算失败
                    // 一个分片连失败 3 次先跳过；播放器真要读它时会再插队重下，不放弃
                    synchronized (this) { if (++cur.fails >= 3 && !wants.containsKey(cur)) cur.done = true; }
                    if (!cur.done) sleepQ(Math.min(5000L, 1000L * cur.fails));
                } finally { active.remove(cur); cur.busy = false; }
            }
        }

        /** 一分钟内被拒两次：5 路 → 2 路 → 1 路，并记下这个域名 */
        synchronized void onReject(Seg s) {
            long now = SystemClock.uptimeMillis();
            if (now - rejectAt > 60_000L) rejects = 0;
            rejectAt = now;
            if (++rejects < 2 || lanes <= 1) return;
            rejects = 0;
            lanes = lanes > 2 ? 2 : 1;
            // 详情页预载时被拒不记（那时还有测速同时连这个站，多半是一时的），只有真正播放时被拒才记下这个域名
            if (gated) { crowded = true; markCrowd(url); }
        }

        Seg seg(Uri u, long off, long len, long st, long en) {
            Seg s = new Seg();
            s.spec = new DataSpec.Builder().setUri(u).setPosition(off).setLength(len).build();
            s.key = u.toString();
            s.startUs = st; s.endUs = en;
            return s;
        }

        /** 选下一个要下载的分片：初始化段优先，其次从播放位置往后、在领先窗口内第一个没下载的 */
        synchronized Seg pick() {
            long p = posUs, lim = p + aheadUs, ahead = 0;
            // 播放器在等的分片最先下（播放器只读磁盘，不自己连片源）
            for (Seg s : wants.keySet()) if (!s.done && !s.busy) { s.busy = true; return s; }
            // 起播前、卡顿或缓冲不足：先集中下眼前 30 秒
            if (gated && (!everReady || !ready || bufUs - p < 15_000_000L)) lim = p + NEAR_US;
            for (Seg s : segs) if (s.startUs < 0 && !s.done && !s.busy) { s.busy = true; return s; }
            for (Seg s : segs) {
                if (s.startUs < 0 || s.endUs <= p) continue;
                if (s.startUs >= lim) return null;
                if (!s.done && !s.busy) {
                    if (ahead > capBytes * 6 / 10) return null;
                    s.busy = true; return s;
                }
                ahead += s.size;
            }
            return null;
        }

        void clearOthers(Set<String> keep) {
            try { for (String k : new ArrayList<>(cache.getKeys())) if (!keep.contains(k)) cache.removeResource(k); } catch (Throwable ignore) {}
        }

        void sleepQ(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
    }
}
