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

### JEP 513 Validation (Early Construction)
Type validation is performed via Early Construction before the config object is observable by any other subsystem.
If the file contains `"abc"` instead of a port integer, the Kernel aborts with `EX-CFG-1002` (Type Mismatch) and
the `actualValue` field in `rawArgs` is caller-redacted before emission.

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

## Error Codes (Black Box Telemetry)

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
`volatile` field on a reload event; every Virtual Thread reader gets O(1) performance with zero contention.

```java
package eu.exeris.kernel.core.config;

public class KernelConfigRegistry {

    @Dynamic(key = "exeris.transport.timeout-ms")
    private volatile int connectionTimeoutMs = 5000;

    private static final VarHandle TIMEOUT_HANDLE;

    static {
        try {
            TIMEOUT_HANDLE = MethodHandles.lookup()
                    .findVarHandle(KernelConfigRegistry.class, "connectionTimeoutMs", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public int getConnectionTimeout() {
        return (int) TIMEOUT_HANDLE.getVolatile(this);
    }
}
```

### 4. No Classpath Secrets — Vault Injection via ScopedValue

```java
public static final ScopedValue<VaultToken> VAULT_TOKEN = ScopedValue.newInstance();

ScopedValue.where(VAULT_TOKEN, vault.acquire()).run(() -> {
    config.loadSecrets(VAULT_TOKEN.get());
});
```

> The `VaultToken` reference is bound to the structured scope lifetime. When the scope exits, the reference becomes
> unreachable and is eligible for GC — the secret never persists beyond the bootstrap phase.

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

