# Kernel Subsystem: Config (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.config.*` (Provider contracts, Key-Value schemas)
- Core: `eu.exeris.kernel.core.config.*` (Hot-reload orchestrator, JEP 513 validation)

**Layer:** L0 (Foundation)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Config subsystem** is the "Instruction Manual" of the Exeris Kernel. It guarantees that runtime parameters
(ports, off-heap arena sizes, backpressure limits) are delivered in an immutable and secure manner before the first
network frame is accepted.

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

- **Instrument-Aware:** Config knows what hardware it runs on. It can automatically size off-heap slab allocations
  based on detected CPU cache line widths (L1/L2), eliminating false sharing without manual tuning.
- **Deterministic T-Minus 0:** If a key marked `REQUIRED` is missing, the Kernel aborts bootstrap with
  `EX-CFG-1001` instead of propagating a `NullPointerException` deep into a subsystem initializer.
- **No Classpath Secrets:** Passwords and tokens are never stored in `.properties` files on the classpath. Exeris
  supports native injection from secure vaults directly into `ScopedValue` slots — the secret never touches the heap
  as a `String`.
- **CWE-532 REDACT Contract:** Any configuration value captured in `rawArgs` (e.g., for `EX-CFG-1002`) **MUST** be
  redacted or truncated by the caller before emission. Raw secrets, credentials, or tokens must never reach the
  binary telemetry dump. See `KernelErrorCodes.EX_CFG_1002` for the canonical enforcement comment.

---

## Key Characteristics

### Hot-Reload (`NIO WatchService`)
A file change triggers an atomic state reload in Core without a JVM restart — but **only** for keys annotated
`@Dynamic`. Keys annotated `@Immutable` are validated once at T-minus 0 and sealed for the lifetime of the process.

> **Note:** `@Immutable` is a planned annotation not yet present in the codebase. Currently, a config key is effectively immutable if it has no `@Dynamic` registration. The `@Immutable` annotation will provide explicit enforcement when implemented.

### JEP 513 Validation (Flexible Constructor Bodies — Closed/Delivered in JDK 25)
Type validation is performed via JEP 513 Flexible Constructor Bodies — environment and Vault state is validated
**before** the `super()` call reaches the base `Object` constructor. If a `REQUIRED` key is absent or malformed,
the Kernel aborts with `EX-CFG-1001` / `EX-CFG-1002` before allocating anything deeper in the object graph.
The secret never reaches a constructor argument if the precondition fails.

---

## Responsibilities

**What Config DOES:**

1. Load configuration from multiple sources and merge by strict precedence (ENV wins).
2. Provide type-safe extraction (`get()`, `getInt()`, `getBoolean()`) via SPI.
3. Watch the filesystem and atomically update fields annotated `@Dynamic`.
4. Abort bootstrap with `EX-CFG-1001` if any `REQUIRED` property is absent at T-minus 0.

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

**Privacy-First enforcement for `EX-CFG-1002`:** The caller constructing the exception **MUST** redact or truncate
`actualValue` before passing it to `rawArgs`. The Kernel runtime never performs this redaction automatically —
it is a strict caller contract. See `KernelErrorCodes.EX_CFG_1002` Javadoc for the canonical CWE-532 enforcement
comment.

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

Instead of `Map` lookups, Core uses `VarHandle` slots for direct field access. The `WatchService` thread updates the
field via `setRelease` on a reload event; every Virtual Thread reader uses `getAcquire` — an Acquire/Release barrier
is cheaper than a full `volatile` load-load/store-store fence, eliminating the redundant `volatile` modifier while
preserving the exact visibility guarantee required for a single-writer/multi-reader hot-path.

```java
// Actual implementation — see eu.exeris.kernel.core.config.KernelConfigRegistry
package eu.exeris.kernel.core.config;

public class KernelConfigRegistry {

    @Dynamic(key = "network.idleTimeoutMillis")
    private long idleTimeoutMillis = 30_000L;          // plain long — VarHandle owns the barrier

    private static final VarHandle IDLE_TIMEOUT_HANDLE;

    static {
        try {
            IDLE_TIMEOUT_HANDLE = MethodHandles.lookup()
                    .findVarHandle(KernelConfigRegistry.class, "idleTimeoutMillis", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** O(1) read — Acquire barrier only (no full fence). Called by millions of Virtual Threads. */
    public long getIdleTimeoutMillis() {
        return (long) IDLE_TIMEOUT_HANDLE.getAcquire(this);
    }

    /** Single-writer: WatchService thread only. Release barrier pairs with every getAcquire above. */
    void reloadIdleTimeoutMillis(long newValue) {
        IDLE_TIMEOUT_HANDLE.setRelease(this, newValue);
    }
}
```

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

> **Key name convention:** Keys are specified in the `ConfigProvider` API format (e.g. `network.port`).
> A typical community configuration provider maps these to system properties by prepending `exeris.` (e.g.
> `-Dexeris.network.port=9090`) and to environment variables by converting to
> `EXERIS_NETWORK_PORT` (uppercase, dots replaced with underscores). Future implementations
> may also load these from Vault or ConfigMap mounts.

