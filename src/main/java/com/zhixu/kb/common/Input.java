package com.zhixu.kb.common;

import java.util.Map;
import java.util.Set;

/** Helpers for reading loosely typed JSON request bodies at the API boundary. */
public final class Input {
    private Input() {}

    public static String text(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        String s = value == null ? "" : String.valueOf(value);
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static String required(Map<String, Object> body, String key, int max, String message) {
        String s = text(body, key, max).trim();
        if (s.isEmpty()) throw ApiException.badRequest(message);
        return s;
    }

    public static String oneOf(Map<String, Object> body, String key, String fallback, Set<String> allowed) {
        Object value = body.get(key);
        String s = value == null ? fallback : String.valueOf(value);
        if (!allowed.contains(s)) throw ApiException.badRequest("参数 " + key + " 无效");
        return s;
    }

    public static Integer integerOrNull(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("参数 " + key + " 必须是数字");
        }
    }

    /**
     * Escapes LIKE wildcards; use together with {@link #ESCAPE}. "!" is used instead of a
     * backslash because MySQL also treats backslashes as string-literal escapes.
     */
    public static String like(String q) {
        return "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static final String ESCAPE = " ESCAPE '!'";
}
