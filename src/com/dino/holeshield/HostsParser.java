package com.dino.holeshield;

import java.util.Locale;

/**
 * Parses the line formats used by hosts files and DNS blocklists.
 *
 * Supported inputs include plain domains, hosts entries such as
 * {@code 0.0.0.0 ads.example.com}, adblock domain rules such as
 * {@code ||ads.example.com^$third-party}, and wildcard entries such as
 * {@code *.tracker.example.com}. Comments and IP-only lines are ignored.
 *
 * This runs once per downloaded line, so it deliberately avoids regular
 * expressions and unnecessary string allocations.
 */
final class HostsParser {

    private HostsParser() { }

    /** Returns a normalized domain, or "" when the line is not usable. */
    static String parseLine(String raw) {
        if (raw == null) return "";
        String line = raw.trim();
        int len = line.length();
        if (len == 0) return "";

        char first = line.charAt(0);
        if (first == '#' || first == '!' || first == '[') return "";
        if (len >= 2 && first == '/' && line.charAt(1) == '/') return "";

        if (len >= 2 && first == '|' && line.charAt(1) == '|') {
            line = line.substring(2);
            int cut = firstOf(line, '^', '/', '$');
            if (cut >= 0) line = line.substring(0, cut);
            return normalizeDomain(line);
        }

        int firstEnd = 0;
        while (firstEnd < len && !isSpace(line.charAt(firstEnd))) firstEnd++;
        if (firstEnd < len) {
            String token = line.substring(0, firstEnd);
            if (isIpLiteral(token)) {
                int start = firstEnd;
                while (start < len && isSpace(line.charAt(start))) start++;
                int end = start;
                while (end < len && !isSpace(line.charAt(end))) end++;
                line = start < end ? line.substring(start, end) : "";
            } else {
                line = token;
            }
        }
        return normalizeDomain(line);
    }

    /** Normalizes a domain and rejects comments, IPs and malformed names. */
    static String normalizeDomain(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        int start = 0;
        int end = value.length();
        if (end == 0) return "";

        if (end - start >= 2 && value.charAt(start) == '*' && value.charAt(start + 1) == '.') {
            start += 2;
        }
        if (start < end && value.charAt(start) == '.') start++;
        if (end > start && value.charAt(end - 1) == '.') end--;
        if (end <= start) return "";

        boolean dot = false;
        boolean upper = false;
        boolean previousDot = false;
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);
            if (ch == '.') {
                if (previousDot) return "";
                dot = true;
                previousDot = true;
                continue;
            }
            previousDot = false;
            if (ch >= 'A' && ch <= 'Z') {
                upper = true;
                continue;
            }
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_';
            if (!ok) return "";
        }
        if (!dot || end - start > 253 || isIpv4(value, start, end)) return "";

        String line = value.substring(start, end);
        if (upper) line = line.toLowerCase(Locale.US);
        if (line.equals("localhost") || line.startsWith("localhost.")
                || line.equals("local") || line.equals("broadcasthost")) {
            return "";
        }
        return line;
    }

    /** True for IPv4 and IPv6 literals, including IPv6 zone/port suffixes. */
    static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.indexOf(':') >= 0) return true;
        return isIpv4(value, 0, value.length());
    }

    private static boolean isIpv4(String value, int start, int end) {
        int parts = 0;
        int digits = 0;
        int number = 0;
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);
            if (ch == '.') {
                if (digits == 0 || parts >= 3) return false;
                parts++;
                digits = 0;
                number = 0;
                continue;
            }
            if (ch < '0' || ch > '9') return false;
            digits++;
            if (digits > 3) return false;
            number = number * 10 + (ch - '0');
            if (number > 255) return false;
        }
        return parts == 3 && digits > 0;
    }

    private static boolean isSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
    }

    private static int firstOf(String value, char a, char b, char c) {
        int first = value.indexOf(a);
        int second = value.indexOf(b);
        if (second >= 0 && (first < 0 || second < first)) first = second;
        int third = value.indexOf(c);
        if (third >= 0 && (first < 0 || third < first)) first = third;
        return first;
    }
}
