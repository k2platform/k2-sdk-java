package com.k2platform.sdk;

import java.util.Map;

/**
 * Smoke-test the SDK against a running K2 platform.
 *
 * <pre>{@code
 *   K2_BASE_URL=http://localhost:8080 \
 *   K2_TOKEN=k2_live_xxx \
 *   K2_ENV=prod \
 *   mvn -q exec:java -Dexec.mainClass=com.k2platform.sdk.K2SdkDemo
 * }</pre>
 *
 * Optional {@code K2_KEY} fetches a single property after the full read.
 */
public final class K2SdkDemo {

    public static void main(String[] args) {
        String baseUrl = env("K2_BASE_URL", "http://localhost:8080");
        String token   = env("K2_TOKEN", null);
        String envName = env("K2_ENV", "prod");
        String key     = System.getenv("K2_KEY");

        if (token == null || token.isBlank()) {
            System.err.println("Set K2_TOKEN to an SDK token minted in the admin UI (Tokens tab).");
            System.exit(2);
        }

        K2Client k2 = K2Client.builder()
                .baseUrl(baseUrl)
                .token(token)
                .build();

        System.out.println("→ GET " + baseUrl + "/api/config/token/" + envName + "/current");
        try {
            K2Configuration cfg = k2.getConfiguration(envName);
            System.out.println("✓ " + cfg.getOrganization() + "/" + cfg.getApplication()
                    + "/" + cfg.getEnvironment()
                    + "  (" + cfg.getProperties().size() + " properties, lastModified="
                    + cfg.getLastModified() + ")");
            for (Map.Entry<String, Object> e : cfg.getProperties().entrySet()) {
                K2Configuration.PropertyMetadata m = cfg.getMetadata().get(e.getKey());
                boolean secret = m != null && Boolean.TRUE.equals(m.getIsSecret());
                System.out.println("    " + e.getKey() + " = " + (secret ? "******" : e.getValue()));
            }

            if (key != null && !key.isBlank()) {
                System.out.println("→ GET property '" + key + "'");
                System.out.println("✓ " + key + " = " + k2.getProperty(envName, key));
            }
        } catch (K2Exception e) {
            System.err.println("✗ " + e.getMessage() + "  (status=" + e.getStatusCode() + ")");
            System.exit(1);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
