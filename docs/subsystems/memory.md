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

### Core Philosophy

**"No Waste Compute"** — every byte allocated must serve a purpose:

- **Zero GC Churn:** No object allocations in the hot path. I/O must use `LoanedBuffer`.
- **Zero Copy:** Data is never copied to `byte[]` on the Heap unless interacting with legacy systems.
- **Implementation Blindness:** The Kernel (Core) must never know if it's using a basic `PanamaArenaAllocator` or an HPC
  `io_uring` allocator.

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

## Error Codes (Black Box Telemetry)

| Code          | Meaning                       | Action                                    |
|:--------------|:------------------------------|:------------------------------------------|
| `EX-MEM-1001` | Off-Heap Memory Leak Detected | Logged in PARANOID mode with stack trace. |
| `EX-MEM-1002` | Memory Exhausted (OOM)        | Trigger `H3_EXCESSIVE_LOAD` backpressure. |
| `EX-MEM-1003` | Invalid Buffer Size/Offset    | Halt current operation (prevent SIGSEGV). |

---

## Code Examples

### 1. The Loan Pattern (Application Code)

Applications interact only with the SPI, using `try-with-resources` to guarantee deterministic cleanup.

```java
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

public class RequestHandler {

    public void process(MemoryAllocator allocator, byte[] payload) {
        // Rent a buffer from the pool (Zero-Allocation)
        try (LoanedBuffer buffer = allocator.allocate(AllocationHint.MEDIUM)) {

            // Write directly to Off-Heap (Zero-Allocation bulk copy)
            buffer.writeBytes(payload, 0, payload.length);

            // Pass to transport layer (Zero-Copy)
            transport.send(buffer);

        } // AutoCloseable calls buffer.close() -> refCount--, returns to pool
    }
}
```

### 2. Retaining Ownership across Threads

```java
try (LoanedBuffer buffer = networkCluster.allocate()) {
    buffer.retain(); // refCount = 2
    
    Thread.startVirtualThread(() -> {
        try {
            processAsync(buffer);
        } finally {
                buffer.close(); // refCount = 1
        }
    });
} // refCount = 0 -> returned to pool
```

## Testing Strategy

### Unit Tests

Reference counting (retain/close logic in AbstractLoanedBuffer).

VarHandle thread-safety under concurrent modifications.

FFM memory bounds checking (preventing out-of-bounds reads/writes).

### Integration Tests (TCK)

Multiple Virtual Threads allocating/deallocating concurrently.

Arena exhaustion (graceful MemoryExhaustedException).

LeakTracker correctly identifying dropped buffers in PARANOID mode.

### Load Tests

100k allocate/close cycles per second with < 1ms P99 latency.

GC pressure baseline verified via JFR (must remain near 0 B/req).

## Summary

The Memory subsystem provides the foundation for hyper-density execution. By enforcing the LoanedBuffer pattern through
SPI, it guarantees that Exeris can scale to millions of concurrent connections without triggering stop-the-world Garbage
Collection pauses.