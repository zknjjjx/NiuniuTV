package com.github.tvbox.osc.util;

import java.net.ProxySelector;

import okhttp3.Authenticator;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

/** 原 TVBox 的网络配置：这里不设代理、不用 DoH，交给 com.github.catvod.net.OkHttp 自建客户端 */
public class OkGoHelper {
    public static Dns dnsOverHttps = null;
    public static OkHttpClient getDefaultClient() { return com.github.catvod.net.OkHttp.client(); }
    public static OkHttpClient getNoRedirectClient() { return null; }
    public static ProxySelector proxySelector() { return ProxySelector.getDefault(); }
    public static Authenticator proxyAuthenticator() { return Authenticator.NONE; }
}
