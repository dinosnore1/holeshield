package com.dino.pihole;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A DNS-only VPN: it owns a tun device, receives every DNS query the TV makes,
 * applies the local blocklist, and forwards the rest to Pi-hole.
 */
public class DnsVpnService extends VpnService implements DnsUpstream.Protector {

    static final String ACTION_STATE = "com.dino.pihole.STATE";
    static final String ACTION_STOP = "com.dino.pihole.STOP";
    static final String ACTION_RELOAD = "com.dino.pihole.RELOAD";
    static final String ACTION_RESTART = "com.dino.pihole.RESTART";

    private static final String CHANNEL = "protection";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "PiholeTv";
    private static final String VPN_ADDRESS = "10.111.221.2";
    private static final String DNS_ADDRESS = "10.111.221.1";
    private static final String VPN_ROUTE = "10.111.221.0";
    // A second, IPv6 DNS endpoint. Android prefers IPv6 DNS when the LAN
    // advertises it, so this keeps DNS inside the filter instead of letting
    // an IPv6 resolver bypass the tun.
    private static final String VPN_ADDRESS6 = "fd00:111:221::2";
    private static final String DNS_ADDRESS6 = "fd00:111:221::1";
    private static final String VPN_ROUTE6 = "fd00:111:221::";

    /** Resolvers shipped by Android vendors or hard-coded by apps. */
    private static final String[] FORCED_V4 = {
            "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "64.6.64.6", "64.6.65.6",
            "77.88.8.8", "77.88.8.1", "76.76.2.0", "76.76.10.0",
            "94.140.14.14", "94.140.15.15", "185.228.168.9", "185.228.169.9",
            "156.154.70.1", "156.154.71.1"
    };
    private static final String[] FORCED_V6 = {
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2620:fe::fe", "2620:fe::9",
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
            "2620:119:35::35", "2620:119:53::53"
    };

    static volatile boolean running = false;
    static volatile boolean starting = false;
    static volatile String lastError = "";
    static volatile boolean lanDnsRunning = false;
    static volatile boolean webRunning = false;
    static volatile String lanDnsError = "";
    static volatile String webError = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private FileInputStream input;
    private FileOutputStream output;
    private Thread reader;
    private ThreadPoolExecutor workers;
    private DnsEngine engine;
    private LanDnsServer lanDns;
    private WebServer web;
    private volatile Prefs prefs;
    private volatile boolean stopping;
    private volatile int generation;

    private final WebServer.Host webHost = new WebServer.Host() {
        @Override public void flushCache() {
            DnsEngine e = engine;
            if (e != null) e.flushCache();
        }
        @Override public String lanIp() {
            return NetInfo.lanIpv4(DnsVpnService.this);
        }
        @Override public boolean lanRunning() {
            return lanDns != null && lanDns.running();
        }
        @Override public String lanError() {
            return lanDnsError;
        }
        @Override public void blockingChanged() {
            broadcast();
            updateNotification();
        }
    };

