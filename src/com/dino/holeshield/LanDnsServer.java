package com.dino.holeshield;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A small DNS listener for other devices on the LAN. Android cannot bind port
 * 53 without root, so this uses a high port (5354 by default) over UDP and TCP.
 */
final class LanDnsServer {

    interface Handler {
        byte[] resolve(byte[] query, String client);
    }

    private static final String TAG = "PiholeTv";

    private final int port;
    private final Handler handler;
    private volatile boolean running;
    private volatile String lastError = "";
    private DatagramSocket udp;
    private ServerSocket tcp;
    private Thread udpThread;
    private Thread tcpThread;
    private ThreadPoolExecutor pool;

    LanDnsServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    boolean start() {
        if (running) return true;
        if (port < 1 || port > 65535) {
            lastError = "Invalid LAN DNS port";
            return false;
        }
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress("0.0.0.0", port));
            udp.setSoTimeout(1000);
        } catch (Exception e) {
            lastError = "UDP " + port + ": " + message(e);
            closeQuietly();
            return false;
        }
        try {
            tcp = new ServerSocket();
            tcp.setReuseAddress(true);
            tcp.bind(new InetSocketAddress("0.0.0.0", port));
            tcp.setSoTimeout(1000);
        } catch (Exception e) {
            // UDP is still useful; keep serving it.
            tcp = null;
            lastError = "UDP only; TCP " + port + " is unavailable";
        }

        pool = new ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(128),
                new ThreadPoolExecutor.DiscardPolicy());
        running = true;

        udpThread = new Thread(this::udpLoop, "lan-dns-udp");
        udpThread.setDaemon(true);
        udpThread.start();

        if (tcp != null) {
            tcpThread = new Thread(this::tcpLoop, "lan-dns-tcp");
            tcpThread.setDaemon(true);
            tcpThread.start();
        }
        Log.i(TAG, "LAN DNS listening on 0.0.0.0:" + port);
        return true;
    }

    private void udpLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                udp.receive(in);
                if (in.getLength() < 12) continue;
                final byte[] query = new byte[in.getLength()];
                System.arraycopy(buf, 0, query, 0, in.getLength());
                final InetSocketAddress from =
                        new InetSocketAddress(in.getAddress(), in.getPort());
                if (pool != null) {
                    pool.execute(() -> {
                        byte[] reply = handler.resolve(query,
                                from.getAddress().getHostAddress());
                        if (reply == null) return;
                        try {
                            udp.send(new DatagramPacket(reply, reply.length, from));
                        } catch (Exception ignored) { }
                    });
                }
            } catch (Exception e) {
                if (!running) return;
                // A receive timeout is normal and just lets us check running.
            }
        }
    }

    private void tcpLoop() {
        while (running) {
            try {
                final Socket socket = tcp.accept();
                if (pool != null) pool.execute(() -> serveTcp(socket));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void serveTcp(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            String client = socket.getInetAddress().getHostAddress();
            for (int i = 0; i < 16 && running; i++) {
                int len;
                try {
                    len = in.readUnsignedShort();
                } catch (Exception eof) {
                    break;
                }
                if (len < 12 || len > 8192) break;
                byte[] query = new byte[len];
                in.readFully(query);
                byte[] reply = handler.resolve(query, client);
                if (reply == null) break;
                out.writeShort(reply.length);
                out.write(reply);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    void stop() {
        running = false;
        if (pool != null) {
            try { pool.shutdownNow(); } catch (Exception ignored) { }
            pool = null;
        }
        try { if (udp != null) udp.close(); } catch (Exception ignored) { }
        try { if (tcp != null) tcp.close(); } catch (Exception ignored) { }
        udp = null;
        tcp = null;
        udpThread = null;
        tcpThread = null;
    }

    private void closeQuietly() {
        try { if (udp != null) udp.close(); } catch (Exception ignored) { }
        udp = null;
        running = false;
    }

    String lastError() { return lastError; }
    int port() { return port; }
    boolean running() { return running; }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
