package com.orhanobut.hawk;

import android.content.SharedPreferences;

import com.github.tvbox.osc.base.App;

/** JS 爬虫 local.get/set 用的小存储 */
public class Hawk {
    private static SharedPreferences sp() { return App.getInstance().getSharedPreferences("box_local", 0); }
    @SuppressWarnings("unchecked")
    public static <T> T get(String k, T d) {
        try { if (d instanceof String || d == null) return (T) sp().getString(k, (String) d); } catch (Exception ignored) {}
        return d;
    }
    public static <T> void put(String k, T v) { sp().edit().putString(k, String.valueOf(v)).apply(); }
    public static void delete(String k) { sp().edit().remove(k).apply(); }
}
