package com.k2platform.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Last-known-good config snapshot on local disk, used as a fallback when {@code k2-app}
 * is unreachable at startup (self-hosting invariant: {@code k2-app} is the customer's single
 * instance, so a client must not crash if it blips).
 *
 * <p>One file per environment under the cache directory (default {@code ~/.k2/cache}, override
 * with {@code K2_CACHE_DIR} or {@link #OfflineConfigCache(ObjectMapper, Path)}). The cache is
 * written on every successful live fetch and read only on an availability failure (transport
 * error or 5xx) — never on a 401/403/404/421, which are intentional and must surface.
 *
 * <p><b>At rest the snapshot is encrypted, signed, and TTL-bounded</b> (matching the
 * K2 SDK cache format shared across the Java/Node/Python SDKs):
 * <ul>
 *   <li><b>Encryption</b> — AES-256-GCM, so config values (db URLs, credentials, feature flags)
 *       are never written in cleartext.</li>
 *   <li><b>Integrity</b> — HMAC-SHA256 over the framed body; a truncated, corrupted, or tampered
 *       file fails verification and is refused.</li>
 *   <li><b>TTL</b> — an expiry stamp (default 24h) is embedded in the file; a snapshot past its
 *       window is refused rather than served arbitrarily stale.</li>
 *   <li><b>Token-bound keys</b> — both keys are derived from the SDK token, so rotating/revoking
 *       the token invalidates the on-disk snapshot (a revoked token can't replay a stale cache),
 *       and a snapshot written under one token is unreadable under another.</li>
 * </ul>
 *
 * <h2>File format (binary)</h2>
 * <pre>
 *   magic("K2C1")  [4]   version + framing sentinel
 *   expiresAt      [8]   big-endian epoch-millis; 0 = no expiry
 *   iv             [12]  AES-GCM nonce
 *   ciphertextLen  [4]   big-endian
 *   ciphertext     [N]   AES-256-GCM(plaintextJson)
 *   hmac           [32]  HMAC-SHA256(key, magic||expiresAt||iv||ciphertextLen||ciphertext)
 * </pre>
 */
public final class OfflineConfigCache {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private static final byte[] MAGIC = { 'K', '2', 'C', '1' };
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HMAC_LEN = 32;
    private static final int HEADER_MIN = MAGIC.length + 8 + IV_LEN + 4 + HMAC_LEN;

    /** Default snapshot TTL: 24 hours. */
    public static final long DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final ObjectMapper mapper;
    private final Path cacheDir;
    private final long ttlMillis;

    public OfflineConfigCache() {
        this(DEFAULT_MAPPER, defaultDir(), DEFAULT_TTL_MILLIS);
    }

    public OfflineConfigCache(ObjectMapper mapper, Path cacheDir) {
        this(mapper, cacheDir, DEFAULT_TTL_MILLIS);
    }

    /**
     * @param ttlMillis snapshot validity window; {@code <= 0} means never expire.
     */
    public OfflineConfigCache(ObjectMapper mapper, Path cacheDir, long ttlMillis) {
        this.mapper = mapper;
        this.cacheDir = cacheDir;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Write the resolved properties for {@code environment}, encrypted and signed with keys
     * derived from {@code token}. Best-effort: a cache write must never break the application,
     * so failures are logged to stderr and swallowed.
     */
    public void save(String environment, String token, Map<String, Object> properties) {
        if (properties == null || token == null) return;
        try {
            Files.createDirectories(cacheDir);
            applyDirPerms(cacheDir);

            byte[] plaintext = mapper.writeValueAsBytes(properties);
            byte[] aesKey = deriveKey(token, "k2-cache-aes-v1");
            byte[] hmacKey = deriveKey(token, "k2-cache-hmac-v1");

            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            byte[] ciphertext = encrypt(aesKey, iv, plaintext);
            long expiresAt = ttlMillis > 0 ? Instant.now().toEpochMilli() + ttlMillis : 0L;

            ByteBuffer body = ByteBuffer.allocate(MAGIC.length + 8 + IV_LEN + 4 + ciphertext.length);
            body.put(MAGIC);
            body.putLong(expiresAt);
            body.put(iv);
            body.putInt(ciphertext.length);
            body.put(ciphertext);

            byte[] mac = hmac(hmacKey, body.array(), 0, body.position());
            ByteBuffer out = ByteBuffer.allocate(body.position() + HMAC_LEN);
            out.put(body.array(), 0, body.position());
            out.put(mac);

            Path tmp = Files.createTempFile(cacheDir, ".k2-", ".tmp");
            Files.write(tmp, out.array());
            applyFilePerms(tmp);
            // Atomic replace so a reader never sees a half-written file.
            Files.move(tmp, fileFor(environment),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("[k2-sdk] offline cache write failed for env '" + environment
                    + "': " + e.getMessage());
        }
    }

    /**
     * Read the last-known properties for {@code environment}, verifying integrity and TTL with
     * keys derived from {@code token}. Returns {@code null} when no snapshot exists, the file is
     * truncated/tampered, the HMAC fails (corruption or wrong token), or the snapshot is past its
     * TTL — i.e. only a valid, fresh, correctly-keyed snapshot is served.
     */
    public Map<String, Object> load(String environment, String token) {
        if (token == null) return null;
        Path file = fileFor(environment);
        if (!Files.isReadable(file)) return null;
        try {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length < HEADER_MIN) {
                System.err.println("[k2-sdk] offline snapshot for env '" + environment
                        + "' is truncated; refusing");
                return null;
            }
            byte[] aesKey = deriveKey(token, "k2-cache-aes-v1");
            byte[] hmacKey = deriveKey(token, "k2-cache-hmac-v1");

            int bodyLen = raw.length - HMAC_LEN;
            byte[] expectedMac = hmac(hmacKey, raw, 0, bodyLen);
            byte[] actualMac = Arrays.copyOfRange(raw, bodyLen, raw.length);
            if (!constantTimeEquals(expectedMac, actualMac)) {
                System.err.println("[k2-sdk] offline snapshot for env '" + environment
                        + "' failed integrity check; refusing (tampered or wrong token)");
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(raw, 0, bodyLen);
            byte[] magic = new byte[MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                return null;
            }
            long expiresAt = buf.getLong();
            if (expiresAt > 0 && Instant.now().toEpochMilli() > expiresAt) {
                System.err.println("[k2-sdk] offline snapshot for env '" + environment
                        + "' is past its TTL; refusing (stale)");
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            int ctLen = buf.getInt();
            if (ctLen < 0 || ctLen > buf.remaining()) {
                return null;
            }
            byte[] ciphertext = new byte[ctLen];
            buf.get(ciphertext);

            byte[] plaintext = decrypt(aesKey, iv, ciphertext);
            return mapper.readValue(plaintext, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (GeneralSecurityException e) {
            System.err.println("[k2-sdk] offline snapshot for env '" + environment
                    + "' could not be decrypted; refusing (wrong token or corruption)");
            return null;
        } catch (IOException e) {
            System.err.println("[k2-sdk] offline cache read failed for env '" + environment
                    + "': " + e.getMessage());
            return null;
        }
    }

    /** The directory backing this cache. */
    public Path cacheDir() {
        return cacheDir;
    }

    private Path fileFor(String environment) {
        // Keep filenames filesystem-safe regardless of the env label.
        String safe = environment.replaceAll("[^A-Za-z0-9._-]", "_");
        return cacheDir.resolve("config-" + safe + ".json.enc");
    }

    // --- crypto helpers -----------------------------------------------------

    private static byte[] deriveKey(String token, String info) throws GeneralSecurityException {
        // Single HMAC-SHA256 pass over (token || info) — no BouncyCastle dep. We don't need HKDF's
        // two-stage extract-then-expand: the token is already high-entropy (k2_{base62}).
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(info.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    private static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] hmac(byte[] key, byte[] data, int offset, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data, offset, length);
        return mac.doFinal();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }

    // --- path / perms -------------------------------------------------------

    /** The default cache directory: {@code $K2_CACHE_DIR} if set, else {@code ~/.k2/cache}. */
    public static Path defaultDir() {
        String override = System.getenv("K2_CACHE_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".k2", "cache");
    }

    private static void applyDirPerms(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows / non-POSIX — skip; the dir is still user-scoped.
        }
    }

    private static void applyFilePerms(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // POSIX not supported; ignore.
        }
    }
}
