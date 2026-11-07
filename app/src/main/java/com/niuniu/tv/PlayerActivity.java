package com.niuniu.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
    static final long START_TIMEOUT = 12000;   // 起播 12 秒还没画面 → 换源
    static final long STALL_TIMEOUT = 20000;   // 播放中卡住 20 秒 → 换源

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

    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (player == null) return;
            if (player.getPlaybackState() == Player.STATE_BUFFERING || !started) {
                switchSource(started ? "卡顿太久" : "起播太慢");
            }
        }
    };
    private final Runnable hideStatus = new Runnable() { public void run() { fade(status, false); } };
    private final Runnable saver = new Runnable() { public void run() { save(); h.postDelayed(this, 10000); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            data = new JSONObject(getIntent().getStringExtra("data"));
            sources = data.getJSONArray("sources");
            si = data.optInt("si", 0);
            ei = data.optInt("ei", 0);
            resumePos = data.optLong("pos", 0);
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

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1500, 3000).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new AdFilter.Factory(http)))
                .setLoadControl(lc).build();
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
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    h.removeCallbacks(watchdog);
                    dropQuiet = android.os.SystemClock.uptimeMillis() + 3000;
                    if (!started) {
                        started = true;
                        failed.clear();
                        showOsd();
                    }
                } else if (st == Player.STATE_BUFFERING) {
                    h.removeCallbacks(watchdog);
                    h.postDelayed(watchdog, started ? STALL_TIMEOUT : START_TIMEOUT);
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
                switchSource("播放出错");
            }
        });
        if (resumePos <= 0) {
            JSONObject old = Hist.get(this, data.optString("key"));
            if (old != null && old.optInt("ei", -1) == ei) resumePos = old.optLong("pos", 0);
        }
        load(resumePos);
        h.postDelayed(saver, 10000);
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
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.contains(".m3u8")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mb.build(), pos > 0 ? pos : C.TIME_UNSET);
        player.prepare();
        player.setPlayWhenReady(true);
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, START_TIMEOUT);
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
        tHint.setText("OK  暂停 / 继续        ◀ ▶  快退 / 快进        ▲  选集 · 换源        ▼  隐藏");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = dp(8);
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
        h.postDelayed(autoHide, 4500);
    }

    private void hideOsd() {
        h.removeCallbacks(autoHide);
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

    // ---------------- 侧边选集 / 换源面板 ----------------
    private boolean panelOn() { return panel != null && panel.getVisibility() == View.VISIBLE; }

    private void buildPanel(FrameLayout root) {
        panel = new FrameLayout(this);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xF70F1015, 0xEB0F1015, 0x000F1015}));
        LinearLayout box = new LinearLayout(this);
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
        tip.setText("◀ ▶ 切换    OK 选择    返回 关闭");
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
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(420), -1, Gravity.END);
        panel.addView(box, bp);
        root.addView(panel, new FrameLayout.LayoutParams(dp(560), -1, Gravity.END));
        panel.setVisibility(View.GONE);
        plist.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int w, long id) {
                hidePanel();
                if (tab == 0) { save(); ei = w; failed.clear(); load(0); }
                else {
                    long pos = player.getCurrentPosition();
                    int to;
                    if (w == 0) { autoSrc = true; to = bestSource(); }
                    else { autoSrc = false; to = w - 1; }
                    if (to != si) {
                        int idx = matchEp(eps(to), epName(), ei);
                        si = to; ei = Math.max(0, idx); failed.clear(); load(pos);
                    }
                }
                showStatus(tab == 0 ? "播放 " + epName() : "切换到「" + sources.optJSONObject(si).optString("site") + "」", 2000);
            }
        });
    }

    private void showPanel(int which) {
        tab = which;
        fillPanel();
        hideOsd();
        fade(panel, true);
        plist.requestFocus();
        kickPanelIdle();
    }

    private void hidePanel() { h.removeCallbacks(panelIdle); fade(panel, false); }

    /** 菜单 15 秒无操作自动收起 */
    private final Runnable panelIdle = new Runnable() { public void run() { if (panelOn()) hidePanel(); } };
    private void kickPanelIdle() { h.removeCallbacks(panelIdle); h.postDelayed(panelIdle, 15000); }

    private boolean autoSrc = true;

    private static String resName(int hh) {
        if (hh >= 2000) return "4K";
        if (hh >= 1300) return "2K";
        if (hh >= 1000) return "1080P";
        if (hh >= 700) return "720P";
        if (hh >= 450) return "480P";
        return hh > 0 ? hh + "P" : "";
    }

    /** 与详情页一致：1080 以上流畅 > 720(含未知)流畅 > 其余能通；同档按速度 */
    private static int tierOf(JSONObject s) {
        int hh = s.optInt("h", 0); long b = s.optLong("bps", 0);
        if (b < 0) return 0;
        if (b == 0) return 1;
        int r = hh >= 1000 ? 3 : (hh >= 700 || hh == 0) ? 2 : 1;
        double need = r == 3 ? 0.9 * 1048576 : r == 2 ? 0.45 * 1048576 : 0;
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
        ll.leftMargin = tab == 0 ? 0 : tabEp.getMeasuredWidth() + dp(30);
        tabLine.setLayoutParams(ll);
        final int n = tab == 0 ? (a == null ? 0 : a.length()) : sources.length() + 1;
        final int cur = tab == 0 ? ei : (autoSrc ? 0 : si + 1);
        plist.setAdapter(new android.widget.BaseAdapter() {
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
                    row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(r, new LinearLayout.LayoutParams(-2, -2));
                } else { l = (TextView) row.getChildAt(0); r = (TextView) row.getChildAt(1); }
                String left, right = "";
                if (tab == 0) left = eps(si).optJSONObject(i).optString("name");
                else if (i == 0) {
                    left = "自动选择";
                    right = autoSrc ? "当前 · " + sources.optJSONObject(si).optString("site") : "高清优先，卡了自动换";
                } else {
                    JSONObject s = sources.optJSONObject(i - 1);
                    long bps = s.optLong("bps", 0);
                    left = s.optString("site");
                    JSONArray e = s.optJSONArray("eps");
                    String res = resName(s.optInt("h", 0));
                    right = (res.length() > 0 ? res + "  ·  " : "") + (bps > 0 ? String.format(java.util.Locale.US, "%.1f MB/s  ·  ", bps / 1048576.0) : bps < 0 ? "不通  ·  " : "")
                            + (e == null ? 0 : e.length()) + " 集";
                    if (i - 1 == si) right = (res.length() > 0 ? res + "  ·  " : "") + "正在播放";
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
        int n = sources.length();
        for (int k = 1; k < n; k++) {
            int c = (si + k) % n;
            if (failed.contains(c)) continue;
            JSONArray a = eps(c);
            if (a == null || a.length() == 0) continue;
            int idx = matchEp(a, name, ei);
            if (idx < 0) continue;
            si = c;
            ei = idx;
            showStatus(why + "，自动换到「" + sources.optJSONObject(si).optString("site") + "」", 3500);
            load(pos);
            return;
        }
        showStatus("所有片源都试过了，按 ▲ 手动选源", 0);
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
                if (down && e.getRepeatCount() == 0) { tab = k == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1; fillPanel(); }
                return true;
            }
            return super.dispatchKeyEvent(e);
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
        if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_INFO) {
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
        h.removeCallbacksAndMessages(null);
        if (panel != null) panel.animate().cancel();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
