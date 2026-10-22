package com.dino.pihole;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/** Finds the TV's LAN address without relying on a third-party library. */
final class NetInfo {

    private NetInfo() { }

    static String lanIpv4(Context c) {
        String fallback = "";
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp == null) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()
                                && !a.isLinkLocalAddress()) {
                            String ip = a.getHostAddress();
                            if (a.isSiteLocalAddress()) return ip;
                            if (fallback.isEmpty()) fallback = ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        if (!fallback.isEmpty()) return fallback;

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    static String dashboardUrl(Context c, Prefs p) {
        if (!p.webEnabled) return "";
        String ip = lanIpv4(c);
        if (ip.isEmpty()) ip = "TV-IP";
        return "http://" + ip + ":" + p.webPort + "/admin/";
    }

    static String lanDnsUrl(Context c, Prefs p) {
        if (!p.lanDns) return "";
        String ip = lanIpv4(c);
        if (ip.isEmpty()) ip = "TV-IP";
        return ip + ":" + p.lanDnsPort;
    }
}
