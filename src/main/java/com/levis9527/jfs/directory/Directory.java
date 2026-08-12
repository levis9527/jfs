package com.levis9527.jfs.directory;

import com.google.gson.Gson;
import com.levis9527.jfs.idgen.Snowflake;
import com.levis9527.jfs.meta.MetaTypes.FileMeta;
import com.levis9527.jfs.meta.MetaTypes.NeedleLoc;
import com.levis9527.jfs.meta.MetaTypes.UploadResult;
import com.levis9527.jfs.needle.Needle;
import com.levis9527.jfs.store.Store;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata + write scheduling (bfs directory).
 * Persists {@code bucket/filename -> needle location} in local JSONL (no HBase).
 */
public final class Directory implements Closeable {
    private final Store store;
    private final Snowflake idgen;
    private final Path metaPath;
    private final Gson gson = new Gson();
    private final Map<String, FileMeta> files = new HashMap<>();
    private final Map<Long, FileMeta> byKey = new HashMap<>();
    private final Object lock = new Object();
    private Writer metaWriter;

    public Directory(Path metaDir, Store store, long workerId) throws IOException {
        this.store = store;
        this.idgen = new Snowflake(workerId);
        Files.createDirectories(metaDir);
        this.metaPath = metaDir.resolve("files.jsonl");
        load();
        this.metaWriter = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static String fileKey(String bucket, String filename) {
        return bucket + "/" + filename;
    }

    private void load() throws IOException {
        if (!Files.exists(metaPath)) {
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                FileMeta m = gson.fromJson(line, FileMeta.class);
                String k = fileKey(m.bucket, m.filename);
                if (m.deleted) {
                    files.remove(k);
                    byKey.remove(m.key);
                } else {
                    files.put(k, m);
                    byKey.put(m.key, m);
                }
            }
        }
    }

    private void appendMeta(FileMeta m) throws IOException {
        metaWriter.write(gson.toJson(m));
        metaWriter.write('\n');
        metaWriter.flush();
    }

    public UploadResult upload(String bucket, String filename, String mime, byte[] data) throws IOException {
        if (bucket == null || bucket.isBlank() || filename == null || filename.isBlank()) {
            throw new DirectoryException("bucket and filename required");
        }
        synchronized (lock) {
            String k = fileKey(bucket, filename);
            if (files.containsKey(k)) {
                throw new DirectoryException("file already exists");
            }
            long key = idgen.nextId();
            int cookie = Snowflake.nextCookie(key);
            long need = Needle.calcTotalSize(data.length);
            int vid = store.pickVolume(need);
            NeedleLoc loc = store.write(vid, key, cookie, data);

            FileMeta fm = new FileMeta();
            fm.bucket = bucket;
            fm.filename = filename;
            fm.mime = mime;
            fm.key = key;
            fm.cookie = cookie;
            fm.vid = loc.vid;
            fm.size = data.length;
            fm.created = System.currentTimeMillis() / 1000L;
            appendMeta(fm);
            files.put(k, fm);
            byKey.put(key, fm);

            UploadResult r = new UploadResult();
            r.bucket = bucket;
            r.filename = filename;
            r.key = key;
            r.cookie = cookie;
            r.vid = loc.vid;
            r.size = fm.size;
            r.url = "/" + bucket + "/" + filename;
            return r;
        }
    }

    public FileMeta getMeta(String bucket, String filename) {
        synchronized (lock) {
            FileMeta fm = files.get(fileKey(bucket, filename));
            if (fm == null || fm.deleted) {
                throw new DirectoryException("file not found");
            }
            return fm.copy();
        }
    }

    public record GetResult(FileMeta meta, byte[] data) {
    }

    public GetResult getData(String bucket, String filename) throws IOException {
        FileMeta fm = getMeta(bucket, filename);
        byte[] data = store.read(fm.vid, fm.key, fm.cookie);
        return new GetResult(fm, data);
    }

    public void delete(String bucket, String filename) throws IOException {
        synchronized (lock) {
            String k = fileKey(bucket, filename);
            FileMeta fm = files.get(k);
            if (fm == null) {
                throw new DirectoryException("file not found");
            }
            store.delete(fm.vid, fm.key, fm.cookie);
            FileMeta tomb = fm.copy();
            tomb.deleted = true;
            appendMeta(tomb);
            files.remove(k);
            byKey.remove(fm.key);
        }
    }

    public List<FileMeta> list(String bucket) {
        synchronized (lock) {
            List<FileMeta> out = new ArrayList<>();
            for (FileMeta fm : files.values()) {
                if (bucket != null && !bucket.isBlank() && !bucket.equals(fm.bucket)) {
                    continue;
                }
                out.add(fm.copy());
            }
            return out;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (metaWriter != null) {
                metaWriter.close();
                metaWriter = null;
            }
        }
    }
}
