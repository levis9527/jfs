package com.levis9527.jfs.index;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Volume sidecar index for fast recovery.
 *
 * <pre>
 * | key(int64) | offset(uint32 units) | size(int32) |  = 16 bytes
 * </pre>
 */
public final class Indexer implements Closeable {
    public static final int RECORD_SIZE = 16;

    public static final class Record {
        public final long key;
        public final long offset; // aligned units (stored as uint32)
        public final int size;

        public Record(long key, long offset, int size) {
            this.key = key;
            this.offset = offset;
            this.size = size;
        }
    }

    private final Path path;
    private final FileChannel channel;
    private final Object lock = new Object();

    public Indexer(Path path) throws IOException {
        this.path = path;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    public void append(Record rec) throws IOException {
        if (rec.size < 0 || rec.offset > 0xffff_ffffL) {
            throw new IOException("invalid index record");
        }
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(rec.key);
        buf.putInt((int) rec.offset);
        buf.putInt(rec.size);
        buf.flip();
        synchronized (lock) {
            channel.position(channel.size());
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
    }

    public Map<Long, Record> recovery() throws IOException {
        Map<Long, Record> out = new HashMap<>();
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.BIG_ENDIAN);
        synchronized (lock) {
            channel.position(0);
            while (true) {
                buf.clear();
                int n = channel.read(buf);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    break;
                }
                if (n < RECORD_SIZE) {
                    throw new IOException("truncated index record");
                }
                buf.flip();
                long key = buf.getLong();
                long offset = buf.getInt() & 0xffff_ffffL;
                int size = buf.getInt();
                if (size < 0) {
                    throw new IOException("invalid index size");
                }
                out.put(key, new Record(key, offset, size));
            }
        }
        return out;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
