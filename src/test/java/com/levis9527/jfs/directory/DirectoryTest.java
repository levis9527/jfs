package com.levis9527.jfs.directory;

import com.levis9527.jfs.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryTest {
    @TempDir
    Path temp;

    @Test
    void uploadGetDelete() throws Exception {
        Path root = temp.resolve("root");
        try (Store st = new Store(root.resolve("store"), 2, 1 << 20);
             Directory dir = new Directory(root.resolve("meta"), st, 1)) {
            byte[] data = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
            var up = dir.upload("img", "a.jpg", "image/jpeg", data);
            assertTrue(up.key != 0);
            assertTrue(up.vid != 0);

            var got = dir.getData("img", "a.jpg");
            assertEquals("image/jpeg", got.meta().mime);
            assertArrayEquals(data, got.data());

            assertThrows(DirectoryException.class,
                    () -> dir.upload("img", "a.jpg", "image/jpeg", data));

            dir.delete("img", "a.jpg");
            assertThrows(DirectoryException.class, () -> dir.getData("img", "a.jpg"));
        }
    }

    @Test
    void metadataPersistence() throws Exception {
        Path storeDir = temp.resolve("store");
        Path metaDir = temp.resolve("meta");
        try (Store st = new Store(storeDir, 1, 1 << 20);
             Directory dir = new Directory(metaDir, st, 2)) {
            dir.upload("b", "f.bin", "application/octet-stream", "xyz".getBytes());
        }
        try (Store st2 = new Store(storeDir, 1, 1 << 20);
             Directory dir2 = new Directory(metaDir, st2, 2)) {
            assertArrayEquals("xyz".getBytes(), dir2.getData("b", "f.bin").data());
        }
    }
}
