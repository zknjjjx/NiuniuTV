package com.niuniu.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
    private TextView info, status;
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
    private final Runnable hideStatus = new Runnable() { public void run() { status.setVisibility(View.GONE); } };
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
        view.setControllerShowTimeoutMs(4000);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        info.setShadowLayer(8, 0, 2, Color.BLACK);
        info.setPadding(dp(36), dp(24), dp(36), dp(12));
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6FF5A36, 0xE6FFB547});
        bg.setCornerRadius(dp(22));
        status.setBackground(bg);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(90);
        root.addView(status, sl);
        status.setVisibility(View.GONE);
        setContentView(root);

        view.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            public void onVisibilityChanged(int v) { info.setVisibility(v == View.VISIBLE ? View.VISIBLE : View.GONE); }
        });

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1500, 3000).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(lc).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    h.removeCallbacks(watchdog);
                    if (!started) {
                        started = true;
                        failed.clear();
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

    private void updateInfo() {
        String t = data.optString("title");
        String ep = epName();
        int total = eps(si).length();
        JSONObject s = sources.optJSONObject(si);
        String src = s == null ? "" : s.optString("site");
        info.setText(t + (total > 1 ? "  ·  " + ep : "") + "\n片源：" + src + "（" + (si + 1) + "/" + sources.length() + "）   ▲ 选集换源");
    }

    private void showStatus(String msg, long ms) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideStatus);
        if (ms > 0) h.postDelayed(hideStatus, ms);
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
        showStatus("所有片源都试过了，播放失败。按 ▲ 手动选源", 0);
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

    private void showPicker() {
        final JSONArray a = eps(si);
        String[] opts = new String[]{"选集（共 " + (a == null ? 0 : a.length()) + " 集）", "换片源（共 " + sources.length() + " 个）"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { if (w == 0) pickEpisode(); else pickSource(); }
                }).show();
    }

    private void pickEpisode() {
        final JSONArray a = eps(si);
        if (a == null) return;
        String[] items = new String[a.length()];
        for (int i = 0; i < items.length; i++) items[i] = (i == ei ? "▶ " : "    ") + a.optJSONObject(i).optString("name");
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(data.optString("title"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dd, int w) { save(); ei = w; failed.clear(); load(0); }
                }).create();
        d.show();
        d.getListView().setSelection(Math.max(0, ei - 3));
    }

    private void pickSource() {
        String[] items = new String[sources.length()];
        for (int i = 0; i < items.length; i++) {
            JSONObject s = sources.optJSONObject(i);
            long bps = s.optLong("bps", 0);
            String sp = bps > 0 ? String.format(java.util.Locale.US, "  %.1f MB/s", bps / 1048576.0) : bps < 0 ? "  不通" : "";
            items[i] = (i == si ? "▶ " : "    ") + s.optString("site") + "  ·  " + s.optJSONArray("eps").length() + " 集" + sp;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("换片源（按测速从快到慢）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        long pos = player.getCurrentPosition();
                        String name = epName();
                        int idx = matchEp(eps(w), name, ei);
                        si = w;
                        ei = Math.max(0, idx);
                        failed.clear();
                        load(pos);
                    }
                }).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down) return true;
            if (view.isControllerFullyVisible()) { view.hideController(); return true; }
            save();
            finish();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU || (k == KeyEvent.KEYCODE_DPAD_UP && !view.isControllerFullyVisible())) {
            if (!down) showPicker();
            return true;
        }
        if (!view.isControllerFullyVisible()) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) {
                    long step = e.getRepeatCount() > 6 ? 60000 : e.getRepeatCount() > 2 ? 30000 : 10000;
                    long p = player.getCurrentPosition() + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step);
                    player.seekTo(Math.max(0, p));
                    showStatus((k == KeyEvent.KEYCODE_DPAD_LEFT ? "◀◀ " : "▶▶ ") + fmt(Math.max(0, p)) + " / " + fmt(player.getDuration()), 1500);
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (!down) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    view.showController();
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!down) view.showController();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
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
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
