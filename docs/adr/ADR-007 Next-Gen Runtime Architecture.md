# ADR-007: Next-Gen Runtime Architecture (Exeris Kernel)

| Atrybut        | Wartość                                                |
|:---------------|:-------------------------------------------------------|
| **Status**     | **ACCEPTED**                                           |
| **Deciders**   | Arkadiusz Przychocki                                   |
| **Date**       | 2025-12-11 (Updated: 2026-02-22)                       |
| **Driven By**  | RFC-2025-12-11: Next-Gen Runtime Architecture          |
| **Compliance** | [Strategic Pillar: No-Waste Compute](../whitepaper.md) |

## Context and Problem Statement

To achieve **"Hyper-Density"** (100k+ concurrent connections per node), legacy Java frameworks (Netty/WebFlux) are
insufficient due to **"Callback Hell"**, JNI overhead, and massive heap allocations. We need a runtime that leverages
the full power of **Java 26** to achieve zero-copy, zero-allocation, and imperative code simplicity.

## 🏁 The Decision

We build the **Exeris Kernel** as a tiered, vertical architecture (**"The Wall"**), rejecting traditional monolithic
designs.

### 1. Tiered Vertical Layout ("The Wall")

Strict separation into layers by trust and execution role:

- **SPI:** Pure contracts and value records. Zero implementation details.
- **Core:** Protocol-agnostic orchestration (The Brain).
- **Drivers (Community/Enterprise):** Protocol-specific execution (The Muscle).

### 2. Execution Model

We reject event loops in favor of the **Virtual Thread-per-request** model (Project Loom).

- **Virtual Threads (JEP 444/491):** 1:1 Request-to-Thread mapping. ~100 bytes of memory vs. ~1 MB for OS threads.
- **Scoped Values (JEP 506):** Immutable, Virtual Thread-safe context propagation. `ThreadLocal` is **BANNED**.
- **Structured Concurrency (JEP 525):** All parallel operations are strictly bound within a
  `StructuredTaskScope`. The sole exception is PAQS ingress: `StructuredTaskScope.fork()` enforces
  `WrongThreadException` for any caller that did not open the scope, making a shared long-lived STS
  incompatible with the multi-carrier ingress model (NIO selectors + io_uring rings calling `schedule()`
  concurrently). Per-stream VTs spawned by PAQS act as Request Tree roots; all operations within them
  MUST use `StructuredTaskScope`.

### 3. Protocol-Agnostic Transport (L2)

We reject the concept of a "QUIC-only" kernel.

- **Community Tier:** Standard **TCP / HTTP/2** based on non-blocking NIO.2.
- **Enterprise Tier:** **QUIC / HTTP/3** using **Panama FFM** and **io_uring** for direct kernel-bypass I/O
  (RFC 9000).
- **PAQS:** Priority-Aware Queue Scheduler integrated at the transport edge.

### 4. Memory Management (Loan Pattern)

We abandon buffer ownership by business logic. Buffers are exclusively **"loaned"**.

- **Zero-Allocation:** Buffers are never "owned" by business logic; they are "loaned" via `LoanedBuffer` (SPI).
- **FFM API:** All high-performance I/O takes place in **Off-Heap** segments (`MemorySegment`).
- **Ref-Counting:** `VarHandle`-based atomic reference counting eliminates GC pressure.
- **MemoryAllocator:** All allocations MUST go through `MemoryAllocator`. Direct use of `Arena.ofConfined()` or
  `Arena.ofShared()` in business logic is **BANNED**.

## ⚠️ Amendment: JEP 491 & Synchronization

**Update (Java 24+):** The `synchronized` keyword is no longer a global taboo.

- **PERMITTED:** For internal, short-lived memory operations (pools, queues) where lock-free ABA risks are too high.
- **STRICTLY BANNED:** Around native FFM `downcall` invocations or any blocking I/O, as this can still induce
  **Thread Pinning**.

## Consequences

### ✅ Positive Outcomes

* **[+] Zero-Copy Hot Path:** Data moves from the NIC to the DB driver without hitting the JVM Heap.
* **[+] Operational Clarity:** Standard stack traces and deterministic error codes (`EX-`) replace reactive
  "debug-hell".
* **[+] Hardware Efficiency:** Direct mapping to NUMA nodes and CPU cache lines in the Enterprise tier.

### ⚠️ Trade-offs

* **[-] Tier Fragmentation:** Maintaining two transport stacks (TCP vs. QUIC) increases the testing surface.
* **[-] High Barrier to Entry:** Developers entering the Kernel must fully master JEP 454 (FFM) and Off-Heap memory
  release rules to prevent `SIGSEGV` or arena leaks.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.
