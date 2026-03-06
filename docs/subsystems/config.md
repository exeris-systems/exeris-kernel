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
package eu.exeris.kernel.spi.config;

public interface MemoryProviderConfig {
    long globalMemoryBytes();
    int networkOffHeapThreshold();
    boolean leakDetectionEnabled();
}
```

### 2. Secure Property Access with Fail-Fast (Core)

```java
public int getNetworkPort() {
    return config.getOptional("network.port")
                 .map(PropertyValue::asInt)
                 .orElseThrow(() -> new ConfigMissingException(
                         KernelErrorCodes.EX_CFG_1001, "network.port"));
}
```

### 3. Lock-Free Dynamic Reloading (Core)

Instead of `Map` lookups, Core uses `VarHandle` slots for direct field access. The `WatchService` thread updates the
field via `setRelease` on a reload event; every Virtual Thread reader uses `getAcquire` — an Acquire/Release barrier
is cheaper than a full `volatile` load-load/store-store fence, eliminating the redundant `volatile` modifier while
preserving the exact visibility guarantee required for a single-writer/multi-reader hot-path.

```java
package eu.exeris.kernel.core.config;

public class KernelConfigRegistry {

    @Dynamic(key = "network.idleTimeoutMillis")
    private int connectionTimeoutMs = 30_000;          // plain int — VarHandle owns the barrier

    private static final VarHandle TIMEOUT_HANDLE;

