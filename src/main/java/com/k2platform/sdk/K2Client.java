package com.k2platform.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal client for reading configuration from a self-hosted K2 platform.
 *
 * <p>Authenticates with an SDK token (minted in the admin UI under
 * Tokens) and reads from the token-scoped config endpoints:
 * <ul>
 *   <li>{@code GET /api/config/token/{env}/current} — full config</li>
 *   <li>{@code GET /api/config/token/{env}/properties/{key}} — single property</li>
 * </ul>
 *
 * <p>Thread-safe and cheap to keep as a singleton. Build with {@link #builder()}:
 * <pre>{@code
 * K2Client k2 = K2Client.builder()
 *         .baseUrl("http://localhost:8080")
 *         .token("k2_live_...")
 *         .build();
 *
 * K2Configuration cfg = k2.getConfiguration("prod");
 * String dbUrl = cfg.getString("db.url", "jdbc:postgresql://localhost/app");
 * }</pre>
 *
 * <p>Optional, off by default: a {@linkplain Builder#cacheTtl TTL cache} (so repeated reads —
 * e.g. from the {@code @K2Config} proxy — don't hit the network each call) and an
 * {@linkplain Builder#offlineCache offline cache} (so an unreachable {@code k2-app} at startup
 * serves the last-known-good snapshot instead of crashing the client). A {@linkplain
 * Builder#defaultEnvironment default environment} lets the Spring integration call the no-arg
 * read methods.
 */
public final class K2Client {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration requestTimeout;

    private final String defaultEnvironment;     // nullable
    private final Duration cacheTtl;              // null/zero ⇒ caching disabled
    private final OfflineConfigCache offlineCache; // nullable

    private final String organization;            // nullable — coordinates for the file source
    private final String application;             // nullable — the k2 app slug (NOT the repo name)
    private final Source source;                  // resolution mode (file|server|auto)
    private final K2ConfigFileSource fileSource;  // nullable ⇒ file source disabled
    private final StsIdentitySigner stsSigner;    // nullable ⇒ bearer-token auth (not STS)

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    /** Where {@link #getConfiguration(String)} reads from. */
    public enum Source {
        /** Always read the local {@link K2ConfigFileSource}; never contact the server. Air-gapped. */
        FILE,
        /** Always fetch from the server (with the managed offline cache as fallback). */
        SERVER,
        /** Read the file when one is present for the app, else fetch from the server. Default. */
        AUTO
    }

    private K2Client(Builder b) {
        this.source = b.source == null ? Source.AUTO : b.source;
        this.fileSource = b.fileSource;
        this.organization = b.organization;
        this.application = b.application;
        this.stsSigner = b.stsSigner;

        // baseUrl/token are not needed in FILE mode (fully offline) — only require them when a
        // server fetch is possible.
        boolean serverPossible = source != Source.FILE;
        if (serverPossible && (b.baseUrl == null || b.baseUrl.isBlank())) {
            throw new K2Exception("K2 baseUrl is required — set k2.base-url / K2_BASE_URL to your "
                    + "platform URL (e.g. http://localhost:8080 or https://k2.acme.com). "
                    + "The SDK never falls back to a vendor URL.", -1);
        }
        this.baseUrl = b.baseUrl == null ? null : stripTrailingSlash(b.baseUrl);
        // A bearer token is required for a server fetch UNLESS STS auth is configured (the signed
        // GetCallerIdentity is the credential) or we're in FILE mode.
        if (serverPossible && stsSigner == null) {
            this.token = Objects.requireNonNull(b.token,
                    "K2 token is required — supply the SDK token via K2_TOKEN (minted in the admin UI, Tokens tab)");
        } else {
            this.token = b.token; // may be null in FILE / STS mode
        }
        this.requestTimeout = b.requestTimeout;
        this.defaultEnvironment = b.defaultEnvironment;
        this.cacheTtl = b.cacheTtl;
        this.offlineCache = b.offlineCache;
        this.http = HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Full resolved configuration for {@code environment} (e.g. {@code "prod"}).
     *
     * <p>Resolution order (design §3c): <b>local file source → server → managed offline cache →
     * (caller defaults)</b>. In {@link Source#FILE} mode the file is authoritative and the server
     * is never contacted; in {@link Source#AUTO} the file is used only when one is present for the
     * app. On a live fetch the managed offline cache (if enabled) is refreshed; on an availability
     * failure (transport error or 5xx) the last-known snapshot is returned when one exists.
     * Auth/not-found errors (401/403/404/421) always surface — they are not availability blips.
     */
    public K2Configuration getConfiguration(String environment) {
        Map<String, Object> fromFile = resolveFromFile(environment);
        if (fromFile != null) {
            return K2Configuration.fromFile(organization, application, environment, fromFile);
        }

        if (stsSigner != null) {
            // STS auth: no static token, so the token-sealed offline cache doesn't apply (STS is
            // an online/cloud credential). The file source above still provides an offline path.
            return sendSts(stsCurrentPath(environment), K2Configuration.class);
        }

        String path = currentPath(environment);
        try {
            K2Configuration cfg = send(path, K2Configuration.class);
            // Offline cache is un-gated (all tiers) — write the last-known-good snapshot on every
            // successful fetch. The server's offlineCacheAllowed flag is ignored (see §0).
            if (offlineCache != null && cfg != null) {
                offlineCache.save(environment, token, cfg.getProperties());
            }
            return cfg;
        } catch (K2Exception e) {
            if (offlineCache != null && isAvailabilityError(e)) {
                Map<String, Object> cached = offlineCache.load(environment, token);
                if (cached != null) {
                    return K2Configuration.fromProperties(environment, cached);
                }
            }
            throw e;
        }
    }

    /**
     * Properties for {@code environment} from the local file source, honoring {@link #source}:
     * {@code null} when the file source is off, or (in {@link Source#AUTO}) no file is present.
     * In {@link Source#FILE} mode a missing file/block is a hard {@link K2Exception} — the caller
     * asked to run fully offline, so falling through to the server would be wrong.
     */
    private Map<String, Object> resolveFromFile(String environment) {
        if (fileSource == null || source == Source.SERVER) {
            return null;
        }
        if (source == Source.AUTO && !fileSource.hasFileFor(application)) {
            return null;
        }
        Map<String, Object> props = fileSource.load(organization, application, environment);
        if (props == null && source == Source.FILE) {
            throw new K2Exception("K2 source=file but no config for env '" + environment
                    + "' in " + fileSource.fileFor(application)
                    + " — add an 'environments." + environment + "' block", -1);
        }
        return props;
    }

    /** Full configuration for the configured {@linkplain Builder#defaultEnvironment default environment}. */
    public K2Configuration getConfiguration() {
        return getConfiguration(requireDefaultEnv());
    }

    /**
     * Configuration for {@code environment} served from the TTL cache when fresh, otherwise
     * re-fetched. If {@code cacheTtl} was not configured this is equivalent to {@link
     * #getConfiguration(String)}. The cache holds the last value through a failed refresh.
     */
    public K2Configuration getCachedConfiguration(String environment) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return getConfiguration(environment);
        }
        long now = System.nanoTime();
        CachedEntry entry = cache.get(environment);
        if (entry != null && now < entry.expiresAtNanos) {
            return entry.config;
        }
        try {
            K2Configuration fresh = getConfiguration(environment);
            cache.put(environment, new CachedEntry(fresh, now + cacheTtl.toNanos()));
            return fresh;
        } catch (K2Exception e) {
            if (entry != null) {
                return entry.config; // serve stale rather than fail a live request
            }
            throw e;
        }
    }

    /** Cached configuration for the configured default environment. */
    public K2Configuration getCachedConfiguration() {
        return getCachedConfiguration(requireDefaultEnv());
    }

    /**
     * The resolved property map for {@code environment} — the shape the Spring PropertySource
     * layers into the environment. Applies the same offline-cache fallback as {@link
     * #getConfiguration(String)}.
     */
    public Map<String, Object> snapshot(String environment) {
        return getConfiguration(environment).getProperties();
    }

    /**
     * Single property value for {@code key} in {@code environment}, or {@code null}
     * if the property is unset.
     */
    public Object getProperty(String environment, String key) {
        Map<String, Object> fromFile = resolveFromFile(environment);
        if (fromFile != null) {
            return fromFile.get(key);
        }
        if (stsSigner != null) {
            String stsPath = "/api/config/sts/" + enc(requireAppForSts()) + "/" + enc(environment)
                    + "/properties/" + enc(key);
            @SuppressWarnings("unchecked")
            Map<String, Object> stsBody = sendSts(stsPath, Map.class);
            return stsBody == null ? null : stsBody.get("value");
        }
        String path = propertyPath(environment, key);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = send(path, Map.class);
        return body == null ? null : body.get("value");
    }

    /** String convenience wrapper over {@link #getProperty}. */
    public String getString(String environment, String key, String defaultValue) {
        Object v = getProperty(environment, key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    /** The default environment this client was built with, or {@code null} if none. */
    public String defaultEnvironment() {
        return defaultEnvironment;
    }

    private String requireDefaultEnv() {
        if (defaultEnvironment == null || defaultEnvironment.isBlank()) {
            throw new K2Exception("No default environment configured — set k2.env / K2_ENV, "
                    + "or call the (String environment) overload.", -1);
        }
        return defaultEnvironment;
    }

    private static boolean isAvailabilityError(K2Exception e) {
        return e.getStatusCode() == -1 || e.getStatusCode() >= 500;
    }

    private <T> T send(String path, Class<T> type) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-API-Token", token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new K2Exception("K2 request to " + path + " failed: " + e.getMessage(), e);
        }
        return parse(path, resp, type);
    }

    /** POST a signed {@code sts:GetCallerIdentity} envelope (§1a) and parse the config response. */
    private <T> T sendSts(String path, Class<T> type) {
        StsIdentitySigner.SignedStsRequest signed = stsSigner.signGetCallerIdentity();
        String json;
        try {
            json = MAPPER.writeValueAsString(signed);
        } catch (Exception e) {
            throw new K2Exception("Failed to serialize the signed STS request: " + e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new K2Exception("K2 STS request to " + path + " failed: " + e.getMessage(), e);
        }
        return parse(path, resp, type);
    }

    private <T> T parse(String path, HttpResponse<String> resp, Class<T> type) {
        int code = resp.statusCode();
        if (code == 401 || code == 403) {
            throw new K2Exception("K2 credential rejected (HTTP " + code + ") for " + path
                    + " — check the token/identity and its scope", code);
        }
        if (code == 404) {
            throw new K2Exception("K2 config not found (HTTP 404) for " + path, code);
        }
        if (code == 421) {
            throw new K2Exception("K2 rejected the request host (HTTP 421) for " + baseUrl
                    + " — baseUrl host is not licensed; it must match the platform's public host "
                    + "(K2_PUBLIC_HOST). See LICENSE_BINDING_DESIGN.md.", code);
        }
        if (code < 200 || code >= 300) {
            throw new K2Exception("K2 request to " + path + " failed: HTTP " + code
                    + " — " + truncate(resp.body()), code);
        }
        try {
            return MAPPER.readValue(resp.body(), type);
        } catch (Exception e) {
            throw new K2Exception("Failed to parse K2 response from " + path + ": " + e.getMessage(), e);
        }
    }

    private String stsCurrentPath(String environment) {
        return "/api/config/sts/" + enc(requireAppForSts()) + "/" + enc(environment) + "/current";
    }

    private String requireAppForSts() {
        if (application == null || application.isBlank()) {
            throw new K2Exception("STS auth is org-scoped — set K2_APP / k2.app (the k2 app slug) so "
                    + "the client can name the app it reads. See §2b.", -1);
        }
        return application;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private String token;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private String defaultEnvironment;
        private Duration cacheTtl;
        private OfflineConfigCache offlineCache;
        private String organization;
        private String application;
        private Source source = Source.AUTO;
        private K2ConfigFileSource fileSource;
        private String tokenEnc;
        private TokenDecryptor tokenDecryptor;
        private StsIdentitySigner stsSigner;

        /** Platform base URL, e.g. {@code http://localhost:8080} or {@code https://k2.acme.com}. Required (except in {@link Source#FILE} mode). */
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        /** SDK token minted in the admin UI (Tokens tab). Required (except in {@link Source#FILE} mode). */
        public Builder token(String token) { this.token = token; return this; }

        /** Org slug (from {@code k2.org} / {@code K2_ORG}). Coordinates for the local file source. */
        public Builder organization(String organization) { this.organization = organization; return this; }

        /** App slug (from {@code k2.app} / {@code K2_APP}) — the k2 app slug, NOT the repo/directory name. */
        public Builder application(String application) { this.application = application; return this; }

        /** Resolution mode: {@code file|server|auto} (default {@code auto}). See {@link Source}. */
        public Builder source(Source source) { this.source = source; return this; }

        /** Attach a local {@link K2ConfigFileSource} (§3b) — the offline-first, human-authored source. */
        public Builder fileSource(K2ConfigFileSource fileSource) { this.fileSource = fileSource; return this; }

        /**
         * KMS-encrypted token ciphertext ({@code K2_TOKEN_ENC}, §1b). Used only when {@link #token}
         * is unset; decrypted once at {@link #build()} via {@link #tokenDecryptor}. Cloud-only.
         */
        public Builder tokenEnc(String tokenEnc) { this.tokenEnc = tokenEnc; return this; }

        /** Decryptor for {@link #tokenEnc} (defaults to {@link KmsTokenDecryptor}). Injectable for tests. */
        public Builder tokenDecryptor(TokenDecryptor tokenDecryptor) { this.tokenDecryptor = tokenDecryptor; return this; }

        /**
         * Authenticate with an AWS STS workload identity (§1a) instead of a bearer token. When set,
         * no token is required; the SDK posts a signed {@code sts:GetCallerIdentity} to k2. STS is
         * org-scoped, so {@link #application} (K2_APP) must be set. Use {@link AwsStsIdentitySigner}.
         */
        public Builder stsSigner(StsIdentitySigner stsSigner) { this.stsSigner = stsSigner; return this; }

        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }

        public Builder requestTimeout(Duration d) { this.requestTimeout = d; return this; }

        /** Environment used by the no-arg read methods (from {@code k2.env} / {@code K2_ENV}). */
        public Builder defaultEnvironment(String environment) {
            this.defaultEnvironment = environment; return this;
        }

        /** Enable TTL caching of {@code getCachedConfiguration}. Null/zero disables it. */
        public Builder cacheTtl(Duration ttl) { this.cacheTtl = ttl; return this; }

        /** Attach an offline cache for last-known-good fallback when {@code k2-app} is unreachable. */
        public Builder offlineCache(OfflineConfigCache cache) { this.offlineCache = cache; return this; }

        /** Convenience: enable the default ({@code ~/.k2/cache}) offline cache. */
        public Builder offlineCacheEnabled(boolean enabled) {
            this.offlineCache = enabled ? new OfflineConfigCache() : null; return this;
        }

        public K2Client build() {
            // Resolve K2_TOKEN_ENC → plaintext once, when a server fetch is possible and no explicit
            // token was given. FILE mode is fully offline, so it never touches KMS.
            if ((token == null || token.isBlank()) && tokenEnc != null && !tokenEnc.isBlank()
                    && source != Source.FILE) {
                TokenDecryptor decryptor = tokenDecryptor != null ? tokenDecryptor : new KmsTokenDecryptor();
                token = decryptor.decrypt(tokenEnc);
            }
            return new K2Client(this);
        }
    }

    private record CachedEntry(K2Configuration config, long expiresAtNanos) {}

    /**
     * Read path for {@code environment}. When {@code application} (K2_APP) is set the SDK uses the
     * client-named-app contract {@code /token/{app}/{env}/current} (required by org-scoped tokens,
     * accepted by app-scoped tokens); otherwise the app is implied by the token
     * ({@code /token/{env}/current}). See §2b.
     */
    private String currentPath(String environment) {
        return application == null || application.isBlank()
                ? "/api/config/token/" + enc(environment) + "/current"
                : "/api/config/token/" + enc(application) + "/" + enc(environment) + "/current";
    }

    private String propertyPath(String environment, String key) {
        return application == null || application.isBlank()
                ? "/api/config/token/" + enc(environment) + "/properties/" + enc(key)
                : "/api/config/token/" + enc(application) + "/" + enc(environment) + "/properties/" + enc(key);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
