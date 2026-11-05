package com.dino.holeshield;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Web dashboard credential checks. Kept free of Android imports so the desktop
 * checks can exercise it.
 *
 * A password is the switch: an empty password leaves the dashboard open, and
 * an optional username is only enforced when both a username and a password
 * are configured. New passwords are stored as a PBKDF2 hash; a legacy
 * plaintext value is still accepted so upgrades keep working.
 */
final class WebAuth {

    private static final int ITERATIONS = 120000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "pbkdf2";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebAuth() { }

    static boolean passwordRequired(String storedPassword) {
        return storedPassword != null && !storedPassword.isEmpty();
    }

    static boolean usernameRequired(String username) {
        return username != null && !username.isEmpty();
    }

    /** True when the stored value is a PBKDF2 record rather than plaintext. */
    static boolean isHashed(String storedPassword) {
        return storedPassword != null && storedPassword.startsWith(PREFIX + "$");
    }

    /** Creates a salted PBKDF2 record. Empty input means "no password". */
    static String hash(String password) {
        if (password == null || password.isEmpty()) return "";
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * Verifies a supplied password against a stored hash or a legacy plaintext
     * value. An empty stored value means no login is configured.
     */
    static boolean verify(String storedPassword, String suppliedPassword) {
        if (!passwordRequired(storedPassword)) return true;
        if (suppliedPassword == null) return false;
        if (!isHashed(storedPassword)) {
            return constantTimeEquals(storedPassword, suppliedPassword);
        }
        try {
            String[] parts = storedPassword.split("\\$", -1);
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1000 || iterations > 1000000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length < 8 || expected.length < 16) return false;
            byte[] actual = pbkdf2(suppliedPassword, salt, iterations,
                    expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean valid(String configuredUsername, String configuredPassword,
                         String suppliedUsername, String suppliedPassword) {
        if (!passwordRequired(configuredPassword)) return true;
        if (!verify(configuredPassword, suppliedPassword)) return false;
        if (!usernameRequired(configuredUsername)) return true;
        return constantTimeEquals(configuredUsername, suppliedUsername);
    }

    /**
     * Validates the legacy {@code auth=...} query parameter.
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
        return constantTimeEquals(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null) return false;
        int diff = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            diff |= l ^ r;
        }
        return diff == 0;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations,
                                 int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                    iterations, keyBits);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // Every Android 8+ device ships this algorithm. Fall back to the
            // older SHA-1 variant only if a vendor image is missing it.
            try {
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                        iterations, keyBits);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                        .generateSecret(spec).getEncoded();
            } catch (Exception ignored) {
                return new byte[0];
            }
        }
    }
}
