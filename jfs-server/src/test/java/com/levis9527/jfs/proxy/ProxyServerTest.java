package com.levis9527.jfs.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.levis9527.jfs.directory.Directory;
import com.levis9527.jfs.store.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyServerTest {
    @TempDir
    Path temp;

    private Store store;
    private Directory directory;
    private ProxyServer proxy;
    private HttpClient http;
    private String base;

    @BeforeEach
    void start() throws Exception {
        store = new Store(temp.resolve("store"), 1, 1 << 20);
        directory = new Directory(temp.resolve("meta"), store, 9);
        proxy = new ProxyServer(directory, store);
        proxy.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + proxy.port();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        if (directory != null) {
            directory.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void adminPageAndAssets() throws Exception {
        var html = get("/admin");
        assertEquals(200, html.statusCode());
        assertTrue(html.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(html.body().contains("jfs 控制台"));
        assertTrue(html.body().contains("/admin/app.js"));

        var js = get("/admin/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("function loadAll"));

        var css = get("/admin/app.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("--accent"));
    }

    @Test
    void htmlRootRedirectsToAdmin() throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + "/"))
                .header("Accept", "text/html")
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.discarding());
        assertEquals(302, res.statusCode());
        assertEquals("/admin", res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void uploadListOverviewAndMetaViaHttp() throws Exception {
        var put = HttpRequest.newBuilder(URI.create(base + "/docs/readme.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("hello-admin"))
                .build();
        var uploaded = http.send(put, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploaded.statusCode());
        JsonObject up = JsonParser.parseString(uploaded.body()).getAsJsonObject();
        assertEquals(1, up.get("ret").getAsInt());
        assertTrue(up.getAsJsonObject("data").get("key").isJsonPrimitive());
        assertTrue(up.getAsJsonObject("data").get("key").getAsJsonPrimitive().isString());

        JsonObject list = JsonParser.parseString(get("/list?bucket=docs").body()).getAsJsonObject();
        assertEquals(1, list.getAsJsonArray("data").size());
        assertEquals("readme.txt", list.getAsJsonArray("data").get(0).getAsJsonObject().get("filename").getAsString());

        JsonObject ov = JsonParser.parseString(get("/overview").body()).getAsJsonObject();
        assertEquals(1, ov.getAsJsonObject("data").get("fileCount").getAsInt());

        var metaReq = HttpRequest.newBuilder(URI.create(base + "/meta?bucket=docs&filename=readme.txt&newFilename=hello.txt&mime=text/markdown"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var metaRes = http.send(metaReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metaRes.statusCode());
        JsonObject meta = JsonParser.parseString(metaRes.body()).getAsJsonObject();
        assertEquals("hello.txt", meta.getAsJsonObject("data").get("filename").getAsString());

        var file = http.send(
                HttpRequest.newBuilder(URI.create(base + "/get?bucket=docs&filename=hello.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("hello-admin", file.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
