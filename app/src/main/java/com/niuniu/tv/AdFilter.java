package com.niuniu.tv;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** m3u8 去广告：按 #EXT-X-DISCONTINUITY 分组，去掉目录/域名与正片不同、重复插播、夹在正片中间的短段（与 CineNest 网页版同一算法） */
@UnstableApi
public class AdFilter {
    static int lastRemoved = 0;  // 秒

    static class Seg { List<String> lines = new ArrayList<>(); String uri; double dur; }

    static String dir(String base, String u) {
        try {
            URI r = new URI(base).resolve(u.trim());
            String p = r.getPath() == null ? "" : r.getPath();
            int i = p.lastIndexOf('/');
            return r.getHost() + (i > 0 ? p.substring(0, i) : "");
        } catch (Exception e) { return ""; }
    }

    static String name(String u) { int i = u.lastIndexOf('/'); String n = i >= 0 ? u.substring(i + 1) : u; int q = n.indexOf('?'); return q >= 0 ? n.substring(0, q) : n; }

    public static String filter(String text, String base) {
        if (!text.contains("#EXTINF") || !text.contains("#EXT-X-DISCONTINUITY") || !text.contains("#EXT-X-ENDLIST")) return text;
        String[] lines = text.split("\r?\n");
        List<String> header = new ArrayList<>();
        List<List<Seg>> groups = new ArrayList<>();
        List<Seg> group = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String ln : lines) {
            if (ln.startsWith("#EXT-X-DISCONTINUITY") && !ln.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                if (!group.isEmpty()) { groups.add(group); group = new ArrayList<>(); }
                continue;
            }
            if (groups.isEmpty() && group.isEmpty() && pending.isEmpty() && (ln.startsWith("#EXTM3U") || ln.startsWith("#EXT-X-VERSION")
                    || ln.startsWith("#EXT-X-TARGETDURATION") || ln.startsWith("#EXT-X-MEDIA-SEQUENCE") || ln.startsWith("#EXT-X-PLAYLIST-TYPE")
                    || ln.startsWith("#EXT-X-ALLOW-CACHE") || ln.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") || ln.startsWith("#EXT-X-INDEPENDENT"))) {
                header.add(ln); continue;
            }
            if (ln.startsWith("#EXT-X-ENDLIST")) continue;
            pending.add(ln);
            if (!ln.isEmpty() && !ln.startsWith("#")) {
                Seg s = new Seg(); s.lines = pending; s.uri = ln;
                for (String p : pending) if (p.startsWith("#EXTINF:")) { try { s.dur = Double.parseDouble(p.substring(8).split(",")[0].trim()); } catch (Exception ignored) {} }
                group.add(s); pending = new ArrayList<>();
            }
        }
        if (!group.isEmpty()) groups.add(group);
        if (groups.size() <= 1) return text;
        Map<String, Integer> counts = new HashMap<>();
        for (List<Seg> g : groups) for (Seg s : g) { String d = dir(base, s.uri); Integer c = counts.get(d); counts.put(d, c == null ? 1 : c + 1); }
        String mainDir = ""; int mc = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > mc) { mc = e.getValue(); mainDir = e.getKey(); }
        int n = groups.size();
        double[] gd = new double[n]; double total = 0, longest = 0;
        String[] sig = new String[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            List<Seg> g = groups.get(i);
            for (int k = 0; k < g.size(); k++) { gd[i] += g.get(k).dur; if (k < 3) sb.append(name(g.get(k).uri)).append('|'); }
            sig[i] = sb.toString(); total += gd[i]; longest = Math.max(longest, gd[i]);
        }
        Map<String, Integer> sc = new HashMap<>();
        for (String s : sig) { Integer c = sc.get(s); sc.put(s, c == null ? 1 : c + 1); }
        Set<Integer> shorts = new HashSet<>();
        for (int i = 0; i < n; i++) if (gd[i] <= 60 && gd[i] < longest * 0.1) shorts.add(i);
        double removed = 0;
        List<String> carryKey = new ArrayList<>();
        List<List<Seg>> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Seg> g = groups.get(i);
            int inMain = 0; for (Seg s : g) if (dir(base, s.uri).equals(mainDir)) inMain++;
            double ratio = inMain / (double) g.size();
            boolean nbLong = (i > 0 && gd[i - 1] >= 120) || (i < n - 1 && gd[i + 1] >= 120);
            boolean shortAd = shorts.contains(i) && nbLong && (shorts.size() >= 2 || (i > 0 && i < n - 1));
            boolean ad = ratio < 0.5 || (sc.get(sig[i]) > 1 && gd[i] <= 90) || shortAd;
            if (ad && removed + gd[i] < total * 0.3) {
                removed += gd[i];
                for (Seg s : g) for (String l : s.lines) if (l.startsWith("#EXT-X-KEY")) carryKey.add(l);
                continue;
            }
            if (!carryKey.isEmpty()) {
                boolean has = false; for (String l : g.get(0).lines) if (l.startsWith("#EXT-X-KEY")) has = true;
                // 被删段里的密钥若影响后续正片，补到下一段开头；正片段本身没写 KEY 时沿用最后一个正片 KEY 更稳妥
                if (!has && lastKeptKey(kept) != null) g.get(0).lines.add(0, lastKeptKey(kept));
                carryKey.clear();
            }
            kept.add(g);
        }
        if (removed <= 0) return text;
        lastRemoved = (int) removed;
        StringBuilder out = new StringBuilder();
        for (String h : header) out.append(h).append('\n');
        for (int j = 0; j < kept.size(); j++) {
            if (j > 0) out.append("#EXT-X-DISCONTINUITY\n");
            for (Seg s : kept.get(j)) for (String l : s.lines) {
                // 相对地址改成绝对地址，避免目录变化
                if (!l.isEmpty() && !l.startsWith("#")) { try { l = new URI(base).resolve(l.trim()).toString(); } catch (Exception ignored) {} }
                out.append(l).append('\n');
            }
        }
        out.append("#EXT-X-ENDLIST\n");
        return out.toString();
    }

    static String lastKeptKey(List<List<Seg>> kept) {
        for (int i = kept.size() - 1; i >= 0; i--) for (int k = kept.get(i).size() - 1; k >= 0; k--) {
            List<String> ls = kept.get(i).get(k).lines;
            for (int x = ls.size() - 1; x >= 0; x--) if (ls.get(x).startsWith("#EXT-X-KEY")) return ls.get(x);
        }
        return null;
    }

    /** 包一层 DataSource：m3u8 列表读进内存过滤，其余原样透传 */
    public static class Factory implements DataSource.Factory {
        final DataSource.Factory up;
        public Factory(DataSource.Factory up) { this.up = up; }
        @NonNull @Override public DataSource createDataSource() { return new Src(up.createDataSource()); }
    }

    static class Src implements DataSource {
        final DataSource up; byte[] buf; int pos; boolean mem; Uri realUri;
        Src(DataSource up) { this.up = up; }
        @Override public void addTransferListener(@NonNull TransferListener t) { up.addTransferListener(t); }
        @Override public long open(@NonNull DataSpec spec) throws IOException {
            String path = spec.uri.getPath() == null ? "" : spec.uri.getPath().toLowerCase();
            if (!path.endsWith(".m3u8") || spec.position != 0) { mem = false; return up.open(spec); }
            up.open(spec);
            Uri real = up.getUri() != null ? up.getUri() : spec.uri;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int r;
            while ((r = up.read(b, 0, b.length)) != C.RESULT_END_OF_INPUT) bo.write(b, 0, r);
            up.close();
            String txt = new String(bo.toByteArray(), Charset.forName("UTF-8"));
            realUri = real;
            String f;
            try { f = filter(txt, real.toString()); } catch (Throwable e) { f = txt; }
            buf = f.getBytes(Charset.forName("UTF-8")); pos = 0; mem = true;
            return buf.length;
        }
        @Override public int read(@NonNull byte[] t, int off, int len) throws IOException {
            if (!mem) return up.read(t, off, len);
            if (pos >= buf.length) return C.RESULT_END_OF_INPUT;
            int n = Math.min(len, buf.length - pos); System.arraycopy(buf, pos, t, off, n); pos += n; return n;
        }
        @Nullable @Override public Uri getUri() { return mem ? realUri : up.getUri(); }
        @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return up.getResponseHeaders(); }
        @Override public void close() throws IOException { if (!mem) up.close(); buf = null; }
    }
}
