package com.k2platform.sdk.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down how environment variables relaxed-bind to {@code k2.*} — the SDK's public env-var contract
 * documented in the runbook. If Spring's binding rules ever change, this test catches it.
 */
class K2EnvBindingTest {

    private K2Properties bindEnv(Map<String, Object> envVars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-systemEnvironment", envVars));
        return Binder.get(env).bind("k2", K2Properties.class).orElseGet(K2Properties::new);
    }

    @Test
    void envVarsBindToK2Properties() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("K2_BASE_URL", "https://k2.acme.com");
        env.put("K2_ENV", "prod");
        env.put("K2_TOKEN", "k2_live_xyz");
        env.put("K2_TOKEN_FILE", "/run/secrets/k2/token");
        env.put("K2_PROPERTYSOURCE_PRECEDENCE", "above-application-yaml");
        env.put("K2_CACHE_TTLSECONDS", "60");
        env.put("K2_OFFLINECACHE_ENABLED", "false");

        K2Properties p = bindEnv(env);

        assertEquals("https://k2.acme.com", p.getBaseUrl());
        assertEquals("prod", p.getEnv());
        assertEquals("k2_live_xyz", p.getToken());
        assertEquals("/run/secrets/k2/token", p.getTokenFile());
        assertEquals(K2Properties.Precedence.ABOVE_APPLICATION_YAML, p.getPropertySource().getPrecedence());
        assertEquals(60, p.getCache().getTtlSeconds());
        assertEquals(false, p.getOfflineCache().isEnabled());
    }
}