| Key                                                | Type      | Default             | Reload       | Status      | Description                                              |
|:---------------------------------------------------|:----------|:-------------------:|:------------:|:-----------:|:---------------------------------------------------------|
| `globalMemoryMb`                                   | `long`    | auto (50% RAM in MB)| ❌ IMMUTABLE | ✅ WIRED    | Total off-heap arena budget (`KernelSettings.globalMemoryMb`) |
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
| `network.idleTimeoutMillis`                        | `long`    | `30000`             | ✅ DYNAMIC   | 🔲 planned  | Connection idle timeout (ms)                             |
| `network.proxyProtocolEnabled`                     | `boolean` | `false`             | ❌ IMMUTABLE | 🔲 planned  | Enable Proxy Protocol v2 parsing                         |
| `network.proxyProtocolRequired`                    | `boolean` | `false`             | ❌ IMMUTABLE | 🔲 planned  | Reject connections without PP2 header                    |
| `network.paqs.warningThreshold`                    | `float`   | `0.70`              | ✅ DYNAMIC   | 🔲 planned  | WM `WARNING` level (fraction of off-heap budget)         |
| `network.paqs.criticalThreshold`                   | `float`   | `0.85`              | ✅ DYNAMIC   | 🔲 planned  | WM `CRITICAL` level (fraction of off-heap budget)        |
| `network.paqs.sheddingThreshold`                   | `float`   | `0.95`              | ✅ DYNAMIC   | 🔲 planned  | WM `SHEDDING` level (fraction of off-heap budget)        |
| `network.paqs.endpointPriority.<path>`             | `string`  | `NORMAL`            | ✅ DYNAMIC   | 🔲 planned  | Static `StreamPriority` for path prefix                  |
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

> **Auto-detection:** `globalMemoryMb` defaults to 50% of available JVM process RAM
> (`Runtime.getRuntime().maxMemory() / 1_048_576 * 0.5`). Override explicitly in production for
> predictable behaviour under K8s memory limits.

> **Persistence helper note:** `PersistenceConfig.defaults(...)` is a fixed development/unit-test preset in the SPI helper API. It is not the Community runtime bootstrap default when `persistence.maxPoolSize` is unset.

---

## Vault Down-at-Boot Strategy

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

| Event                              | Maximum latency (P99)   | Measurement                                    |
|:-----------------------------------|:-----------------------:|:-----------------------------------------------|
| File change detected (`inotify`)   | ≤ 50 ms                | OS `inotify` → `WatchService` event            |
| Config value updated (`VarHandle`) | ≤ 1 µs                 | `IDLE_TIMEOUT_HANDLE.setRelease()` — single CAS     |
| End-to-end reload visible          | ≤ 100 ms               | From filesystem write to `getAcquire()` read   |

> **`inotify` note:** On Linux, `WatchService` uses `inotify` — kernel-level file system change
> notification. Latency is typically < 10 ms on a locally mounted filesystem. NFS-mounted ConfigMaps
> in Kubernetes may have higher latency depending on mount options and poll intervals.

### Audit Log — JFR Event

Every hot-reload of a `@Dynamic` key emits a JFR event. This satisfies audit requirements in
regulated environments (fintech, healthcare) without logging raw values.

Two event classes are emitted (from `eu.exeris.kernel.core.config.jfr.DynamicReloadEvent`):

```java
// Emitted on successful hot-reload
@jdk.jfr.Label("Config Dynamic Field Reloaded")
@jdk.jfr.Category({"Exeris Kernel", "Config"})
@jdk.jfr.StackTrace(false)
public final class DynamicFieldReloadedEvent extends jdk.jfr.Event {
    String file;
    String key;
    long durationUs;
    // NOTE: old/new values are NEVER included — CWE-532 contract
}

// Emitted on reload failure
@jdk.jfr.Label("Config Dynamic Reload Failed")
@jdk.jfr.Category({"Exeris Kernel", "Config"})
@jdk.jfr.StackTrace(false)
public final class DynamicReloadFailedEvent extends jdk.jfr.Event {
    String file;
    String key;
    String reason;
    // NOTE: value is intentionally excluded — CWE-532 compliance
}
```

> **Note:** `telemetry.md` had this event as planned/TRL-4 under the name `ConfigHotReloadEvent`, but it is now implemented under `DynamicFieldReloadedEvent` and `DynamicReloadFailedEvent`.

The events record **which key changed** and **which file triggered the reload**, but never
the old or new value itself. In regulated environments, this event stream is the config audit log.

---

## Testing Strategy

### Unit Tests

- Configuration loading and merging precedence (ENV → Vault → File → Classpath).
- `VarHandle` volatile update accuracy under concurrent reads.
- Fail-fast for missing `REQUIRED` fields (`EX-CFG-1001` with correct `rawArgs` layout).
- Type mismatch detection (`EX-CFG-1002`) with redacted `actualValue` — verified that raw value is NOT present.

### Integration Tests

- `FileWatcher` triggering hot-reload on file modification (`@Dynamic` keys only).
- Concurrent read/write safety: 100 Virtual Threads reading while `FileWatcher` updates.
- `@Immutable` keys rejected on hot-reload attempt (sealed after T-minus 0).

> **Note:** `AbstractConfigProviderTck` currently covers structural contract only (LazyConstant, banned parsers, watch() no-op contract). EX-CFG-1001 and EX-CFG-1002 path coverage lives at the unit level in `exeris-kernel-spi` tests. Full TCK coverage pending.

---

## Summary

The Config subsystem is the anchor of the Exeris Kernel. By combining `VarHandle`-based lock-free reads, `NIO`
filesystem watching, Vault-native secret injection, and a strict CWE-532 redaction contract, it delivers a
zero-overhead, K8s-ready configuration mechanism that does not block Carrier Threads, does not leak secrets into
telemetry, and fails deterministically before the first network frame is ever accepted.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.config.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md): `ConfigProvider` / `KernelProfile` / `Dynamic` are
mature 0.5.0 contracts. The additive `@Immutable` annotation arriving in v0.9 Sprint 5 will be
marked **preview** on landing until its TCK lands. See the matrix for the semver policy and TCK
coverage status.

