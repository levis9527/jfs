package com.levis9527.jfs.volume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VolumeTest {
    @TempDir
    Path temp;

    @Test
    void writeReadDelete() throws Exception {
        try (Volume v = new Volume(temp, 1, 1 << 20)) {
            byte[] payload = "image-bytes-001".getBytes();
            var loc = v.write(1001L, 7, payload);
            assertEquals(1, loc.vid);
            assertEquals(1001L, loc.key);
            assertArrayEquals(payload, v.read(1001L, 7));
            assertThrows(VolumeException.class, () -> v.read(1001L, 8));
            v.delete(1001L, 7);
            assertThrows(VolumeException.class, () -> v.read(1001L, 7));
        }
    }

    @Test
    void recoveryFromIndex() throws Exception {
        Path dir = temp.resolve("v");
        try (Volume v = new Volume(dir, 2, 1 << 20)) {
            v.write(42L, 3, "persist-me".getBytes());
        }
        try (Volume v2 = new Volume(dir, 2, 1 << 20)) {
            assertArrayEquals("persist-me".getBytes(), v2.read(42L, 3));
        }
    }
}
