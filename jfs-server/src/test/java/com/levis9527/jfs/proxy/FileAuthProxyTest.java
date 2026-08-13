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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAuthProxyTest {
    private static final String TOKEN = "test-secret-token";

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
        directory = new Directory(temp.resolve("meta"), store, 11);
        proxy = new ProxyServer(directory, store, TOKEN);
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
    void publicFileReadableWithoutToken() throws Exception {
        put("/pub/open.txt", "plain", false);
        var res = send(HttpRequest.newBuilder(URI.create(base + "/pub/open.txt")).GET().build());
        assertEquals(200, res.statusCode());
        assertEquals("plain", res.body());
    }

    @Test
    void privateFileRequiresToken() throws Exception {
        put("/sec/hidden.txt", "secret", true);

        var denied = send(HttpRequest.newBuilder(URI.create(base + "/sec/hidden.txt")).GET().build());
        assertEquals(401, denied.statusCode());

        var ok = send(HttpRequest.newBuilder(URI.create(base + "/sec/hidden.txt"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build());
        assertEquals(200, ok.statusCode());
        assertEquals("secret", ok.body());

        var queryOk = send(HttpRequest.newBuilder(URI.create(base + "/get?bucket=sec&filename=hidden.txt&token=" + TOKEN))
                .GET().build());
        assertEquals(200, queryOk.statusCode());
        assertEquals("secret", queryOk.body());
    }

    @Test
    void uploadWithoutTokenRejectedWhenAuthEnabled() throws Exception {
        var res = send(HttpRequest.newBuilder(URI.create(base + "/open/x.txt"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("nope"))
                .build());
        assertEquals(401, res.statusCode());
    }

    @Test
    void listHidesPrivateFilesWithoutToken() throws Exception {
        put("/mix/a.txt", "a", false);
        put("/mix/b.txt", "b", true);

        JsonObject anon = JsonParser.parseString(send(HttpRequest.newBuilder(URI.create(base + "/list?bucket=mix")).GET().build()).body()).getAsJsonObject();
        assertEquals(1, anon.getAsJsonArray("data").size());
        assertEquals("a.txt", anon.getAsJsonArray("data").get(0).getAsJsonObject().get("filename").getAsString());
        assertFalse(anon.getAsJsonArray("data").get(0).getAsJsonObject().get("auth").getAsBoolean());

        JsonObject authed = JsonParser.parseString(send(HttpRequest.newBuilder(URI.create(base + "/list?bucket=mix"))
                .header("X-JFS-Token", TOKEN)
                .GET().build()).body()).getAsJsonObject();
        assertEquals(2, authed.getAsJsonArray("data").size());
    }

    @Test
    void authEndpointReportsEnabled() throws Exception {
        JsonObject json = JsonParser.parseString(send(HttpRequest.newBuilder(URI.create(base + "/auth")).GET().build()).body()).getAsJsonObject();
        assertEquals(1, json.get("ret").getAsInt());
        assertTrue(json.getAsJsonObject("data").get("enabled").getAsBoolean());
    }

    private void put(String path, String body, boolean auth) throws Exception {
        var res = send(HttpRequest.newBuilder(URI.create(base + path + (auth ? "?auth=1" : "?auth=0")))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
        assertEquals(200, res.statusCode());
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
