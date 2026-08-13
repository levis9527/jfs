package com.levis9527.jfs.proxy;

import com.levis9527.jfs.directory.Directory;
import com.levis9527.jfs.directory.DirectoryException;
import com.levis9527.jfs.meta.MetaTypes;
import com.levis9527.jfs.meta.MetaTypes.FileMeta;
import com.levis9527.jfs.store.Store;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Public HTTP API (bfs proxy) plus the {@code /admin} web console.
 */
public final class ProxyServer {
    private final Directory directory;
    private final Store store;
    private final AccessControl access;
    private HttpServer server;

    public ProxyServer(Directory directory, Store store) {
        this(directory, store, null);
    }

    public ProxyServer(Directory directory, Store store, String token) {
        this.directory = directory;
        this.store = store;
        this.access = new AccessControl(token);
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/ping", this::ping);
        server.createContext("/auth", this::authInfo);
        server.createContext("/stats", this::stats);
        server.createContext("/overview", this::overview);
        server.createContext("/buckets", this::buckets);
        server.createContext("/upload", this::upload);
        server.createContext("/get", this::get);
        server.createContext("/del", this::del);
        server.createContext("/list", this::list);
        server.createContext("/meta", this::meta);
        server.createContext("/admin", AdminUi::handle);
        server.createContext("/", this::restObject);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public int port() {
        if (server == null) {
            throw new IllegalStateException("server not started");
        }
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void ping(HttpExchange ex) throws IOException {
        writeJson(ex, 200, MetaTypes.RET_OK, "pong", null);
    }

    private void authInfo(HttpExchange ex) throws IOException {
        writeJson(ex, 200, MetaTypes.RET_OK, null, Map.of("enabled", access.enabled()));
    }

    private void stats(HttpExchange ex) throws IOException {
        try {
            requireToken(ex, query(ex));
            writeJson(ex, 200, MetaTypes.RET_OK, null, store.states());
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        }
    }

    private void overview(HttpExchange ex) throws IOException {
        try {
            requireToken(ex, query(ex));
            writeJson(ex, 200, MetaTypes.RET_OK, null, directory.overview());
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        }
    }

    private void buckets(HttpExchange ex) throws IOException {
        try {
            requireToken(ex, query(ex));
            writeJson(ex, 200, MetaTypes.RET_OK, null, directory.overview().buckets);
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        }
    }

    private void upload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, MetaTypes.RET_BAD_REQUEST, "POST required", null);
            return;
        }
        Map<String, String> q = query(ex);
        try {
            requireToken(ex, q);
            String bucket = q.getOrDefault("bucket", "");
            String filename = q.getOrDefault("filename", "");
            String mime = q.getOrDefault("mime", "");
            boolean auth = AccessControl.parseFlag(q.get("auth"));
            doUpload(ex, bucket, filename, mime, readBody(ex), auth);
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        }
    }

    private void doUpload(HttpExchange ex, String bucket, String filename, String mime, byte[] data, boolean auth) throws IOException {
        try {
            var res = directory.upload(bucket, filename, mime, data, auth);
            writeJson(ex, 200, MetaTypes.RET_OK, null, res);
        } catch (DirectoryException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                writeJson(ex, 409, MetaTypes.RET_CONFLICT, e.getMessage(), null);
            } else {
                writeJson(ex, 400, MetaTypes.RET_BAD_REQUEST, e.getMessage(), null);
            }
        } catch (Exception e) {
            writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
        }
    }

    private void get(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String bucket = q.getOrDefault("bucket", "");
        String filename = q.getOrDefault("filename", "");
        boolean metaOnly = "1".equals(q.get("meta"));
        boolean download = "1".equals(q.get("download"));
        try {
            FileMeta meta = directory.getMeta(bucket, filename);
            requireFileRead(ex, q, meta);
            if (metaOnly) {
                writeJson(ex, 200, MetaTypes.RET_OK, null, meta);
                return;
            }
            var got = directory.getData(bucket, filename);
            Headers h = ex.getResponseHeaders();
            String mime = got.meta().mime;
            h.set("Content-Type", mime == null || mime.isBlank() ? "application/octet-stream" : mime);
            h.set("X-JFS-Key", String.valueOf(got.meta().key));
            h.set("X-JFS-Vid", String.valueOf(got.meta().vid));
            h.set("X-JFS-Auth", got.meta().auth ? "1" : "0");
            if (download) {
                h.set("Content-Disposition", "attachment; filename=\"" + got.meta().filename + "\"");
            }
            ex.sendResponseHeaders(200, got.data().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(got.data());
            }
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        } catch (DirectoryException e) {
            writeJson(ex, 404, MetaTypes.RET_NOT_FOUND, e.getMessage(), null);
        } catch (Exception e) {
            writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
        }
    }

    private void del(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            writeJson(ex, 405, MetaTypes.RET_BAD_REQUEST, "POST/DELETE required", null);
            return;
        }
        Map<String, String> q = query(ex);
        try {
            requireToken(ex, q);
            directory.delete(q.getOrDefault("bucket", ""), q.getOrDefault("filename", ""));
            writeJson(ex, 200, MetaTypes.RET_OK, "deleted", null);
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        } catch (DirectoryException e) {
            writeJson(ex, 404, MetaTypes.RET_NOT_FOUND, e.getMessage(), null);
        } catch (Exception e) {
            writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
        }
    }

    private void list(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        List<FileMeta> files = directory.list(q.get("bucket"), q.get("q"));
        boolean privileged = access.authorized(ex, q);
        if (access.enabled() && !privileged) {
            files.removeIf(f -> f.auth);
        }
        writeJson(ex, 200, MetaTypes.RET_OK, null, files);
    }

    private void meta(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) && !"PUT".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, MetaTypes.RET_BAD_REQUEST, "POST/PUT required", null);
            return;
        }
        Map<String, String> q = query(ex);
        try {
            requireToken(ex, q);
            String bucket = q.getOrDefault("bucket", "");
            String filename = q.getOrDefault("filename", "");
            String newFilename = q.get("newFilename");
            String mime = q.containsKey("mime") ? q.get("mime") : null;
            Boolean auth = AccessControl.parseOptionalFlag(q.get("auth"));
            var updated = directory.updateMeta(bucket, filename, newFilename, mime, auth);
            writeJson(ex, 200, MetaTypes.RET_OK, null, updated);
        } catch (UnauthorizedException e) {
            unauthorized(ex);
        } catch (DirectoryException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                writeJson(ex, 409, MetaTypes.RET_CONFLICT, e.getMessage(), null);
            } else if (e.getMessage() != null && e.getMessage().contains("not found")) {
                writeJson(ex, 404, MetaTypes.RET_NOT_FOUND, e.getMessage(), null);
            } else {
                writeJson(ex, 400, MetaTypes.RET_BAD_REQUEST, e.getMessage(), null);
            }
        } catch (Exception e) {
            writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
        }
    }

    private void restObject(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex);
        if ("/".equals(path)) {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod()) && wantsHtml(ex)) {
                ex.getResponseHeaders().set("Location", "/admin");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            writeJson(ex, 200, MetaTypes.RET_OK, "jfs ready", Map.of(
                    "admin", "GET /admin",
                    "overview", "GET /overview",
                    "upload", "POST /upload?bucket=&filename=&auth=0|1",
                    "get", "GET /get?bucket=&filename=",
                    "del", "POST /del?bucket=&filename=",
                    "meta", "POST /meta?bucket=&filename=&mime=&newFilename=&auth=0|1",
                    "rest", "PUT|GET|DELETE /{bucket}/{filename}",
                    "auth", access.enabled()
            ));
            return;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            writeJson(ex, 400, MetaTypes.RET_BAD_REQUEST, "use /{bucket}/{filename}", null);
            return;
        }
        String bucket = trimmed.substring(0, slash);
        String filename = trimmed.substring(slash + 1);
        String method = ex.getRequestMethod();
        switch (method) {
            case "PUT", "POST" -> {
                try {
                    requireToken(ex, q);
                    String mime = ex.getRequestHeaders().getFirst("Content-Type");
                    boolean auth = AccessControl.parseFlag(firstNonBlank(q.get("auth"),
                            ex.getRequestHeaders().getFirst("X-JFS-Auth")));
                    doUpload(ex, bucket, filename, mime, readBody(ex), auth);
                } catch (UnauthorizedException e) {
                    unauthorized(ex);
                }
            }
            case "GET" -> {
                try {
                    FileMeta meta = directory.getMeta(bucket, filename);
                    requireFileRead(ex, q, meta);
                    var got = directory.getData(bucket, filename);
                    Headers h = ex.getResponseHeaders();
                    String mime = got.meta().mime;
                    h.set("Content-Type", mime == null || mime.isBlank() ? "application/octet-stream" : mime);
                    h.set("X-JFS-Auth", got.meta().auth ? "1" : "0");
                    ex.sendResponseHeaders(200, got.data().length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(got.data());
                    }
                } catch (UnauthorizedException e) {
                    unauthorized(ex);
                } catch (DirectoryException e) {
                    writeJson(ex, 404, MetaTypes.RET_NOT_FOUND, e.getMessage(), null);
                } catch (Exception e) {
                    writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
                }
            }
            case "DELETE" -> {
                try {
                    requireToken(ex, q);
                    directory.delete(bucket, filename);
                    writeJson(ex, 200, MetaTypes.RET_OK, "deleted", null);
                } catch (UnauthorizedException e) {
                    unauthorized(ex);
                } catch (DirectoryException e) {
                    writeJson(ex, 404, MetaTypes.RET_NOT_FOUND, e.getMessage(), null);
                } catch (Exception e) {
                    writeJson(ex, 500, MetaTypes.RET_INTERNAL, e.getMessage(), null);
                }
            }
            default -> writeJson(ex, 405, MetaTypes.RET_BAD_REQUEST, "method not allowed", null);
        }
    }

    private void requireToken(HttpExchange ex, Map<String, String> query) {
        if (!access.authorized(ex, query)) {
            throw new UnauthorizedException();
        }
    }

    private void requireFileRead(HttpExchange ex, Map<String, String> query, FileMeta meta) {
        if (meta.auth && !access.authorized(ex, query)) {
            throw new UnauthorizedException();
        }
    }

    private void unauthorized(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"jfs\"");
        writeJson(ex, 401, MetaTypes.RET_UNAUTHORIZED, "unauthorized", null);
    }

    private static boolean wantsHtml(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.contains("text/html");
    }

    private void writeJson(HttpExchange ex, int http, int ret, String msg, Object data) throws IOException {
        byte[] body = JsonSupport.GSON.toJson(new MetaTypes.ApiResponse(ret, msg, data)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(http, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                map.put(urlDecode(part), "");
            } else {
                map.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
