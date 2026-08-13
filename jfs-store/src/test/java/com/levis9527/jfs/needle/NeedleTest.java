package com.levis9527.jfs.needle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeedleTest {
    @Test
    void encodeDecodeRoundTrip() {
        byte[] data = "hello-jfs-small-file".getBytes();
        byte[] buf = Needle.encode(12345L, 99, data);
        assertEquals(0, buf.length % Needle.PADDING_SIZE);
        Needle got = Needle.decode(buf);
        assertEquals(12345L, got.key);
        assertEquals(99, got.cookie);
        assertArrayEquals(data, got.data);
    }

    @Test
    void offsetAlign() {
        assertEquals(2, Needle.needleOffset(16));
        assertEquals(16, Needle.blockOffset(2));
        assertEquals(24, Needle.align(21));
    }

    @Test
    void corruptChecksumDetected() {
        byte[] buf = Needle.encode(1L, 1, "abc".getBytes());
        buf[21] ^= (byte) 0xff;
        assertThrows(NeedleException.class, () -> Needle.decode(buf));
    }

    @Test
    void emptyPayload() {
        byte[] buf = Needle.encode(7L, 3, new byte[0]);
        Needle n = Needle.decode(buf);
        assertEquals(0, n.size);
        assertArrayEquals(new byte[0], n.data);
    }
}
