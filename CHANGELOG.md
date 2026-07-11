# Changelog

All notable changes to the **K2 Java SDK** (`com.k2platform:k2-sdk-java`) are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-07-03

### Added
- Initial release of the K2 Java SDK — a single jar for reading token-scoped configuration from a
  self-hosted K2 / KeyKosh platform (`/api/config/token/**`). One mandatory runtime dependency
  (Jackson); HTTP over the JDK's `java.net.http`. Java 17+.
- **Core `K2Client`** (Mode 3) — builder-configured, thread-safe client:
  `getConfiguration(env)`, `getCachedConfiguration(env)`, `snapshot(env)`, `getProperty(env, key)`,
  `getString(...)`, and no-arg overloads driven by a `defaultEnvironment`. Token sent as both
  `Authorization: Bearer` and `X-API-Token`. Required `baseUrl` with no vendor default.
- **`K2Configuration`** model with typed accessors (`getString`/`getInt`/`getBoolean`) and the
  server-driven `offlineCacheAllowed` flag.
- **`K2Exception`** carrying the HTTP status (`getStatusCode()`), or `-1` for transport failures;
  `401/403/404/421` surface as intentional errors, `5xx`/transport are availability errors.
- **Spring `PropertySource`** (Mode 1) via `K2EnvironmentPostProcessor` — K2 replaces
  `application.yml` as the config source of record, with `highest` / `above-application-yaml`
  precedence; never blocks boot when `k2-app` is unreachable.
- **`@K2Config` typed proxy** (Mode 2) via `@EnableK2Config` — live, per-call typed views over K2
  config with method→key derivation and return-type coercion.
- **TTL cache** for repeated reads (`cacheTtl` / `k2.cache.ttl-seconds`).
- **Encrypted offline last-known-good cache** — the `K2C1` on-disk format (AES-256-GCM + HMAC-SHA256
  + embedded TTL, token-derived keys), byte-compatible with the Node and Python SDKs. Written on
  successful fetch, read only on availability failures, and suppressed when the server reports
  `offlineCacheAllowed=false`.
- Spring bootstrap via `k2.*` properties (relaxed-bound from `K2_BASE_URL`/`K2_TOKEN`/`K2_ENV`),
  plus `k2.token-file` (`K2_TOKEN_FILE`) for Docker/Kubernetes secret mounts.
- `K2SdkDemo` runnable smoke-test main class.
- Documentation: `README.md`, `USER_MANUAL.md`, and `RELEASING.md`.

[Unreleased]: https://github.com/k2platform/k2-sdk-java/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/k2platform/k2-sdk-java/releases/tag/v1.0.0
