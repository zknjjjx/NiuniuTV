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
        return p.endsWith(".m3u8");
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
        @Override public long open(@NonNull DataSpec s) throws IOException { cur = isList(s.uri) ? direct : cached; return cur.open(s); }
        @Override public int read(@NonNull byte[] b, int o, int l) throws IOException { return cur.read(b, o, l); }
        @Nullable @Override public Uri getUri() { return cur == null ? null : cur.getUri(); }
        @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return cur == null ? java.util.Collections.<String, List<String>>emptyMap() : cur.getResponseHeaders(); }
        @Override public void close() throws IOException { if (cur != null) cur.close(); }
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
        if (!ready) {
            Seg c = job.curSeg; CacheWriter w = job.writer;
            // 正在下的分片不是眼前要用的（比如刚快进），立刻停下让路
            if (c != null && w != null && (c.endUs <= p || c.startUs > p + 15_000_000L)) w.cancel();
        }
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

    static class Seg { DataSpec spec; String key; long startUs, endUs, size; boolean done; int fails; }

    static class Job extends Thread {
        final String url;
        volatile long posUs, aheadUs, lastData, bufUs;
        volatile boolean dead, gated, ready;
        volatile Seg curSeg;
        volatile CacheWriter writer;
        volatile List<Seg> segs = new ArrayList<>();

        Job(String url, long posMs, long aheadMs) {
            super("nn-pre");
            this.url = url; posUs = Math.max(0, posMs) * 1000; aheadUs = aheadMs * 1000;
            setPriority(Thread.MIN_PRIORITY + 1);
        }

        void halt() { dead = true; CacheWriter w = writer; if (w != null) w.cancel(); interrupt(); }

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
                if (!isList(Uri.parse(url))) { dead = true; clearOthers(new HashSet<String>()); return; }
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
                if (!mp.hasEndTag) { dead = true; return; }   // 直播不预下载
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
                this.segs = segs;
                clearOthers(keys);
                long lastClean = SystemClock.uptimeMillis();
                byte[] tmp = new byte[128 * 1024];
                while (!dead) {
                    if (SystemClock.uptimeMillis() - lastClean > 30000) { clearOthers(keys); lastClean = SystemClock.uptimeMillis(); }
                    Seg next = pick();
                    if (next == null) { sleepQ(800); continue; }
                    final Seg cur = next;
                    curSeg = cur;
                    try {
                        CacheWriter w = new CacheWriter(new CacheDataSource(cache, http().createDataSource(), CacheDataSource.FLAG_BLOCK_ON_CACHE),
                                cur.spec, tmp, new CacheWriter.ProgressListener() {
                            public void onProgress(long len, long done, long nb) { if (nb > 0) lastData = SystemClock.uptimeMillis(); cur.size = done; }
                        });
                        writer = w;
                        if (dead) break;
                        w.cache();
                        cur.done = true;
                    } catch (Exception e) {
                        if (dead) break;
                        if (gated && !ready) continue;   // 被播放器让路取消的，不算失败
                        if (++cur.fails >= 3) cur.done = true;   // 一个分片连失败 3 次就跳过，交给播放器自己拉
                        else sleepQ(1000L * cur.fails);
                    } finally { writer = null; curSeg = null; }
                }
            } catch (Throwable ignore) {
            } finally { dead = true; }
        }

        Seg seg(Uri u, long off, long len, long st, long en) {
            Seg s = new Seg();
            s.spec = new DataSpec.Builder().setUri(u).setPosition(off).setLength(len).build();
            s.key = u.toString();
            s.startUs = st; s.endUs = en;
            return s;
        }

        /** 选下一个要下载的分片：初始化段优先，其次从播放位置往后、在领先窗口内第一个没下载的 */
        Seg pick() {
            long p = posUs, lim = p + aheadUs, ahead = 0, from = p;
            if (gated) {
                if (!ready || bufUs - p < 15_000_000L) return null;
                from = bufUs;   // 播放器手里已有的不重复拉，从它缓冲的尽头往后下
            }
            for (Seg s : segs) if (s.startUs < 0 && !s.done) return gated ? null : s;
            for (Seg s : segs) {
                if (s.startUs < 0 || s.endUs <= from) continue;
                if (s.startUs >= lim) return null;
                if (!s.done) return ahead > capBytes * 6 / 10 ? null : s;
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