    static {
        try {
            TIMEOUT_HANDLE = MethodHandles.lookup()
                    .findVarHandle(KernelConfigRegistry.class, "connectionTimeoutMs", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** O(1) read — Acquire barrier only (no full fence). Called by millions of Virtual Threads. */
    public int getConnectionTimeout() {
        return (int) TIMEOUT_HANDLE.getAcquire(this);
    }

    /** Single-writer: WatchService thread only. Release barrier pairs with every getAcquire above. */
    void reloadConnectionTimeout(int newValue) {
        TIMEOUT_HANDLE.setRelease(this, newValue);
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

The table below lists all configuration keys consumed **internally** by the Exeris Kernel itself.
Application-level keys are defined by the application layer and not listed here.

> **Key name convention:** Keys are specified in the `ConfigProvider` API format (e.g. `network.port`).
> `CommunityConfigProvider` maps these to system properties by prepending `exeris.` (e.g.
> `-Dexeris.network.port=9090`) and to environment variables by converting to
> `EXERIS_NETWORK_PORT` (uppercase, dots replaced with underscores). Enterprise implementations
> may also load these from Vault or ConfigMap mounts.

| Key                                                | Type      | Default             | Reload    | Description                                              |
|:---------------------------------------------------|:----------|:-------------------:|:---------:|:---------------------------------------------------------|
| `bootstrap.healthPort`                             | `int`     | `9090`              | ❌ IMMUTABLE | HTTP health probe port                                |
| `bootstrap.failFast`                               | `boolean` | `true`              | ❌ IMMUTABLE | FAIL_FAST vs DEGRADE on subsystem init failure        |
| `network.port`                                     | `int`     | `8080`              | ❌ IMMUTABLE | Data-plane TCP/QUIC port                              |
| `network.idleTimeoutMillis`                        | `long`    | `30000`             | ✅ DYNAMIC   | Connection idle timeout (ms)                          |
| `network.nativeTransportPreferred`                 | `boolean` | `false`             | ❌ IMMUTABLE | Hint to prefer native async I/O transport             |
| `network.reactorCount`                             | `int`     | auto (≤ 4 CPUs)     | ❌ IMMUTABLE | Number of carrier reactor threads                     |
| `network.quicEnabled`                              | `boolean` | `false`             | ❌ IMMUTABLE | Enable QUIC/HTTP3 (Enterprise only)                   |
| `network.proxyProtocolEnabled`                     | `boolean` | `false`             | ❌ IMMUTABLE | Enable Proxy Protocol v2 parsing                      |
| `network.proxyProtocolRequired`                    | `boolean` | `false`             | ❌ IMMUTABLE | Reject connections without PP2 header                 |
| `network.paqs.warningThreshold`                    | `float`   | `0.70`              | ✅ DYNAMIC   | WM `WARNING` level (fraction of off-heap budget)      |
| `network.paqs.criticalThreshold`                   | `float`   | `0.85`              | ✅ DYNAMIC   | WM `CRITICAL` level (fraction of off-heap budget)     |
| `network.paqs.sheddingThreshold`                   | `float`   | `0.95`              | ✅ DYNAMIC   | WM `SHEDDING` level (fraction of off-heap budget)     |
| `network.paqs.endpointPriority.<path>`             | `string`  | `NORMAL`            | ✅ DYNAMIC   | Static `StreamPriority` for path prefix               |
| `globalMemoryMb`                                   | `long`    | auto (50% RAM in MB)| ❌ IMMUTABLE | Total off-heap arena budget                           |
| `memory.watermarkPollIntervalMs`                   | `int`     | `50`               | ✅ DYNAMIC   | `WatermarkManager` sampling interval                  |
| `telemetry.jfrEnabled`                             | `boolean` | `true`              | ❌ IMMUTABLE | Enable JFR telemetry sink                             |
| `telemetry.allocationSampleRate`                   | `double`  | `0.01`              | ✅ DYNAMIC   | JFR allocation event sampling rate (0.0–1.0)          |
| `telemetry.consoleSinkEnabled`                     | `boolean` | `false`             | ❌ IMMUTABLE | Enable Console telemetry sink                         |
| `memory.leakDetection`                             | `string`  | `SAMPLED`           | ❌ IMMUTABLE | `DISABLED`, `SAMPLED`, `PARANOID`                     |
| `crypto.tls.minVersion`                            | `string`  | `TLSv1.3`           | ❌ IMMUTABLE | Minimum TLS version accepted                          |
| `persistence.pool.maxSize`                         | `int`     | `20`                | ❌ IMMUTABLE | JDBC connection pool max connections                  |
| `persistence.pool.connectionTimeoutMs`             | `int`     | `5000`              | ✅ DYNAMIC   | JDBC pool acquisition timeout                         |
| `persistence.pool.idleTimeoutMs`                   | `int`     | `600000`            | ✅ DYNAMIC   | JDBC pool idle connection timeout                     |
| `persistence.pool.keepaliveMs`                     | `int`     | `30000`             | ✅ DYNAMIC   | JDBC pool keepalive heartbeat interval                |
| `persistence.outbox.maxRetries`                    | `int`     | `10`                | ✅ DYNAMIC   | Max Outbox delivery retries before DLQ               |
| `persistence.outbox.backoffBaseMs`                 | `int`     | `100`               | ✅ DYNAMIC   | Outbox retry base backoff (ms)                       |
| `config.vault.timeoutMs`                           | `int`     | `3000`              | ❌ IMMUTABLE | Vault connection timeout during bootstrap             |
| `config.vault.retryCount`                          | `int`     | `3`                 | ❌ IMMUTABLE | Vault connection retry attempts before FAIL_FAST      |
| `flow.saga.globalParkTimeoutMs`                    | `long`    | `1800000` (30 min)  | ✅ DYNAMIC   | Max Saga park duration before timeout compensation    |
| `crashDir`                                         | `string`  | platform default    | ❌ IMMUTABLE | Glass-Box crash buffer directory (also: `EXERIS_CRASH_DIR` ENV) |

> **Auto-detection:** `globalMemoryMb` defaults to 50% of available JVM process RAM
> (`Runtime.getRuntime().maxMemory() / 1_048_576 * 0.5`). Override explicitly in production for
> predictable behaviour under K8s memory limits.

---

## Vault Down-at-Boot Strategy

When Vault is unavailable during the bootstrap phase (`exeris.config.vault.timeout-ms` exceeded):

| Mode              | Behaviour                                                                                         |
|:------------------|:--------------------------------------------------------------------------------------------------|
| `FAIL_FAST` (default) | `EX-CFG-1001` thrown after `vault.retry-count` attempts × `vault.timeout-ms` deadline. Kernel halts. K8s liveness probe returns `503` → pod is replaced. |
| `DEGRADE`         | Last-known configuration (from file/classpath) is used for secrets. A `KernelBootstrapEvent` WARNING is emitted in JFR. **NEVER deploy DEGRADE mode to production** — it means the application starts with potentially stale or empty secrets. |

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
| Config value updated (`VarHandle`) | ≤ 1 µs                 | `TIMEOUT_HANDLE.setRelease()` — single CAS     |
| End-to-end reload visible          | ≤ 100 ms               | From filesystem write to `getAcquire()` read   |

> **`inotify` note:** On Linux, `WatchService` uses `inotify` — kernel-level file system change
> notification. Latency is typically < 10 ms on a locally mounted filesystem. NFS-mounted ConfigMaps
> in Kubernetes may have higher latency depending on mount options and poll intervals.

### Audit Log — JFR Event

Every hot-reload of a `@Dynamic` key emits a JFR event. This satisfies audit requirements in
regulated environments (fintech, healthcare) without logging raw values.

```java
@jdk.jfr.Label("Config Hot-Reload")
@jdk.jfr.Category({"Exeris", "Config"})
@jdk.jfr.StackTrace(false)
public final class ConfigHotReloadEvent extends jdk.jfr.Event {
    String configKey;
    String providerName;   // "file", "env", "vault"
    boolean succeeded;
    // NOTE: old/new values are NEVER included — CWE-532 contract
}
```

The event records **which key changed** and **which provider delivered the new value**, but never
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

---

## Summary

The Config subsystem is the anchor of the Exeris Kernel. By combining `VarHandle`-based lock-free reads, `NIO`
filesystem watching, Vault-native secret injection, and a strict CWE-532 redaction contract, it delivers a
zero-overhead, K8s-ready configuration mechanism that does not block Carrier Threads, does not leak secrets into
telemetry, and fails deterministically before the first network frame is ever accepted.

