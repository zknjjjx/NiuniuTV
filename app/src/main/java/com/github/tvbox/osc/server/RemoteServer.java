package com.github.tvbox.osc.server;

import android.content.Context;

/** 爬虫本地代理端口（com.niuniu.tv.BoxServer 监听在 127.0.0.1） */
public class RemoteServer {
    public static int serverPort = 9966;
    public static String getLocalIPAddress(Context c) { return "127.0.0.1"; }
}
