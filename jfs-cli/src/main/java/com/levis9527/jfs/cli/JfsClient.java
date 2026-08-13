package com.levis9527.jfs.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** HTTP client for the jfs proxy API. */
public final class JfsClient {
    private final String base;
    private final String token;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JfsClient(String base, String token) {
        this.base = trimSlash(base);
        this.token = token == null ? "" : token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public JsonObject ping() throws IOException, InterruptedException {
        return json("GET", "/ping", null);
    }

    public JsonObject auth() throws IOException, InterruptedException {
        return json("GET", "/auth", null);
    }

    public JsonObject overview() throws IOException, InterruptedException {
        return json("GET", "/overview", null);
    }

    public JsonObject stats() throws IOException, InterruptedException {
        return json("GET", "/stats", null);
    }

    public JsonObject buckets() throws IOException, InterruptedException {
        return json("GET", "/buckets", null);
    }

    public JsonObject list(String bucket, String query) throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        if (notBlank(bucket)) {
            q.put("bucket", bucket);
        }
        if (notBlank(query)) {
            q.put("q", query);
        }
        return json("GET", "/list" + qs(q), null);
    }

    public JsonObject upload(String bucket, String filename, String mime, boolean auth, byte[] body)
            throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("bucket", bucket);
        q.put("filename", filename);
        if (notBlank(mime)) {
            q.put("mime", mime);
        }
        q.put("auth", auth ? "1" : "0");
        return json("POST", "/upload" + qs(q), body);
    }

    public byte[] get(String bucket, String filename) throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("bucket", bucket);
        q.put("filename", filename);
        HttpResponse<byte[]> res = sendBytes("GET", "/get" + qs(q));
        String type = res.headers().firstValue("Content-Type").orElse("");
        if (type.contains("application/json")) {
            throw fromJsonError(new String(res.body(), StandardCharsets.UTF_8), res.statusCode());
        }
        if (res.statusCode() == 401) {
            throw new CliException("unauthorized");
        }
        if (res.statusCode() >= 400) {
            throw new CliException("HTTP " + res.statusCode());
        }
        return res.body();
    }

    public JsonObject getMeta(String bucket, String filename) throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("bucket", bucket);
        q.put("filename", filename);
        q.put("meta", "1");
        return json("GET", "/get" + qs(q), null);
    }

    public JsonObject delete(String bucket, String filename) throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("bucket", bucket);
        q.put("filename", filename);
        return json("POST", "/del" + qs(q), new byte[0]);
    }

    public JsonObject updateMeta(String bucket, String filename, String rename, String mime, Boolean auth)
            throws IOException, InterruptedException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("bucket", bucket);
        q.put("filename", filename);
        if (notBlank(rename)) {
            q.put("newFilename", rename);
        }
        if (mime != null) {
            q.put("mime", mime);
        }
        if (auth != null) {
            q.put("auth", auth ? "1" : "0");
        }
        return json("POST", "/meta" + qs(q), new byte[0]);
    }

    public String pretty(JsonElement el) {
        return gson.toJson(el);
    }

    private JsonObject json(String method, String path, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        applyAuth(b);
        if ("GET".equals(method)) {
            b.GET();
        } else if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject root;
        try {
            root = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new CliException("bad response HTTP " + res.statusCode() + ": " + res.body());
        }
        int ret = root.has("ret") ? root.get("ret").getAsInt() : res.statusCode();
        if (ret != 1) {
            String msg = root.has("msg") && !root.get("msg").isJsonNull() ? root.get("msg").getAsString() : "error";
            throw new CliException(msg + " (ret=" + ret + ")");
        }
        return root;
    }

    private HttpResponse<byte[]> sendBytes(String method, String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        applyAuth(b);
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void applyAuth(HttpRequest.Builder b) {
        if (!token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
    }

    private static CliException fromJsonError(String body, int status) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String msg = root.has("msg") && !root.get("msg").isJsonNull() ? root.get("msg").getAsString() : "error";
            return new CliException(msg + " (HTTP " + status + ")");
        } catch (Exception e) {
            return new CliException("HTTP " + status);
        }
    }

    private static String qs(Map<String, String> q) {
        if (q.isEmpty()) {
            return "";
        }
        StringJoiner j = new StringJoiner("&", "?", "");
        q.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return j.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String base) {
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
