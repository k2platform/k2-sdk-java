package com.k2platform.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineConfigCacheTest {

    private static final String TOKEN = "k2_live_abc123";

    private static Map<String, Object> sampleProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("db.url", "jdbc:postgresql://h/db");
        props.put("db.pool", 20);
        return props;
    }

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path dir) {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        cache.save("prod", TOKEN, sampleProps());
        Map<String, Object> loaded = cache.load("prod", TOKEN);

        assertEquals("jdbc:postgresql://h/db", loaded.get("db.url"));
        assertEquals(20, ((Number) loaded.get("db.pool")).intValue());
    }

    @Test
    void loadReturnsNullWhenAbsent(@TempDir Path dir) {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        assertNull(cache.load("never-written", TOKEN));
    }

    @Test
    void snapshotIsEncryptedAtRest(@TempDir Path dir) throws Exception {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        cache.save("prod", TOKEN, sampleProps());

        Path file = dir.resolve("config-prod.json.enc");
        assertTrue(Files.isReadable(file), "snapshot file should exist");
        byte[] raw = Files.readAllBytes(file);
        byte[] needle = "jdbc:postgresql://h/db".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(indexOf(raw, needle) >= 0,
                "config values must not appear in cleartext on disk");
    }

    @Test
    void wrongTokenIsRefused(@TempDir Path dir) {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        cache.save("prod", TOKEN, sampleProps());

        assertNull(cache.load("prod", "k2_live_rotated_999"),
                "a snapshot written under one token must be unreadable under another (token rotation invalidates the cache)");
    }

    @Test
    void tamperedSnapshotIsRefused(@TempDir Path dir) throws Exception {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        cache.save("prod", TOKEN, sampleProps());

        Path file = dir.resolve("config-prod.json.enc");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01; // flip a bit in the HMAC tail
        Files.write(file, bytes);

        assertNull(cache.load("prod", TOKEN), "an integrity-check failure must be refused");
    }

    @Test
    void staleSnapshotPastTtlIsRefused(@TempDir Path dir) throws Exception {
        // 1ms TTL — the snapshot expires almost immediately.
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir, 1L);
        cache.save("prod", TOKEN, sampleProps());
        Thread.sleep(10);

        assertNull(cache.load("prod", TOKEN), "a snapshot past its TTL must be refused as stale");
    }

    @Test
    void zeroTtlNeverExpires(@TempDir Path dir) throws Exception {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir, 0L);
        cache.save("prod", TOKEN, sampleProps());
        Thread.sleep(10);

        Map<String, Object> loaded = cache.load("prod", TOKEN);
        assertEquals("jdbc:postgresql://h/db", loaded.get("db.url"));
    }

    @Test
    void preservesNestedAndListValues(@TempDir Path dir) {
        OfflineConfigCache cache = new OfflineConfigCache(new ObjectMapper(), dir);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("feature.flags", List.of("a", "b"));
        props.put("nested", Map.of("k", "v"));
        cache.save("prod", TOKEN, props);

        Map<String, Object> loaded = cache.load("prod", TOKEN);
        assertEquals(List.of("a", "b"), loaded.get("feature.flags"));
        assertEquals(Map.of("k", "v"), loaded.get("nested"));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
