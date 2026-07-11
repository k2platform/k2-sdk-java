# K2 Java SDK — User Manual

A client library for reading token-scoped configuration from a **self-hosted K2 / KeyKosh
platform** (your own `k2-app`). One jar, one mandatory runtime dependency (Jackson), HTTP over the
JDK's `java.net.http`. **Spring support is optional** and ships in the same jar — plain-JVM apps
never pull Spring transitively. **Java 17+** (built and tested on 21).

The SDK talks to **exactly one host — your own platform**. There is no vendor default URL and no
callback home: `baseUrl` is always required, and nothing is ever sent to KeyKosh infrastructure.

- **Coordinates:** `com.k2platform:k2-sdk-java:1.0.0`
- **License:** MIT
- **API contract:** `GET /api/config/token/{env}/**` (SDK-token authenticated)

---

## Table of contents

1. [Installation](#1-installation)
2. [Quick start](#2-quick-start)
3. [Usage modes](#3-usage-modes)
   - [Mode 1 — Spring `PropertySource`](#mode-1--spring-propertysource-replaces-applicationyml)
   - [Mode 2 — `@K2Config` typed proxy](#mode-2--k2config-typed-proxy-live-per-call)
   - [Mode 3 — core `K2Client`](#mode-3--core-k2client-plain-jvm)
4. [Configuration reference](#4-configuration-reference)
5. [Offline cache](#5-offline-cache)
6. [Error handling](#6-error-handling)
7. [Troubleshooting](#7-troubleshooting)
8. [API mapping](#8-api-mapping)

---

## 1. Installation

### Maven

```xml
<dependency>
  <groupId>com.k2platform</groupId>
  <artifactId>k2-sdk-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.k2platform:k2-sdk-java:1.0.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'com.k2platform:k2-sdk-java:1.0.0'
```

The SDK pulls in `jackson-databind` and `jackson-datatype-jsr310` transitively. The two
`spring-boot*` dependencies are declared `<optional>true</optional>` — they are **not** resolved
into a plain-JVM app. In a Spring Boot app the Spring classes are already present, which is what
activates Modes 1 and 2 automatically.

### Getting a token

Every read is authenticated by an **SDK token** scoped to one app + environment. Mint one in the
admin UI: log in → workspace → **Tokens** → generate (choose the app and env). Treat it like a
password — inject it via env var / secret mount, never commit it.

---

## 2. Quick start

```java
import com.k2platform.sdk.K2Client;
import com.k2platform.sdk.K2Configuration;

K2Client k2 = K2Client.builder()
        .baseUrl("http://localhost:8080")   // your platform — https://k2.acme.com in prod
        .token(System.getenv("K2_TOKEN"))   // SDK token from the admin UI
        .build();

K2Configuration cfg = k2.getConfiguration("prod");
String  dbUrl = cfg.getString("db.url", "jdbc:postgresql://localhost/app");
int     pool  = cfg.getInt("db.pool", 10);
boolean flag  = cfg.getBoolean("feature.x", false);
```

`K2Client` is thread-safe and cheap to hold as a singleton.

---

## 3. Usage modes

| Mode | For | What you write |
|---|---|---|
| **1. PropertySource** (Spring) | Spring Boot apps — *replace `application.yml`* | nothing in code; a few bootstrap props |
| **2. `@K2Config` proxy** (Spring) | typed, live-refreshing config beans | one interface |
| **3. Core `K2Client`** | plain JVM / batch / non-Spring | direct calls |

### Mode 1 — Spring `PropertySource` (replaces `application.yml`)

K2 becomes a high-precedence Spring `PropertySource`. Add the dependency and set the bootstrap
values; every existing `@Value("${...}")` / `@ConfigurationProperties` resolves **from K2 first**,
with `application.yml` as fallback. **No code change.**

```yaml
# application.yml — now just bootstrap + fallback defaults
k2:
  base-url: http://localhost:8080      # https://k2.acme.com in prod
  env: prod
  property-source:
    precedence: highest                # highest = beats application.yml AND -D/OS env
                                       # above-application-yaml = operators keep -D/env override
db:
  url: jdbc:postgresql://localhost/app # FALLBACK only — K2's "db.url" wins on a name match
```

```bash
export K2_TOKEN=k2_live_...            # the one secret — never commit it
```

```java
@Service
class MyService {
    @Value("${db.url}") String dbUrl;  // resolved FROM K2 at startup; falls back to yaml
}
```

The bootstrap trio can come from `application.yml` **or** the env vars `K2_BASE_URL` / `K2_TOKEN` /
`K2_ENV` (Spring relaxed binding). No separate `k2.yml`, and you never declare an app name — the
token carries the app + env scope.

**Precedence:**
- `highest` (default) — K2 beats everything, including `-D` system properties and OS env.
- `above-application-yaml` — K2 beats `application.yml`, but `-D`/OS env still override (an
  operator escape hatch).

**Startup safety:** Mode 1 runs in an `EnvironmentPostProcessor` **before** beans exist. If
`k2-app` is unreachable at startup it never blocks boot — it falls back to the offline-cache
snapshot, then to `application.yml` defaults.

**Hot-reload note:** `@Value` / `@ConfigurationProperties` bind **once** at startup. To pick up a
live change without a context refresh, use Mode 2.

### Mode 2 — `@K2Config` typed proxy (live per-call)

For the handful of properties you need live without a refresh. Each interface method reads through
the client's TTL cache on **every call**, so values stay current within the TTL window.

```java
@K2Config(prefix = "feature")
public interface FeatureFlags {
    @K2ConfigProperty(key = "x", defaultValue = "false")
    boolean x();                       // reads the K2 cache on every call
}

@SpringBootApplication
@EnableK2Config(basePackages = "com.acme.config")
public class MyApp { }
```

```java
@Service
class Gate {
    private final FeatureFlags flags;
    Gate(FeatureFlags flags) { this.flags = flags; }   // injected like any bean
}
```

**Method → key resolution:** explicit `key()`, else `getXxx` / `isXxx` → `xxx`, else camelCase →
dotted (`maxRetries` → `max.retries`); then prefixed with `@K2Config.prefix`. Return types
`String` / `int` / `long` / `boolean` / `double` (and their boxed forms) are coerced;
`defaultValue` applies when the key is absent (for a primitive with no default you get the Java
zero value; for a `String` with no default you get `null`).

`@EnableK2Config` takes either `basePackages` or `basePackageClasses`; with neither, the annotated
class's own package is scanned. Mode 2 requires a `K2Client` bean, which is auto-configured as soon
as `k2.base-url` is set (see Mode 1's bootstrap props).

### Mode 3 — core `K2Client` (plain JVM)

No Spring required.

```java
K2Client k2 = K2Client.builder()
        .baseUrl("http://localhost:8080")
        .token("k2_live_...")
        .defaultEnvironment("prod")          // optional — enables the no-arg read methods
        .cacheTtl(Duration.ofSeconds(300))   // optional — TTL cache for getCachedConfiguration()
        .offlineCacheEnabled(true)           // optional — last-known-good fallback (~/.k2/cache)
        .connectTimeout(Duration.ofSeconds(5))
        .requestTimeout(Duration.ofSeconds(10))
        .build();

// Full config for an environment (live fetch)
K2Configuration cfg = k2.getConfiguration("prod");

// TTL-cached read (falls back to a live fetch if no TTL was set)
K2Configuration cached = k2.getCachedConfiguration("prod");

// Raw property map (what the Spring PropertySource layers in)
Map<String,Object> props = k2.snapshot("prod");

// Single property
Object value = k2.getProperty("prod", "db.url");
String s     = k2.getString("prod", "db.url", "jdbc:postgresql://localhost/app");

// No-arg overloads use defaultEnvironment() if configured
K2Configuration def = k2.getConfiguration();
```

The token is sent as **both** `Authorization: Bearer <token>` **and** `X-API-Token: <token>`, so it
works regardless of which header your platform build expects. The environment scope is enforced
server-side.

---

## 4. Configuration reference

### `K2Client.Builder` options (Mode 3)

| Builder method | Type | Default | Meaning |
|---|---|---|---|
| `baseUrl(String)` | String | — (**required**) | Platform URL, e.g. `http://localhost:8080` or `https://k2.acme.com`. Trailing slash stripped. No vendor fallback. |
| `token(String)` | String | — (**required**) | SDK token from the admin UI (Tokens tab). |
| `defaultEnvironment(String)` | String | `null` | Environment used by the no-arg read methods. |
| `connectTimeout(Duration)` | Duration | `5s` | TCP connect timeout. |
| `requestTimeout(Duration)` | Duration | `10s` | Per-request timeout. |
| `cacheTtl(Duration)` | Duration | `null` (off) | TTL for `getCachedConfiguration()`. Null/zero/negative disables it. |
| `offlineCache(OfflineConfigCache)` | object | `null` | Attach a custom offline cache (dir + TTL). |
| `offlineCacheEnabled(boolean)` | boolean | `false` | Convenience: enable the default `~/.k2/cache` offline cache. |

### Spring properties (Modes 1 & 2), bound from `k2.*`

| Property | Env var (relaxed) | Default | Meaning |
|---|---|---|---|
| `k2.base-url` | `K2_BASE_URL` | — | Platform URL. **Required** to activate the SDK. |
| `k2.token` | `K2_TOKEN` | — | SDK token (the one secret). |
| `k2.token-file` | `K2_TOKEN_FILE` | — | Path to a file holding the token (Docker/k8s secret mount, the `*_FILE` convention). Used only when `k2.token` is blank. |
| `k2.env` | `K2_ENV` | — | Environment label this app reads (e.g. `prod`). |
| `k2.property-source.enabled` | — | `true` | Register the Mode 1 PropertySource. |
| `k2.property-source.precedence` | — | `highest` | `highest` or `above-application-yaml`. |
| `k2.cache.ttl-seconds` | — | `300` | TTL cache used by the `@K2Config` proxy (Mode 2). |
| `k2.offline-cache.enabled` | — | `true` | Enable last-known-good fallback. |
| `k2.offline-cache.dir` | — | `~/.k2/cache` (or `K2_CACHE_DIR`) | Override the cache directory. |
| `k2.offline-cache.ttl-seconds` | — | `86400` (24h) | Snapshot validity window; `<= 0` = never expire. |

> **Spring vs core defaults differ deliberately.** Under Spring the TTL cache (300s) and the offline
> cache (on) default *active*, since a long-lived web app benefits from both. The bare `K2Client`
> defaults them *off* so a script does exactly one predictable thing unless you opt in.

### Environment variables read directly (no Spring)

| Env var | Used by | Meaning |
|---|---|---|
| `K2_CACHE_DIR` | `OfflineConfigCache.defaultDir()` | Overrides the default `~/.k2/cache` cache directory. |
| `K2_BASE_URL`, `K2_TOKEN`, `K2_ENV`, `K2_KEY` | `K2SdkDemo` | The bundled smoke-test main class. |

---

## 5. Offline cache

The offline cache stores a **last-known-good** snapshot per environment on local disk, so an
unreachable `k2-app` at startup serves the previous config instead of crashing the client (the
self-hosting invariant: `k2-app` is the customer's single instance, so a blip must not take apps
down).

**When it is written/read:**
- **Written** on every *successful* live fetch of `getConfiguration(env)`.
- **Read** *only* on an **availability failure** — a transport error or a `5xx` response. Intentional
  errors (`401` / `403` / `404` / `421`) always surface; they are never masked by a stale snapshot.

**At rest the snapshot is encrypted, signed, and TTL-bounded** (the `K2C1` on-disk format, byte-
compatible across the Java / Node / Python SDKs — do not modify it):

- **Encryption** — AES-256-GCM, so config values (db URLs, credentials, flags) are never cleartext.
- **Integrity** — HMAC-SHA256 over the framed body; a truncated, corrupted, or tampered file fails
  verification and is refused.
- **TTL** — an embedded expiry stamp (default 24h); a snapshot past its window is refused rather
  than served arbitrarily stale.
- **Token-bound keys** — both keys are derived from the SDK token, so rotating/revoking the token
  invalidates the on-disk snapshot and a snapshot written under one token is unreadable under
  another.

**File location:** `<cacheDir>/config-<env>.json.enc`, where `cacheDir` is `k2.offline-cache.dir`
→ `K2_CACHE_DIR` → `~/.k2/cache`. The cache directory is created `rwx------` and files `rw-------`
on POSIX systems. Cache writes are best-effort: a failure is logged to stderr and swallowed — it
never breaks the application.

### The server-driven gate: `offlineCacheAllowed`

The offline cache is a **paid-tier feature**. On each full-config response the server may include an
`offlineCacheAllowed` flag:

- `false` → the SDK **skips the cache write** even if you enabled it locally (a FREE-tier server
  turns the cache off client-side).
- `true` or **absent/`null`** (older server) → the SDK honors its own configured setting.

You still control whether the cache is *enabled* at all via the builder / Spring property; the flag
only ever *suppresses* writing, it never forces it on.

---

## 6. Error handling

All read failures throw `com.k2platform.sdk.K2Exception` (an unchecked `RuntimeException`).
`getStatusCode()` carries the HTTP status, or `-1` for a transport-level failure.

| `getStatusCode()` | Meaning | Availability error? (offline-cache eligible) |
|---|---|---|
| `401` / `403` | Token rejected — bad token or wrong environment scope. | No — always surfaces. |
| `404` | Config (or property) not found. | No — always surfaces. |
| `421` | `baseUrl` host is not licensed; it must match the platform's `K2_PUBLIC_HOST` (see `LICENSE_BINDING_DESIGN.md`). | No — always surfaces. |
| `5xx` | Server error. | **Yes** — offline snapshot served if available. |
| `-1` | Transport failure (connection refused, timeout, DNS, malformed body). | **Yes** — offline snapshot served if available. |

```java
try {
    K2Configuration cfg = k2.getConfiguration("prod");
    // ...
} catch (K2Exception e) {
    switch (e.getStatusCode()) {
        case 401, 403 -> log.error("K2 token rejected: {}", e.getMessage());
        case 404      -> log.warn("No config for this env yet");
        case 421      -> log.error("baseUrl host not licensed by the platform");
        default       -> log.error("K2 unavailable ({}): {}", e.getStatusCode(), e.getMessage());
    }
}
```

**Behavioral notes:**
- `getCachedConfiguration(env)` serves the last cached value through a failed refresh rather than
  throwing, if a value was previously cached in the TTL cache.
- A missing/blank `baseUrl` or `token` throws immediately at `build()` time (status `-1`).
- Calling a no-arg read method without a `defaultEnvironment` throws (status `-1`).

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `K2 baseUrl is required …` at startup | `k2.base-url` / `K2_BASE_URL` unset. | Set your platform URL. There is no vendor default. |
| `K2 token is required …` | `k2.token` / `K2_TOKEN` (or `k2.token-file`) unset. | Mint a token in the admin UI and inject it. |
| Mode 1: log says *"k2.base-url is set but k2.token / k2.env is missing — skipping"* | Bootstrap trio incomplete. | Set `K2_TOKEN` (or `K2_TOKEN_FILE`) **and** `k2.env`. |
| `@Value` still shows the `application.yml` value | Mode 1 disabled, precedence lowered, or key name mismatch. | Confirm `k2.property-source.enabled=true`, `precedence: highest`, and the K2 key name matches `${...}` exactly. |
| `@K2Config` bean not found | Interface not scanned. | Ensure `@EnableK2Config` covers the interface's package; the interface must be annotated `@K2Config`. |
| Live changes not picked up | Using Mode 1 (binds once) or a long TTL. | Use Mode 2 for live reads; lower `k2.cache.ttl-seconds`. |
| `HTTP 421` | `baseUrl` host isn't the platform's licensed public host. | Point `baseUrl` at the exact `K2_PUBLIC_HOST` the platform is licensed for. |
| `offline snapshot … failed integrity check` (stderr) | Snapshot written under a different token, or the file was tampered. | Expected after a token rotation — it self-heals on the next successful fetch. |
| `offline snapshot … is past its TTL` (stderr) | Snapshot older than `k2.offline-cache.ttl-seconds`. | Expected; raise the TTL if you need a longer offline window. |
| Offline cache never writes | Server sent `offlineCacheAllowed=false` (FREE tier), or the cache is disabled. | The offline cache is a paid-tier feature; upgrade the platform tier or enable it locally on a paid server. |

### Smoke test against a running platform

```bash
K2_BASE_URL=http://localhost:8080 \
K2_TOKEN=k2_live_xxx \
K2_ENV=prod \
mvn -q exec:java -Dexec.mainClass=com.k2platform.sdk.K2SdkDemo
# add K2_KEY=some.property to also fetch a single property
```

---

## 8. API mapping

| SDK call | Endpoint |
|---|---|
| `getConfiguration(env)` / `getCachedConfiguration(env)` / `snapshot(env)` | `GET /api/config/token/{env}/current` |
| `getProperty(env, key)` / `getString(env, key, default)` | `GET /api/config/token/{env}/properties/{key}` |

The response of `/current` maps to `K2Configuration` (`organization`, `application`, `environment`,
`properties`, `metadata`, `lastModified`, `propertyCount`, `offlineCacheAllowed`). Unknown fields
are ignored, so a newer server never breaks an older SDK.
