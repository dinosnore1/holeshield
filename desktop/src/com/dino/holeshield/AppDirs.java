package com.dino.holeshield;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the per-user data directory and enforces a single running copy. */
final class AppDirs {

    private static File dataDir;
    private static FileLock lock;
    private static RandomAccessFile lockFile;

    private AppDirs() { }

    static synchronized File dataDir() {
        if (dataDir != null) return dataDir;
        String override = System.getenv("HOLESH_DESKTOP_HOME");
        if (override != null && !override.trim().isEmpty()) {
            dataDir = new File(override.trim());
            dataDir.mkdirs();
            return dataDir;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = userHome();
        File base;
        if (os.contains("mac")) {
            base = new File(home, "Library/Application Support/Hole Shield");
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData == null || appData.isEmpty()
                    ? new File(home, "AppData/Roaming/Hole Shield")
                    : new File(appData, "Hole Shield");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            base = xdg == null || xdg.isEmpty()
                    ? new File(home, ".config/holeshield")
                    : new File(xdg, "holeshield");
        }
        base.mkdirs();
        dataDir = base;
        return dataDir;
    }

    static synchronized void setDataDir(File dir) {
        if (dir == null) return;
        dir.mkdirs();
        dataDir = dir;
    }

    private static String userHome() {
        String home = System.getProperty("user.home", ".");
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser != null && !sudoUser.isEmpty() && !"root".equals(sudoUser)) {
            String os = System.getProperty("os.name", "").toLowerCase();
            File candidate = os.contains("mac")
                    ? new File("/Users", sudoUser)
                    : new File("/home", sudoUser);
            if (candidate.isDirectory()) return candidate.getAbsolutePath();
        }
        return home;
    }

    static File file(String name) {
        return new File(dataDir(), name);
    }

    /**
     * Returns true when this process owns the instance lock. When another copy
     * is already running the caller can open the dashboard and exit.
     */
    static synchronized boolean acquireLock() {
        if (lock != null) return true;
        try {
            Path path = file(".instance.lock").toPath();
            lockFile = new RandomAccessFile(path.toFile(), "rw");
            FileChannel channel = lockFile.getChannel();
            lock = channel.tryLock();
            return lock != null;
        } catch (Exception e) {
            return true;
        }
    }

    static synchronized void releaseLock() {
        try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        try { if (lockFile != null) lockFile.close(); } catch (Exception ignored) { }
        lock = null;
        lockFile = null;
    }

    static void open(File file) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", file.getAbsolutePath()).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("explorer", file.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
            }
        } catch (Exception ignored) { }
    }

    static void browse(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ignored) { }
    }

    static String lanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                java.net.NetworkInterface nic = all.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    static String hostName() {
        String name = System.getenv("HOSTNAME");
        if (name == null || name.isEmpty()) name = System.getProperty("user.name", "computer");
        return name;
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    static void ensureDir(File dir) {
        try { Files.createDirectories(dir.toPath()); } catch (Exception ignored) { }
    }
}
