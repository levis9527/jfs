package com.levis9527.jfs.needle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Haystack-style needle format used by bilibili bfs.
 *
 * <pre>
 * | magic(4) | cookie(4) | key(8) | flag(1) | size(4) | data | magic(4) | checksum(4) | padding |
 * </pre>
 * Aligned to 8 bytes. Checksum uses CRC32/Koopman polynomial like bfs.
 */
public final class Needle {
    public static final int MAGIC_SIZE = 4;
    public static final int COOKIE_SIZE = 4;
    public static final int KEY_SIZE = 8;
    public static final int FLAG_SIZE = 1;
    public static final int SIZE_SIZE = 4;
    public static final int CHECKSUM_SIZE = 4;

    public static final int HEADER_SIZE = MAGIC_SIZE + COOKIE_SIZE + KEY_SIZE + FLAG_SIZE + SIZE_SIZE; // 21
    public static final int FOOTER_SIZE = MAGIC_SIZE + CHECKSUM_SIZE; // 8
    public static final int PADDING_SIZE = 8;

    public static final byte FLAG_OK = 0;
    public static final byte FLAG_DEL = 1;

    private static final byte[] HEADER_MAGIC = new byte[]{0x12, 0x34, 0x56, 0x78};
    private static final byte[] FOOTER_MAGIC = new byte[]{(byte) 0x87, 0x65, 0x43, 0x21};
    /** CRC-32/Koopman polynomial (same family bfs uses via crc32.Koopman). */
    private static final int KOOPMAN_POLY = 0xEB31D82E;

    public int cookie;
    public long key;
    public byte flag;
    public int size;
    public byte[] data;
    public int checksum;
    public int totalSize;
    public int paddingLen;

    private Needle() {
    }

    public static int align(int n) {
        return (n + (PADDING_SIZE - 1)) & ~(PADDING_SIZE - 1);
    }

    public static long needleOffset(long byteOffset) {
        return byteOffset / PADDING_SIZE;
    }

    public static long blockOffset(long alignedOffset) {
        return alignedOffset * PADDING_SIZE;
    }

    public static int calcTotalSize(int dataSize) {
        return align(HEADER_SIZE + dataSize + FOOTER_SIZE);
    }

    public static long flagOffset() {
        return 16L;
    }

    public static byte[] encode(long key, int cookie, byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        Needle n = new Needle();
        n.key = key;
        n.cookie = cookie;
        n.flag = FLAG_OK;
        n.size = data.length;
        n.data = data;
        n.checksum = crc32Koopman(data);
        n.totalSize = calcTotalSize(n.size);
        n.paddingLen = n.totalSize - (HEADER_SIZE + n.size + FOOTER_SIZE);

        ByteBuffer buf = ByteBuffer.allocate(n.totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.put(HEADER_MAGIC);
        buf.putInt(cookie);
        buf.putLong(key);
        buf.put(FLAG_OK);
        buf.putInt(n.size);
        buf.put(data);
        buf.put(FOOTER_MAGIC);
        buf.putInt(n.checksum);
        // remaining bytes already zero (padding)
        return buf.array();
    }

    public static Needle decode(byte[] buf) {
        if (buf == null || buf.length < HEADER_SIZE + FOOTER_SIZE) {
            throw new NeedleException("needle too small");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (!Arrays.equals(magic, HEADER_MAGIC)) {
            throw new NeedleException("bad header magic");
        }
        Needle n = new Needle();
        n.cookie = bb.getInt();
        n.key = bb.getLong();
        n.flag = bb.get();
        if (n.flag != FLAG_OK && n.flag != FLAG_DEL) {
            throw new NeedleException("invalid flag");
        }
        n.size = bb.getInt();
        if (n.size < 0) {
            throw new NeedleException("invalid size");
        }
        n.totalSize = calcTotalSize(n.size);
        if (buf.length < n.totalSize) {
            throw new NeedleException("buffer shorter than needle");
        }
        n.data = new byte[n.size];
        bb.get(n.data);
        bb.get(magic);
        if (!Arrays.equals(magic, FOOTER_MAGIC)) {
            throw new NeedleException("bad footer magic");
        }
        n.checksum = bb.getInt();
        int expect = crc32Koopman(n.data);
        if (expect != n.checksum) {
            throw new NeedleException("checksum mismatch");
        }
        n.paddingLen = n.totalSize - (HEADER_SIZE + n.size + FOOTER_SIZE);
        return n;
    }

    /**
     * CRC32 with Koopman polynomial. Java's {@link CRC32} is IEEE; bfs uses Koopman,
     * so we implement a simple bit-wise CRC for compatibility with the documented format.
     */
    static int crc32Koopman(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xff);
            for (int i = 0; i < 8; i++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ KOOPMAN_POLY;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc;
    }
}
