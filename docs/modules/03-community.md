# Physical Tier: Community (The Muscle)

**Module:** `exeris-kernel-community`
**Dependencies:** `exeris-kernel-spi` ONLY.

## 💪 Architectural Rules (L0 Enforcement)

1. **Panama-Powered TCP:** This tier provides a custom, high-performance Off-Heap TCP network stack. It leverages
   JEP 454 (FFM) to bind directly to OS sockets and OpenSSL, completely bypassing standard Java NIO buffers.
2. **Zero-Allocation Network Path (Best-Effort):** Ingress and Egress traffic targets zero heap allocation. Packets are
   written directly to `PanamaArenaAllocator` and passed via `LoanedBuffer`. However, integrations with external
   frameworks (JDBC, JFR) may generate bounded, predictable allocations that are monitored by the TCK and must not
   exceed safe thresholds.
3. **The Heap Boundary (JDBC):** While the transport layer is strictly zero-copy, standard integrations (like JDBC
   persistence) remain on the JVM heap. The Garbage Collector will only trigger for business logic and legacy database
   drivers.
4. **No Kernel-Bypass:** Uses standard POSIX networking (`epoll`/`kqueue`). Advanced kernel-bypass techniques
   (`io_uring`, strictly off-heap custom DB drivers) are reserved for the Enterprise module.
5. **No Direct Core Access:** Community drivers cannot access `exeris-kernel-core` internals. They strictly implement
   the SPI contracts.

## 📊 Allocation Contract (Community vs. Enterprise)

| Feature               | Community (Best-Effort)                    | Enterprise (Zero-GC)                          |
|-----------------------|--------------------------------------------|-----------------------------------------------|
| **Network I/O**       | Zero-Alloc (Off-Heap TCP + OpenSSL/FFM)    | Zero-Alloc + Kernel-Bypass (`io_uring`)       |
| **Telemetry**         | Bounded (~65 bytes / JFR event)            | Strict Zero (Binary Ring-Buffer off-heap)     |
| **Persistence**       | JDBC overhead (heap-bound)                 | Strict Zero (Native DB driver)                |
| **Context**           | `ScopedValue` (low overhead)               | `ScopedValue` + Off-Heap Arena                |

## 🎯 Open-Core Strategy

The Community tier is an engineering-grade, production-ready engine — not a crippled fallback. It eliminates the
Garbage Collector from the network hot-path entirely (the bottleneck for 95% of applications). The upgrade path to
Enterprise targets the remaining bottlenecks: OS-level syscall overhead (solved by `io_uring`) and heap-bound DB
drivers (solved by the native persistence driver).
