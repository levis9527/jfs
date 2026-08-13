package com.levis9527.jfs.meta;

/**
 * Shared metadata types across jfs modules (bfs-style).
 */
public final class MetaTypes {
    private MetaTypes() {
    }

    public static final int RET_OK = 1;
    public static final int RET_BAD_REQUEST = 400;
    public static final int RET_UNAUTHORIZED = 401;
    public static final int RET_NOT_FOUND = 404;
    public static final int RET_CONFLICT = 409;
    public static final int RET_INTERNAL = 500;

    public static final class NeedleLoc {
        public long key;
        public int cookie;
        public int vid;
        public long offset; // aligned offset units
        public int size;

        public NeedleLoc() {
        }

        public NeedleLoc(long key, int cookie, int vid, long offset, int size) {
            this.key = key;
            this.cookie = cookie;
            this.vid = vid;
            this.offset = offset;
            this.size = size;
        }
    }

    public static final class FileMeta {
        public String bucket;
        public String filename;
        public String mime;
        public long key;
        public int cookie;
        public int vid;
        public int size;
        public long created;
        public boolean deleted;
        /** When true, reading this object requires the server token. */
        public boolean auth;

        public FileMeta copy() {
            FileMeta c = new FileMeta();
            c.bucket = bucket;
            c.filename = filename;
            c.mime = mime;
            c.key = key;
            c.cookie = cookie;
            c.vid = vid;
            c.size = size;
            c.created = created;
            c.deleted = deleted;
            c.auth = auth;
            return c;
        }
    }

    public static final class UploadResult {
        public String bucket;
        public String filename;
        public long key;
        public int cookie;
        public int vid;
        public int size;
        public String url;
        public boolean auth;
    }

    public static final class VolumeState {
        public int id;
        public String path;
        public long freeSpace;
        public long usedBytes;
        public long maxSize;
        public int fileCount;
        public boolean readOnly;
    }

    public static final class BucketStat {
        public String name;
        public int fileCount;
        public long totalBytes;
    }

    public static final class Overview {
        public int fileCount;
        public int bucketCount;
        public long totalBytes;
        public java.util.List<BucketStat> buckets;
        public java.util.List<VolumeState> volumes;
    }

    public static final class ApiResponse {
        public int ret;
        public String msg;
        public Object data;

        public ApiResponse(int ret, String msg, Object data) {
            this.ret = ret;
            this.msg = msg;
            this.data = data;
        }
    }
}
