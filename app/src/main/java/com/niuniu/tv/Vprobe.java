package com.niuniu.tv;

import java.io.ByteArrayOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 从 TS 分片开头读 H.264/H.265 的 SPS 取分辨率（移植自影巢 vprobe.py）。读不出返回 null */
final class Vprobe {
    private Vprobe() {}

    /** AES-128-CBC 解密（不去填充，长度截到 16 的倍数） */
    static byte[] aes(byte[] key, byte[] iv, byte[] data) {
        try {
            int n = data.length - data.length % 16;
            if (n <= 0) return null;
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data, 0, n);
        } catch (Throwable e) { return null; }
    }

    static byte[] ivOf(String hex, long seq) {
        byte[] iv = new byte[16];
        if (hex != null && hex.length() > 2) {
            String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
            while (h.length() < 32) h = "0" + h;
            h = h.substring(h.length() - 32);
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        } else {
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (seq >>> (8 * i));
        }
        return iv;
    }

    /** 返回 {宽, 高} 或 null */
    static int[] tsRes(byte[] d) {
        if (d == null) return null;
        int st = -1, lim = Math.min(d.length - 400, 4096);
        for (int i = 0; i < lim; i++) if ((d[i] & 0xFF) == 0x47 && (d[i + 188] & 0xFF) == 0x47 && (d[i + 376] & 0xFF) == 0x47) { st = i; break; }
        if (st < 0) return null;
        ByteArrayOutputStream pl = new ByteArrayOutputStream();
        for (int i = st; i + 188 <= d.length; i += 188) {
            if ((d[i] & 0xFF) != 0x47) break;
            int f = d[i + 3] & 0xFF, o = 4;
            if ((f & 0x20) != 0) o = 5 + (d[i + 4] & 0xFF);
            if ((f & 0x10) != 0 && o < 188) pl.write(d, i + o, 188 - o);
        }
        byte[] p = pl.toByteArray();
        for (int k = 0; k + 3 < p.length; k++) {
            if (p[k] != 0 || p[k + 1] != 0 || p[k + 2] != 1) continue;
            int h = k + 3, b = p[h] & 0xFF;
            try {
                if ((b & 0x1F) == 7 && (b & 0x80) == 0) { int[] r = h264(rbsp(p, h + 1)); if (ok(r)) return r; }
                else if (((b >> 1) & 0x3F) == 33) { int[] r = h265(rbsp(p, h + 2)); if (ok(r)) return r; }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    private static boolean ok(int[] r) { return r != null && r[0] >= 64 && r[1] >= 64 && r[0] <= 8192 && r[1] <= 8192; }

    private static byte[] rbsp(byte[] p, int from) {
        int end = Math.min(p.length, from + 200);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int z = 0;
        for (int i = from; i < end; i++) {
            int v = p[i] & 0xFF;
            if (z >= 2 && v == 3) { z = 0; continue; }
            o.write(v);
            z = v == 0 ? z + 1 : 0;
        }
        return o.toByteArray();
    }

    private static final class BR {
        final byte[] b; int p = 0;
        BR(byte[] b) { this.b = b; }
        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if ((p >> 3) >= b.length) throw new IllegalStateException();
                v = (v << 1) | ((b[p >> 3] >> (7 - (p & 7))) & 1); p++;
            }
            return v;
        }
        void skip(int n) { p += n; if ((p >> 3) > b.length) throw new IllegalStateException(); }
        int ue() { int z = 0; while (u(1) == 0) { if (++z > 31) throw new IllegalStateException(); } return (1 << z) - 1 + u(z); }
        int se() { int v = ue(); return (v & 1) != 0 ? (v + 1) / 2 : -(v / 2); }
    }

    private static int[] h264(byte[] b) {
        BR r = new BR(b);
        int prof = r.u(8); r.skip(16); r.ue(); int cf = 1;
        if (prof == 100 || prof == 110 || prof == 122 || prof == 244 || prof == 44 || prof == 83 || prof == 86 || prof == 118 || prof == 128 || prof == 138 || prof == 139 || prof == 134 || prof == 135) {
            cf = r.ue();
            if (cf == 3) r.u(1);
            r.ue(); r.ue(); r.u(1);
            if (r.u(1) == 1) {
                for (int i = 0; i < (cf != 3 ? 8 : 12); i++) if (r.u(1) == 1) {
                    int last = 8, nxt = 8;
                    for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                        if (nxt != 0) nxt = (last + r.se() + 256) % 256;
                        last = nxt == 0 ? last : nxt;
                    }
                }
            }
        }
        r.ue(); int pt = r.ue();
        if (pt == 0) r.ue();
        else if (pt == 1) { r.u(1); r.se(); r.se(); int n = r.ue(); for (int i = 0; i < n; i++) r.se(); }
        r.ue(); r.u(1);
        int w = (r.ue() + 1) * 16, hm = r.ue() + 1, fmo = r.u(1), h = (2 - fmo) * hm * 16;
        if (fmo == 0) r.u(1);
        r.u(1);
        if (r.u(1) == 1) {
            int cl = r.ue(), cr = r.ue(), ct = r.ue(), cb = r.ue();
            int sx = cf == 3 ? 1 : 2, sy = cf == 1 ? 2 : 1;
            w -= (cl + cr) * sx; h -= (ct + cb) * sy * (2 - fmo);
        }
        return new int[]{w, h};
    }

    private static int[] h265(byte[] b) {
        BR r = new BR(b);
        r.skip(4); int ms = r.u(3); r.u(1);
        r.skip(8); r.skip(32); r.skip(48); r.skip(8);
        boolean[] a = new boolean[ms], c = new boolean[ms];
        for (int i = 0; i < ms; i++) { a[i] = r.u(1) == 1; c[i] = r.u(1) == 1; }
        if (ms > 0) r.skip(2 * (8 - ms));
        for (int i = 0; i < ms; i++) { if (a[i]) r.skip(88); if (c[i]) r.skip(8); }
        r.ue(); int cf = r.ue();
        if (cf == 3) r.u(1);
        int w = r.ue(), h = r.ue();
        if (r.u(1) == 1) {
            int cl = r.ue(), cr = r.ue(), ct = r.ue(), cb = r.ue();
            int sx = cf == 3 ? 1 : 2, sy = cf == 1 ? 2 : 1;
            w -= (cl + cr) * sx; h -= (ct + cb) * sy;
        }
        return new int[]{w, h};
    }

    static boolean isMp4(byte[] d) {
        if (d == null || d.length < 12) return false;
        String t = new String(d, 4, 4);
        return t.equals("ftyp") || t.equals("styp") || t.equals("moof") || t.equals("moov") || t.equals("mdat") || t.equals("free");
    }

    /** 在一段 mp4 数据里找视频轨的 tkhd，读宽高（16.16 定点）；没有返回 null */
    static int[] mp4Res(byte[] d) {
        if (d == null) return null;
        int best = 0; int[] r = null;
        for (int i = 4; i + 92 < d.length; i++) {
            if (d[i] != 't' || d[i + 1] != 'k' || d[i + 2] != 'h' || d[i + 3] != 'd') continue;
            int ver = d[i + 4] & 0xFF;
            int off = i + 4 + (ver == 1 ? 88 : 76);   // 宽高在 box 尾部
            if (off + 8 > d.length) continue;
            int w = u16(d, off), h = u16(d, off + 4);
            int[] c = { w, h };
            if (ok(c) && w * h > best) { best = w * h; r = c; }
        }
        return r;
    }

    /** mp4 的 mvhd：整片时长（秒）；没有返回 0 */
    static long mp4Dur(byte[] d) {
        if (d == null) return 0;
        for (int i = 4; i + 40 < d.length; i++) {
            if (d[i] != 'm' || d[i + 1] != 'v' || d[i + 2] != 'h' || d[i + 3] != 'd') continue;
            int ver = d[i + 4] & 0xFF;
            long ts, du;
            if (ver == 1) { ts = u32(d, i + 24); du = (u32(d, i + 28) << 32) | u32(d, i + 32); }
            else { ts = u32(d, i + 16); du = u32(d, i + 20); }
            if (ts > 0 && du > 0) { long sec = du / ts; if (sec > 0 && sec < 86400) return sec; }
        }
        return 0;
    }
    private static long u32(byte[] d, int o) { return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF); }

    private static int u16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
}
