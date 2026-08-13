package com.levis9527.jfs.idgen;

/**
 * Lightweight snowflake-like ID generator (bfs uses gosnowflake).
 */
public final class Snowflake {
    private static final long EPOCH = 1609459200000L; // 2021-01-01 UTC
    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private long sequence;
    private long lastStamp = -1L;

    public Snowflake(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("worker id out of range: " + workerId);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastStamp) {
            throw new IllegalStateException("clock moved backwards");
        }
        if (now == lastStamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                while (now <= lastStamp) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0;
        }
        lastStamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    }

    /** Non-zero cookie derived from key (mitigate brute-force store access). */
    public static int nextCookie(long key) {
        int c = (int) (key ^ (key >>> 32));
        if (c == 0) {
            return 1;
        }
        return c < 0 ? -c : c;
    }
}
