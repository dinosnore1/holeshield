package com.dino.holeshield;

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
        String wifi = "";
        String ethernet = "";
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
                    boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    boolean isEthernet = caps.hasTransport(
                            NetworkCapabilities.TRANSPORT_ETHERNET);
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()
                                && !a.isLinkLocalAddress()) {
                            String ip = a.getHostAddress();
                            if (a.isSiteLocalAddress()) {
                                if (isWifi && wifi.isEmpty()) wifi = ip;
                                else if (isEthernet && ethernet.isEmpty()) ethernet = ip;
                                else if (fallback.isEmpty()) fallback = ip;
                            } else if (fallback.isEmpty()) {
                                fallback = ip;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        if (!wifi.isEmpty()) return wifi;
        if (!ethernet.isEmpty()) return ethernet;
        if (!fallback.isEmpty()) return fallback;

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback() || isVpnInterface(ni.getName())) continue;
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

    private static boolean isVpnInterface(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp")
                || n.startsWith("rmnet") || n.startsWith("ip6tnl")
                || n.startsWith("v4-") || n.startsWith("dummy");
    }

    static String dashboardUrl(Context c, Prefs p) {
        if (!p.webEnabled) return "";
        String ip = lanIpv4(c);
        if (ip.isEmpty()) ip = "DEVICE-IP";
        return "http://" + ip + ":" + p.webPort + "/admin/";
    }

    static String lanDnsUrl(Context c, Prefs p) {
        if (!p.lanDns) return "";
        String ip = lanIpv4(c);
        if (ip.isEmpty()) ip = "DEVICE-IP";
        return ip + ":" + p.lanDnsPort;
    }
}
