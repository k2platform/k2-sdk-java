package com.k2platform.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Human-authored, offline-first local config source (design §3b). A plaintext JSON file the
 * customer <b>places</b> on the box — the SDK reads it directly, no server contact. This is the
 * air-gapped / "drop a file and go" path, and it is <b>distinct from the managed encrypted
 * last-known-good {@link OfflineConfigCache}</b> (§3a): that one is SDK-<i>written</i>, sealed to
 * the token, and read only as a fallback; this one is operator-<i>written</i>, plaintext, and read
 * first (per {@code k2.source}).
 *
 * <h2>Layout — one file per app, per-env inside</h2>
 * <pre>
 * // &lt;configDir&gt;/&lt;app&gt;.k2.json
 * {
 *   "org": "acme",
 *   "app": "test-app",
 *   "environments": {
 *     "dev":  { "app.name": "test11",  "app.code": "ABC123", "app.status": 4 },
 *     "prod": { "app.name": "prod-11", "app.code": "XYZ999", "app.status": 1 }
 *   }
 * }
 * </pre>
 *
 * <h2>File resolution</h2>
 * <ol>
 *   <li>{@code K2_CONFIG_FILE} / an explicit {@code file} — point at one exact file.</li>
 *   <li>else {@code <configDir>/<app>.k2.json}, where {@code configDir} is (in order) the
 *       constructor {@code dir}, {@code K2_CONFIG_DIR}, or the {@linkplain #defaultDir() OS
 *       default} ({@code ~/.k2/config/} — respecting {@code $XDG_CONFIG_HOME} — on POSIX,
 *       {@code %APPDATA%\k2\config\} on Windows).</li>
 * </ol>
 *
 * <p>The document's {@code org}/{@code app} are validated against the caller's {@code K2_ORG} /
 * {@code K2_APP} so a mismatched drop-in fails <b>loudly</b> rather than silently serving the wrong
 * app's config.
 */
public final class K2ConfigFileSource {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private final ObjectMapper mapper;
    private final Path configDir;   // nullable ⇒ OS default
    private final Path configFile;  // nullable ⇒ derive from dir + app

    public K2ConfigFileSource() {
        this(DEFAULT_MAPPER, null, null);
    }

    /**
     * @param dir  override the config directory ({@code K2_CONFIG_DIR} / {@code k2.config-dir}); a
     *             blank/null value falls back to {@code K2_CONFIG_DIR} then the OS default.
     * @param file point at one exact file ({@code K2_CONFIG_FILE} / {@code k2.config-file}); when
     *             set, {@code dir} and the {@code <app>.k2.json} convention are ignored.
     */
    public K2ConfigFileSource(ObjectMapper mapper, String dir, String file) {
        this.mapper = mapper == null ? DEFAULT_MAPPER : mapper;
        this.configFile = hasText(file) ? Paths.get(file.trim()) : null;
        this.configDir = hasText(dir) ? Paths.get(dir.trim()) : null;
    }

    /**
     * Whether a config file for {@code app} exists (used by {@code k2.source=auto} to decide
     * whether to read the file before falling through to the server). With an explicit
     * {@code configFile} this ignores {@code app} and just tests that file.
     */
    public boolean hasFileFor(String app) {
        Path f = fileFor(app);
        return f != null && Files.isReadable(f);
    }

    /**
     * The resolved properties for {@code (org, app, env)} from the local file, or {@code null}
     * when the file is absent or has no block for {@code env}. Throws {@link K2Exception} when the
     * file is present but malformed, or its {@code org}/{@code app} disagree with the supplied
     * {@code expectedOrg}/{@code expectedApp} (a loud mismatch beats silently wrong config).
     *
     * @param expectedOrg {@code K2_ORG}; when non-blank it must equal the file's {@code org}.
     * @param expectedApp {@code K2_APP}; when non-blank it must equal the file's {@code app}.
     */
    public Map<String, Object> load(String expectedOrg, String expectedApp, String env) {
        Path f = fileFor(expectedApp);
        if (f == null) {
            throw new K2Exception("K2 file source needs an app name — set K2_APP / k2.app "
                    + "(the k2 app slug), or point K2_CONFIG_FILE at one file", -1);
        }
        if (!Files.isReadable(f)) {
            return null;
        }
        FileDocument doc;
        try {
            doc = mapper.readValue(Files.readAllBytes(f), FileDocument.class);
        } catch (IOException e) {
            throw new K2Exception("K2 config file '" + f + "' could not be parsed: " + e.getMessage(), e);
        }
        if (doc == null) {
            return null;
        }
        requireMatch("org", doc.org, expectedOrg, f);
        requireMatch("app", doc.app, expectedApp, f);
        if (doc.environments == null) {
            return null;
        }
        Map<String, Object> block = doc.environments.get(env);
        return block == null ? null : block;
    }

    /** The file that would be read for {@code app} (or the explicit {@code configFile}). */
    Path fileFor(String app) {
        if (configFile != null) {
            return configFile;
        }
        if (!hasText(app)) {
            return null;
        }
        Path dir = configDir != null ? configDir : defaultDir();
        String safe = app.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return dir.resolve(safe + ".k2.json");
    }

    private static void requireMatch(String field, String actual, String expected, Path f) {
        if (hasText(expected) && hasText(actual) && !actual.trim().equalsIgnoreCase(expected.trim())) {
            throw new K2Exception("K2 config file '" + f + "' " + field + "='" + actual
                    + "' does not match the configured " + field + "='" + expected
                    + "' — check K2_" + field.toUpperCase(Locale.ROOT) + " (the k2 slug, not the repo name)", -1);
        }
    }

    /**
     * OS-default config directory: {@code K2_CONFIG_DIR} if set, else
     * {@code $XDG_CONFIG_HOME/k2/config} or {@code ~/.k2/config} on POSIX, and
     * {@code %APPDATA%\k2\config} on Windows.
     */
    public static Path defaultDir() {
        String override = System.getenv("K2_CONFIG_DIR");
        if (hasText(override)) {
            return Paths.get(override.trim());
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (hasText(appData)) {
                return Paths.get(appData, "k2", "config");
            }
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (hasText(xdg)) {
            return Paths.get(xdg, "k2", "config");
        }
        return Paths.get(System.getProperty("user.home"), ".k2", "config");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class FileDocument {
        public String org;
        public String app;
        public Map<String, Map<String, Object>> environments = Collections.emptyMap();
    }
}
