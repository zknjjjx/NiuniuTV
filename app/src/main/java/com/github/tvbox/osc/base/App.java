package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** 爬虫宿主要的 Application：jar/JS/Python 爬虫通过 App.getInstance() 拿 Context */
public class App extends Application {
    private static App instance;
    private Activity cur;

    public static App getInstance() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { cur = a; }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { cur = a; }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (cur == a) cur = null; }
        });
    }

    public Activity getCurrentActivity() { return cur; }
}
