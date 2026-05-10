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

- **PERMITTED:** For critical sections within off-heap memory pool internals (e.g., `SlabPool` slab
  reclamation, `SegmentPool` free-list management) where lock-free CAS sequences are susceptible to
  ABA problems — specifically, when a pointer can be reclaimed and reallocated between a `compareAndSet`
  load and its subsequent store, invalidating the assumption of pointer uniqueness. In these narrow cases,
  a brief `synchronized` block provides correctness guarantees that a single-word CAS cannot.
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

* **[-] High Barrier to Entry:** Contributing to the Kernel requires mastery of a specific set of non-standard
  engineering disciplines. Concretely, a contributor must understand:

  1. **Off-Heap Memory Release Rules:** Every `MemorySegment` acquired through `MemoryAllocator` is bound to
     an `Arena`. The contributor must understand the Arena lifecycle (confined vs. shared vs. auto), know when
     to call `LoanedBuffer.release()`, and understand that forgetting to do so is a **silent memory leak** — not
     a Java `OutOfMemoryError`. Over-releasing (double-free) results in `IllegalStateException` on the release
     path, but may cause a `SIGSEGV` if the underlying native pointer is reused by the allocator.

  2. **`LoanedBuffer` Contract and `Arena.ofConfined()` Ban:** Business logic must **never** call
     `Arena.ofConfined()` or `Arena.ofShared()` directly. All allocations flow through `MemoryAllocator` to
     guarantee tier-specific slab pooling and `LoanedBuffer` ref-count lifecycle. Any direct Arena usage
     bypasses the pool and breaks the Zero-Allocation Covenant.

  3. **Glass-Box Debugging via JFR:** Native FFM `downcall` frames are opaque to the standard JDWP debugger.
     Debugging a failed `SSL_do_handshake` or a corrupted `io_uring` SQE requires reading JFR event streams
     (custom `@StackTrace(false)` events emitted around every FFM callsite) and correlating them with
     `perf`/`bpftrace` traces on the kernel side. The standard "set a breakpoint and inspect" workflow
     does not apply to the off-heap execution path.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.
