package com.niuniu.tv;

import android.content.Context;

/** 手机版与电视版共用一套代码，按包名区分 */
final class Ui {
    static boolean phone(Context c) { return c.getPackageName().endsWith(".m"); }
}
