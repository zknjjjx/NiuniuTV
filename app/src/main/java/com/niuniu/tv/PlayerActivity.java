package com.niuniu.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.common.Format;
import android.os.Build;
import android.view.Display;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
    // 换源判断看「有没有进账」（播放器自己读到的 + 后台预下载收到的）：
    // 起播：6 秒一个字节都没收到就换，有数据在进最多等 12 秒
    // 卡顿：5 秒没进账就换（刚快进过放宽到 10 秒），有进账最多等 30 秒
    static final long START_DEAD = 6000, START_MAX = 12000;
    static final long STALL_DEAD = 5000, STALL_SEEK = 10000, STALL_MAX = 30000;

    private JSONObject data;
    private JSONArray sources;
    private int si, ei;
    private ExoPlayer player;
    private PlayerView view;
    private TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean started = false;
    private final List<Integer> failed = new ArrayList<>();
    private long resumePos = 0;

    private long seekAt = 0;
    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (player == null) return;
            if (started && player.getPlaybackState() != Player.STATE_BUFFERING) return;
            long now = android.os.SystemClock.uptimeMillis();
            long since = now - bufSince;
            long quiet = now - Math.max(bufSince, Math.max(Pre.playerData, Pre.lastData(curUrl)));
            if (!started) {
                if (quiet >= START_DEAD) { switchSource("片源没响应"); return; }
                if (since >= START_MAX) { switchSource("起播太慢"); return; }
            } else {
                long dead = now - seekAt < STALL_SEEK ? STALL_SEEK : STALL_DEAD;
                if (quiet >= dead) { switchSource("卡住了且没有数据"); return; }
                if (since >= STALL_MAX) { switchSource("卡顿太久"); return; }
            }
            h.postDelayed(this, 1000);
        }
    };
    private final Runnable hideStatus = new Runnable() { public void run() { fade(status, false); } };
    private String curUrl = "";
    private long errAt;
    private int errN;
    private long bufSince = 0;
    private final Runnable posTick = new Runnable() { public void run() {
        reportPos();
        h.postDelayed(this, 1000);
    } };
    private void reportPos() {
        if (player == null) return;
        Pre.position(curUrl, player.getCurrentPosition(), player.getBufferedPosition(),
                started && player.getPlaybackState() == Player.STATE_READY);
        if (player.getDuration() > 0 && !player.isCurrentMediaItemLive()) Pre.duration(curUrl, player.getDuration());
        crowdCheck();
    }
    /** 片源嫌连接多、退回单路后：1080 单路不到 0.9 MB/s 撑不住，缓存剩不到 1 分钟就主动换源，不等卡住 */
    private void crowdCheck() {
        if (!started || !autoSrc || sources == null || sources.length() < 2 || !Pre.crowded(curUrl)) return;
        JSONObject s = sources.optJSONObject(si);
        if (s == null || s.optInt("h", 0) < 1000 || s.optLong("bps", 0) >= crowdNeed(s)) return;
        if (Pre.cachedAheadMs(curUrl, player.getCurrentPosition()) >= 60000) return;
        switchSource("片源限制多路下载，单路不够 1080");
    }
    private final Runnable saver = new Runnable() { public void run() { save(); h.postDelayed(this, 10000); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        appCtx = getApplicationContext();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            data = new JSONObject(getIntent().getStringExtra("data"));
            current = new java.lang.ref.WeakReference<>(this);
            sources = data.getJSONArray("sources");
            wantDur = data.optLong("want", 0);
            si = data.optInt("si", 0);
            ei = data.optInt("ei", 0);
            resumePos = data.optLong("pos", 0);
            searchOver = !data.optBoolean("searching", false);
        } catch (Exception e) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setUseController(false);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        buildOsd(root);
        setContentView(root);
        if (Ui.phone(this)) touch(root);

        // 分片走磁盘缓存（后台 Pre 领先 10 分钟预下载）；卡顿后多攒 5 秒再继续，避免一卡再卡
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1500, 5000).build();
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector ts = new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this);
        ts.setParameters(ts.buildUponParameters().setForceHighestSupportedBitrate(true));
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(Pre.playerFactory(this)))
                .setTrackSelector(ts).setLoadControl(lc).build();
        view.setPlayer(player);
        // m3u8/ts 多数不标帧率：按实际渲染的帧时间戳估算，再对齐屏幕刷新率
        player.setVideoFrameMetadataListener(new androidx.media3.exoplayer.video.VideoFrameMetadataListener() {
            @Override
            public void onVideoFrameAboutToBeRendered(long pt, long rel, @NonNull Format f, android.media.MediaFormat mf) {
                if (fDone) return;
                if (fLast >= 0) { long d = pt - fLast; if (d > 5000 && d < 100000) { fSum += d; fCnt++; } }
                fLast = pt;
                if (fCnt >= 48) {
                    fDone = true;
                    final float fps = 1e6f * fCnt / fSum;
                    h.post(new Runnable() { public void run() { estFps = snap(fps); matchRefresh(estFps, 0, 0); updateInfo(); } });
                }
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(@NonNull EventTime t, @NonNull String name, long a, long b) {
                decName = name; updateInfo();
            }
            @Override
            public void onVideoInputFormatChanged(@NonNull EventTime t, @NonNull Format f, DecoderReuseEvaluation e) {
                vFmt = f.width + "x" + f.height + (f.frameRate > 0 ? String.format(java.util.Locale.US, " %.3gfps", f.frameRate) : "");
                if (f.frameRate > 0) matchRefresh(f.frameRate, f.width, f.height);
                try { if (f.height > 0) sources.optJSONObject(si).put("h", Math.max(f.height, f.width * 9 / 16)); } catch (Exception ignore) {}
                updateInfo();
            }
            @Override
            public void onDroppedVideoFrames(@NonNull EventTime t, int n, long ms) {
                // 起播、缓冲恢复、快进后 3 秒内解码器追帧掉的不算，只统计正常播放时的掉帧
                if (android.os.SystemClock.uptimeMillis() < dropQuiet) return;
                dropped += n; updateInfo();
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean pwr, int reason) { refreshCenter(); }
            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo o, @NonNull Player.PositionInfo n, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekAt = android.os.SystemClock.uptimeMillis();
            }
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    h.removeCallbacks(watchdog);
                    dropQuiet = android.os.SystemClock.uptimeMillis() + 3000;
                    if (wantDur > 0 && player.getDuration() > 60000 && !player.isCurrentMediaItemLive()) {   // 电影：记下真实时长，换源列表里显示
                        try { sources.optJSONObject(si).put("dur", player.getDuration() / 1000); } catch (Exception ignore) {}
                    }
                    if (!started) {
                        started = true;
                        failed.clear();
                        showOsd();
                        probeRest();
                    }
                } else if (st == Player.STATE_BUFFERING) {
                    bufSince = android.os.SystemClock.uptimeMillis();
                    reportPos();   // 一缓冲就让后台预下载让路
                    h.removeCallbacks(watchdog);
                    h.postDelayed(watchdog, 1000);
                } else if (st == Player.STATE_ENDED) {
                    h.removeCallbacks(watchdog);
                    nextEpisode();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                h.removeCallbacks(watchdog);
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                // 已经在播的片源偶尔出一次网络错（分片超时、CDN 抽风），先原地重试，别急着换源
                long now = SystemClock.uptimeMillis();
                if (now - errAt > 90_000L) errN = 0;
                errAt = now;
                int c = e.errorCode;
                boolean net = c >= 2000 && c < 3000;
                if (c == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) Pre.backoff(curUrl);
                if (started && net && errN < 3) {
                    errN++;
                    showStatus("网络波动，重试中（" + errN + "/3）", 2500);
                    h.postDelayed(new Runnable() { public void run() { if (player != null) { player.prepare(); player.play(); } } }, 800L * errN);
                    return;
                }
                errN = 0;
                switchSource("播放出错（" + e.getErrorCodeName().replace("ERROR_CODE_", "") + "）");
            }
        });
        if (resumePos <= 0) {
            JSONObject old = Hist.get(this, data.optString("key"));
            if (old != null && old.optInt("ei", -1) == ei) resumePos = old.optLong("pos", 0);
        }
        load(resumePos);
        h.postDelayed(saver, 10000);
        h.postDelayed(posTick, 1000);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private JSONArray eps(int s) {
        JSONObject o = sources.optJSONObject(s);
        return o == null ? new JSONArray() : o.optJSONArray("eps");
    }

    private String epName() {
        JSONArray a = eps(si);
        JSONObject e = a == null ? null : a.optJSONObject(ei);
        return e == null ? "" : e.optString("name");
    }

    private void load(long pos) {
        dropped = 0; estFps = 0; vFmt = ""; fLast = -1; fSum = 0; fCnt = 0; fDone = false;
        JSONArray a = eps(si);
        if (a == null || a.length() == 0) { switchSource("没有剧集"); return; }
        if (ei >= a.length()) ei = a.length() - 1;
        String url = a.optJSONObject(ei).optString("url");
        started = false;
        curUrl = url;
        bufSince = android.os.SystemClock.uptimeMillis();
        Pre.start(this, url, pos, Pre.AHEAD_MS);   // 换源/换集＝新地址：旧缓存删掉，开始缓存新的
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.contains(".m3u8")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mb.build(), pos > 0 ? pos : C.TIME_UNSET);
        player.prepare();
        player.setPlayWhenReady(true);
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 1000);
        updateInfo();
    }

    private String decName = "", vFmt = "";
    private int dropped = 0;
    private long dropQuiet = 0;

    /** 让电视刷新率跟片源帧率对齐（25fps→50Hz、24→24/48Hz），消除 25 帧片在 60Hz 上的抖动 */
    private void matchRefresh(float fps, int vw, int vh) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode();
            Display.Mode best = null;
            float bestScore = 1e9f;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                float r = m.getRefreshRate(), k = r / fps, frac = Math.abs(k - Math.round(k));
                if (Math.round(k) < 1 || frac > 0.012f) continue;
                float score = Math.abs(r - cur.getRefreshRate());
                if (score < bestScore) { bestScore = score; best = m; }
            }
            if (best != null && best.getModeId() != cur.getModeId()) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = best.getModeId();
                getWindow().setAttributes(lp);
                refresh = Math.round(best.getRefreshRate()) + "Hz";
            } else refresh = Math.round(cur.getRefreshRate()) + "Hz";
        } catch (Throwable ignored) {}
    }
    private String refresh = "";
    private float estFps = 0;
    private volatile long fLast = -1, fSum = 0;
    private volatile int fCnt = 0;
    private volatile boolean fDone = false;
    static float snap(float f) {
        float[] std = {23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f};
        float b = f, bd = 9;
        for (float s : std) if (Math.abs(s - f) < bd) { bd = Math.abs(s - f); b = s; }
        return bd < 0.6f ? b : f;
    }

    private String decInfo() {
        if (decName.isEmpty()) return "";
        String n = decName.toLowerCase(java.util.Locale.US);
        boolean sw = n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg");
        if (refresh.isEmpty() && Build.VERSION.SDK_INT >= 23) refresh = Math.round(getWindowManager().getDefaultDisplay().getMode().getRefreshRate()) + "Hz";
        String fps = vFmt.contains("fps") || estFps <= 0 ? "" : String.format(java.util.Locale.US, " %.3gfps", estFps);
        return (sw ? "软解" : "硬解") + "  " + vFmt + fps + "  屏幕 " + refresh + "  掉帧 " + dropped;
    }

    private void updateInfo() {
        if (tTitle == null) return;
        String ep = epName();
        int total = eps(si).length();
        JSONObject s = sources.optJSONObject(si);
        String src = s == null ? "" : s.optString("site");
        tTitle.setText(data.optString("title"));
        tSub.setText((total > 1 ? ep + "   ·   " : "") + src + "   ·   片源 " + (si + 1) + "/" + sources.length());
        String d = decInfo();
        tTech.setText(d);
        tTech.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showStatus(String msg, long ms) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        status.animate().cancel();
        status.setAlpha(1f);
        h.removeCallbacks(hideStatus);
        if (ms > 0) h.postDelayed(hideStatus, ms);
    }

    // ---------------- 自绘播放界面 ----------------
    private static final int AC = 0xFFFF6A3D;
    private LinearLayout top, bottom;
    private TextView tTitle, tSub, tTech, tClock, tCur, tDur, tHint;
    private Bar bar;
    private PlayIcon center;
    private boolean osdOn = false;
    private boolean okDown = false;
    private long seekTarget = -1, seekHold = 0, seekLast = 0;
    private FrameLayout panel;
    // 遥控器按 ▼ 出现的底部快捷栏：暂停 / 从头播放 / 上一集 / 下一集 / 换源
    private static final int K_PLAY = 0, K_HEAD = 1, K_PREV = 2, K_NEXT = 3, K_SRC = 4;
    private LinearLayout keys, srcRow;
    private android.widget.HorizontalScrollView srcScroll;
    private final TextView[] kb = new TextView[5];
    private int keySel = -1;   // -1 未进入快捷栏
    private int srcSel = -1;   // >=0 正在选片源（0=自动选择，i+1=第 i 个源）
    private ListView plist;
    private TextView tabEp, tabSrc;
    private View tabLine;
    private int tab = 0;

    private final Runnable ticker = new Runnable() {
        public void run() {
            tick();
            if (osdOn) h.postDelayed(this, 500);
        }
    };
    private final Runnable autoHide = new Runnable() {
        public void run() { if (player != null && player.getPlayWhenReady() && seekTarget < 0 && !panelOn()) hideOsd(); }
    };

    private TextView tv(float sp, int color, String face) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(face, Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        v.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
        return v;
    }

    private void buildOsd(FrameLayout root) {
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(56), dp(34), dp(56), dp(70));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x59000000, 0x00000000}));
        tTitle = tv(26, 0xFFFFFFFF, "sans-serif");
        tTitle.setSingleLine(true);
        tTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tSub = tv(15, 0xB3FFFFFF, "sans-serif");
        tTech = tv(13, 0x80FFFFFF, "sans-serif-light");
        top.addView(tTitle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        top.addView(tSub, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
        lp2.topMargin = dp(8);
        top.addView(tTech, lp2);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        tClock = tv(24, 0xE6FFFFFF, "sans-serif-light");
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cl.topMargin = dp(32); cl.rightMargin = dp(56);
        root.addView(tClock, cl);

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(56), dp(80), dp(56), dp(30));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x66000000, 0x00000000}));
        FrameLayout times = new FrameLayout(this);
        tCur = tv(15, 0xFFFFFFFF, "sans-serif");
        tDur = tv(15, 0x99FFFFFF, "sans-serif");
        times.addView(tCur, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(tDur, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        bottom.addView(times);
        bar = new Bar(this, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(22));
        bl.topMargin = dp(6);
        bottom.addView(bar, bl);
        tHint = tv(13, 0x73FFFFFF, "sans-serif");
        tHint.setText("OK  暂停 / 继续        ◀ ▶  快退 / 快进        ▲  选集        ▼  暂停 · 从头 · 上下集 · 换源");
        // 片源一行（选「换源」后出现在快捷栏上方，可横向滚动）
        srcScroll = new android.widget.HorizontalScrollView(this);
        srcScroll.setHorizontalScrollBarEnabled(false);
        srcScroll.setFocusable(false);
        srcRow = new LinearLayout(this);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        srcScroll.addView(srcRow);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(-1, -2);
        spl.topMargin = dp(12);
        bottom.addView(srcScroll, spl);
        srcScroll.setVisibility(View.GONE);
        keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"暂停", "从头播放", "上一集", "下一集", "换源"};
        for (int i = 0; i < 5; i++) {
            kb[i] = chip(names[i]);
            keys.addView(kb[i]);
        }
        LinearLayout.LayoutParams kpl = new LinearLayout.LayoutParams(-2, -2);
        kpl.topMargin = dp(12);
        bottom.addView(keys, kpl);
        keys.setVisibility(View.GONE);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = dp(10);
        bottom.addView(tHint, hl);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        center = new PlayIcon(this);
        root.addView(center, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));

        status = tv(16, 0xF2FFFFFF, "sans-serif");
        status.setShadowLayer(0, 0, 0, 0);
        status.setPadding(dp(22), dp(11), dp(22), dp(11));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xE01C1D24);
        sb.setCornerRadius(dp(24));
        sb.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        status.setBackground(sb);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sl.topMargin = dp(40);
        root.addView(status, sl);
        status.setVisibility(View.GONE);

        buildPanel(root);
        top.setVisibility(View.GONE); bottom.setVisibility(View.GONE); tClock.setVisibility(View.GONE); center.setVisibility(View.GONE);
    }

    private void fade(View v, boolean on) {
        v.animate().cancel();
        if (on) {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(1f).setDuration(180).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            final View w = v;
            v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() { public void run() { w.setVisibility(View.GONE); } }).start();
        }
    }

    private void showOsd() {
        h.removeCallbacks(autoHide);
        if (!osdOn) {
            osdOn = true;
            updateInfo();
            fade(top, true); fade(bottom, true); fade(tClock, true);
            h.removeCallbacks(ticker);
            h.post(ticker);
        }
        h.postDelayed(autoHide, keySel >= 0 ? 10000 : 4500);
    }

    private int epCount() { JSONArray a = eps(si); return a == null ? 0 : a.length(); }
    private boolean canKey(int w) {
        if (w == K_PREV) return ei > 0;
        if (w == K_NEXT) return ei + 1 < epCount();
        if (w == K_SRC) return sources != null && sources.length() > 1;
        return w >= 0 && w < 5;
    }

    private TextView chip(String text) {
        TextView t = tv(15, 0xF2FFFFFF, "sans-serif");
        t.setShadowLayer(0, 0, 0, 0);
        t.setSingleLine(true);
        t.setText(text);
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-2, -2);
        kl.rightMargin = dp(12);
        t.setLayoutParams(kl);
        return t;
    }

    private void styleChip(TextView t, boolean sel, boolean on, boolean cur) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(sel ? 0x40FF6A3D : 0x1FFFFFFF);
        g.setStroke(Math.max(1, dp(sel ? 2 : 1)), sel ? AC : 0x26FFFFFF);
        t.setBackground(g);
        t.setTextColor(!on ? 0x4DFFFFFF : cur ? 0xFFFFA477 : sel ? 0xFFFFFFFF : 0xCCFFFFFF);
    }

    private void paintKeys() {
        if (keys == null) return;
        kb[K_PLAY].setText(player != null && !player.getPlayWhenReady() ? "播放" : "暂停");
        for (int i = 0; i < 5; i++) styleChip(kb[i], keySel == i && srcSel < 0, canKey(i), false);
    }

    private void enterKeys() {
        keySel = canKey(K_NEXT) ? K_NEXT : K_PLAY;
        srcSel = -1;
        keys.setVisibility(View.VISIBLE);
        paintKeys();
        showOsd();
    }

    private void exitKeys() {
        keySel = -1;
        srcSel = -1;
        if (keys != null) keys.setVisibility(View.GONE);
        if (srcScroll != null) srcScroll.setVisibility(View.GONE);
    }

    private void moveKey(int d) {
        for (int w = keySel + d; w >= 0 && w < 5; w += d) if (canKey(w)) { keySel = w; break; }
        paintKeys();
    }

    private void doKey() {
        switch (keySel) {
            case K_PLAY:
                player.setPlayWhenReady(!player.getPlayWhenReady());
                refreshCenter();
                break;
            case K_HEAD:
                player.seekTo(0);
                player.setPlayWhenReady(true);
                refreshCenter();
                showStatus("从头播放", 1500);
                break;
            case K_PREV: jumpEp(ei - 1); break;
            case K_NEXT: jumpEp(ei + 1); break;
            case K_SRC: exitKeys(); showPanel(1); return;   // 换源：弹出右侧半透明片源菜单
        }
        if (!canKey(keySel)) moveKey(keySel == K_PREV ? 1 : -1);
        paintKeys();
        showOsd();
    }

    // ---- 片源一行 ----
    private void openSrc() {
        srcRow.removeAllViews();
        for (int i = 0; i <= sources.length(); i++) {
            String t;
            if (i == 0) t = "自动选择";
            else {
                JSONObject o = sources.optJSONObject(i - 1);
                String res = resName(o.optInt("h", 0)), dn = durName(o);
                long bps = o.optLong("bps", 0);
                t = o.optString("site") + (res.length() > 0 ? "  " + res : "") + (dn.length() > 0 ? "  " + dn : "")
                        + (bps > 0 ? String.format(java.util.Locale.US, "  %.1fM", bps / 1048576.0) : bps < 0 ? "  不通" : "");
            }
            srcRow.addView(chip(t));
        }
        srcSel = autoSrc ? 0 : si + 1;
        srcScroll.setVisibility(View.VISIBLE);
        paintSrc();
        paintKeys();
        showOsd();
    }

    private void paintSrc() {
        int cur = autoSrc ? 0 : si + 1;
        for (int i = 0; i < srcRow.getChildCount(); i++)
            styleChip((TextView) srcRow.getChildAt(i), i == srcSel, true, i == cur);
        final View v = srcRow.getChildAt(Math.max(0, srcSel));
        if (v != null) srcScroll.post(new Runnable() { public void run() {
            int x = v.getLeft() - (srcScroll.getWidth() - v.getWidth()) / 2;
            srcScroll.smoothScrollTo(Math.max(0, x), 0);
        } });
    }

    private void closeSrc() {
        srcSel = -1;
        srcScroll.setVisibility(View.GONE);
        paintKeys();
        showOsd();
    }

    /** w=0 自动选择，w=i+1 第 i 个源；保留进度 */
    private void pickSource(int w) {
        long pos = player.getCurrentPosition();
        int to;
        if (w == 0) { autoSrc = true; to = bestSource(); }
        else { autoSrc = false; to = w - 1; }
        if (to != si) {
            int idx = matchEp(eps(to), epName(), ei);
            si = to; ei = Math.max(0, idx); failed.clear(); load(pos);
        }
        showStatus("切换到「" + sources.optJSONObject(si).optString("site") + "」", 2000);
    }

    private void jumpEp(int to) {
        if (to < 0 || to >= epCount() || to == ei) return;
        save();
        ei = to;
        failed.clear();
        showStatus("播放 " + epName(), 2000);
        load(0);
        paintKeys();
        showOsd();
    }

    private void hideOsd() {
        h.removeCallbacks(autoHide);
        exitKeys();
        if (!osdOn) return;
        osdOn = false;
        fade(top, false); fade(bottom, false); fade(tClock, false);
    }

    private void tick() {
        if (player == null) return;
        long dur = player.getDuration(), pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        boolean live = dur == C.TIME_UNSET || dur <= 0;
        tCur.setText(fmt(pos));
        tCur.setTextColor(seekTarget >= 0 ? 0xFFFFB27A : 0xFFFFFFFF);
        tDur.setText(live ? "直播" : fmt(dur));
        bar.set(live ? 0 : (float) pos / dur, live ? 0 : (float) player.getBufferedPosition() / dur, seekTarget >= 0);
        tClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date()));
    }

    private void refreshCenter() {
        boolean paused = player != null && !player.getPlayWhenReady();
        fade(center, paused);
        if (keySel >= 0) paintKeys();
        if (paused) { h.removeCallbacks(autoHide); if (!osdOn) showOsd(); h.removeCallbacks(autoHide); }
    }

    private void seekBy(boolean fwd, int repeat) {
        long dur = player.getDuration();
        long now = android.os.SystemClock.uptimeMillis();
        if (repeat == 0) seekHold = now;
        else if (now - seekLast < 180) return;   // 长按节流：约每 0.18 秒走一步
        seekLast = now;
        if (seekTarget < 0) seekTarget = player.getCurrentPosition();
        long held = now - seekHold;
        long step = repeat == 0 ? 10000 : held < 2500 ? 5000 : held < 6000 ? 10000 : 20000;
        seekTarget += fwd ? step : -step;
        if (seekTarget < 0) seekTarget = 0;
        if (dur > 0 && dur != C.TIME_UNSET && seekTarget > dur - 3000) seekTarget = Math.max(0, dur - 3000);
        showOsd();
        tick();
    }

    private void commitSeek() {
        if (seekTarget < 0) return;
        player.seekTo(seekTarget);
        seekTarget = -1;
        tick();
        showOsd();
    }

    // ---------------- 手机：触屏操作 ----------------
    // 点一下显示/隐藏控制栏，双击暂停/继续，左右滑动快进快退（滑满一屏约 2 分钟），点进度条直接跳
    private long dragBase = -1;
    private void togglePlay() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        showOsd(); refreshCenter();
    }
    private void touch(FrameLayout root) {
        tHint.setVisibility(View.GONE);
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        final TextView bPlay = pill("暂停");
        final TextView bEp = pill("选集"), bSrc = pill("换源"), bBack = pill("返回");
        acts.addView(bBack);
        View sp = new View(this);
        acts.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        acts.addView(bPlay); acts.addView(bEp); acts.addView(bSrc);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(6);
        bottom.addView(acts, al);
        bottom.setPadding(dp(28), dp(60), dp(28), dp(16));
        top.setPadding(dp(28), dp(20), dp(28), dp(50));
        ((FrameLayout.LayoutParams) tClock.getLayoutParams()).rightMargin = dp(28);
        ((FrameLayout.LayoutParams) tClock.getLayoutParams()).topMargin = dp(18);
        bBack.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save(); finish(); } });
        bPlay.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { togglePlay(); } });
        bEp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showPanel(0); } });
        bSrc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showPanel(1); } });
        h.post(new Runnable() { public void run() {
            if (player != null) bPlay.setText(player.getPlayWhenReady() ? "暂停" : "播放");
            h.postDelayed(this, 500);
        } });
        tabEp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tab = 0; fillPanel(); kickPanelIdle(); } });
        tabSrc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tab = 1; fillPanel(); kickPanelIdle(); } });
        bar.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                long dur = player == null ? 0 : player.getDuration();
                if (dur <= 0 || dur == C.TIME_UNSET) return false;
                float f = Math.max(0, Math.min(1, e.getX() / Math.max(1, v.getWidth())));
                seekTarget = (long) (f * dur);
                showOsd(); tick();
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) commitSeek();
                return true;
            }
        });
        final android.view.GestureDetector gd = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                if (panelOn()) hidePanel();
                else if (osdOn && player != null && player.getPlayWhenReady()) hideOsd();
                else showOsd();
                return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent e) { if (!panelOn()) togglePlay(); return true; }
            @Override public boolean onScroll(android.view.MotionEvent a, android.view.MotionEvent b, float dx, float dy) {
                if (a == null || panelOn() || player == null) return false;
                float mx = b.getX() - a.getX();
                if (dragBase < 0) { if (Math.abs(mx) < dp(16) || Math.abs(mx) < Math.abs(b.getY() - a.getY())) return false; dragBase = player.getCurrentPosition(); }
                long dur = player.getDuration();
                long t = dragBase + (long) (mx / Math.max(1, root.getWidth()) * 120000);
                if (t < 0) t = 0;
                if (dur > 0 && dur != C.TIME_UNSET && t > dur - 3000) t = Math.max(0, dur - 3000);
                seekTarget = t;
                showOsd(); tick();
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                gd.onTouchEvent(e);
                int a = e.getActionMasked();
                if ((a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) && dragBase >= 0) { dragBase = -1; commitSeek(); }
                return true;
            }
        });
    }
    private TextView pill(String s) {
        TextView t = tv(15, 0xF2FFFFFF, "sans-serif");
        t.setText(s);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(18));
        t.setBackground(g);
        t.setClickable(true);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.leftMargin = dp(10);
        t.setLayoutParams(l);
        return t;
    }

    // ---------------- 侧边选集 / 换源面板 ----------------
    /** 起播时还没测完的源和起播后才搜到的源，播放稳了以后在后台接着测：单路逐个测，缓存够 30 秒才测（最多等 2 分钟），不和正片抢带宽 */
    private final java.util.concurrent.ConcurrentLinkedQueue<Integer> probeQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean probing, gone;
    private final java.util.concurrent.atomic.AtomicInteger workers = new java.util.concurrent.atomic.AtomicInteger();
    private static java.lang.ref.WeakReference<PlayerActivity> current;
    private void probeRest() {
        if (probing || sources == null) return;
        probing = true;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s != null && s.optLong("bps", 0) == 0 && s.optJSONArray("eps") != null && s.optJSONArray("eps").length() > 0) probeQ.add(i);
        }
        kickProbe();
        h.postDelayed(new Runnable() { public void run() { searchOver = true; checkAllDone(); } }, 100000);   // 网页没报搜完也最多等 100 秒
        checkAllDone();
    }
    /** 网页报告搜源结束 */
    static void searchEnded() {
        final PlayerActivity a = current == null ? null : current.get();
        if (a == null || a.gone) return;
        a.h.post(new Runnable() { public void run() { a.searchOver = true; a.checkAllDone(); } });
    }
    private boolean searchOver, settled;
    /** 搜源和测速全部结束后：换源列表按自动选源的顺序重排一次；自动模式下，除非已在放 1080 且缓存够 1 分钟，否则按新排序选一次（只在档次更高时才换） */
    private void checkAllDone() {
        if (settled || gone || !started || !searchOver || !probeQ.isEmpty() || workers.get() > 0) return;
        settled = true;
        try {
            int n = sources.length();
            final long[] sc = new long[n];
            Integer[] ord = new Integer[n];
            for (int i = 0; i < n; i++) {
                JSONObject o = sources.optJSONObject(i);
                sc[i] = tierOf(o) * 10000000000L + Math.max(0, o.optLong("bps", 0));
                ord[i] = i;
            }
            java.util.Arrays.sort(ord, new java.util.Comparator<Integer>() { public int compare(Integer x, Integer y) { return Long.compare(sc[y], sc[x]); } });
            JSONArray ns = new JSONArray();
            int[] map = new int[n];
            for (int k = 0; k < n; k++) { ns.put(sources.optJSONObject(ord[k])); map[ord[k]] = k; }
            List<Integer> nf = new ArrayList<>();
            for (Integer f : failed) if (f >= 0 && f < n) nf.add(map[f]);
            si = map[si];
            sources = ns;
            data.put("sources", ns);
            failed.clear(); failed.addAll(nf);
        } catch (Exception ignore) { return; }
        if (panelOn() && tab == 1) { fillPanel(); plist.requestFocus(); }   // 光标回到当前源，不停在别的行
        if (!autoSrc || player == null) return;
        JSONObject cur = sources.optJSONObject(si);
        long pos = player.getCurrentPosition();
        long ahead = Math.max(Pre.cachedAheadMs(curUrl, pos), player.getBufferedPosition() - pos);
        if (cur.optInt("h", 0) >= 1000 && ahead >= 60000) return;
        int to = bestSource();
        if (to == si || tierOf(sources.optJSONObject(to)) <= tierOf(cur)) return;
        int idx = matchEp(eps(to), epName(), ei);
        if (idx < 0) return;
        si = to; ei = idx; failed.clear();
        showStatus("测完了，换到更清楚的「" + sources.optJSONObject(si).optString("site") + "」", 3500);
        load(pos);
    }
    private void kickProbe() {
        while (probing && !gone && !probeQ.isEmpty() && workers.get() < 1) {
            workers.incrementAndGet();
            new Thread(new Runnable() { public void run() {
                try {
                    Integer i;
                    while (!gone && (i = probeQ.poll()) != null) {
                        for (int w = 0; w < 120 && !gone && !calm(); w++) { try { Thread.sleep(1000); } catch (InterruptedException e) { return; } }
                        if (gone) return;
                        final int k = i;
                        String url;
                        try { url = sources.optJSONObject(k).getJSONArray("eps").getJSONObject(0).optString("url"); } catch (Exception e) { continue; }
                        final JSONObject o = Net.probe(url, 8000, 524288);
                        h.post(new Runnable() { public void run() {
                            try {
                                JSONObject s = sources.optJSONObject(k);
                                s.put("bps", o.optLong("bps", 0));
                                if (o.has("h")) s.put("h", o.optInt("h"));
                                if (o.has("dur") && s.optLong("dur", 0) == 0) s.put("dur", o.optLong("dur"));
                                if (o.has("br")) s.put("br", o.optLong("br"));
                            } catch (Exception ignore) {}
                            refreshPanel();
                        }});
                    }
                } finally { workers.decrementAndGet(); h.post(new Runnable() { public void run() { checkAllDone(); } }); }
            }}, "probe-rest").start();
        }
    }
    /** 换源列表开着时刷新，保留遥控器当前停留的那一行 */
    private void refreshPanel() {
        if (!panelOn() || tab != 1) return;
        int p = plist.getSelectedItemPosition();
        fillPanel();
        if (p >= 0) plist.setSelection(p);
        plist.requestFocus();
    }
    /** 网页起播后新搜到的源：同一部片才收，同站同地址的不重复加 */
    static void addSource(String key, String json) {
        PlayerActivity a = current == null ? null : current.get();
        if (a == null || a.gone || a.sources == null || a.data == null || !a.data.optString("key").equals(key)) return;
        try {
            JSONObject n = new JSONObject(json);
            JSONArray ne = n.optJSONArray("eps");
            if (ne == null || ne.length() == 0) return;
            String u = ne.optJSONObject(0).optString("url");
            for (int i = 0; i < a.sources.length(); i++) {
                JSONObject s = a.sources.optJSONObject(i);
                JSONArray e = s.optJSONArray("eps");
                if (s.optString("site").equals(n.optString("site")) && e != null && e.length() > 0 && u.equals(e.optJSONObject(0).optString("url"))) return;
            }
            n.put("bps", 0);
            a.sources.put(n);
            a.probeQ.add(a.sources.length() - 1);
            a.kickProbe();
            a.refreshPanel();
        } catch (Exception ignore) {}
    }
    /** 播放顺：已起播、缓存领先 30 秒以上 */
    private boolean calm() {
        try {
            final long[] pos = {-1};
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            h.post(new Runnable() { public void run() { pos[0] = player == null ? -1 : player.getCurrentPosition(); l.countDown(); } });
            l.await(2, java.util.concurrent.TimeUnit.SECONDS);
            return pos[0] >= 0 && Pre.cachedAheadMs(curUrl, pos[0]) >= 30000;
        } catch (Exception e) { return false; }
    }
    private boolean panelOn() { return panel != null && panel.getVisibility() == View.VISIBLE; }

    private LinearLayout pbox;
    private void buildPanel(FrameLayout root) {
        panel = new FrameLayout(this);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xF70F1015, 0xEB0F1015, 0x000F1015}));
        LinearLayout box = pbox = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(36), dp(40), dp(44), dp(28));
        LinearLayout tabs = new LinearLayout(this);
        tabEp = tv(20, 0xFFFFFFFF, "sans-serif");
        tabSrc = tv(20, 0x80FFFFFF, "sans-serif");
        tabs.addView(tabEp);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(30);
        tabs.addView(tabSrc, tl);
        box.addView(tabs);
        tabLine = new View(this);
        GradientDrawable ln = new GradientDrawable();
        ln.setColor(AC);
        ln.setCornerRadius(dp(2));
        tabLine.setBackground(ln);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(22), dp(3));
        ll.topMargin = dp(9);
        box.addView(tabLine, ll);
        TextView tip = tv(12, 0x66FFFFFF, "sans-serif");
        tip.setText(Ui.phone(this) ? "点「选集 / 换源」切换    点空白处关闭" : "OK 选择    返回 关闭");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.topMargin = dp(12);
        box.addView(tip, pl);
        plist = new ListView(this);
        plist.setDivider(null);
        plist.setVerticalScrollBarEnabled(false);
        plist.setCacheColorHint(0);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0x26FFFFFF);
        sel.setCornerRadius(dp(10));
        sel.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        plist.setSelector(sel);
        plist.setDrawSelectorOnTop(false);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, 0, 1f);
        ql.topMargin = dp(16);
        box.addView(plist, ql);
        boolean ph = Ui.phone(this);   // 电视：右侧只留选集，换源移到 ▼ 快捷栏，菜单收窄
        if (!ph) { tabSrc.setVisibility(View.GONE); box.setPadding(dp(28), dp(40), dp(32), dp(28)); }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(ph ? 420 : 280), -1, Gravity.END);
        panel.addView(box, bp);
        root.addView(panel, new FrameLayout.LayoutParams(dp(ph ? 560 : 400), -1, Gravity.END));
        panel.setVisibility(View.GONE);
        plist.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int w, long id) {
                hidePanel();
                if (tab == 0) { save(); ei = w; failed.clear(); load(0); }
                else { pickSource(w); return; }
                showStatus("播放 " + epName(), 2000);
            }
        });
    }

    private void showPanel(int which) {
        tab = which;
        if (!Ui.phone(this)) {   // 电视：选集窄栏；片源要放分辨率和速率，宽一些
            panel.getLayoutParams().width = dp(which == 1 ? 720 : 400);
            pbox.getLayoutParams().width = dp(which == 1 ? 600 : 280);
            panel.requestLayout();
        }
        fillPanel();
        hideOsd();
        fade(panel, true);
        plist.requestFocus();
        kickPanelIdle();
        h.removeCallbacks(panelTick);
        h.postDelayed(panelTick, 1000);
    }

    private android.widget.BaseAdapter padapter;
    /** 播放位置往后已缓存多久：磁盘上连续的分片与播放器内存缓冲取大者 */
    private String cachedText() {
        if (player == null) return "";
        long pos = player.getCurrentPosition();
        long ms = Math.max(Pre.cachedAheadMs(curUrl, pos), player.getBufferedPosition() - pos);
        long sec = Math.max(0, ms / 1000);
        return "  ·  已缓存 " + (sec >= 60 ? sec / 60 + " 分 " + sec % 60 + " 秒" : sec + " 秒");
    }
    private final Runnable panelTick = new Runnable() { public void run() {
        if (!panelOn()) return;
        if (tab == 1 && padapter != null) padapter.notifyDataSetChanged();
        h.postDelayed(this, 1000);
    } };

    private void hidePanel() { h.removeCallbacks(panelIdle); fade(panel, false); }

    /** 菜单 15 秒无操作自动收起 */
    private final Runnable panelIdle = new Runnable() { public void run() { if (panelOn()) hidePanel(); } };
    private void kickPanelIdle() { h.removeCallbacks(panelIdle); h.postDelayed(panelIdle, 15000); }

    private boolean autoSrc = true;

    /** 电影的豆瓣时长（秒），0＝不比；片源时长差太多（多半是同名的别的片子）排到最后 */
    private static long wantDur;
    static boolean durOff(JSONObject s) {
        long d = s.optLong("dur", 0);
        return wantDur > 0 && d > 0 && Math.abs(d - wantDur) > Math.max(600, wantDur * 0.12);
    }
    private static String durName(JSONObject s) {
        long d = s.optLong("dur", 0);
        if (d <= 0) return "";
        return (d >= 3600 ? (d / 3600) + "小时" + (d % 3600 / 60) + "分" : (d / 60) + "分钟") + (durOff(s) ? "（时长不符）" : "");
    }
    private static String resName(int hh) {
        if (hh >= 2000) return "4K";
        if (hh >= 1300) return "2K";
        if (hh >= 1000) return "1080P";
        if (hh >= 700) return "720P";
        if (hh >= 450) return "480P";
        return hh > 0 ? hh + "P" : "";
    }

    /** 与详情页一致：1080 以上流畅 > 720(含未知)流畅 > 其余能通；同档按速度 */
    private static Context appCtx;
    private static boolean crowdHost(JSONObject s) {
        try { return appCtx != null && Pre.isCrowdHost(appCtx, s.getJSONArray("eps").getJSONObject(0).optString("url")); } catch (Exception e) { return false; }
    }
    /** 嫌连接多的站只能单路：知道码率就要单路速度够码率的 1.5 倍（至少 0.2 MB/s），不知道还按 0.9 MB/s */
    static double crowdNeed(JSONObject s) {
        long br = s.optLong("br", 0);
        return br > 0 ? Math.max(0.2 * 1048576, br * 1.5) : 0.9 * 1048576;
    }
    private static int tierOf(JSONObject s) {
        int hh = s.optInt("h", 0); long b = s.optLong("bps", 0);
        if (b < 0) return 0;
        if (b == 0) return 1;
        if (durOff(s)) return 1;
        int r = hh >= 1000 ? 3 : (hh >= 700 || hh == 0) ? 2 : 1;
        // 5 路预下载：1080 单路 0.2 MB/s 就够；嫌连接多的域名只能单路，要 0.9 才排在 720 前
        double need = r == 3 ? (crowdHost(s) ? crowdNeed(s) : 0.2 * 1048576) : r == 2 ? 0.15 * 1048576 : 0;
        return (b >= need ? r : 1) + 1;
    }
    private int bestSource() {
        String name = epName(); int best = si; long bs = Long.MIN_VALUE;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (failed.contains(i) && i != si) continue;
            JSONArray a = eps(i);
            if (a == null || a.length() == 0 || matchEp(a, name, ei) < 0) continue;
            long sc = tierOf(s) * 10000000000L + Math.max(0, s.optLong("bps", 0));
            if (sc > bs) { bs = sc; best = i; }
        }
        return best;
    }

    private void fillPanel() {
        tabEp.setTextColor(tab == 0 ? 0xFFFFFFFF : 0x80FFFFFF);
        tabSrc.setTextColor(tab == 1 ? 0xFFFFFFFF : 0x80FFFFFF);
        JSONArray a = eps(si);
        tabEp.setText("选集  " + (a == null ? 0 : a.length()));
        tabSrc.setText("片源  " + sources.length());
        LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) tabLine.getLayoutParams();
        tabEp.measure(0, 0);
        boolean tvOne = !Ui.phone(this);   // 电视一次只显示一个标题
        if (tvOne) { tabEp.setVisibility(tab == 0 ? View.VISIBLE : View.GONE); tabSrc.setVisibility(tab == 1 ? View.VISIBLE : View.GONE); ((LinearLayout.LayoutParams) tabSrc.getLayoutParams()).leftMargin = tab == 1 ? 0 : dp(30); }
        ll.leftMargin = tab == 0 || tvOne ? 0 : tabEp.getMeasuredWidth() + dp(30);
        tabLine.setLayoutParams(ll);
        final int n = tab == 0 ? (a == null ? 0 : a.length()) : sources.length() + 1;
        final int cur = tab == 0 ? ei : (autoSrc ? 0 : si + 1);
        plist.setAdapter(padapter = new android.widget.BaseAdapter() {
            public int getCount() { return n; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, android.view.ViewGroup pg) {
                LinearLayout row = cv instanceof LinearLayout ? (LinearLayout) cv : null;
                TextView l, r;
                if (row == null) {
                    row = new LinearLayout(PlayerActivity.this);
                    row.setPadding(dp(18), dp(13), dp(18), dp(13));
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    l = tv(17, 0xFFFFFFFF, "sans-serif"); l.setShadowLayer(0, 0, 0, 0);
                    l.setSingleLine(true); l.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    r = tv(13, 0x80FFFFFF, "sans-serif"); r.setShadowLayer(0, 0, 0, 0);
                    r.setSingleLine(true); r.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    // 右边说明太长时站名不能被挤没：站名最多占一半宽，至少留 5 个字
                    l.setMinWidth(dp(90)); l.setMaxWidth(dp(260));
                    row.addView(l, new LinearLayout.LayoutParams(-2, -2));
                    View sp = new View(PlayerActivity.this);
                    row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.leftMargin = dp(16);
                    row.addView(r, rp);
                } else { l = (TextView) row.getChildAt(0); r = (TextView) row.getChildAt(2); }
                String left, right = "";
                if (tab == 0) left = eps(si).optJSONObject(i).optString("name");
                else if (i == 0) {
                    left = "自动选择";
                    right = autoSrc ? "当前 · " + sources.optJSONObject(si).optString("site") + cachedText() : "高清优先，卡了自动换";
                } else {
                    JSONObject s = sources.optJSONObject(i - 1);
                    long bps = s.optLong("bps", 0);
                    left = s.optString("site");
                    JSONArray e = s.optJSONArray("eps");
                    String res = resName(s.optInt("h", 0)), dn = durName(s);
                    if (dn.length() > 0) res = res.length() > 0 ? res + "  ·  " + dn : dn;
                    right = (res.length() > 0 ? res + "  ·  " : "") + (bps > 0 ? String.format(java.util.Locale.US, "%.1f MB/s  ·  ", bps / 1048576.0) : bps < 0 ? "不通  ·  " : "")
                            + (e == null ? 0 : e.length()) + " 集";
                    if (i - 1 == si) right = (res.length() > 0 ? res + "  ·  " : "") + (bps > 0 ? String.format(java.util.Locale.US, "%.1f MB/s  ·  ", bps / 1048576.0) : "") + "正在播放" + (autoSrc ? "" : cachedText());
                }
                if (tab == 0 && i == cur) right = "正在播放";
                l.setText(left);
                l.setTextColor(i == cur ? 0xFFFFA477 : 0xE6FFFFFF);
                r.setText(right);
                r.setTextColor(i == cur ? 0xCCFFA477 : 0x80FFFFFF);
                return row;
            }
        });
        plist.setSelection(Math.max(0, cur));
    }

    /** 当前源不行 → 同一集换下一个源，保留进度 */
    private void switchSource(String why) {
        long pos = player == null ? 0 : Math.max(player.getCurrentPosition(), started ? 0 : resumePos);
        failed.add(si);
        String name = epName();
        // 每次换源都按最新档位重排（含刚记下嫌连接多的站），挑没失败过的最好那个
        int c = -1, idx = -1; long bs = Long.MIN_VALUE;
        for (int i = 0; i < sources.length(); i++) {
            if (i == si || failed.contains(i)) continue;
            JSONArray a = eps(i);
            if (a == null || a.length() == 0) continue;
            int k = matchEp(a, name, ei);
            if (k < 0) continue;
            JSONObject s = sources.optJSONObject(i);
            long sc = tierOf(s) * 10000000000L + s.optLong("bps", 0);
            if (sc > bs) { bs = sc; c = i; idx = k; }
        }
        if (c >= 0) {
            si = c;
            ei = idx;
            showStatus(why + "，自动换到「" + sources.optJSONObject(si).optString("site") + "」", 3500);
            load(pos);
            return;
        }
        showStatus(Ui.phone(this) ? "所有片源都试过了，点「换源」手动选" : "所有片源都试过了，按 ▼ 选「换源」", 0);
        failed.clear();
    }

    private int matchEp(JSONArray a, String name, int idx) {
        String want = digits(name);
        if (want.length() > 0) {
            for (int i = 0; i < a.length(); i++) {
                if (want.equals(digits(a.optJSONObject(i).optString("name")))) return i;
            }
        }
        if (a.length() == 1 && idx > 0) return -1;
        return idx < a.length() ? idx : -1;
    }

    private static String digits(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? String.valueOf(Integer.parseInt(m.group(1))) : "";
    }

    private void nextEpisode() {
        JSONArray a = eps(si);
        if (a != null && ei + 1 < a.length()) {
            save();
            ei++;
            failed.clear();
            showStatus("自动播放下一集：" + epName(), 3000);
            load(0);
        } else {
            save();
            finish();
        }
    }

    private void save() {
        if (player == null || data == null) return;
        try {
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            if (!started && pos <= 0) return;
            JSONObject o = new JSONObject();
            o.put("key", data.optString("key"));
            o.put("title", data.optString("title"));
            o.put("pic", data.optString("pic"));
            o.put("id", data.optString("id"));
            o.put("type", data.optString("type"));
            o.put("year", data.optString("year"));
            o.put("ei", ei);
            o.put("epName", epName());
            o.put("eps", eps(si).length());
            o.put("pos", pos);
            o.put("dur", dur == C.TIME_UNSET ? 0 : dur);
            o.put("site", sources.optJSONObject(si).optString("site"));
            o.put("t", System.currentTimeMillis());
            Hist.put(this, o);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (panelOn()) {
            kickPanelIdle();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { if (!down) hidePanel(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && e.getRepeatCount() == 0 && Ui.phone(this)) { tab = k == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1; fillPanel(); }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (keySel >= 0) {   // 快捷栏：◀ ▶ 选择，OK 执行，▲ / 返回 退出，▼ 收起；选片源时 ▼ ▲ 返回 回到快捷栏
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) {
                    int d = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (srcSel >= 0) { srcSel = Math.max(0, Math.min(srcRow.getChildCount() - 1, srcSel + d)); paintSrc(); }
                    else if (e.getRepeatCount() == 0) moveKey(d);
                    showOsd();
                }
                return true;
            }
            if (ok) {
                if (down) { okDown = true; return true; }
                if (!okDown) return true;
                okDown = false;
                if (srcSel >= 0) { int w = srcSel; closeSrc(); pickSource(w); }
                else doKey();
                return true;
            }
            if (srcSel >= 0 && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_UP)) {
                if (!down) closeSrc();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_BACK) {
                if (!down) { exitKeys(); showOsd(); }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!down) hideOsd();
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down) return true;
            if (seekTarget >= 0) { seekTarget = -1; tick(); return true; }
            if (osdOn) { hideOsd(); return true; }
            save();
            finish();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_DPAD_UP) {
            if (!down) showPanel(0);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            boolean fwd = k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            if (down) seekBy(fwd, e.getRepeatCount()); else commitSeek();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down) { okDown = true; return true; }
            if (!okDown) return true;   // 详情页按 OK 的“抬起”会落到播放页，忽略，避免一进来就暂停
            okDown = false;
            {
                if (seekTarget >= 0) { commitSeek(); return true; }
                boolean play = k == KeyEvent.KEYCODE_MEDIA_PLAY || (k != KeyEvent.KEYCODE_MEDIA_PAUSE && !player.getPlayWhenReady());
                player.setPlayWhenReady(play);
                showOsd();
                refreshCenter();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (!down) jumpEp(k == KeyEvent.KEYCODE_MEDIA_NEXT ? ei + 1 : ei - 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!down) enterKeys();
            return true;
        }
        if (k == KeyEvent.KEYCODE_INFO) {
            if (!down) { if (osdOn && player.getPlayWhenReady()) hideOsd(); else showOsd(); }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 细进度条：轨道 / 已缓冲 / 已播放 / 圆点 */
    static class Bar extends View {
        final float d; float pos, buf; boolean seeking;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF r = new android.graphics.RectF();
        Bar(android.content.Context c, float density) { super(c); d = density; }
        void set(float pos, float buf, boolean seeking) {
            this.pos = Math.max(0, Math.min(1, pos)); this.buf = Math.max(0, Math.min(1, buf)); this.seeking = seeking; invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, th = (seeking ? 5 : 3.5f) * d, kr = (seeking ? 8 : 6) * d;
            float x0 = kr, x1 = w - kr, len = x1 - x0;
            p.setColor(0x33FFFFFF); r.set(x0, cy - th / 2, x1, cy + th / 2); c.drawRoundRect(r, th, th, p);
            p.setColor(0x55FFFFFF); r.set(x0, cy - th / 2, x0 + len * buf, cy + th / 2); c.drawRoundRect(r, th, th, p);
            p.setColor(AC); r.set(x0, cy - th / 2, x0 + len * pos, cy + th / 2); c.drawRoundRect(r, th, th, p);
            p.setColor(0xFFFFFFFF); c.drawCircle(x0 + len * pos, cy, kr, p);
        }
    }

    /** 暂停时画面中央的播放符号 */
    static class PlayIcon extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path tri = new android.graphics.Path();
        PlayIcon(android.content.Context c) { super(c); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2, R = w / 2 - 2;
            p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0x66000000); c.drawCircle(cx, cy, R, p);
            p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, w / 60)); p.setColor(0x4DFFFFFF); c.drawCircle(cx, cy, R, p);
            float s = R * 0.42f;
            tri.reset(); tri.moveTo(cx - s * 0.6f, cy - s); tri.lineTo(cx + s, cy); tri.lineTo(cx - s * 0.6f, cy + s); tri.close();
            p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0xF2FFFFFF);
            p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.18f)); c.drawPath(tri, p); p.setPathEffect(null);
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0 || ms == C.TIME_UNSET) return "--:--";
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onDestroy() {
        gone = true;
        if (current != null && current.get() == this) current = null;
        h.removeCallbacksAndMessages(null);
        if (panel != null) panel.animate().cancel();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
