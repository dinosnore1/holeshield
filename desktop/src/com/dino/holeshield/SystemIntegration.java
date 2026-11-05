package com.dino.holeshield;

import javax.swing.JOptionPane;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OS integration: autostart, DNS setup hints and privileged relaunch. */
final class SystemIntegration {

    private SystemIntegration() { }

    static String launchCommand() {
        String java = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + (AppDirs.isWindows() ? "java.exe" : "java");
        String jar = currentJar();
        if (jar != null && new File(java).isFile()) {
            return quote(java) + " -jar " + quote(jar);
        }
        String command = ProcessHandle.current().info().command().orElse("");
        if (!command.isEmpty()) return quote(command);
        return jar == null ? quote(java) : quote(java) + " -jar " + quote(jar);
    }

    private static String currentJar() {
        try {
            java.security.CodeSource source = SystemIntegration.class
                    .getProtectionDomain().getCodeSource();
            if (source == null) return null;
            File file = new File(source.getLocation().toURI());
            if (file.isFile() && file.getName().endsWith(".jar")) {
                return file.getAbsolutePath();
            }
        } catch (Exception ignored) { }
        return null;
    }

    static void setLaunchAtLogin(boolean enabled) {
        if (AppDirs.isMac()) {
            File file = new File(System.getProperty("user.home"),
                    "Library/LaunchAgents/com.dino.holeshield.desktop.plist");
            if (!enabled) {
                file.delete();
                return;
            }
            file.getParentFile().mkdirs();
            String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                    + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\"><dict>\n"
                    + "  <key>Label</key><string>com.dino.holeshield.desktop</string>\n"
                    + "  <key>ProgramArguments</key><array>\n"
                    + "    <string>/bin/sh</string><string>-c</string>\n"
                    + "    <string>" + xml(launchCommand() + " --minimized") + "</string>\n"
                    + "  </array>\n"
                    + "  <key>RunAtLoad</key><true/>\n"
                    + "  <key>KeepAlive</key><false/>\n"
                    + "</dict></plist>\n";
            write(file, plist);
        } else if (AppDirs.isWindows()) {
            try {
                if (enabled) {
                    new ProcessBuilder("reg", "add",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v", "HoleShield", "/t", "REG_SZ", "/d",
                            launchCommand() + " --minimized", "/f").start().waitFor();
                } else {
                    new ProcessBuilder("reg", "delete",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v", "HoleShield", "/f").start().waitFor();
                }
            } catch (Exception ignored) { }
        } else {
            File file = new File(System.getProperty("user.home"),
                    ".config/autostart/holeshield.desktop");
            if (!enabled) {
                file.delete();
                return;
            }
            file.getParentFile().mkdirs();
            String entry = "[Desktop Entry]\n"
                    + "Type=Application\n"
                    + "Name=Hole Shield\n"
                    + "Comment=Local DNS filtering\n"
                    + "Exec=" + launchCommand() + " --minimized\n"
                    + "Terminal=false\n"
                    + "X-GNOME-Autostart-enabled=true\n";
            write(file, entry);
        }
    }

    private static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static List<String> dnsCommands(int port) {
        ArrayList<String> commands = new ArrayList<>();
        if (AppDirs.isMac()) {
            commands.add("# Point this Mac at Hole Shield (run in Terminal)");
            commands.add("sudo networksetup -setdnsservers \"Wi-Fi\" 127.0.0.1");
            commands.add("# Restore automatic DNS");
            commands.add("sudo networksetup -setdnsservers \"Wi-Fi\" Empty");
        } else if (AppDirs.isWindows()) {
            commands.add("# Point this PC at Hole Shield (admin PowerShell)");
            commands.add("netsh interface ip set dns name=\"Wi-Fi\" static 127.0.0.1");
            commands.add("# Restore automatic DNS");
            commands.add("netsh interface ip set dns name=\"Wi-Fi\" dhcp");
        } else {
            commands.add("# Point this machine at Hole Shield");
            commands.add("sudo resolvectl dns <interface> 127.0.0.1");
            commands.add("sudo resolvectl flush-caches");
            commands.add("# Restore automatic DNS");
            commands.add("sudo resolvectl revert <interface>");
        }
        commands.add("");
        commands.add("# Test the resolver");
        commands.add("dig @127.0.0.1 -p " + port + " example.com");
        return commands;
    }

    static String dnsCommandText(int port) {
        return String.join("\n", dnsCommands(port));
    }

    static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    /**
     * Relaunches the app with administrator rights on macOS/Linux. The caller
     * must stop services and release the instance lock first.
     */
    static boolean relaunchAsAdmin(String extraArgs) {
        try {
            String command = launchCommand() + " --replace " + (extraArgs == null ? "" : extraArgs);
            if (AppDirs.isMac()) {
                String script = "do shell script " + appleScriptQuote(
                        "nohup " + command + " >/tmp/holeshield-desktop.log 2>&1 &")
                        + " with administrator privileges";
                new ProcessBuilder("osascript", "-e", script).start();
                return true;
            }
            if (AppDirs.isLinux()) {
                new ProcessBuilder("pkexec", "sh", "-c",
                        "nohup " + command + " >/tmp/holeshield-desktop.log 2>&1 &").start();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static void showError(java.awt.Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Hole Shield",
                JOptionPane.ERROR_MESSAGE);
    }

    private static String appleScriptQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quote(String value) {
        if (value == null) return "";
        if (value.indexOf(' ') < 0 && value.indexOf('"') < 0) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
