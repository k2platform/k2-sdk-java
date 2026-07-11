package com.k2platform.sdk.spring;

import com.k2platform.sdk.FakeK2Server;
import com.k2platform.sdk.K2Client;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mode 2 — verifies the {@code @K2Config} proxy: key mapping, type coercion, defaults. */
class K2ConfigProxyTest {

    private static final String BODY = """
        { "environment":"prod",
          "properties": {
            "db.url":"jdbc:postgresql://h/db",
            "dbPool":20,
            "feature.x":true,
            "timeout.ms":1500,
            "ratio":0.5
          } }
        """;

    @K2Config
    interface AppConfig {
        @K2ConfigProperty(key = "db.url")                       String dbUrl();
        @K2ConfigProperty                                       int getDbPool();      // derived → "dbPool"
        @K2ConfigProperty(key = "feature.x")                    boolean featureX();
        @K2ConfigProperty(key = "timeout.ms", defaultValue = "0") long timeoutMs();
        @K2ConfigProperty(key = "ratio", defaultValue = "0")    double ratio();
        @K2ConfigProperty(key = "missing", defaultValue = "fb") String missing();
        @K2ConfigProperty(key = "absent")                       String absent();      // no default
    }

    @K2Config(prefix = "db")
    interface DbConfig {
        @K2ConfigProperty(key = "url") String url();            // prefixed → "db.url"
    }

    @Test
    void proxyMapsCoercesAndDefaults() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .defaultEnvironment("prod").cacheTtl(Duration.ofMinutes(5))
                    .build();

            AppConfig cfg = (AppConfig) Proxy.newProxyInstance(
                    AppConfig.class.getClassLoader(), new Class<?>[]{AppConfig.class},
                    new K2ConfigInvocationHandler(client, ""));

            assertEquals("jdbc:postgresql://h/db", cfg.dbUrl());
            assertEquals(20, cfg.getDbPool());
            assertTrue(cfg.featureX());
            assertEquals(1500L, cfg.timeoutMs());
            assertEquals(0.5d, cfg.ratio());
            assertEquals("fb", cfg.missing());   // default applied
            assertNull(cfg.absent());             // absent, no default → null
        }
    }

    @Test
    void prefixIsAppliedToDerivedKeys() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            K2Client client = K2Client.builder()
                    .baseUrl(server.baseUrl()).token("t")
                    .defaultEnvironment("prod").cacheTtl(Duration.ofMinutes(5))
                    .build();

            DbConfig cfg = (DbConfig) Proxy.newProxyInstance(
                    DbConfig.class.getClassLoader(), new Class<?>[]{DbConfig.class},
                    new K2ConfigInvocationHandler(client, "db"));

            assertEquals("jdbc:postgresql://h/db", cfg.url()); // prefix "db" + "url" → db.url
        }
    }
}
