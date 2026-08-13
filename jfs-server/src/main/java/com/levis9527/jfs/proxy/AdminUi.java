package com.levis9527.jfs.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Serves the web console from classpath {@code /web/*} under {@code /admin}.
 */
final class AdminUi {
    private AdminUi() {
    }

    static void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            byte[] body = "method not allowed".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            return;
        }
        String path = ex.getRequestURI().getPath();
        String rel;
        if ("/admin".equals(path) || "/admin/".equals(path)) {
            rel = "index.html";
        } else {
            rel = path.substring("/admin/".length());
        }
        if (rel.isBlank() || rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            notFound(ex);
            return;
        }
        String resource = "/web/" + rel;
        try (InputStream in = AdminUi.class.getResourceAsStream(resource)) {
            if (in == null) {
                notFound(ex);
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(rel));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static void notFound(HttpExchange ex) throws IOException {
        byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(404, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }
}
