# Kernel Subsystem: Config (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.config.*` (Interfaces, Annotations)
- Core: `eu.exeris.kernel.core.config.*` (Loaders, FileWatchers)
  **Layer:** L0 (Foundation)  
  **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Config subsystem** is the absolute lowest-level foundation of the Exeris Kernel. It initializes before any other
system (including Memory) and provides:

- **Hierarchical configuration loading** with strict precedence: ENV → File → Classpath
- **Zero-downtime hot-reload** via `@Dynamic` annotations and `java.nio.file.WatchService`
- **Lock-free updates** using `VarHandle` volatile semantics for O(1) read performance
- **Zero external dependencies** — pure Java, no heavy JSON/YAML parsers required in the hot path
- **Kubernetes-native** — designed for ConfigMap mounts at `/etc/exeris/config`

### Core Philosophy

All kernel subsystems depend on Config. Therefore, it must follow extreme constraints:

- **No Heavy Dependencies:** We do not use Spring Environment, Hibernate Validator, or heavy reflection.
- **Fast Initialization:** Must complete in milliseconds to allow the `MemoryBootstrap` to start.
- **Thread-Safe by Design:** All configuration reads in the Kernel happen without `synchronized` blocks.

---

## Responsibilities

**What Config DOES:**

1. Load configuration from multiple sources.
2. Merge configurations respecting the hierarchy (ENV wins over File wins over Classpath).
3. Provide type-safe extraction methods (`get()`, `getInt()`, `getBoolean()`, etc.) via SPI.
4. Watch the filesystem for changes and dynamically update fields annotated with `@Dynamic`.
5. Fail fast (`EX-CFG-1001`) if required properties are missing during T-minus 0 bootstrap.

**What Config DOES NOT DO:**

1. **No Dependency Injection:** It does not wire beans or manage lifecycles (that's `KernelBootstrap`'s job).
2. **No Expression Language:** It does not parse SpEL or complex logic within config files.

---

## Error Codes (Black Box Telemetry)

When configuration fails, it uses the standardized `EX-` error codes:

| Code          | Meaning                                        | Action                                |
|:--------------|:-----------------------------------------------|:--------------------------------------|
| `EX-CFG-1001` | Missing Required Property                      | Kernel halts immediately (FAIL_FAST). |
| `EX-CFG-1002` | Type Mismatch (e.g., expected INT, got STRING) | Kernel halts immediately.             |
| `EX-CFG-1003` | Hot-Reload File Read Error                     | Log warning, keep previous state.     |

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

### 2. Lock-Free Dynamic Reloading (Core)

```java
package eu.exeris.kernel.core.config;

import eu.exeris.kernel.spi.config.Dynamic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class KernelConfigRegistry {

    @Dynamic(key = "corelio.transport.timeout-ms")
    private volatile int connectionTimeoutMs = 5000;

    private static final VarHandle TIMEOUT_HANDLE;

    static {
        try {
            TIMEOUT_HANDLE = MethodHandles.lookup().findVarHandle(KernelConfigRegistry.class, "connectionTimeoutMs", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public int getConnectionTimeout() {
        return (int) TIMEOUT_HANDLE.getVolatile(this);
    }
}
```

## Testing Strategy

### Unit Tests

Configuration loading and merging precedence (ENV > File > Classpath)

VarHandle volatile updates accuracy

Fail-fast mechanism for missing required fields (EX-CFG-1001)

### Integration Tests

FileWatcher triggering hot-reload on file modification

Concurrent read/write safety (100 Virtual Threads reading while FileWatcher updates)

## Summary

The Config subsystem is the anchor of the Exeris Kernel. By using VarHandle and native file watching, it provides a
zero-overhead, K8s-ready configuration mechanism that doesn't block Carrier Threads and survives high-density traffic.