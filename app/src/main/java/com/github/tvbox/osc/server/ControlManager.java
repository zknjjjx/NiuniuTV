package com.github.tvbox.osc.server;

public class ControlManager {
    private static final ControlManager I = new ControlManager();
    public static ControlManager get() { return I; }
    public String getAddress(boolean local) { return "http://127.0.0.1:" + RemoteServer.serverPort + "/"; }
}
