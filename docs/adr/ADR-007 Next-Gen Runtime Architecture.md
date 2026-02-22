# ADR-007: Next-Gen Runtime Architecture (Exeris Kernel)

| Atrybut        | Wartość                                                |
|:---------------|:-------------------------------------------------------|
| **Status**     | **ACCEPTED**                                           |
| **Deciders**   | Arkadiusz Przychocki                                   |
| **Date**       | 2025-12-11 (Updated: 2026-02-22)                       |
| **Driven By**  | RFC-2025-12-11: Next-Gen Runtime Architecture          |
| **Compliance** | [Strategic Pillar: No-Waste Compute](../whitepaper.md) |

## Context and Problem Statement

To achieve "Hyper-Density" (100k+ concurrent connections/node), legacy Java frameworks (Netty/WebFlux) are insufficient
due to "Callback Hell", JNI overhead, and massive heap allocation. We need a runtime that leverages the full power of *
*Java 26** to achieve zero-copy, zero-allocation, and imperative simplicity.

## 🏁 The Decision

We build the **Exeris Kernel** as a tiered, vertical architecture ("The Wall"), rejecting traditional monolithic
designs.

### 1. Tiered Vertical Layout ("The Wall")

We strictly separate the system into:

- **SPI:** Pure contracts and value records.
- **Core:** Protocol-agnostic orchestration (The Brain).
- **Drivers (Community/Enterprise):** Protocol-specific execution (The Muscle).

### 2. Concurrency & Context

- **Virtual Threads:** 1:1 Request-to-Thread mapping (Project Loom).
- **Scoped Values:** Immutable, Virtual Thread-safe context propagation (JEP 506). `ThreadLocal` is **BANNED**.

### 3. Protocol-Agnostic Transport

We reject the idea of a "QUIC-only" kernel.

- **Community Tier:** Standard **TCP / HTTP/2** using non-blocking NIO.2.
- **Enterprise Tier:** **QUIC / HTTP/3** using **Panama FFM** and **io_uring** for direct kernel-bypass I/O (RFC 9000).
- **PAQS:** Priority-Aware Queue Scheduler integrated into the transport edge.

### 4. Memory Management (Loan Pattern)

- **Zero-Allocation:** Buffers are never "owned" by business logic; they are "loaned" via `LoanedBuffer` (SPI).
- **FFM API:** All high-performance I/O happens in **Off-Heap** segments.
- **Ref-Counting:** VarHandle-based atomic reference counting to eliminate GC pressure.

## ⚠️ Amendment: JEP 491 & Synchronization

**Update (Java 24+):** `synchronized` is no longer a global taboo.

- `synchronized` is **PERMITTED** for internal, short-lived memory operations (pools, queues) where lock-free ABA
  problems are too risky.
- `synchronized` is **STRICTLY BANNED** around native FFM downcalls or any blocking I/O, as it may still induce pinning.

## Positive Outcomes

- **Zero-Copy Hot Path:** Data moves from NIC to DB-driver without hitting the JVM Heap.
- **Operational Clarity:** Standard stack traces and `EX-` error codes replace reactive "debug-hell".
- **Hardware Efficiency:** Direct mapping to NUMA nodes and CPU cache lines in the Enterprise tier.

## Trade-offs / Risks

- **Tier Fragmentation:** Maintaining two transport stacks (TCP vs QUIC) increases testing surface.
- **FFM Stability:** Native memory management requires "Paranoid" leak detection during development.