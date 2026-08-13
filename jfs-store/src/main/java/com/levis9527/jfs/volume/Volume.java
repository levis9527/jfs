package com.levis9527.jfs.volume;

import com.levis9527.jfs.index.Indexer;
import com.levis9527.jfs.meta.MetaTypes.NeedleLoc;
import com.levis9527.jfs.meta.MetaTypes.VolumeState;
import com.levis9527.jfs.needle.Needle;
import com.levis9527.jfs.needle.NeedleException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One Haystack volume: superblock (.dat) + index (.idx) + in-memory key map.
 */
public final class Volume implements Closeable {
    private final int id;
    private final Path dataPath;
    private final FileChannel data;
    private final Indexer indexer;
    private final Map<Long, Indexer.Record> needles = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final long maxSize;
    private long offset;
    private boolean readOnly;
    private boolean closed;

    public Volume(Path dir, int id, long maxSize) throws IOException {
        this.id = id;
        this.maxSize = maxSize <= 0 ? (1L << 30) : maxSize;
        Files.createDirectories(dir);
        this.dataPath = dir.resolve(id + ".dat");
        Path idxPath = dir.resolve(id + ".idx");
        this.data = FileChannel.open(dataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        this.indexer = new Indexer(idxPath);
        recover();
    }

    private void recover() throws IOException {
        Map<Long, Indexer.Record> recs = indexer.recovery();
        offset = data.size();
        if (recs.isEmpty() && offset > 0) {
            replayFrom(0);
            return;
        }
        long lastByte = 0;
        for (Indexer.Record r : recs.values()) {
            needles.put(r.key, r);
            long end = Needle.blockOffset(r.offset) + Needle.calcTotalSize(r.size);
            if (end > lastByte) {
                lastByte = end;
            }
        }
        if (offset > lastByte) {
            replayFrom(lastByte);
        }
    }

    private void replayFrom(long from) throws IOException {
        long off = from;
        while (off < data.size()) {
            ByteBuffer hdr = ByteBuffer.allocate(Needle.HEADER_SIZE);
            int n = data.read(hdr, off);
            if (n < Needle.HEADER_SIZE) {
                // truncated tail
                data.truncate(off);
                offset = off;
                return;
            }
            hdr.flip();
            hdr.position(17);
            int size = hdr.getInt();
            if (size < 0) {
                throw new IOException("corrupt needle size at " + off);
            }
            int total = Needle.calcTotalSize(size);
            if (off + total > data.size()) {
                data.truncate(off);
                offset = off;
                return;
            }
            ByteBuffer full = ByteBuffer.allocate(total);
            int read = data.read(full, off);
            if (read < total) {
                data.truncate(off);
                offset = off;
                return;
            }
            Needle needle = Needle.decode(full.array());
            Indexer.Record rec = new Indexer.Record(needle.key, Needle.needleOffset(off), needle.size);
            if (needle.flag == Needle.FLAG_DEL) {
                needles.remove(needle.key);
            } else {
                needles.put(needle.key, rec);
            }
            indexer.append(rec);
            off += total;
        }
        offset = off;
    }

    public NeedleLoc write(long key, int cookie, byte[] payload) throws IOException {
        lock.writeLock().lock();
        try {
            ensureWritable();
            byte[] buf = Needle.encode(key, cookie, payload);
            if (offset + buf.length > maxSize) {
                throw new VolumeException("volume full");
            }
            ByteBuffer bb = ByteBuffer.wrap(buf);
            long pos = offset;
            while (bb.hasRemaining()) {
                int written = data.write(bb, pos);
                if (written <= 0) {
                    throw new IOException("failed to write needle");
                }
                pos += written;
            }
            data.force(true);
            Indexer.Record rec = new Indexer.Record(key, Needle.needleOffset(offset), payload.length);
            indexer.append(rec);
            needles.put(key, rec);
            offset += buf.length;
            return new NeedleLoc(key, cookie, id, rec.offset, rec.size);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] read(long key, int cookie) throws IOException {
        Indexer.Record rec;
        lock.readLock().lock();
        try {
            rec = needles.get(key);
        } finally {
            lock.readLock().unlock();
        }
        if (rec == null) {
            throw new VolumeException("needle not found");
        }
        int total = Needle.calcTotalSize(rec.size);
        ByteBuffer buf = ByteBuffer.allocate(total);
        long byteOff = Needle.blockOffset(rec.offset);
        int n = data.read(buf, byteOff);
        if (n < total) {
            throw new IOException("short read");
        }
        Needle needle = Needle.decode(buf.array());
        if (needle.flag == Needle.FLAG_DEL) {
            lock.writeLock().lock();
            try {
                needles.remove(key);
            } finally {
                lock.writeLock().unlock();
            }
            throw new VolumeException("needle not found");
        }
        if (needle.key != key) {
            throw new NeedleException("key mismatch");
        }
        if (needle.cookie != cookie) {
            throw new VolumeException("cookie mismatch");
        }
        return needle.data;
    }

    public void delete(long key, int cookie) throws IOException {
        lock.writeLock().lock();
        try {
            Indexer.Record rec = needles.get(key);
            if (rec == null) {
                throw new VolumeException("needle not found");
            }
            int total = Needle.calcTotalSize(rec.size);
            ByteBuffer buf = ByteBuffer.allocate(total);
            long byteOff = Needle.blockOffset(rec.offset);
            if (data.read(buf, byteOff) < total) {
                throw new IOException("short read");
            }
            Needle needle = Needle.decode(buf.array());
            if (needle.cookie != cookie) {
                throw new VolumeException("cookie mismatch");
            }
            ByteBuffer flag = ByteBuffer.wrap(new byte[]{Needle.FLAG_DEL});
            data.write(flag, byteOff + Needle.flagOffset());
            data.force(true);
            needles.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VolumeState state() {
        lock.readLock().lock();
        try {
            VolumeState s = new VolumeState();
            s.id = id;
            s.path = dataPath.toString();
            s.usedBytes = offset;
            s.maxSize = maxSize;
            s.freeSpace = maxSize - offset;
            s.fileCount = needles.size();
            s.readOnly = readOnly || closed;
            return s;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setReadOnly(boolean readOnly) {
        lock.writeLock().lock();
        try {
            this.readOnly = readOnly;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int id() {
        return id;
    }

    private void ensureWritable() {
        if (closed || readOnly) {
            throw new VolumeException("volume read only");
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            closed = true;
            indexer.close();
            data.close();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
