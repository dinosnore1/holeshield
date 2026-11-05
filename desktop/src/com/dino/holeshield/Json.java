package com.dino.holeshield;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON writer/parser used by the desktop dashboard API. The Android build
 * uses org.json; the desktop port intentionally depends only on the JDK.
 */
final class Json {

    private Json() { }

    static final class Obj {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        Obj put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        Obj put(String key, String value) {
            values.put(key, value);
            return this;
        }

        Obj put(String key, long value) {
            values.put(key, value);
            return this;
        }

        Obj put(String key, int value) {
            values.put(key, value);
            return this;
        }

        Obj put(String key, double value) {
            values.put(key, value);
            return this;
        }

        Obj put(String key, boolean value) {
            values.put(key, value);
            return this;
        }

        Obj putNull(String key) {
            values.put(key, null);
            return this;
        }

        @Override
        public String toString() {
            return write(this);
        }
    }

    static final class Arr {
        final List<Object> values = new ArrayList<>();

        Arr add(Object value) {
            values.add(value);
            return this;
        }

        Arr add(String value) {
            values.add(value);
            return this;
        }

        Arr add(long value) {
            values.add(value);
            return this;
        }

        Arr add(int value) {
            values.add(value);
            return this;
        }

        Arr add(boolean value) {
            values.add(value);
            return this;
        }

        int size() {
            return values.size();
        }

        @Override
        public String toString() {
            return write(this);
        }
    }

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append(quote((String) value));
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Obj) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Obj) value).values.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Arr) {
            out.append('[');
            boolean first = true;
            for (Object item : ((Arr) value).values) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey()))).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else {
            out.append(quote(value.toString()));
        }
    }

    /** Parses a JSON object into a plain map. Returns an empty map on error. */
    static Map<String, Object> parseObject(String text) {
        try {
            Object value = new Parser(text).parse();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                return map;
            }
        } catch (Exception ignored) { }
        return new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parse() {
            skip();
            Object value = value();
            skip();
            if (at != text.length()) throw new IllegalArgumentException("trailing data");
            return value;
        }

        private Object value() {
            skip();
            if (at >= text.length()) throw new IllegalArgumentException("eof");
            char c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
            if (c == 'f' && text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
            if (c == 'n' && text.startsWith("null", at)) { at += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            at++;
            skip();
            if (peek() == '}') { at++; return map; }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                Object value = value();
                map.put(key, value);
                skip();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("object");
            }
        }

        private List<Object> array() {
            ArrayList<Object> list = new ArrayList<>();
            at++;
            skip();
            if (peek() == ']') { at++; return list; }
            while (true) {
                list.add(value());
                skip();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("array");
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char e = text.charAt(at++);
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default: throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("string");
        }

        private Number number() {
            int start = at;
            if (peek() == '-') at++;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at < text.length() && text.charAt(at) == '.') {
                at++;
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            }
            String raw = text.substring(start, at);
            if (raw.isEmpty() || "-".equals(raw)) throw new IllegalArgumentException("number");
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try { return Long.parseLong(raw); } catch (Exception ignored) { }
            }
            return Double.parseDouble(raw);
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private char peek() {
            return at < text.length() ? text.charAt(at) : '\0';
        }

        private char next() {
            if (at >= text.length()) throw new IllegalArgumentException("eof");
            return text.charAt(at++);
        }

        private void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("expected " + c);
        }
    }
}
