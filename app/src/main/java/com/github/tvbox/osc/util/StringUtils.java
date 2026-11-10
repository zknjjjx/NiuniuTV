package com.github.tvbox.osc.util;

public class StringUtils {
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static boolean isNotEmpty(CharSequence s) { return !isEmpty(s); }
}