    private final Runnable notificationTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateNotification();
            main.postDelayed(this, 30000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        channel();
        Stats.load(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs = Prefs.load(this);
            prefs.protectionWanted = false;
            prefs.save(this);
            stopVpn();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            prefs = Prefs.load(this);
            if (!running) {
                stopSelf();
                return START_NOT_STICKY;
            }
            updateNotification();
            broadcast();
            return START_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            prefs = Prefs.load(this);
            teardownTunnel();
            startForeground(NOTIFICATION_ID, notification("Restarting protection…"));
            startVpn();
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (running || starting) return START_STICKY;

        prefs = Prefs.load(this);
        if (intent == null && !prefs.protectionWanted) {
            // A sticky restart after the user turned protection off.
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Starting protection…"));
        if (VpnService.prepare(this) != null) {
            fail("VPN permission is missing");
            return START_NOT_STICKY;
        }

        // Parse gravity off the main thread. A 500k-domain list can take
        // several seconds on a TV, and blocking here would trigger the
        // startForegroundService ANR watchdog.
        starting = true;
        final int loadGeneration = generation;
        Thread loader = new Thread(() -> {
            try {
                Blocklist.reload(DnsVpnService.this);
            } catch (Throwable t) {
                main.post(() -> {
                    if (!stopping && loadGeneration == generation) {
                        fail("Could not load blocklists");
                    }
                });
                return;
            }
            main.post(() -> {
                if (!stopping && loadGeneration == generation) startVpn();
            });
        }, "gravity-load");
        loader.setDaemon(true);
        loader.start();
        return START_STICKY;
    }

    private void startVpn() {
        stopping = false;
        starting = true;
        lastError = "";
        final int gen = ++generation;
        try {
            Builder b = new Builder();
            b.setSession("Pi-hole TV");
            b.setMtu(1500);
            b.addAddress(VPN_ADDRESS, 32);
            b.addDnsServer(DNS_ADDRESS);
            b.addRoute(VPN_ROUTE, 24);
            b.addAddress(VPN_ADDRESS6, 128);
            b.addDnsServer(DNS_ADDRESS6);
            b.addRoute(VPN_ROUTE6, 64);
            if (prefs.forceDns) {
                for (String ip : FORCED_V4) addRoute(b, ip, 32);
                for (String ip : FORCED_V6) addRoute(b, ip, 128);
            }
            tun = b.establish();
            if (tun == null) {
                fail("Android refused to start the VPN");
                return;
            }

            input = new FileInputStream(tun.getFileDescriptor());
            output = new FileOutputStream(tun.getFileDescriptor());
            workers = new ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(256),
                    new ThreadPoolExecutor.DiscardPolicy());
            engine = new DnsEngine(this, this, prefs);

            running = true;
            starting = false;
            startServers();
            updateNotification();
            broadcast();
            Log.i(TAG, "protection started (" + engine.upstreamStatus() + ")");

            reader = new Thread(() -> readLoop(gen), "dns-reader");
            reader.setDaemon(true);
            reader.start();
            main.postDelayed(notificationTick, 30000);
        } catch (Exception e) {
            fail(e.getMessage() == null ? "Could not start protection" : e.getMessage());
        }
    }

    private static void addRoute(Builder b, String address, int prefix) {
        try {
            b.addRoute(address, prefix);
        } catch (Exception ignored) { }
    }

    private void readLoop(int gen) {
        FileInputStream in = input;
        if (in == null) return;
        byte[] buffer = new byte[32768];
        while (!stopping) {
            try {
                int n = in.read(buffer);
                if (n <= 0) continue;
                final byte[] packet = Arrays.copyOf(buffer, n);
                if (workers != null) {
                    workers.execute(() -> handle(packet));
                }
            } catch (Exception e) {
                if (!stopping && gen == generation) {
                    Log.w(TAG, "tunnel read loop stopped", e);
                    fail("The VPN tunnel closed");
                }
                return;
            }
        }
    }

    private void handle(byte[] packet) {
        // The tun device delivers complete IP packets. Strip the IP/UDP
        // headers before handing the DNS message to the parser.
        byte[] payload = IpPacket.udpPayload(packet, packet.length);
        if (payload == null) return;

        String client = IpPacket.sourceAddress(packet, packet.length);
        byte[] reply = null;
        DnsEngine e = engine;
        if (e != null) {
            reply = e.resolve(payload, client);
        } else {
            DnsPacket.Query q;
            try {
                q = DnsPacket.parseQuery(payload, payload.length);
            } catch (Exception ex) {
                return;
            }
            if (q == null || q.name.isEmpty()) return;
            reply = DnsPacket.errorReply(q, 2); // SERVFAIL
        }
        if (reply == null) return;

        byte[] out = IpPacket.udpReply(packet, packet.length, reply);
        if (out != null) write(out);
        Stats.maybeSave(this);
    }

    private void write(byte[] packet) {
        FileOutputStream out = output;
        if (out == null) return;
        try {
            synchronized (out) {
                out.write(packet);
            }
        } catch (Exception ignored) { }
    }

    private void startServers() {
        stopServers();
        lanDnsError = "";
        webError = "";
        Prefs p = prefs;
        if (p.lanDns) {
            LanDnsServer server = new LanDnsServer(p.lanDnsPort,
                    (query, client) -> {
                        DnsEngine e = engine;
                        return e == null ? null : e.resolve(query, client);
                    });
            if (server.start()) {
                lanDns = server;
                lanDnsRunning = true;
                if (!server.lastError().isEmpty()) lanDnsError = server.lastError();
            } else {
                lanDnsError = server.lastError();
                lanDnsRunning = false;
            }
        }
        if (p.webEnabled) {
            WebServer server = new WebServer(this, p.webPort, webHost);
            if (server.start()) {
                web = server;
                webRunning = true;
                if (!server.lastError().isEmpty()) webError = server.lastError();
            } else {
                webError = server.lastError();
                webRunning = false;
            }
        }
    }

    private void stopServers() {
        if (lanDns != null) {
            lanDns.stop();
            lanDns = null;
        }
        if (web != null) {
            web.stop();
            web = null;
        }
        lanDnsRunning = false;
        webRunning = false;
    }

    /**
     * Closes the tun and stops the worker threads without asking Android to
     * destroy the service. A restart needs this: the system keeps a VPN
     * service bound, so stopSelf() alone cannot remove it.
     */
    private void teardownTunnel() {
        generation++;
        stopping = true;
        running = false;
        starting = false;
        main.removeCallbacks(notificationTick);
        stopServers();
        Stats.save(this);
        try {
            if (workers != null) workers.shutdownNow();
        } catch (Exception ignored) { }
        workers = null;
        try {
            if (input != null) input.close();
        } catch (Exception ignored) { }
        try {
            if (output != null) output.close();
        } catch (Exception ignored) { }
        input = null;
        output = null;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) { }
        tun = null;
        engine = null;
        lanDnsError = "";
        webError = "";
    }

    private void stopVpn() {
        teardownTunnel();
        stopForeground(true);
        stopSelf();
        broadcast();
    }

    private void fail(String message) {
        lastError = message;
        teardownTunnel();
        stopForeground(true);
        stopSelf();
        broadcast();
    }

    @Override public void onRevoke() {
        // The user turned the VPN off in system settings (or another VPN took
        // over). Do not fight them: clear the auto-start intent as well.
        Prefs p = Prefs.load(this);
        p.protectionWanted = false;
        p.save(this);
        stopVpn();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        teardownTunnel();
        stopForeground(true);
        broadcast();
        super.onDestroy();
    }

    private void broadcast() {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void channel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "DNS protection", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows whether Pi-hole TV is filtering this TV's DNS.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Pi-hole TV")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification() {
        if (!running) return;
        Prefs p = prefs == null ? Prefs.load(this) : prefs;
        String blocked = Stats.blocked() + " blocked today";
        String text;
        if (!p.blockingActive()) {
            text = "Blocking paused · " + blocked;
        } else if (p.webEnabled) {
            String ip = NetInfo.lanIpv4(this);
            text = "Pi-hole on · " + blocked
                    + (ip.isEmpty() ? "" : " · " + ip + ":" + p.webPort);
        } else {
            text = "Pi-hole on · " + blocked;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification(text));
    }
}
