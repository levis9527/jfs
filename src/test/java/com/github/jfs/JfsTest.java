package com.github.jfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfsTest {

    @Test
    void putThenGetRoundTrips(@TempDir Path dir) throws IOException {
        try (Jfs store = Jfs.open(dir)) {
            byte[] content = "an image or other".getBytes(StandardCharsets.UTF_8);
            String id = store.put(content);
            assertArrayEquals(content, store.get(id));
            assertTrue(store.contains(id));
            assertEquals(1, store.size());
        }
    }

    @Test
    void getUnknownIdReturnsNull(@TempDir Path dir) throws IOException {
        try (Jfs store = Jfs.open(dir)) {
            assertNull(store.get("does-not-exist"));
            assertFalse(store.contains("does-not-exist"));
        }
    }

    @Test
    void dataPersistsAcrossReopen(@TempDir Path dir) throws IOException {
        String id1;
        String id2;
        try (Jfs store = Jfs.open(dir)) {
            id1 = store.put("first".getBytes(StandardCharsets.UTF_8));
            id2 = store.put("second".getBytes(StandardCharsets.UTF_8));
        }
        try (Jfs store = Jfs.open(dir)) {
            assertEquals(2, store.size());
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), store.get(id1));
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), store.get(id2));
        }
    }

    @Test
    void overwriteWithSameIdKeepsLatestValue(@TempDir Path dir) throws IOException {
        try (Jfs store = Jfs.open(dir)) {
            store.put("k", "v1".getBytes(StandardCharsets.UTF_8));
            store.put("k", "v2".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, store.size());
            assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), store.get("k"));
        }
        try (Jfs store = Jfs.open(dir)) {
            assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), store.get("k"));
        }
    }

    @Test
    void deleteRemovesRecordAndPersists(@TempDir Path dir) throws IOException {
        String id;
        try (Jfs store = Jfs.open(dir)) {
            id = store.put("bye".getBytes(StandardCharsets.UTF_8));
            assertTrue(store.delete(id));
            assertFalse(store.delete(id));
            assertNull(store.get(id));
            assertEquals(0, store.size());
        }
        try (Jfs store = Jfs.open(dir)) {
            assertNull(store.get(id));
            assertEquals(0, store.size());
        }
    }

    @Test
    void listReflectsInsertionOrder(@TempDir Path dir) throws IOException {
        try (Jfs store = Jfs.open(dir)) {
            store.put("a", new byte[] {1});
            store.put("b", new byte[] {2});
            store.put("c", new byte[] {3});
            assertEquals(List.of("a", "b", "c"), store.list());
        }
    }

    @Test
    void handlesManyBinaryBlobs(@TempDir Path dir) throws IOException {
        Random random = new Random(42);
        try (Jfs store = Jfs.open(dir)) {
            for (int i = 0; i < 200; i++) {
                byte[] blob = new byte[random.nextInt(1024)];
                random.nextBytes(blob);
                String id = store.put(blob);
                assertArrayEquals(blob, store.get(id));
            }
            assertEquals(200, store.size());
        }
    }

    @Test
    void rejectsInvalidArguments(@TempDir Path dir) throws IOException {
        try (Jfs store = Jfs.open(dir)) {
            assertThrows(IllegalArgumentException.class, () -> store.put("", new byte[] {1}));
            assertThrows(IllegalArgumentException.class, () -> store.put("id", null));
        }
    }
}
