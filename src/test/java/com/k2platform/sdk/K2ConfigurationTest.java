package com.k2platform.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the SDK model deserializes the platform's ConfigurationResponseDto shape. */
class K2ConfigurationTest {

    private static final ObjectMapper M = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void deserializesAndTypedGettersWork() throws Exception {
        String json = """
            {
              "organization": "acme",
              "application": "billing",
              "environment": "prod",
              "properties": { "db.pool": 20, "feature.x": true, "db.url": "jdbc:postgresql://h/db" },
              "metadata": { "db.url": { "type": "string", "isSecret": true } },
              "lastModified": "2026-06-02T10:00:00Z",
              "propertyCount": 3
            }
            """;

        K2Configuration cfg = M.readValue(json, K2Configuration.class);

        assertEquals("acme", cfg.getOrganization());
        assertEquals(3, cfg.getProperties().size());
        assertEquals(20, cfg.getInt("db.pool", 0));
        assertTrue(cfg.getBoolean("feature.x", false));
        assertEquals("fallback", cfg.getString("missing", "fallback"));
        assertTrue(cfg.getMetadata().get("db.url").getIsSecret());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        K2Configuration cfg = M.readValue("{\"organization\":\"a\",\"futureField\":123}", K2Configuration.class);
        assertEquals("a", cfg.getOrganization());
        assertFalse(cfg.getBoolean("nope", false));
    }
}
