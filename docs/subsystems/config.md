---
title: "Kernel Subsystem: Config (L0 Foundation)"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Kernel Subsystem: Config (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.config.*` (Provider contracts, Key-Value schemas)
- Core: `eu.exeris.kernel.core.config.*` (Hot-reload orchestrator — `KernelConfigRegistry`,
  `DynamicConfigFileWatcher`; no JEP 513 validation code exists here — see the JEP 513 section below)

**Layer:** L0 (Foundation)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Config subsystem** is the "Instruction Manual" of the Exeris Kernel: the parameters it carries (ports,
off-heap arena sizes, backpressure limits) reach subsystems immutably, and the SPI provides a redaction path
for a value a caller puts into telemetry — see the "CWE-532 REDACT Contract" and Error Codes sections below for
why that path is a caller contract this kernel does not enforce automatically today, not something the
subsystem itself performs. What Config does not do today, despite the SPI shape suggesting otherwise, is refuse
to boot on its own when a required parameter is missing — see "Deterministic T-Minus 0" below.

It initializes before any other subsystem (including Memory) and provides:

- **Hierarchical configuration loading** with strict Cloud-Native precedence:
  `ENV` (container overrides) → `Vault` (secrets) → `File` (ConfigMap mounts) → `Classpath` (defaults).
  This ordering is intentional: Kubernetes operators override any parameter at runtime by injecting an environment
  variable into the pod — no restart required.
- **Zero-downtime hot-reload** via `@Dynamic` annotations and `java.nio.file.WatchService`.
  Parsing occurs **only** during bootstrap or on a `@Dynamic` reload event. Runtime reads bypass the parser entirely
  and go directly to `volatile` fields or `VarHandle` slots — O(1), zero GC, zero contention.
- **Lock-free updates** using `VarHandle` volatile semantics for O(1) read performance even under millions of
  concurrent Virtual Threads.
- **Zero parser overhead at runtime** — pure Java property files parsed once at bootstrap. No JSON/YAML
  deserialization on the hot path. No reflection, no `Map` lookups.
- **Kubernetes-native** — designed for ConfigMap mounts at `/etc/exeris/config`.

> **Community tier:** sys props (`exeris.*`) → env vars (`EXERIS_*`) → compiled defaults. File and Vault sources are not implemented in the Community tier.

---

## Core Philosophy: "Immutable Sovereignty"

> **Two bullets below describe target behaviour with no implementation in this repository —
> checked by grep, not assumed. See the callouts inline.**

- ~~**Instrument-Aware:** Config knows what hardware it runs on. It can automatically size off-heap
  slab allocations based on detected CPU cache line widths (L1/L2), eliminating false sharing without
  manual tuning.~~ **Not implemented.** No CPU cache-line-width detection and no config-driven slab
  sizing on that basis exists anywhere in `exeris-kernel-spi`, `-core` or `-community`.
- **Deterministic T-Minus 0 — API exists, nothing calls it today:** `ConfigProviderException`
  ships typed factories for exactly this (`missingProperty()` → `EX-CFG-1001`, `typeMismatch()` →
  `EX-CFG-1002`), and `Dynamic.required()` is declared on every `@Dynamic` field for the same intent.
  But grepping the whole repository for `missingProperty(`, `typeMismatch(`, `EX_CFG_1001` and
  `EX_CFG_1002` turns up no call site outside `ConfigProvider.java` itself (and the error-code /
  mapper-registry tests, which exercise the codes generically, not through a real missing-key path);
  `.required()` is likewise never read anywhere. Nothing today aborts bootstrap through this specific
  mechanism because a `REQUIRED` key is absent — a subsystem that needs one enforces it itself, with
  its own exception type (`CommunityStorageSubsystem`'s `BlobStorageException.missingConfiguration`,
  for example, not `ConfigProviderException`).
- **No Classpath Secrets:** Passwords and tokens are never stored in `.properties` files on the classpath.
  `ScopedValue`-bound secret injection (Code Example 4 below) is an available, real JDK/SPI pattern for a
  caller to use — Vault as the *source* feeding it is not implemented in this tier (see the callout above
  the Vault section).
- **CWE-532 REDACT Contract:** Any configuration value captured in `rawArgs` (e.g., for `EX-CFG-1002`) **MUST** be
  redacted or truncated by the caller before emission. Raw secrets, credentials, or tokens must never reach the
  binary telemetry dump. See `KernelErrorCodes.EX_CFG_1002` for the canonical enforcement comment — this
  redaction logic (a private `sanitizeConfigValue` helper inside `ConfigProviderException`) is real and
  runs whenever `typeMismatch(...)` is called, even though — per the point above — nothing in this
  repository currently calls it from a live missing/malformed key at bootstrap.

---

## Key Characteristics

### Hot-Reload (`NIO WatchService`)
A file change triggers an atomic state reload in Core without a JVM restart — but **only** for keys annotated
`@Dynamic`. Keys annotated `@Immutable` are validated once at T-minus 0 and sealed for the lifetime of the process.

`@Immutable` (`eu.exeris.kernel.spi.config.Immutable`, since 0.9.0) makes that seal **explicit and enforced**:

- **Compile time** — the `ImmutableConfigProcessor` annotation processor in `exeris-kernel-build-config`
  fails the build if a key carries both `@Immutable` and `@Dynamic` (contradictory intents), or if an
  `@Immutable` field is not declared `static final`.
- **Runtime** — when the `WatchService` driver observes an on-disk change to a sealed key, it **refuses**
  the reload (the field is never mutated), keeps the boot-time value authoritative, and emits the secret-safe
  `EX-CFG-1004` audit event (file + key name only — never the value). A guard is registered symmetrically to
  `@Dynamic`: `ConfigProvider.guardImmutable(file, key)`. The raw per-tier provider (e.g. `CommunityConfigProvider`)
  implements this — like `watch()` — as a no-op. `AbstractConfigProviderTck`'s `WatchContract` tests assert
  `watch()`'s no-op behavior against the bare provider in isolation; the TCK does not test `guardImmutable()`
  at all — no such assertion exists in that file. But no caller ever holds that bare instance: `KernelBootstrap` always wraps the resolved
  provider in a Core-owned `RegistryBackedConfigProvider` before binding it to `CURRENT_CONFIG`, and that wrapper
  routes every `watch()`/`guardImmutable()` call through `KernelConfigRegistry`, dispatched by the same
  `WatchService`-backed `DynamicConfigFileWatcher` regardless of tier — gated only on whether the configured
  directory exists on disk, not on which provider resolved the initial values. Some Community-tier subsystem
  comments still describe hot-reload as Enterprise-only; that no longer matches this wiring.

  The audit event is emitted **per detection, not per mutation**, and the count carries no meaning as
  an attempt count. A single logical edit is several filesystem modifications (a write with
  `TRUNCATE_EXISTING` is a truncate and a write) which `WatchService` usually merges into one event
  but sometimes does not — 19 of 20 measured runs delivered one, one delivered two — and the watch
  loop dispatches per event. The deterministic half matters more: because the sealed baseline is never
  updated, any later change to an **unrelated** key in the same file re-audits the sealed one, for as
  long as the file holds the rejected value. The signal means *the sealed key is still wrong on disk*.
  Whether it should be coalesced is open — see
  [`RFC-2026-09-03`](../rfc/RFC-2026-09-03-immutable-refusal-event-granularity.md).

### JEP 513 Validation (Flexible Constructor Bodies — Closed/Delivered in JDK 25)

> **Fictional as written — no such validation exists in this repository.** Grepping every module
> (`exeris-kernel-spi`, `-core`, `-community`, `-build-config`) for `JEP 513`, `JEP513` or
> `Flexible Constructor` returns nothing. `ConfigProviderException`'s constructor is a plain
> `super(errorCode, message, cause, rawArgs)` call with no statements ahead of it, and no class in
> the config subsystem performs pre-`super()` validation of any kind. Combined with the
> "Deterministic T-Minus 0" finding above (nothing currently throws `EX-CFG-1001`/`EX-CFG-1002` from
> a real missing/malformed key), this whole subsection describes an intended validation strategy that
> was never built, not a delivered mechanism. Left here as a record of intent rather than deleted
> outright — a future implementation of the required-key check above is the natural place to either
> build this or drop the idea.

---

## Responsibilities

**What Config DOES:**

1. Load configuration from multiple sources and merge by strict precedence. In the one tier this
   repository implements, that is system property over environment variable over compiled default
   (`CommunityConfigProvider.resolveRaw`) — **system property wins, not `ENV`**, the reverse of the
   `ENV → Vault → File → Classpath` ordering described in the Overview above, which is the broader,
   mostly-unimplemented design target rather than what `CommunityConfigProvider` does today.
2. Provide type-safe extraction (`get()`, `getInt()`, `getBoolean()`) via SPI.
3. Watch the filesystem and atomically update fields annotated `@Dynamic`.
4. ~~Abort bootstrap with `EX-CFG-1001` if any `REQUIRED` property is absent at T-minus 0.~~ Not
   today — see "Deterministic T-Minus 0" under Core Philosophy above; the mechanism exists in the SPI
   but nothing in this repository calls it.

**What Config DOES NOT DO:**

1. **No Dependency Injection:** It does not wire beans or manage lifecycles (that is `KernelBootstrap`'s job).
2. **No Expression Language:** It does not evaluate SpEL or embedded logic in config values.
3. **No Secret Storage:** It never holds credentials in heap `String` objects — only `ScopedValue`-bound references.

---

## Error Codes (Glass-Box Telemetry)

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Meaning                | Action                             | Security Contract                                               |
|:--------------|:-----------------------|:-----------------------------------|:----------------------------------------------------------------|
| `EX-CFG-1001` | Missing Property       | Fatal halt (`FAIL_FAST`) at T‑0    | Log missing key name only — value cannot exist                  |
| `EX-CFG-1002` | Type Mismatch          | Fatal halt (`FAIL_FAST`) at T‑0    | ⚠️ **REDACTED** — `actualValue` never enters logs (CWE-532)     |
| `EX-CFG-1003` | Hot-Reload Read Error  | Warn; keep last known stable state | Trace OS file-lock contention — no value in scope               |
| `EX-CFG-1004` | Immutable Reload Refused | Refuse; keep sealed boot value; audit | Log file + key name only — value never enters telemetry      |

**Privacy-First enforcement for `EX-CFG-1002`:** The caller constructing the exception **MUST** redact or truncate
`actualValue` before passing it to `rawArgs`. The Kernel runtime never performs this redaction automatically —
it is a strict caller contract. See `KernelErrorCodes.EX_CFG_1002` Javadoc for the canonical CWE-532 enforcement
comment.

> **`EX-CFG-1001` / `EX-CFG-1002` are defined and ready, not yet thrown.** As detailed under "Deterministic
> T-Minus 0" above, no call site in this repository currently constructs a `ConfigProviderException` with
> either code — the "Fatal halt at T-0" row describes the contract a caller gets by using the typed factory,
> not something this kernel currently does on its own for any key. `EX-CFG-1003` and `EX-CFG-1004` are real:
> both are emitted by `DynamicConfigFileWatcher` today.

---

## Code Examples

### 1. Defining Config via SPI (Immutable)

```java
// Note: MemoryProviderConfig is actually a record in eu.exeris.kernel.spi.memory, not an interface
// in eu.exeris.kernel.spi.config. The example below illustrates the pattern using a generic name.
package eu.exeris.kernel.spi.config;

public interface MemoryProviderConfig {
    long globalMemoryBytes();
    int networkOffHeapThreshold();
    boolean leakDetectionEnabled();
}
```

### 2. Secure Property Access with Fail-Fast (Core)

```java
public int getNetworkPort(ConfigProvider config) {
    return config.getInt("network.port")
                 .orElseThrow(() ->
                     ConfigProvider.ConfigProviderException.missingProperty(
                         "network.port", config.providerName()));
}
```

### 3. Lock-Free Dynamic Reloading (Core)

`KernelConfigRegistry` itself does not hold per-key `VarHandle` slots — it is a type-agnostic dispatcher: a
list of `(file, key, callback)` registrations plus a `sealed` boolean guarded by one `VarHandle`
(`getAcquire`/`setRelease`) so `register()` after boot is a safe, cheap no-op rather than a race. Type
conversion and field publication happen at the *call site*, not inside the registry:

```java
// eu.exeris.kernel.core.config.KernelConfigRegistry (abridged — the real fireReload() also
// wraps each callback in a try/catch that emits DynamicFieldReloadedEvent on success and
// DynamicReloadFailedEvent (EX-CFG-1003) on a RuntimeException; see the Audit Log section below)
package eu.exeris.kernel.core.config;

public final class KernelConfigRegistry {

    private static final VarHandle SEALED_HANDLE; // guards the boolean below, not a config value

    private boolean sealed;

    public void register(String file, String key, Consumer<String> callback) {
        if ((boolean) SEALED_HANDLE.getAcquire(this)) {
            return; // late registration after seal() — logged and ignored
        }
        registrations.add(new Registration(file, key, callback));
    }

    /** Called by DynamicConfigFileWatcher on its watcher Virtual Thread — never on a carrier. */
    public void fireReload(String file, String key, String newValue) {
        for (Registration reg : registrations) {
            if (reg.matches(file, key)) {
                reg.callback().accept(newValue); // the call site owns the field write
            }
        }
    }
}
```

The call site is a `public static volatile` field on an immutable record, annotated `@Dynamic` and updated by
the registered callback — the shape every real hot-reloadable key in this repository uses (for example
`CommunityAdmissionConfig.CURRENT`, ADR-035):

```java
// eu.exeris.kernel.community.persistence.CommunityAdmissionConfig (abridged)
@Dynamic(file = CONFIG_FILE, key = KEY_PREFIX, required = false)
public static volatile CommunityAdmissionConfig CURRENT = DEFAULT;
```

```java
// eu.exeris.kernel.community.bootstrap.CommunityPersistenceSubsystem#initialize() (abridged)
CommunityAdmissionConfig.CURRENT = CommunityAdmissionConfig.fromConfigProvider(configProvider);
configProvider.watch(CommunityAdmissionConfig.CONFIG_FILE, CommunityAdmissionConfig.KEY_PREFIX,
        _ -> CommunityAdmissionConfig.CURRENT = CommunityAdmissionConfig.fromConfigProvider(configProvider));
```

A read is one plain `volatile` load; a reload is one plain `volatile` store swapping the whole record reference
(`@Dynamic`'s own contract: "`VarHandle.setVolatile()` or an equivalent release store") — not the split
acquire/release scheme a manually-managed `VarHandle` field would use. The record's immutability is what makes
the single-reference swap safe: a reader never observes a half-updated object.

### 4. No Classpath Secrets — Vault Injection via ScopedValue (Explicit Zeroing)

```java
public static final ScopedValue<byte[]> VAULT_TOKEN = ScopedValue.newInstance();

byte[] vaultToken = VaultClient.fetchToken();
try {
    ScopedValue.where(VAULT_TOKEN, vaultToken).run(() -> {
        config.loadSecrets(VAULT_TOKEN.get());
    });
} finally {
    Arrays.fill(vaultToken, (byte) 0);   // Explicit Zeroing — Mechanical Sympathy for secrets
}
```

> We do not trust Garbage Collectors with security. A reference that is merely *eligible for GC* is still plaintext
> in physical RAM — visible to `jmap -dump`, a core dump, or a cold-boot memory attack. In Exeris, cryptographic
> buffers are **explicitly zeroed** (`Arrays.fill`) immediately after the `ScopedValue` scope exits. The secret
> never persists beyond the bootstrap phase as recoverable data.

---

## Kernel Configuration Reference

The table below lists the configuration keys consumed **internally** by the Exeris Kernel.
The **Status** column indicates whether the key is wired to a `ConfigProvider.KernelSettings`
constant today (`✅ WIRED`) or is a committed design target not yet represented by a record
field (`🔲 planned`). Application-level keys are defined by the application layer and are
not listed here.

> **Not exhaustive.** A `configProvider.get*(...)` grep across this repository turns up several
> dozen real, code-read keys this table does not list — most of `http.*` (`h2cUpgradeEnabled`,
> `maxHeaderBlockSize`, `maxResponseBodyBytes`, `client.defaultAuthority`, …), most of
> `persistence.*` (`connectionTimeoutMs`, `maxLifetimeMs`, `perTenantPooling`, `rlsEnabled`,
> `useTls`, `pool.warmup.*`, …), `event.*`, `flow.*`, `graph.*`, `scheduling.schedulerName`,
> `transport.auto.{minReactors,maxReactors,reserveCores}`, and the plain `network.certPath` /
> `network.keyPath` pair `transport.certPath` / `transport.keyPath` fall back to. Each of those
> subsystems is its own `docs/subsystems/*.md`; this table catalogues the keys that live in or
> map onto `KernelSettings` plus the boundary-crossing transport/websocket knobs, not a complete
> inventory of every key any subsystem reads.

> **Key name convention:** Keys are specified in the `ConfigProvider` API format (e.g. `network.port`).
> A typical community configuration provider maps these to system properties by prepending `exeris.` (e.g.
> `-Dexeris.network.port=9090`) and to environment variables by converting to
> `EXERIS_NETWORK_PORT` (uppercase, dots replaced with underscores). Future implementations
> may also load these from Vault or ConfigMap mounts.

| Key                                                | Type      | Default             | Reload       | Status      | Description                                              |
|:---------------------------------------------------|:----------|:-------------------:|:------------:|:-----------:|:---------------------------------------------------------|
| `globalMemoryMb`                                   | `long`    | `512`                | ❌ IMMUTABLE | ✅ WIRED    | Total off-heap arena budget (`KernelSettings.globalMemoryMb`) |
| `network.port`                                     | `int`     | `8443`              | ❌ IMMUTABLE | ✅ WIRED    | Data-plane TCP/QUIC port (`NetworkSettings.port`)        |
| `network.bufferSize`                               | `int`     | `65536`             | ❌ IMMUTABLE | ✅ WIRED    | Per-connection off-heap buffer size in bytes (`NetworkSettings.bufferSize`) |
| `network.nativeTransportPreferred`                 | `boolean` | `true`              | ❌ IMMUTABLE | ✅ WIRED    | Hint to prefer native async I/O transport (`NetworkSettings.nativeTransportPreferred`) |
| `network.reactorCount`                             | `int`     | `0` (auto)          | ❌ IMMUTABLE | ✅ WIRED    | Number of carrier reactor threads; 0 = auto from CPU topology (`NetworkSettings.reactorCount`) |
| `network.quicEnabled`                              | `boolean` | `true`              | ❌ IMMUTABLE | ✅ WIRED    | Enable QUIC/HTTP3 (planned; `NetworkSettings.quicEnabled`) |
| `persistence.jdbcUrl`                              | `string`  | `jdbc:postgresql://localhost:5432/exeris` | ❌ IMMUTABLE | ✅ WIRED | JDBC connection URL (`PersistenceSettings.jdbcUrl`) |
| `persistence.username`                             | `string`  | `exeris`            | ❌ IMMUTABLE | ✅ WIRED    | Database user — **SECRET**, redacted in telemetry        |
| `persistence.password`                             | `string`  | `""`                | ❌ IMMUTABLE | ✅ WIRED    | Database password — **SECRET**, redacted in telemetry    |
| `persistence.maxPoolSize`                          | `int`     | adaptive when unset (`max(min(cores × 2, 32), 2)`) | ❌ IMMUTABLE | ✅ WIRED    | JDBC connection pool max connections; explicit config overrides the adaptive Community bootstrap sizing |
| `persistence.runMigrations`                        | `boolean` | `false`             | ❌ IMMUTABLE | ✅ WIRED    | Run schema migrations on startup (`PersistenceSettings.runMigrations`) |
| `telemetry.jfrEnabled`                             | `boolean` | `true`              | ❌ IMMUTABLE | ✅ WIRED    | Enable JFR telemetry sink (`TelemetrySettings.jfrEnabled`) |
| `telemetry.metricsEnabled`                         | `boolean` | `true`              | ❌ IMMUTABLE | ✅ WIRED    | Enable Prometheus metrics endpoint (`TelemetrySettings.metricsEnabled`) |
| `telemetry.tracingEnabled`                         | `boolean` | `false`             | ❌ IMMUTABLE | ✅ WIRED    | Enable distributed tracing / OTEL (`TelemetrySettings.tracingEnabled`) |
| `telemetry.nodeId`                                 | `string`  | `local`             | ❌ IMMUTABLE | ✅ WIRED    | Unique kernel instance identifier (`TelemetrySettings.nodeId`) |
| `telemetry.region`                                 | `string`  | `default`           | ❌ IMMUTABLE | ✅ WIRED    | Deployment region for distributed tracing (`TelemetrySettings.region`) |
| `bootstrap.healthPort`                             | `int`     | `9090`              | ❌ IMMUTABLE | 🔲 planned  | HTTP health probe port — not yet in `KernelSettings`     |
| `bootstrap.failFast`                               | `boolean` | `true`              | ❌ IMMUTABLE | 🔲 planned  | FAIL_FAST vs DEGRADE on subsystem init failure           |
| `network.idleTimeoutMillis`                        | `long`    | `30000`             | ✅ DYNAMIC   | 🔲 planned  | Legacy name; nothing reads it. The wired key is `transport.idleTimeoutMillis` |
| `network.proxyProtocolEnabled`                     | `boolean` | `false`             | ❌ IMMUTABLE | 🔲 planned  | Enable Proxy Protocol v2 parsing                         |
| `network.proxyProtocolRequired`                    | `boolean` | `false`             | ❌ IMMUTABLE | 🔲 planned  | Reject connections without PP2 header                    |
| `network.paqs.warningThreshold`                    | `float`   | `0.70`              | ✅ DYNAMIC   | 🔲 planned  | WM `WARNING` level (fraction of off-heap budget)         |
| `network.paqs.criticalThreshold`                   | `float`   | `0.85`              | ✅ DYNAMIC   | 🔲 planned  | WM `CRITICAL` level (fraction of off-heap budget)        |
| `network.paqs.sheddingThreshold`                   | `float`   | `0.95`              | ✅ DYNAMIC   | 🔲 planned  | WM `SHEDDING` level (fraction of off-heap budget)        |
| `network.paqs.endpointPriority.<path>`             | `string`  | `NORMAL`            | ✅ DYNAMIC   | 🔲 planned  | Static `StreamPriority` for path prefix                  |
| `http.stream.creditWindowBytes`                    | `int`     | `65536`             | ❌ IMMUTABLE | ✅ WIRED    | SSE server-push (ADR-043) egress credit window: outstanding bytes before `emit()` parks the streaming VT. Direct `-D` system property (see note ⁑) |
| `websocket.enabled`                                | `boolean` | `false`             | ❌ IMMUTABLE | ✅ WIRED    | Whether the `websocket` subsystem boots a listener at all. **Default `false` on purpose**: the subsystem is on every Community classpath from 0.12, and a deployment that merely upgraded must not gain an open socket it never configured. Unlike `http`, the mode is not inferred from a configured port — a duplex endpoint is not the thing an application boots the kernel *for*, so inference would be the wrong default (ADR-084) |
| `websocket.bindHost`                               | `string`  | `InetAddress.getLoopbackAddress()` (typically `127.0.0.1`) | ❌ IMMUTABLE | ✅ WIRED    | Listen address. Loopback by default, so enabling the subsystem without choosing an address does not publish it to the network — resolved from the JDK rather than a hardcoded literal, so an IPv6-only host gets its own loopback form instead of a failing `127.0.0.1` |
| `websocket.port`                                   | `int`     | `8081`              | ❌ IMMUTABLE | ✅ WIRED    | Listen port. RFC 6455 defines no default, so this is the kernel's; `0` binds an ephemeral port, which is what the tests use |
| `websocket.allowedOrigins`                         | `string`  | *(empty)*           | ❌ IMMUTABLE | ✅ WIRED    | Comma-separated origins permitted to open a connection. **Empty accepts no browser origin** — the refusing default ADR-084 §6 asks for, reached by leaving the key alone rather than by writing one |
| `websocket.maxConnections`                         | `int`     | `1024`              | ❌ IMMUTABLE | ✅ WIRED    | Concurrent connection ceiling (`WebSocketConfig.DEFAULT_MAX_CONNECTIONS`) |
| `websocket.idleTimeoutMillis`                      | `long`    | `60000`             | ❌ IMMUTABLE | ✅ WIRED    | Idle reclamation, matching `http.idleTimeoutMillis`'s default (`WebSocketConfig.DEFAULT_IDLE_TIMEOUT_MILLIS`) |
| `websocket.keepAliveIntervalMillis`                | `long`    | `20000`             | ❌ IMMUTABLE | ✅ WIRED    | Carried on `WebSocketConfig` (`DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS`) and **not honoured — the engine sends no server-initiated pings**, as `CommunityWebSocketProvider`'s javadoc states. `WIRED` here means the key reaches the settings record, which it does; it does not mean a ping rides on it. A client that sends its own PING is answered |
| `websocket.maxMessageBytes`                        | `long`    | `1048576`           | ❌ IMMUTABLE | ✅ WIRED    | Maximum inbound message size, 1 MiB — two orders of magnitude above the 8 KB ADR-084 §5 measured as too small for a serialised model (`WebSocketConfig.DEFAULT_MAX_MESSAGE_BYTES`) |
| `transport.paqs.maxActiveStreams`                  | `int`     | `5000`              | ❌ IMMUTABLE | ✅ WIRED    | PAQS ceiling on concurrently admitted streams (per engine). `-1` = no ceiling — memory-pressure shedding still applies; `0` and other negatives are refused at startup (ADR-071) |
| `transport.tls` | `boolean` | `true` | ❌ IMMUTABLE | ✅ WIRED | Opt-out from TLS for any transport that would otherwise have it — server, client and dual alike. **The two sides cannot key on the same signal**: a server arms TLS from material it was given, a client holds no server material (the TLS end-to-end tests build the server with a certificate and the client with none, and both speak TLS) and arms from a bound crypto provider. Before 0.12 the client's answer had no override at all, so a kernel booting crypto to serve HTTPS could not make a plaintext outbound call. `false` is that missing escape hatch; half-configured material — one of the two paths — stays a boot failure regardless, because it is a deployment mistake and not a request for plaintext. Direct `-D` system property (see note ⁑): the provider is handed a `TransportConfig` and no `ConfigProvider`, and adding a component to that SPI record for a boolean costs more than the knob is worth (ADR-071 records the same reasoning for its siblings). |
| `transport.idleTimeoutMillis`                      | `long`    | `30000`             | ❌ IMMUTABLE | ✅ WIRED    | Reclaim a connection that has moved no bytes for this long. `0` disables reclamation (ADR-071 capacity/timeout class); negatives are refused. `http.idleTimeoutMillis` is the same limit reaching the same carrier through `HttpConfig`. Enforced by a per-reactor sweep since 0.12.0 — **carried but enforced by nothing before that** |
| `transport.socket.backend`                         | `string`  | `auto`              | ❌ IMMUTABLE | ✅ WIRED    | Community carrier socket path: `auto`, `nio`, `posix-hybrid`. Resolved through the provider first, then the legacy `-Dexeris.community.transport.socket.backend` / `EXERIS_COMMUNITY_TRANSPORT_SOCKET_BACKEND` ladder, which stays because it was published |
| `transport.maxTlsRecordsPerRead`                   | `int`     | `32`                | ❌ IMMUTABLE | ✅ WIRED    | Fairness cap on TLS records drained per readable event. Direct `-D` system property (see note ⁑) |
| `transport.queueBackpressureEnabled`               | `boolean` | `false`             | ❌ IMMUTABLE | ✅ WIRED    | Bounds the TLS ingress queue at 1000 entries; `false` leaves it count-unbounded — entries are off-heap loans, so the watermark arbiter still sheds under memory pressure. Direct `-D` system property (see note ⁑) |
| `memory.jfr.sampleEvery`                           | `int`     | `64`                | ❌ IMMUTABLE | ✅ WIRED    | Emit one `CommunityAllocation` JFR event per N allocations; `1` emits every one. Resolved through the provider first, then the legacy `-Dexeris.community.memory.jfr.sampleEvery` |
| `persistence.sqlTranslationCacheMaxEntries`        | `int`     | `1024`              | ❌ IMMUTABLE | ✅ WIRED    | Bound on the JDBC placeholder-translation memo cache, which **never evicts** — past the bound an application keeps the earliest statements it saw, not the hottest. `0` disables caching; negatives are refused rather than corrected |
| `transport.acceptedSendBufferBytes`                | `int`     | `0` (OS default)    | ❌ IMMUTABLE | ✅ WIRED    | Optional `SO_SNDBUF` override on accepted sockets; `0` leaves the OS default. Tightens egress backpressure (smaller window ⇒ earlier `emit()` park). Direct `-D` system property (see note ⁑) |
| `memory.watermarkPollIntervalMs`                   | `int`     | `50`                | ✅ DYNAMIC   | 🔲 planned  | `WatermarkManager` sampling interval                     |
| `memory.leakDetection`                             | `string`  | `SAMPLED`           | ❌ IMMUTABLE | 🔲 planned  | `DISABLED`, `SAMPLED`, `PARANOID`                        |
| `telemetry.allocationSampleRate`                   | `double`  | `0.01`              | ✅ DYNAMIC   | 🔲 planned  | JFR allocation event sampling rate (0.0–1.0)             |
| `telemetry.consoleSinkEnabled`                     | `boolean` | `false`             | ❌ IMMUTABLE | 🔲 planned  | Enable Console telemetry sink                            |
| `crypto.tls.minVersion`                            | `string`  | `TLSv1.3`           | ❌ IMMUTABLE | 🔲 planned  | Minimum TLS version accepted                             |
| `persistence.pool.connectionTimeoutMs`             | `int`     | `5000`              | ✅ DYNAMIC   | 🔲 planned  | JDBC pool acquisition timeout                            |
| `persistence.pool.idleTimeoutMs`                   | `int`     | `600000`            | ✅ DYNAMIC   | 🔲 planned  | JDBC pool idle connection timeout                        |
| `persistence.pool.keepaliveMs`                     | `int`     | `30000`             | ✅ DYNAMIC   | 🔲 planned  | JDBC pool keepalive heartbeat interval                   |
| `persistence.outbox.maxRetries`                    | `int`     | `10`                | ✅ DYNAMIC   | 🔲 planned  | Max Outbox delivery retries before DLQ                   |
| `persistence.outbox.backoffBaseMs`                 | `int`     | `100`               | ✅ DYNAMIC   | 🔲 planned  | Outbox retry base backoff (ms)                           |
| `config.vault.timeoutMs`                           | `int`     | `3000`              | ❌ IMMUTABLE | 🔲 planned  | Vault connection timeout during bootstrap                |
| `config.vault.retryCount`                          | `int`     | `3`                 | ❌ IMMUTABLE | 🔲 planned  | Vault connection retry attempts before FAIL_FAST         |
| `flow.saga.globalParkTimeoutMs`                    | `long`    | `1800000` (30 min)  | ✅ DYNAMIC   | 🔲 planned  | Max Saga park duration before timeout compensation       |
| `crashDir`                                         | `string`  | platform default    | ❌ IMMUTABLE | 🔲 planned  | Glass-Box crash buffer directory (also: `EXERIS_CRASH_DIR` ENV) |

> **No RAM-percentage auto-detection in this tree:** `KernelSettings.globalMemoryMb` javadoc
> attributes the value to "`ExerisSmartLauncher`", but no class of that name, and no
> RAM-percentage sizing logic for this key, exists anywhere in this repository — checked by
> grep across `exeris-kernel-spi`, `-core` and `-community`. `CommunityConfigProvider` falls
> back to the flat compiled default of `512` (MB) when `globalMemoryMb` /
> `EXERIS_GLOBALMEMORYMB` is unset; nothing scales it against
> `Runtime.getRuntime().maxMemory()`. Set it explicitly for anything beyond a 512 MB budget.

> **Persistence helper note:** `PersistenceConfig.defaults(...)` is a fixed development/unit-test preset in the SPI helper API. It is not the Community runtime bootstrap default when `persistence.maxPoolSize` is unset.

> **⁑ Direct system-property knobs:** `http.stream.creditWindowBytes`, `transport.acceptedSendBufferBytes`, `transport.maxTlsRecordsPerRead`, `transport.queueBackpressureEnabled` and `transport.tls` are read directly via `-Dexeris.<key>`, **not** through the config provider — they do not appear in `KernelSettings`/`NetworkSettings`.
>
> **The last two are on this list for a different reason than the first two, and it is worth stating.** They are `static final` fields on `NativeTcpCarrier` and `NativeTcpStream`, resolved when the class loads — before any `ConfigProvider` exists, and once per JVM for whatever touches the class first. Reading the provider *at class initialisation* would not fix that: it would freeze whatever happened to be bound at the moment of first load, which is worse than an honest `-D`, because it looks configurable and is not. Making them properly configurable means moving them to instance state on the ingress path — a hot-path change that owes a measurement, so a separate slice rather than a rename. Recorded here rather than left looking like drift.

---

## Vault Down-at-Boot Strategy

> **Not implemented in this repository.** No Vault client, no `FAIL_FAST`/`DEGRADE` mode distinction for a
> Vault-down boot, and no code path reading `config.vault.timeoutMs` or `config.vault.retryCount` exists
> anywhere in `exeris-kernel-spi`, `-core` or `-community` — checked by grep. Both keys carry `🔲 planned`
> status in the reference table above, consistent with this. The strategy below describes the intended
> target behaviour, not something a Community-tier boot exercises today.

When Vault is unavailable during the bootstrap phase (`config.vault.timeoutMs` exceeded; system property: `exeris.config.vault.timeoutMs`):

| Mode              | Behaviour                                                                                         |
|:------------------|:--------------------------------------------------------------------------------------------------|
| `FAIL_FAST` (default) | `EX-CFG-1001` thrown after `config.vault.retryCount` attempts × `config.vault.timeoutMs` deadline. Kernel halts. K8s liveness probe returns `503` → pod is replaced. |
| `DEGRADE`         | Last-known configuration (from file/classpath) is used for secrets. A bootstrap warning is emitted through the current bootstrap JFR telemetry path. **NEVER deploy DEGRADE mode to production** — it means the application starts with potentially stale or empty secrets. |

System properties mirror the canonical keys with an `exeris.` prefix (for example, `exeris.config.vault.timeoutMs` → `config.vault.timeoutMs`).

**Recommended K8s pattern:**
Use `initContainer` to validate Vault connectivity before the main container starts. This prevents the
Exeris bootstrap from wasting retry cycles:

```yaml
initContainers:
  - name: vault-check
    image: curlimages/curl:8.6.0
    command: ["sh", "-c", "until curl -fs http://vault:8200/v1/sys/health; do sleep 2; done"]
```

---

## Hot-Reload — Performance Contract and Audit Log

### Latency SLO

> **Design targets, not a measured or gated benchmark.** No test or benchmark harness in this repository
> asserts the numbers below; there is no JMH/JFR-driven latency gate for the hot-reload path. Treat this
> table as intent, the same way the Vault section above is intent.

| Event                              | Maximum latency (P99)   | Measurement                                    |
|:-----------------------------------|:-----------------------:|:-----------------------------------------------|
| File change detected (`inotify`)   | ≤ 50 ms                | OS `inotify` → `WatchService` event            |
| Config value updated               | ≤ 1 µs                 | One `volatile` store at the `@Dynamic` field call site |
| End-to-end reload visible          | ≤ 100 ms               | From filesystem write to the next `volatile` read of the field |

> **`inotify` note:** On Linux, `WatchService` uses `inotify` — kernel-level file system change
> notification. Latency is typically < 10 ms on a locally mounted filesystem. NFS-mounted ConfigMaps
> in Kubernetes may have higher latency depending on mount options and poll intervals. This, too, is
> unmeasured in this repository.

### Audit Log — JFR Event

Every hot-reload of a `@Dynamic` key emits a JFR event. This satisfies audit requirements in
regulated environments (fintech, healthcare) without logging raw values.

Two event classes are emitted (from `eu.exeris.kernel.core.config.jfr.DynamicReloadEvent`):

```java
// Emitted on successful hot-reload
@jdk.jfr.Name("eu.exeris.kernel.config.DynamicFieldReloaded")
@jdk.jfr.Label("Dynamic Field Reloaded")
@jdk.jfr.Category({"Exeris", "Config"})
@jdk.jfr.StackTrace(false)
public final class DynamicFieldReloadedEvent extends jdk.jfr.Event {
    String file;
    String key;
    long durationUs;
    // NOTE: old/new values are NEVER included — CWE-532 contract
}

// Emitted on reload failure (EX-CFG-1003)
@jdk.jfr.Name("eu.exeris.kernel.config.DynamicReloadFailed")
@jdk.jfr.Label("Dynamic Reload Failed")
@jdk.jfr.Category({"Exeris", "Config"})
@jdk.jfr.StackTrace(false)
public final class DynamicReloadFailedEvent extends jdk.jfr.Event {
    String file;
    String key;
    String reason;
    // NOTE: value is intentionally excluded — CWE-532 compliance
}
```

A third, sibling event covers the `@Immutable` refusal path (EX-CFG-1004), from the neighbouring
`eu.exeris.kernel.core.config.jfr.ImmutableReloadEvent`: `ImmutableReloadRefusedEvent` (`@Name`
`eu.exeris.kernel.config.ImmutableReloadRefused`), carrying only `file` and `key` — no `durationUs`,
no `reason`, since a refusal is not a failure to explain, just a fact to audit.

> **Note:** `telemetry.md` had this event as planned/TRL-4 under the name `ConfigHotReloadEvent`, but it is now implemented under `DynamicFieldReloadedEvent` and `DynamicReloadFailedEvent`.

The events record **which key changed** and **which file triggered the reload**, but never
the old or new value itself. In regulated environments, this event stream is the config audit log.

---

## Testing Strategy

### Unit Tests

- `CommunityConfigProviderTest` covers the two sources this tier actually has — system property
  (`exeris.<key>`) and environment variable (`EXERIS_<KEY>`) — per accessor (`getString`/`getInt`/
  `getLong`/`getBoolean`), plus malformed-value and blank/absent-key degradation. It does not assert
  system-property-over-environment-variable ordering explicitly (both cannot be set in the same JVM
  process without an env-mocking library, which this suite does not use), and Vault/File sources have
  no test because they have no implementation (see the callout above).
- `KernelConfigRegistry`'s `sealed`-flag `VarHandle` correctness under concurrent access — see
  `KernelConfigRegistryTest.SealContract` and `.ConcurrencyContract`.
- ~~Fail-fast for missing `REQUIRED` fields (`EX-CFG-1001` with correct `rawArgs` layout).~~ ~~Type
  mismatch detection (`EX-CFG-1002`) with redacted `actualValue` — verified that raw value is NOT
  present.~~ **No test exists for either.** A repository-wide grep for `missingProperty(` and
  `typeMismatch(` turns up only their own definitions in `ConfigProvider.java` — no test file, in
  `exeris-kernel-spi` or anywhere else, calls either factory or asserts its `rawArgs` layout or
  redaction behaviour. This is the same gap as the missing call site noted above; the two are one
  finding, not two.

### Integration Tests

- `FileWatcher` triggering hot-reload on file modification (`@Dynamic` keys only) —
  `DynamicConfigFileWatcherTest.HotReloadDelivery`.
- Concurrent dispatch safety: `KernelConfigRegistryTest.ConcurrencyContract` fires `fireReload()` from 64
  Virtual Threads concurrently against one registered callback and asserts every one of the 64 values is
  delivered with no exception — a writer/writer race on the registry, not a reader/writer race against a
  live `FileWatcher`.
- `@Immutable` keys rejected on hot-reload attempt (sealed after T-minus 0) — covered by
  `DynamicConfigFileWatcherTest.ImmutableKeyRefusal`: a sealed key is refused while a sibling `@Dynamic`
  key still reloads, and the `EX-CFG-1004` (`ImmutableReloadRefused`) JFR event is asserted.
- `@Immutable` + `@Dynamic` on the same key, and non-`static-final` `@Immutable` fields, are rejected at
  compile time — covered by `ImmutableConfigProcessorTest` in `exeris-kernel-build-config`.

> **Note:** `AbstractConfigProviderTck` covers the `ConfigProvider` structural contract only (LazyConstant,
> banned parsers, the raw `watch()` no-op contract on the per-tier provider in isolation).
> `AbstractDynamicConfigRegistryTck` (bound by `CoreDynamicConfigRegistryTckTest`) separately covers
> `KernelConfigRegistry`'s register/fireReload/seal dispatch contract. Neither exercises EX-CFG-1001 or
> EX-CFG-1002 — that path coverage lives at the unit level in `exeris-kernel-spi` tests. Full end-to-end
> TCK coverage (a bound provider driving a real file change through `KernelBootstrap`) is still pending.

---

## Summary

The Config subsystem is the anchor of the Exeris Kernel. By combining plain-`volatile` lock-free reads on
`@Dynamic` fields, `NIO` filesystem watching for hot-reload and `@Immutable` refusal, and a strict CWE-532
redaction contract on the values it does carry, it delivers a low-overhead configuration mechanism that does
not block Carrier Threads and does not leak secrets into telemetry. Vault-backed secret *injection* and a
kernel-wide, `EX-CFG-1001`-driven "fail deterministically before the first network frame" guarantee are the
subsystem's stated design targets, not delivered behaviour today — see the callouts throughout this document
for what each currently is. What subsystems get today, ready for a caller to use, is the `ScopedValue` +
explicit-zeroing pattern (Code Example 4) and the redacted-`rawArgs` exception shape (`EX-CFG-1001`/`1002`) —
the mechanisms exist; a required-key bootstrap check wiring them in system-wide does not yet.

---

## Owning ADRs

- [ADR-071](../adr/ADR-071-operational-limit-configuration-path.md) — Give operational limits a configuration path, and rule what a zero means

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.config.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md): `ConfigProvider` / `KernelProfile` / `Dynamic` are
mature 0.5.0 contracts. The additive `@Immutable` annotation landed in v0.9 Sprint 5 (since 0.9.0) and is
classified **preview** — its enforcement is exercised by `DynamicConfigFileWatcherTest` and
`ImmutableConfigProcessorTest`, with a dedicated `AbstractConfigProviderTck` binding to follow. See the matrix
for the semver policy and TCK coverage status.

### Security-Relevant Key Catalog (`@Immutable` at GA)

The following key classes are trust anchors whose runtime mutation would be a security or correctness
hazard; they **MUST** carry `@Immutable` at 1.0 GA. The annotation and enforcement machinery ship in v0.9;
adopting it on each production key below is tracked as GA hardening (no such key is wired to a hot-reload
watcher today, so each is already effectively sealed):

| Trust anchor class            | Example keys                                         | Why sealed                                              |
|:------------------------------|:-----------------------------------------------------|:--------------------------------------------------------|
| Security / identity anchors   | JWKS URI, issuer allow-list, isolation strategy      | Runtime rotation would bypass issuer / fail-closed validation (ADR-012) |
| Tenant isolation boundaries   | `StorageContext` routing / isolation keys            | Mutation could cross-wire tenant data                   |
| Native library paths          | OpenSSL / FFM library load locations                 | Repointing at runtime is a code-execution surface       |

