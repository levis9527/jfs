package com.levis9527.jfs.cli;

import com.levis9527.jfs.directory.Directory;
import com.levis9527.jfs.proxy.ProxyServer;
import com.levis9527.jfs.store.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfsCliTest {
    @TempDir
    Path temp;

    private Store store;
    private Directory directory;
    private ProxyServer proxy;
    private String url;

    @BeforeEach
    void start() throws Exception {
        store = new Store(temp.resolve("store"), 1, 1 << 20);
        directory = new Directory(temp.resolve("meta"), store, 21);
        proxy = new ProxyServer(directory, store, "cli-token");
        proxy.start("127.0.0.1", 0);
        url = "http://127.0.0.1:" + proxy.port();
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
    void uploadListGetDeleteViaCli() throws Exception {
        Path src = temp.resolve("hello.txt");
        Files.writeString(src, "cli-hello");

        assertEquals(0, run("ping"));
        assertEquals(0, run("upload", src.toString(), "-b", "docs", "-n", "hello.txt"));

        ByteArrayOutputStream list = new ByteArrayOutputStream();
        int listCode = JfsCli.run(new String[]{"--url", url, "--token", "cli-token", "list", "-b", "docs"},
                new PrintStream(list, true, StandardCharsets.UTF_8), System.err);
        assertEquals(0, listCode);
        assertTrue(list.toString(StandardCharsets.UTF_8).contains("hello.txt"));

        Path dest = temp.resolve("out.txt");
        assertEquals(0, run("get", "docs/hello.txt", "-o", dest.toString()));
        assertEquals("cli-hello", Files.readString(dest));

        assertEquals(0, run("meta", "docs/hello.txt", "--auth", "1"));
        assertEquals(0, run("rm", "docs/hello.txt"));
        assertEquals(1, run("get", "docs/hello.txt"));
    }

    private int run(String... args) {
        String[] full = new String[args.length + 4];
        full[0] = "--url";
        full[1] = url;
        full[2] = "--token";
        full[3] = "cli-token";
        System.arraycopy(args, 0, full, 4, args.length);
        return JfsCli.run(full, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }
}
