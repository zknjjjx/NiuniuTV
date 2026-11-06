package com.niuniu.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 观看记录：key = 豆瓣类型+id（无豆瓣 id 时用片名） */
public class Hist {
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("hist", Context.MODE_PRIVATE); }

    public static synchronized JSONArray all(Context c) {
        try { return new JSONArray(sp(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static synchronized void put(Context c, JSONObject item) {
        try {
            JSONArray old = all(c), out = new JSONArray();
            out.put(item);
            String key = item.optString("key");
            for (int i = 0; i < old.length() && out.length() < 60; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !key.equals(o.optString("key"))) out.put(o);
            }
            sp(c).edit().putString("list", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized JSONObject get(Context c, String key) {
        JSONArray a = all(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && key.equals(o.optString("key"))) return o;
        }
        return null;
    }

    public static synchronized void clear(Context c) { sp(c).edit().putString("list", "[]").apply(); }

    public static synchronized void remove(Context c, String key) {
        JSONArray old = all(c), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) out.put(o);
        }
        sp(c).edit().putString("list", out.toString()).apply();
    }
}
