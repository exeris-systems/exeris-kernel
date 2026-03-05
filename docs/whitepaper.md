# Exeris Kernel: The Vision & Whitepaper

**Author:** Arkadiusz Przychocki, Founder & Lead Architect  
**Status:** TRL-3 (Validated Architectural Prototype)  
**Target:** Java 26+

---

## 1. Executive Summary

### 1.1 The Problem: Software Inflation & The Abstraction Tax

Modern cloud computing is suffering from a silent crisis: **"Software Inflation"**. For decades, the industry
prioritized developer convenience over hardware efficiency, leading to a critical execution tax.

- **The Inflation Factor:** Processing a 4GB network payload often triggers >160GB of temporary heap allocations just
  for wrappers, POJOs, and DTOs.
- **The Consequence:** Infrastructure is massively over-provisioned. We pay for 16GB RAM cloud nodes to run workloads
  that functionally require megabytes, just to buffer the Garbage Collection (GC) pauses.

### 1.2 The Insight: Data-Oriented Design (DOD)

**Data is a Stream, not an Object.** Exeris rejects the dogma that every byte from the network must be immediately
deserialized into a Java Object. By treating data as primitive binary payloads managed entirely Off-Heap, we eliminate
the translation layer completely.

### 1.3 The Solution: Exeris Kernel

Exeris is a **drop-in, zero-copy runtime platform** designed from first principles. Built exclusively on modern Java
(JEPs), it bypasses the JVM heap to deliver extreme throughput, microsecond latency, and profound cloud bill
defragmentation.

---

## 2. Technical Pillars (The "No Waste" Stack)

### 2.1 Density (Project Valhalla)

We eliminate object headers and identity overhead using **Value Records** and **Value Classes**. This allows our core
telemetry and routing logic to achieve L1 cache alignment, resulting in **~11.9M operations per second on a single
thread**.

### 2.2 Concurrency without Complexity (Project Loom)

Exeris replaces legacy reactive event loops with massive, blocking concurrency.

- **Thread-per-Request:** Simplified programming model with sub-millisecond context switching on Carrier Threads.
- **Structured Concurrency:** Guaranteed resource cleanup and fail-safe error propagation via `StructuredTaskScope`
  (JEP 525). All parallel operations are strictly bound — never unstructured.

### 2.3 The Graph Paradox (Data over Driver)

By transpiling our unified `MATCH` DSL directly to **Postgres SQL:2023 PGQ** over a Loom-optimized JDBC flow, Exeris
achieves graph traversals that mathematically outperform standard native graph databases (like Neo4j) running on legacy,
high-allocation drivers. **Zero-churn architecture beats native DB advantages.**

---

## 3. Architectural Philosophy

### 3.1 "The Wall" (Separation of Concerns)

Exeris is built on a strict, physically separated tiered architecture. Modules are divided not by domain, but by
**trust and execution tier**:

- 📜 **SPI (The Constitution):** Immutable contracts. Tier-agnostic. Zero implementation details. Any mention of
  `io_uring`, `Netty`, or `epoll` inside SPI is a critical violation.
- 🧠 **Core (The Brain):** Intelligent orchestration, load-shedding, `KernelBootstrap`, `WatermarkManager`,
  and strictly enforced `ScopedValue` context propagation (JEP 506).
- 💪 **Drivers (The Muscle):**
  - *Community:* Standard Java-based adapters (NIO/JDBC) — free and open.
  - *Enterprise:* Native, Off-Heap drivers (`io_uring`, QUIC, OpenSSL via Project Panama FFM) — the secret sauce.

Logical subsystems span four layers (L0–L4), each strictly optional above L0:

| Layer | Name | Responsibility |
|-------|------|----------------|
| **L0** | Foundation | Config, Memory (`LoanedBuffer`, Arenas), Telemetry (JFR) |
| **L1** | Data & Integrity | Security (Citadel, `ScopedValue`), Persistence (Zero-Copy DB) |
| **L2** | Data Synthesis | Transport (I/O, Priority Scheduler), Graph (PGQ DSL) |
| **L3** | Logic Engines | Events (Sourcing, Outbox), optional |
| **L4** | Orchestration | Flow (Sagas, Off-Heap State Machine), optional |

### 3.2 "Glass Box" (Auditability)

Unlike "Black Box" frameworks that hide performance degradation, Exeris provides total transparency via
**Java Flight Recorder (JFR)**. Every off-heap allocation, bootstrap phase, and transport event is traceable with
**nanosecond-resolution** at a sub-1% CPU overhead tax.

---

## 4. Operational Mantras

1. **No Waste Compute:** Every CPU cycle and every byte of RAM must serve the business logic.
2. **Fail-Fast Bootstrap:** Validate memory partitions and native dependencies at T-minus 0, before accepting a single
   byte of traffic.
3. **Hardware Awareness:** The runtime must know the machine it runs on (L1/L2 Cache Lines, NUMA nodes) to properly
   partition off-heap slabs.
