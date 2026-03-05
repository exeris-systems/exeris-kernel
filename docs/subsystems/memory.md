# Kernel Subsystem: Memory (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.memory.*` (Allocators, Clusters, LoanedBuffer)
- Core: `eu.exeris.kernel.core.memory.*` (Watermarks, ResourceArbiter, AbstractLoanedBuffer)
- Drivers: `exeris-kernel-community` / `exeris-kernel-enterprise`

**Layer:** L0 (Foundation)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Memory subsystem** implements the **Zero-Allocation Memory Model** for the Exeris Kernel using Java 26 Panama
FFM (Foreign Function & Memory API). Instead of a monolithic manager, memory is handled via a strict SPI-driven provider
model. It provides:

- **Pluggable Allocation Strategies:** Drivers implement `MemoryAllocator` and `NetworkBufferCluster` (e.g., standard
  Arenas in Community, global `mmap` rings in Enterprise).
- **Loan Pattern (RAII):** Automatic reference counting via `LoanedBuffer` and VarHandle-based atomics (zero GC
  pressure).
- **Paranoid Leak Detector:** Integrated `java.lang.ref.Cleaner` to detect unclosed Off-Heap segments.
- **Watermark & Resource Arbiter:** Core intelligence that monitors memory exhaustion and triggers load shedding.

### Core Philosophy: "No Waste Compute"

Every byte allocated must serve a purpose:

- **Zero GC Churn:** No object allocations on the I/O hot-path. Data flows via `LoanedBuffer` references.
- **Zero Copy:** Data is never copied to the JVM Heap unless interacting with legacy Java systems.
- **Deterministic Lifecycle:** Reference counting via `VarHandle` atomics ensures memory is returned to native pools
  immediately after use.
- **Implementation Blindness:** Core operates exclusively on the `MemoryAllocator` interface injected via
  `ServiceLoader`. It must never know whether it is backed by a `PanamaArenaAllocator` (Community) or a
  `GlobalMemoryArbiter` `mmap` ring (Enterprise).

---

## Responsibilities

**What Memory SPI/Core DOES:**

1. Define strict contracts for allocation (`AllocationHint`, `PartitionedPool`).
2. Provide lock-free base classes (`AbstractLoanedBuffer`).
3. Monitor memory pressure via `WatermarkManager`.
4. Decide when to shed load (`ResourceArbiter`).

**What Memory Drivers DO:**

1. Allocate and manage physical Off-Heap memory.
2. Maintain pools of pre-sliced slabs for O(1) network buffer allocation.
3. Integrate with specific hardware/OS capabilities.

---

## Error Codes (Glass-Box Telemetry)

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. The `rawArgs` binary layout is defined
> in the Javadoc of each constant and must not diverge from this table.

| Code          | Meaning                  | Action                                          | Glass-Box Payload (`rawArgs`)                        |
|:--------------|:-------------------------|:------------------------------------------------|:-----------------------------------------------------|
| `EX-MEM-1001` | Off-heap Exhausted       | Trigger `H3_EXCESSIVE_LOAD` backpressure.       | `[0] long requestedBytes, [1] long availableBytes`   |
| `EX-MEM-1002` | Arena Leak Detected      | Log in `PARANOID` mode with native stack trace. | `[0] long segmentAddress, [1] long segmentByteSize`  |
| `EX-MEM-1003` | AllocationHint Conflict  | Reject allocation request (fallback or fail).   | *(no rawArgs)*                                       |

---

## The Loan Pattern (RAII)

Exeris replaces `byte[]` with `LoanedBuffer`. Applications must use the `try-with-resources` pattern to ensure
deterministic cleanup. Applications interact only with the SPI — the underlying allocator is invisible.

```java
try (LoanedBuffer buffer = allocator.allocate(AllocationHint.MEDIUM)) {
    buffer.writeBytes(payload, 0, payload.length);
    transport.send(buffer);
}
```

### Async Ownership Transfer (StructuredTaskScope)

When passing a `LoanedBuffer` to a subtask forked inside a `StructuredTaskScope`, you **MUST** explicitly retain
ownership before forking. The scope's `join()` barrier does not manage buffer lifetimes.

```java
try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())) {
    try (LoanedBuffer buffer = networkCluster.allocate()) {
        buffer.retain();

        scope.fork(() -> {
            try {
                return processAsync(buffer);
            } finally {
                buffer.close();
            }
        });

        scope.join();
    }
}
```

> If a subtask outlives its parent scope (advanced orchestration only), `retain()` is mandatory to prevent a
> use-after-free on the native segment.

---

## Multi-Tier Memory Strategy

| Tier           | Allocator                                                                     | GC Handshakes on hot-path | Use Case                          |
|:---------------|:------------------------------------------------------------------------------|:--------------------------|:----------------------------------|
| **Community**  | `MemoryAllocator` via `KernelProviders.MEMORY_ALLOCATOR` (shared, bounded)   | Low (JVM thread-local)    | Standard TCP, JDBC persistence    |
| **Enterprise** | `GlobalMemoryArbiter` `mmap`                                                  | **Zero**                  | `io_uring`, native DB driver, HFT |

---

## Code Examples

### 1. Subsystem Registration via ServiceLoader

Core never directly instantiates an allocator. It receives it through the SPI discovery chain.

```java
MemoryAllocator allocator = ServiceLoader.load(MemoryAllocator.class)
        .findFirst()
        .orElseThrow(() -> new KernelBootstrapException(KernelErrorCodes.EX_BOOT_0002));
```

### 2. WatermarkManager Integration

```java
public class ResourceArbiter {

    private final WatermarkManager watermark;
    private final MemoryAllocator allocator;

    public LoanedBuffer tryAllocate(AllocationHint hint) {
        if (watermark.isHighWatermarkBreached()) {
            throw new MemoryExhaustedException(hint.bytes(), watermark.availableBytes());
        }
        return allocator.allocate(hint);
    }
}
```

---

## Testing Strategy

### Unit Tests

- Reference counting (`retain`/`close` logic in `AbstractLoanedBuffer`).
- `VarHandle` thread-safety under concurrent modifications.
- FFM memory bounds checking (preventing out-of-bounds reads/writes).

### Integration Tests (TCK)

- Multiple Virtual Threads allocating/deallocating concurrently.
- Arena exhaustion (graceful `MemoryExhaustedException` with correct `EX-MEM-1001` code).
- `LeakTracker` correctly identifying dropped buffers in `PARANOID` mode (`EX-MEM-1002`).

### Load Tests

- 100k allocate/close cycles per second with < 1ms P99 latency.
- GC pressure baseline verified via JFR (must remain near 0 B/req).

---

## Summary

The Memory subsystem provides the foundation for hyper-density execution. By enforcing the `LoanedBuffer` pattern
through SPI and resolving allocator implementations via `ServiceLoader`, it guarantees that Exeris can scale to millions
of concurrent connections without triggering stop-the-world Garbage Collection pauses — regardless of which driver tier
is active.
