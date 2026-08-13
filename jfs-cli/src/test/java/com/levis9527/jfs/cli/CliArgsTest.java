package com.levis9527.jfs.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {
    @Test
    void parsesObjectRefAndAuth() {
        CliArgs a = CliArgs.parse(new String[]{
                "--url", "http://127.0.0.1:9", "--token", "s",
                "put", "photo.jpg", "img/photo.jpg", "--auth"
        });
        assertEquals("http://127.0.0.1:9", a.url);
        assertEquals("s", a.token);
        assertEquals("put", a.command);
        assertEquals("img", a.bucket);
        assertEquals("photo.jpg", a.name);
        assertTrue(a.auth);
        assertEquals("photo.jpg", a.localFile().toString());
    }

    @Test
    void parsesFlags() {
        CliArgs a = CliArgs.parse(new String[]{"list", "-b", "img", "-q", "png", "--json"});
        assertEquals("list", a.command);
        assertEquals("img", a.bucket);
        assertEquals("png", a.query);
        assertTrue(a.json);
        assertNull(a.auth);
    }
}
