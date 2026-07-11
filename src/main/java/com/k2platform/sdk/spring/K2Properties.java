package com.k2platform.sdk.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap + behavior settings for the K2 SDK, bound from {@code k2.*}. Because Spring's
 * environment already merges {@code application.yml}, profiles and env vars, {@code K2_BASE_URL} /
 * {@code K2_TOKEN} / {@code K2_ENV} relaxed-bind here automatically.
 *
 * <p>Only the bootstrap trio ({@code base-url}, {@code token}, {@code env}) lives in
 * {@code application.yml}; everything K2 manages comes back as a {@code PropertySource}.
 */
@ConfigurationProperties("k2")
public class K2Properties {

    /** Platform URL — the customer's own k2-app. Required to activate the SDK. */
    private String baseUrl;

    /** SDK token (the one secret) — normally from the {@code K2_TOKEN} env var. */
    private String token;

    /**
     * Path to a file containing the token, for Docker/Kubernetes secret mounts (the {@code *_FILE}
     * convention). Used only when {@link #token} is blank; from {@code K2_TOKEN_FILE}.
     */
    private String tokenFile;

    /**
     * KMS-encrypted token ciphertext ({@code K2_TOKEN_ENC}, §1b) — a cloud-only credential decrypted
     * at boot via the workload's ambient IAM {@code kms:Decrypt}. Used only when {@link #token} and
     * {@link #tokenFile} are blank. Needs the optional {@code software.amazon.awssdk:kms} dependency.
     */
    private String tokenEnc;

    /** Environment label this app reads (e.g. {@code prod}). From {@code K2_ENV}. */
    private String env;

    /** Org slug. Optional online (derived from the token server-side); required for the file source. From {@code K2_ORG}. */
    private String org;

    /**
     * App slug — the <b>k2 app slug, NOT the repo/directory name</b>. Optional for app-scoped
     * tokens; required for the file source (names {@code <app>.k2.json}). From {@code K2_APP}.
     */
    private String app;

    /** Where config is read from: {@code file}, {@code server}, or {@code auto} (default). From {@code K2_SOURCE}. */
    private Source source = Source.AUTO;

    /** Override the local file-source directory (default OS config dir). From {@code K2_CONFIG_DIR}. */
    private String configDir;

    /** Point the file source at one exact file (ignores {@link #configDir} + the {@code <app>.k2.json} rule). From {@code K2_CONFIG_FILE}. */
    private String configFile;

    private final PropertySource propertySource = new PropertySource();
    private final Cache cache = new Cache();
    private final OfflineCache offlineCache = new OfflineCache();
    private final Sts sts = new Sts();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }

    public String getTokenEnc() { return tokenEnc; }
    public void setTokenEnc(String tokenEnc) { this.tokenEnc = tokenEnc; }

    /**
     * Whether some credential is configured (plaintext, file, or KMS-envelope) — the guard used to
     * decide if the server path can run. {@link #resolveToken()} intentionally does <b>not</b>
     * decrypt {@code tokenEnc} (that happens once at client build), so this is the honest check.
     */
    public boolean hasCredential() {
        return (token != null && !token.isBlank())
                || (tokenFile != null && !tokenFile.isBlank())
                || (tokenEnc != null && !tokenEnc.isBlank());
    }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getOrg() { return org; }
    public void setOrg(String org) { this.org = org; }

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }

    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }

    /** Config resolution mode — mirrors {@link com.k2platform.sdk.K2Client.Source}. */
    public enum Source { FILE, SERVER, AUTO }

    /**
     * The effective token: {@link #token} if set, otherwise the trimmed contents of
     * {@link #tokenFile} (Docker/k8s secret mount), otherwise {@code null}.
     */
    public String resolveToken() {
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (tokenFile != null && !tokenFile.isBlank()) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Paths.get(tokenFile)).trim();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("k2.token-file '" + tokenFile + "' could not be read", e);
            }
        }
        return null;
    }

    public PropertySource getPropertySource() { return propertySource; }
    public Cache getCache() { return cache; }
    public OfflineCache getOfflineCache() { return offlineCache; }
    public Sts getSts() { return sts; }

    /** AWS STS workload-identity auth (§1a) — no bearer token; the signed identity is the credential. */
    public static class Sts {
        /** Turn on STS auth ({@code K2_STS_ENABLED}). Requires the optional awssdk auth+regions deps. */
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Mode 1 — layering K2 into Spring's Environment. */
    public static class PropertySource {
        /** Register the K2 PropertySource so K2 values override {@code application.yml}. */
        private boolean enabled = true;
        /** Where K2 sits in the precedence order on a key-name match. */
        private Precedence precedence = Precedence.HIGHEST;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Precedence getPrecedence() { return precedence; }
        public void setPrecedence(Precedence precedence) { this.precedence = precedence; }
    }

    public enum Precedence {
        /** K2 beats everything, including {@code -D} system properties and OS env. */
        HIGHEST,
        /** K2 beats {@code application.yml} but {@code -D}/OS env still override (operator escape hatch). */
        ABOVE_APPLICATION_YAML
    }

    /** TTL cache used by the {@code @K2Config} proxy (Mode 2). */
    public static class Cache {
        private long ttlSeconds = 300;
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    /** Last-known-good fallback when k2-app is unreachable. */
    public static class OfflineCache {
        private boolean enabled = true;
        /** Override the cache directory (default {@code ~/.k2/cache}, or {@code K2_CACHE_DIR}). */
        private String dir;
        /** Snapshot validity window in seconds (default 24h); {@code <= 0} means never expire. */
        private long ttlSeconds = 86_400;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }
}
