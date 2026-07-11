package com.k2platform.sdk.spring;

import com.k2platform.sdk.FakeK2Server;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mode 1 — verifies K2 layers in as a PropertySource that overrides application.yml. */
class K2EnvironmentPostProcessorTest {

    private static final String BODY = """
        { "environment":"prod",
          "properties": { "db.url":"jdbc:from-k2", "feature.x":true } }
        """;

    private static final DeferredLogFactory LOGS = new DeferredLogFactory() {
        @Override public Log getLog(Class<?> destination) { return LogFactory.getLog(destination); }
        @Override public Log getLog(Log destination) { return destination; }
        @Override public Log getLog(Supplier<Log> destination) { return destination.get(); }
    };

    private MapPropertySource appConfig(String baseUrl, String precedence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k2.base-url", baseUrl);
        m.put("k2.token", "t");
        m.put("k2.env", "prod");
        m.put("k2.offline-cache.enabled", "false");
        if (precedence != null) m.put("k2.property-source.precedence", precedence);
        m.put("db.url", "jdbc:from-application-yaml");   // fallback that K2 should beat
        m.put("only.local", "kept");                     // no K2 value → falls through
        // The name is what K2EnvironmentPostProcessor anchors against for ABOVE_APPLICATION_YAML.
        return new MapPropertySource("Config resource 'class path resource [application.yml]'", m);
    }

    @Test
    void k2OverridesApplicationYamlByDefault() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addLast(appConfig(server.baseUrl(), null));

            new K2EnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

            assertEquals("jdbc:from-k2", env.getProperty("db.url"), "K2 should win on a name match");
            assertEquals("true", env.getProperty("feature.x"), "K2-only key should resolve");
            assertEquals("kept", env.getProperty("only.local"), "non-K2 key should fall through to yaml");
        }
    }

    @Test
    void aboveApplicationYamlLetsAnExplicitOverrideWin() throws Exception {
        try (FakeK2Server server = new FakeK2Server(BODY)) {
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addLast(appConfig(server.baseUrl(), "above-application-yaml"));
            // A higher-precedence operator override (mimics -Ddb.url=...).
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("db.url", "jdbc:operator-override");
            env.getPropertySources().addFirst(new MapPropertySource("manualOverride", override));

            new K2EnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

            assertEquals("jdbc:operator-override", env.getProperty("db.url"),
                    "in above-application-yaml mode an explicit override beats K2");
            assertEquals("true", env.getProperty("feature.x"),
                    "but K2 still beats application.yml for keys the override lacks");
        }
    }

    @Test
    void doesNothingWhenBaseUrlUnset() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("db.url", "jdbc:from-application-yaml");
        env.getPropertySources().addLast(new MapPropertySource("application", m));

        new K2EnvironmentPostProcessor(LOGS).postProcessEnvironment(env, null);

        assertEquals("jdbc:from-application-yaml", env.getProperty("db.url"),
                "no k2.base-url ⇒ SDK is inert");
    }
}
