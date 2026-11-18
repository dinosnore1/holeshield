package com.dino.holeshield;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Checksum and version checks for downloaded updates. No Android dependencies. */
final class UpdateVerifier {

    private UpdateVerifier() { }

    static String sha256Hex(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** True only when both values are present and equal, ignoring case. */
    static boolean matches(String expected, String actual) {
        return expected != null && !expected.isEmpty()
                && actual != null && expected.trim().equalsIgnoreCase(actual.trim());
    }

    /** Compares dotted numeric versions such as 2.3.5 and 2.10.0. */
    static int compareVersions(String a, String b) {
        String[] left = (a == null ? "" : a).split("\\.");
        String[] right = (b == null ? "" : b).split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            long x = i < left.length ? numberAt(left[i]) : 0;
            long y = i < right.length ? numberAt(right[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static long numberAt(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Long.parseLong(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
