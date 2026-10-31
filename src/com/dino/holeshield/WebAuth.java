package com.dino.holeshield;

import java.nio.charset.StandardCharsets;

/**
 * Web dashboard credential checks. Kept free of Android imports so the desktop
 * checks can exercise it.
 *
 * A password is the switch: an empty password leaves the dashboard open, and
 * an optional username is only enforced when both a username and a password
 * are configured.
 */
final class WebAuth {

    private WebAuth() { }

    static boolean passwordRequired(String password) {
        return password != null && !password.isEmpty();
    }

    static boolean usernameRequired(String username) {
        return username != null && !username.isEmpty();
    }

    static boolean valid(String configuredUsername, String configuredPassword,
                         String suppliedUsername, String suppliedPassword) {
        if (!passwordRequired(configuredPassword)) return true;
        if (!constantTimeEquals(configuredPassword, suppliedPassword)) return false;
        if (!usernameRequired(configuredUsername)) return true;
        return constantTimeEquals(configuredUsername, suppliedUsername);
    }

    /**
     * Validates the Pi-hole-style {@code auth=...} query parameter.
     * With a username configured, the value is {@code username:password};
     * otherwise it is the password alone.
     */
    static boolean validAuthParam(String configuredUsername, String configuredPassword,
                                  String auth) {
        if (!passwordRequired(configuredPassword)) return true;
        if (auth == null || auth.isEmpty()) return false;
        String username = "";
        String password = auth;
        if (usernameRequired(configuredUsername)) {
            int colon = auth.indexOf(':');
            if (colon < 0) return false;
            username = auth.substring(0, colon);
            password = auth.substring(colon + 1);
        }
        return valid(configuredUsername, configuredPassword, username, password);
    }

    /** Length-safe comparison for passwords and usernames. */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            diff |= l ^ r;
        }
        return diff == 0;
    }
}
