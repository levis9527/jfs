package com.levis9527.jfs.store;

import com.levis9527.jfs.meta.MetaTypes.NeedleLoc;
import com.levis9527.jfs.meta.MetaTypes.VolumeState;
import com.levis9527.jfs.volume.Volume;
import com.levis9527.jfs.volume.VolumeException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical storage service (bfs store): owns volumes and exposes add/get/del.
 */
public final class Store implements Closeable {
    private final Path root;
    private final Map<Integer, Volume> volumes = new LinkedHashMap<>();

    public Store(Path dataDir, int volumeCount, long volumeSize) throws IOException {
        this.root = dataDir;
        if (volumeCount <= 0) {
            volumeCount = 1;
        }
        for (int i = 1; i <= volumeCount; i++) {
            Path dir = dataDir.resolve("volume_" + i);
            volumes.put(i, new Volume(dir, i, volumeSize));
        }
    }

    public NeedleLoc write(int vid, long key, int cookie, byte[] data) throws IOException {
        return get(vid).write(key, cookie, data);
    }

    public byte[] read(int vid, long key, int cookie) throws IOException {
        return get(vid).read(key, cookie);
    }

    public void delete(int vid, long key, int cookie) throws IOException {
        get(vid).delete(key, cookie);
    }

    public int pickVolume(long needBytes) {
        int best = -1;
        long bestFree = -1;
        for (Volume vol : volumes.values()) {
            VolumeState st = vol.state();
            if (st.readOnly || st.freeSpace < needBytes) {
                continue;
            }
            if (st.freeSpace > bestFree) {
                bestFree = st.freeSpace;
                best = st.id;
            }
        }
        if (best < 0) {
            throw new VolumeException("no writable volume");
        }
        return best;
    }

    public List<VolumeState> states() {
        List<VolumeState> out = new ArrayList<>();
        for (Volume vol : volumes.values()) {
            out.add(vol.state());
        }
        return out;
    }

    private Volume get(int vid) {
        Volume vol = volumes.get(vid);
        if (vol == null) {
            throw new VolumeException("volume not found: " + vid);
        }
        return vol;
    }

    public Path root() {
        return root;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (Volume vol : volumes.values()) {
            try {
                vol.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        volumes.clear();
        if (first != null) {
            throw first;
        }
    }
}
