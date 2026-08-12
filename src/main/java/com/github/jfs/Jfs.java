package com.github.jfs;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * A tiny append-only store that packs many small files (images, blobs, ...) into a
 * single container file, keeping an in-memory index of the latest live record per id.
 *
 * <p>The on-disk format is a log of self-describing records. Updates and deletes are
 * appended as new records; the last record for a given id wins. This keeps writes
 * sequential (fast) while reads are a single seek + read using the index.
 */
public final class Jfs implements Closeable {

    /** Magic marker for a record header: the ASCII bytes "JFS1". */
    private static final int MAGIC = 0x4A465331;

    private static final byte FLAG_DELETED = 0x1;

    private final Path dataFile;
    private final RandomAccessFile raf;

    /** id -> byte offset of the latest live record's payload section. */
    private final Map<String, Long> index = new LinkedHashMap<>();

    private Jfs(Path dataFile, RandomAccessFile raf) {
        this.dataFile = dataFile;
        this.raf = raf;
    }

    /**
     * Opens (creating if necessary) a store rooted at {@code dir}. The container file is
     * scanned once to rebuild the in-memory index.
     */
    public static Jfs open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path dataFile = dir.resolve("data.jfs");
        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        Jfs store = new Jfs(dataFile, raf);
        store.rebuildIndex();
        return store;
    }

    private void rebuildIndex() throws IOException {
        index.clear();
        long length = raf.length();
        raf.seek(0);
        long pos = 0;
        while (pos < length) {
            raf.seek(pos);
            int magic = raf.readInt();
            if (magic != MAGIC) {
                throw new IOException("Corrupt store: bad magic at offset " + pos);
            }
            byte flags = raf.readByte();
            int idLen = raf.readInt();
            byte[] idBytes = new byte[idLen];
            raf.readFully(idBytes);
            String id = new String(idBytes, StandardCharsets.UTF_8);
            int dataLen = raf.readInt();
            int expectedCrc = raf.readInt();
            long payloadOffset = raf.getFilePointer();

            if ((flags & FLAG_DELETED) != 0) {
                index.remove(id);
            } else {
                byte[] data = new byte[dataLen];
                raf.readFully(data);
                if (crc32(data) != expectedCrc) {
                    throw new IOException("Corrupt store: CRC mismatch for id " + id);
                }
                index.put(id, payloadOffset);
            }
            pos = payloadOffset + dataLen;
        }
        raf.seek(raf.length());
    }

    /** Stores {@code content} under a freshly generated id and returns that id. */
    public String put(byte[] content) throws IOException {
        String id = UUID.randomUUID().toString();
        put(id, content);
        return id;
    }

    /** Stores (or overwrites) {@code content} under the given {@code id}. */
    public void put(String id, byte[] content) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must be non-empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        long recordStart = raf.length();
        raf.seek(recordStart);
        raf.writeInt(MAGIC);
        raf.writeByte(0);
        raf.writeInt(idBytes.length);
        raf.write(idBytes);
        raf.writeInt(content.length);
        raf.writeInt(crc32(content));
        long payloadOffset = raf.getFilePointer();
        raf.write(content);
        index.put(id, payloadOffset);
    }

    /** Returns the stored content for {@code id}, or {@code null} if absent. */
    public byte[] get(String id) throws IOException {
        Long payloadOffset = index.get(id);
        if (payloadOffset == null) {
            return null;
        }
        // The payload is preceded by two ints in the header: dataLen then crc.
        raf.seek(payloadOffset - 2L * Integer.BYTES);
        int dataLen = raf.readInt();
        int expectedCrc = raf.readInt();
        byte[] data = new byte[dataLen];
        raf.readFully(data);
        if (crc32(data) != expectedCrc) {
            throw new IOException("Corrupt store: CRC mismatch for id " + id);
        }
        return data;
    }

    /** Returns {@code true} if a live record exists for {@code id}. */
    public boolean contains(String id) {
        return index.containsKey(id);
    }

    /**
     * Deletes {@code id} by appending a tombstone record. Returns {@code true} if the id
     * existed.
     */
    public boolean delete(String id) throws IOException {
        if (!index.containsKey(id)) {
            return false;
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        raf.seek(raf.length());
        raf.writeInt(MAGIC);
        raf.writeByte(FLAG_DELETED);
        raf.writeInt(idBytes.length);
        raf.write(idBytes);
        raf.writeInt(0);
        raf.writeInt(crc32(new byte[0]));
        index.remove(id);
        return true;
    }

    /** Returns the ids of all live records, in insertion order. */
    public List<String> list() {
        return Collections.unmodifiableList(new ArrayList<>(index.keySet()));
    }

    /** Number of live records currently stored. */
    public int size() {
        return index.size();
    }

    /** Total size of the underlying container file in bytes. */
    public long fileSize() throws IOException {
        return raf.length();
    }

    /** Path of the underlying container file. */
    public Path dataFile() {
        return dataFile;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
