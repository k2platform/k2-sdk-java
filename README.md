# KeyKosh Java SDK (`com.k2platform:k2-sdk-java`)

A client for reading configuration from a **self-hosted KeyKosh (K2) platform**. The core has one runtime
dependency (Jackson) and uses the JDK's `java.net.http`. **Spring support is optional** and lives in
the same jar — plain-JVM apps never pull Spring transitively. Java 17+.

The SDK talks to **exactly one host — your own `k2-app`**. There is no vendor default URL and no
callback home; `baseUrl` is required.

## Install

```xml
<dependency>
  <groupId>com.k2platform</groupId>
  <artifactId>k2-sdk-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Three ways to use it

| Mode | For | What you write |
|---|---|---|
| **1. PropertySource** (Spring) | Spring Boot apps — *replace `application.yml`* | nothing in code; 2 bootstrap props |
| **2. `@K2Config` proxy** (Spring) | typed, live-refreshing config beans | one interface |
| **3. Core `K2Client`** | plain JVM / batch / non-Spring | direct calls |

---

## Mode 1 — K2 as a high-precedence Spring `PropertySource` (the headline)

K2 *replaces* `application.yml` as the source of truth. Just add the dependency and set the bootstrap
trio; every existing `@Value("${...}")` / `@ConfigurationProperties` resolves **from K2 first**, with
`application.yml` as fallback. **No code change.**

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
    @Value("${db.url}") String dbUrl;  // ← resolved FROM K2 at startup; falls back to yaml
}
```
**Hot-reload note:** `@Value`/`@ConfigurationProperties` bind once at startup. To pick up a live change
without a context refresh, use Mode 2. If `k2-app` is unreachable at startup, Mode 1 boots from the
offline cache snapshot (then `application.yml` defaults) — it never blocks boot.

## Mode 2 — `@K2Config` typed proxy (live per-call)

For the handful of properties you need live without a refresh:

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
Method→key: explicit `key()`, else `getXxx`/`isXxx` → `xxx`, else camelCase → dotted; prefixed with
`@K2Config.prefix`. Return types `String`/`int`/`long`/`boolean`/`double` are coerced; `defaultValue`
applies when the key is absent.

In both Spring modes the bootstrap props bind from your existing `application.yml` (and `K2_BASE_URL`/
`K2_TOKEN`/`K2_ENV` env vars via Spring relaxed binding) — **no separate `k2.yml`**, and you never
declare an app name (the token carries app+env scope).

---

## Mode 3 — core `K2Client` (plain JVM, no Spring)

```java
K2Client k2 = K2Client.builder()
        .baseUrl("http://localhost:8080")   // your K2 URL — https://k2.acme.com in prod
        .token("k2_live_...")               // SDK token from the admin UI (Tokens tab)
        .build();

// Full config for an environment
K2Configuration cfg = k2.getConfiguration("prod");
String dbUrl = cfg.getString("db.url", "jdbc:postgresql://localhost/app");
int    pool  = cfg.getInt("db.pool", 10);
boolean flag = cfg.getBoolean("feature.x", false);

// Or a single property
Object value = k2.getProperty("prod", "db.url");
```

The token is sent as both `Authorization: Bearer <token>` and `X-API-Token: <token>`,
so it works regardless of which header your platform build expects. The token's
environment scope is enforced server-side.

## API mapping

| SDK call | Endpoint |
|---|---|
| `getConfiguration(env)` | `GET /api/config/token/{env}/current` |
| `getProperty(env, key)` | `GET /api/config/token/{env}/properties/{key}` |

`K2Exception` carries the HTTP status (`getStatusCode()`): `401/403` token rejected,
`404` config not found, `421` baseUrl host not licensed (must match the platform's `K2_PUBLIC_HOST`),
`-1` transport failure.

## Resilience & config knobs

| Concern | Builder | Spring property | Default |
|---|---|---|---|
| TTL cache (collapses repeat reads) | `.cacheTtl(Duration)` | `k2.cache.ttl-seconds` | 300s (Spring) / off (core) |
| Offline last-known-good fallback | `.offlineCache(...)` / `.offlineCacheEnabled(true)` | `k2.offline-cache.enabled`, `k2.offline-cache.dir` | on (Spring), dir `~/.k2/cache` or `K2_CACHE_DIR` |
| PropertySource on/off + precedence | — | `k2.property-source.enabled`, `k2.property-source.precedence` | enabled, `highest` |

The offline cache is written on every successful fetch and read **only** on an availability failure
(transport error or 5xx) — `401/403/404/421` always surface.

## Try it against a running platform

```bash
K2_BASE_URL=http://localhost:8080 \
K2_TOKEN=k2_live_xxx \
K2_ENV=prod \
mvn -q exec:java -Dexec.mainClass=com.k2platform.sdk.K2SdkDemo
```

Add `K2_KEY=some.property` to also fetch a single property. Mint the token first in
the admin UI: log in → workspace → **Tokens** → generate, scoped to the app + env.

## Build & test

```bash
mvn clean test     # 15 tests — model, HTTP (in-JVM fake server), offline cache, both Spring modes
mvn package        # builds the jar
```

All tests run offline against an in-process fake `k2-app` (`FakeK2Server`) — no network, no external platform.
