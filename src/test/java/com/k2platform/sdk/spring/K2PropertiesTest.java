package com.k2platform.sdk.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class K2PropertiesTest {

    @Test
    void resolveTokenPrefersExplicitToken() {
        K2Properties p = new K2Properties();
        p.setToken("k2_live_explicit");
        assertEquals("k2_live_explicit", p.resolveToken());
    }

    @Test
    void resolveTokenReadsSecretFileWhenTokenBlank(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("k2_token");
        Files.writeString(secret, "k2_live_from_file\n");   // trailing newline trimmed
        K2Properties p = new K2Properties();
        p.setTokenFile(secret.toString());
        assertEquals("k2_live_from_file", p.resolveToken());
    }

    @Test
    void resolveTokenNullWhenNeitherSet() {
        assertNull(new K2Properties().resolveToken());
    }
}
