package com.github.tvbox.osc.util;

public class LOG {
    public static void i(String s) { android.util.Log.i("NNBox", String.valueOf(s)); }
    public static void e(String s) { android.util.Log.e("NNBox", String.valueOf(s)); }
    public static void e(Throwable t) { android.util.Log.e("NNBox", "err", t); }
}
