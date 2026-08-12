package com.levis9527.jfs.idgen;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeTest {
    @Test
    void uniqueIds() {
        Snowflake g = new Snowflake(1);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = g.nextId();
            assertTrue(seen.add(id), "duplicate " + id);
            assertNotEquals(0, Snowflake.nextCookie(id));
        }
    }
}
