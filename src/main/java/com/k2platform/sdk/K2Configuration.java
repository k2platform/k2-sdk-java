package com.k2platform.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Resolved configuration for one (application, environment), as returned by
 * {@code GET /api/config/token/{env}/current}. Mirrors the platform's
 * {@code ConfigurationResponseDto} shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class K2Configuration {

    private String organization;
    private String application;
    private String environment;
    private Map<String, Object> properties;
    private Map<String, PropertyMetadata> metadata;
    private Instant lastModified;
    private Integer propertyCount;
    private Boolean offlineCacheAllowed;

    public String getOrganization()                    { return organization; }
    public String getApplication()                     { return application; }
    public String getEnvironment()                     { return environment; }
    public Instant getLastModified()                   { return lastModified; }
    public Integer getPropertyCount()                  { return propertyCount; }

    /**
     * Whether the server permits the offline cache (paid-tier feature). {@code null} when the
     * server didn't send the flag (older server) — the SDK then honors its own setting.
     */
    public Boolean getOfflineCacheAllowed()            { return offlineCacheAllowed; }

    public Map<String, Object> getProperties() {
        return properties == null ? Collections.emptyMap() : properties;
    }

    public Map<String, PropertyMetadata> getMetadata() {
        return metadata == null ? Collections.emptyMap() : metadata;
    }

    /**
     * Build a configuration from a raw properties map — used to reconstitute an
     * offline-cache snapshot when the live fetch fails. Metadata/timestamps are not cached.
     */
    public static K2Configuration fromProperties(String environment, Map<String, Object> properties) {
        K2Configuration cfg = new K2Configuration();
        cfg.environment = environment;
        cfg.properties = properties;
        cfg.propertyCount = properties == null ? 0 : properties.size();
        return cfg;
    }

    /**
     * Build a configuration read from a local {@link K2ConfigFileSource} (§3b), carrying the
     * org/app coordinates the file names so {@link #toString()} and callers see the full slice.
     */
    public static K2Configuration fromFile(String organization, String application,
                                           String environment, Map<String, Object> properties) {
        K2Configuration cfg = fromProperties(environment, properties);
        cfg.organization = organization;
        cfg.application = application;
        return cfg;
    }

    /** Raw value for {@code key}, or {@code null} if absent. */
    public Object get(String key) {
        return getProperties().get(key);
    }

    /** String value for {@code key}, or {@code defaultValue} if absent. */
    public String getString(String key, String defaultValue) {
        Object v = get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    /** Integer value for {@code key}, or {@code defaultValue} if absent or unparseable. */
    public int getInt(String key, int defaultValue) {
        Object v = get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? defaultValue : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Boolean value for {@code key}, or {@code defaultValue} if absent. */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? defaultValue : Boolean.parseBoolean(String.valueOf(v).trim());
    }

    @Override
    public String toString() {
        return "K2Configuration{" + organization + "/" + application + "/" + environment
                + ", properties=" + getProperties().keySet() + "}";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PropertyMetadata {
        private String type;
        private Boolean isSecret;
        private String description;

        public String getType()        { return type; }
        public Boolean getIsSecret()   { return isSecret; }
        public String getDescription() { return description; }
    }
}
