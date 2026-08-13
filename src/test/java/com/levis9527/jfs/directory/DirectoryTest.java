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

    @Test
    void overviewAndMetadataUpdate() throws Exception {
        Path root = temp.resolve("ov");
        try (Store st = new Store(root.resolve("store"), 2, 1 << 20);
             Directory dir = new Directory(root.resolve("meta"), st, 3)) {
            dir.upload("img", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});
            dir.upload("doc", "n.txt", "text/plain", "hi".getBytes());

            var ov = dir.overview();
            assertEquals(2, ov.fileCount);
            assertEquals(2, ov.bucketCount);
            assertEquals(5, ov.totalBytes);
            assertEquals(2, ov.volumes.size());
            assertTrue(ov.volumes.get(0).usedBytes > 0 || ov.volumes.get(1).usedBytes > 0);

            var updated = dir.updateMeta("doc", "n.txt", "note.txt", "text/markdown");
            assertEquals("note.txt", updated.filename);
            assertEquals("text/markdown", updated.mime);
            assertEquals("text/markdown", dir.getMeta("doc", "note.txt").mime);
            assertThrows(DirectoryException.class, () -> dir.getMeta("doc", "n.txt"));
        }
    }

    @Test
    void renamePersistsAcrossRestart() throws Exception {
        Path storeDir = temp.resolve("store2");
        Path metaDir = temp.resolve("meta2");
        try (Store st = new Store(storeDir, 1, 1 << 20);
             Directory dir = new Directory(metaDir, st, 4)) {
            dir.upload("b", "old.bin", "application/octet-stream", "xyz".getBytes());
            dir.updateMeta("b", "old.bin", "new.bin", null);
        }
        try (Store st2 = new Store(storeDir, 1, 1 << 20);
             Directory dir2 = new Directory(metaDir, st2, 4)) {
            assertArrayEquals("xyz".getBytes(), dir2.getData("b", "new.bin").data());
            assertEquals(1, dir2.list(null).size());
            assertThrows(DirectoryException.class, () -> dir2.getMeta("b", "old.bin"));
        }
    }
}
