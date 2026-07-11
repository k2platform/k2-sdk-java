package com.k2platform.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K2ClientResilienceTest {

    private static final String BODY = """
        { "organization":"acme","application":"billing","environment":"prod",
          "properties": { "db.url":"jdbc:postgresql://h/db", "db.pool":20, "feature.x":true },
          "propertyCount":3 }
        """;

    @Test
    void liveFetchReadsProperties() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder().baseUrl(server.baseUrl()).token("t").build();
            K2Configuration cfg = client.getConfiguration("prod");
            assertEquals("jdbc:postgresql://h/db", cfg.getString("db.url", null));
            assertEquals(20, cfg.getInt("db.pool", 0));
            assertTrue(cfg.getBoolean("feature.x", false));
        }
    }

    @Test
    void cachedConfigurationHitsServerOnlyOnceWithinTtl() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .defaultEnvironment("prod")
                    .cacheTtl(Duration.ofMinutes(5))
                    .build();

            client.getCachedConfiguration();
            client.getCachedConfiguration();
            client.getCachedConfiguration();

            assertEquals(1, server.hits(), "TTL cache should collapse repeat reads into one fetch");
        }
    }

    @Test
    void servesOfflineSnapshotWhenServerUnreachable(@TempDir Path dir) throws Exception {
        OfflineConfigCache offline = new OfflineConfigCache(new ObjectMapper(), dir);
        String baseUrl;
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            baseUrl = server.baseUrl();
            K2Client warm = K2Client.builder()
                    .baseUrl(baseUrl).token("t").offlineCache(offline).build();
            warm.getConfiguration("prod"); // writes the offline snapshot
        }
        // Server is now down; a fresh client must still serve the last-known config.
        K2Client cold = K2Client.builder()
                .baseUrl(baseUrl).token("t").offlineCache(offline).build();
        K2Configuration cfg = cold.getConfiguration("prod");
        assertEquals("jdbc:postgresql://h/db", cfg.getString("db.url", null));
    }

    @Test
    void authErrorDoesNotFallBackToCache(@TempDir Path dir) throws Exception {
        OfflineConfigCache offline = new OfflineConfigCache(new ObjectMapper(), dir);
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client warm = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t").offlineCache(offline).build();
            warm.getConfiguration("prod"); // populate cache

            server.setStatus(403);
            K2Exception ex = assertThrows(K2Exception.class, () -> warm.getConfiguration("prod"));
            assertEquals(403, ex.getStatusCode(), "403 must surface, not serve a stale snapshot");
        }
    }

    @Test
    void host421SurfacesAsActionableError() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            server.setStatus(421);
            K2Client client = K2Client.builder().baseUrl(server.baseUrl()).token("t").build();
            K2Exception ex = assertThrows(K2Exception.class, () -> client.getConfiguration("prod"));
            assertEquals(421, ex.getStatusCode());
            assertTrue(ex.getMessage().contains("not licensed"));
        }
    }

    @Test
    void appScopedTokenUsesEnvOnlyPath() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder().baseUrl(server.baseUrl()).token("t").build();
            client.getConfiguration("prod");
            assertEquals("/api/config/token/prod/current", server.lastPath());
        }
    }

    @Test
    void orgScopedTokenNamesAppInPath() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .application("billing")           // K2_APP set ⇒ client-named-app contract (§2b)
                    .source(K2Client.Source.SERVER)   // no file lookup
                    .build();
            client.getConfiguration("prod");
            assertEquals("/api/config/token/billing/prod/current", server.lastPath());
        }
    }

    @Test
    void missingBaseUrlFailsFastWithoutVendorFallback() {
        K2Exception ex = assertThrows(K2Exception.class,
                () -> K2Client.builder().token("t").build());
        assertTrue(ex.getMessage().contains("baseUrl is required"));
        assertTrue(ex.getMessage().contains("never falls back to a vendor URL"));
    }
}
