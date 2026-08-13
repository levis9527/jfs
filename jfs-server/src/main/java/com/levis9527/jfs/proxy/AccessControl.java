package com.levis9527.jfs.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Optional bearer token. When unset, per-file {@code auth} flags are stored but not enforced.
 */
final class AccessControl {
    private final String token;

    AccessControl(String token) {
        this.token = (token == null || token.isBlank()) ? null : token;
    }

    boolean enabled() {
        return token != null;
    }

    boolean authorized(HttpExchange ex, Map<String, String> query) {
        if (token == null) {
            return true;
        }
        String provided = extract(ex, query);
        return provided != null && constantEquals(token, provided);
    }

    private static String extract(HttpExchange ex, Map<String, String> query) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String named = ex.getRequestHeaders().getFirst("X-JFS-Token");
        if (named != null && !named.isBlank()) {
            return named.trim();
        }
        if (query != null) {
            String q = query.get("token");
            if (q != null && !q.isBlank()) {
                return q;
            }
        }
        return null;
    }

    private static boolean constantEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    static boolean parseFlag(String raw) {
        if (raw == null) {
            return false;
        }
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw);
    }

    static Boolean parseOptionalFlag(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        return null;
    }
}
