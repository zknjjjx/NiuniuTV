package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** 爬虫宿主要的 Application：jar/JS/Python 爬虫通过 App.getInstance() 拿 Context */
public class App extends Application {
    private static App instance;
    private Activity cur;
    private final Set<Activity> live = java.util.Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private final Handler h = new Handler(Looper.getMainLooper());
    private int resumed;

    public static App getInstance() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        // 爬虫在后台线程抛的异常不拖垮 App；主线程崩溃先把原因记下来，下次打开时提示
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                Log.e("App", "uncaught in " + t.getName(), e);
                if (Looper.getMainLooper().getThread() != t) return;   // 后台线程：吞掉，线程结束，App 继续
                try {
                    StackTraceElement[] st = e.getStackTrace();
                    String at = st.length > 0 ? st[0].getClassName() + "." + st[0].getMethodName() : "";
                    java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "crash.txt"));
                    o.write((e.getClass().getSimpleName() + " " + at).getBytes("UTF-8")); o.close();
                } catch (Throwable ignore) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { cur = a; live.add(a); }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { cur = a; live.add(a); if (resumed++ == 0) h.post(guard); }
            public void onActivityPaused(Activity a) { if (--resumed <= 0) { resumed = 0; h.removeCallbacks(guard); } }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (cur == a) cur = null; live.remove(a); }
        });
    }

    public Activity getCurrentActivity() { return cur; }

    private static String readSmall(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try { byte[] b = new byte[(int) Math.min(f.length(), 4096)]; int n = in.read(b); return n > 0 ? new String(b, 0, n, "UTF-8") : ""; } finally { in.close(); }
    }

    /** 上次是不是意外退出，是的话返回一句原因（只取一次） */
    public String lastExit() {
        String why = null;
        try {
            java.io.File f = new java.io.File(getFilesDir(), "crash.txt");
            if (f.exists()) { why = "程序出错 " + readSmall(f); f.delete(); }
        } catch (Throwable ignore) {}
        if (why == null && android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(null, 0, 1);
                if (!l.isEmpty()) {
                    android.app.ApplicationExitInfo x = l.get(0);
                    android.content.SharedPreferences sp = getSharedPreferences("exit", 0);
                    if (sp.getLong("t", 0) != x.getTimestamp()) {
                        sp.edit().putLong("t", x.getTimestamp()).apply();
                        switch (x.getReason()) {
                            case android.app.ApplicationExitInfo.REASON_CRASH: why = "程序出错"; break;
                            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: why = "爬虫底层崩溃（JS/Python/加壳 jar）"; break;
                            case android.app.ApplicationExitInfo.REASON_ANR: why = "卡死无响应"; break;
                            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: why = "内存不够被系统关掉"; break;
                            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: why = "被某个爬虫主动关掉"; break;
                            case android.app.ApplicationExitInfo.REASON_SIGNALED: why = "被系统强制关掉"; break;
                            default: break;
                        }
                        if (why != null && x.getDescription() != null) why += "：" + x.getDescription();
                    }
                }
            } catch (Throwable ignore) {}
        }
        return why;
    }

    /**
     * 爬虫自己弹的窗（网盘扫码登录、公告、广告等）一律关掉：
     * 本 App 只有 Activity 本身的窗口和系统 Toast，其余窗口都是爬虫加的，发现就移除。
     */
    private final Runnable guard = new Runnable() { public void run() {
        try { sweep(); } catch (Throwable e) { Log.w("App", "guard " + e); h.removeCallbacks(this); return; }
        h.postDelayed(this, 400);
    } };

    @SuppressWarnings("unchecked")
    private void sweep() throws Exception {
        Class<?> g = Class.forName("android.view.WindowManagerGlobal");
        Object wmg = g.getMethod("getInstance").invoke(null);
        Field fv = g.getDeclaredField("mViews"); fv.setAccessible(true);
        Field fp = g.getDeclaredField("mParams"); fp.setAccessible(true);
        List<View> views; List<WindowManager.LayoutParams> ps;
        synchronized (g) { views = new ArrayList<>((List<View>) fv.get(wmg)); ps = new ArrayList<>((List<WindowManager.LayoutParams>) fp.get(wmg)); }
        List<View> mine = new ArrayList<>();
        for (Activity a : live) if (a.getWindow() != null) mine.add(a.getWindow().getDecorView());
        Activity a = cur;
        if (a == null) return;
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            WindowManager.LayoutParams p = i < ps.size() ? ps.get(i) : null;
            if (mine.contains(v)) continue;
            if (p != null && (p.type == WindowManager.LayoutParams.TYPE_TOAST || p.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)) continue;
            if (v.getClass().getName().contains("Toast")) continue;
            try { a.getWindowManager().removeViewImmediate(v); Log.i("App", "closed spider window " + v.getClass().getName()); } catch (Throwable e) { Log.w("App", "close " + e); }
        }
    }
}
