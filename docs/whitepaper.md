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

### 2.1 Density (Project Valhalla — Readiness)

All core data carriers (`record`, `final class`) in the Exeris Kernel are designed to be
**Valhalla-migration-ready**: they avoid `synchronized`, `System.identityHashCode()`, and
identity `==` operations, allowing the C2 JIT to apply Escape Analysis scalarisation on
hot-paths today — without requiring JEP 401 to be finalized.

**JEP 401 (Value Classes and Objects)** is currently in Early Access preview. The keyword
`value` is **not yet used** in the codebase to preserve toolchain stability (Checkstyle/PMD/AOT).
When JEP 401 reaches mainline GA, the migration path is a single-keyword addition (`value record`)
per data carrier, with no architectural change required. This preparation targets **~11.9M operations
per second on a single thread** via L1 cache alignment after value-type flattening.

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
| **L1** | Data & Integrity | Security (Citadel, `ScopedValue`), Persistence (Zero-Copy DB), **Crypto (Zero-Alloc TLS via OpenSSL/Panama FFM — Core `OffHeapTlsEngine`; zero-alloc Community contract is best-effort, full zero-alloc TCK-enforced on Enterprise path)** |
| **L2** | Data Synthesis | Transport (I/O, Priority Scheduler), Graph (PGQ DSL) |
| **L3** | Logic Engines | Events (Sourcing, Outbox), optional |
| **L4** | Orchestration | Flow (Sagas, Off-Heap State Machine), optional |

### 3.2 "Glass Box" (Auditability)

Unlike "Black Box" frameworks that hide performance degradation, Exeris provides total transparency via
**Java Flight Recorder (JFR)**. Every off-heap allocation, bootstrap phase, and transport event is traceable with
**nanosecond-resolution** at a sub-1% CPU overhead tax.

---

## 4. Deployment Topology

Exeris Kernel is a **library embedded in your application JVM process** — not a sidecar, not a standalone proxy,
not a middleware server. Your application code links against `exeris-kernel-core` and one driver module
(`community` or `enterprise`). The Kernel bootstraps within your JVM and owns the network socket.

> **Note:** The Community driver module (`exeris-kernel-community`) is **not yet implemented** in this repository — it is an active placeholder. Full Community driver implementations will be available in future releases.

```
┌─────────────────────────────────────────────────────────────┐
│  Your Application JVM (single process)                      │
│  ┌───────────────────────────────────┐                      │
│  │  exeris-kernel-core               │  ← owns lifecycle    │
│  │  + exeris-kernel-community        │  ← owns TCP socket   │
│  │  (or enterprise)                  │                      │
│  ├───────────────────────────────────┤                      │
│  │  Your Business Logic (L3/L4)      │  ← your code        │
│  └───────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────┘
         ▲ TCP/QUIC port (data plane)
         ▲ [Planned, TRL-4] HTTP port 9090 (K8s health probes; not available in TRL-3 prototype)
```

**No sidecar required.** Observability (JFR events) is in-process. Secret injection (Vault) is at bootstrap.
The only external dependencies are the data stores (PostgreSQL, Redis) and optionally a message broker
(Kafka / Redpanda) — all managed via Helm / Kubernetes operators per ADR-001.

---

## 5. SLA / SLO Baseline Table

This section separates what has been **measured** from what the TCK enforces as a **design contract**: measured results first, contract targets second.

### 5.1 Measured Results to Date

**Saga compensation correctness (`e2e-shop-order-saga`, 2026-05-05, dev-laptop, loopback HTTP/1.1 — claim scope: exploratory; reproducibility artifacts published in `exeris-benchmarks`).** A five-step order saga with payment failure injected at a configured 3% rate, run against Quarkus 3 + Axon Framework and Spring Boot 3 + Axon Framework (each requiring a separate Axon Server process). Full-system totals (app + Axon Server): Exeris in-process **459 MB RSS / 66 threads / 24.7 CPU-s**, versus **~1.54 GB / ~217 threads / ~48 CPU-s** for Quarkus + Axon and **~2.16 GB / ~221 threads / ~85 CPU-s** for Spring + Axon — i.e. **3.4× lower memory / 3.3× fewer threads / ~1.9× lower CPU** vs Quarkus + Axon, and **4.7× / 3.3× / ~3.4×** vs Spring + Axon. On compensation correctness at the configured 3% failure rate: Exeris compensated **3.32%** of sagas (matching the injection within statistical noise), while both Axon stacks reported **0%** compensations — their async `202 Accepted` dispatch returns before the saga completes, leaving 1.82% (Quarkus) / 1.22% (Spring) of sagas unresolved at window close. Full write-up: B2B technical whitepaper §4.1 and the blog post "What you measure depends on where you draw the boundary" (blog.arkstack.dev).

**TLS record path (JMH micro-matrix, report `20260501-123118-all` in `exeris-benchmarks/results/reports/`; publication mode: public; baseline rows `comparison_eligible`; recorded hardware profile: `linux-generic`).** The Exeris Enterprise `OffHeapTlsEngine` on the in-process Memory-BIO harness (B5) measured **~923,617 ops/s at p99 2.10 µs** — on par with the JDK `SSLEngine` baseline (B3: ~905,855 ops/s, p99 2.97 µs) and Netty tcnative (B4: ~850,225 ops/s, p99 2.72 µs). The Exeris Community FD-owner integration path (B6, real loopback socket) measured **~365,375 ops/s**: the gap versus the engine-level rows is socket-wiring overhead on the Community integration path, and per the published report B5 (Memory-BIO lens) and B6 (FD-owner) are deliberately not collapsed into a single row.

**HTTP scenario throughput (`entity-read-by-id`, 2026-03-25, dev-laptop, loopback HTTP/1.1 — claim scope: exploratory).** Single-target run (wrk, 4 threads / 64 connections / 30 s): **16,635 RPS at p99 12.25 ms**, zero errors. Comparative fairness-gated re-runs on the reference profile are roadmap.

All runtime-scenario measurements to date were taken on dev-laptop-class hardware over loopback and are published as exploratory (or as labeled in the per-run artifacts); the reference-profile (`perf-box-amd64`, EU bare metal) validation campaign is on the public roadmap.

### 5.2 Contract Targets (TCK-Enforced Design Limits)

The following figures are **TCK-enforced design limits ("the contract")**, defined against the designated reference profile below.
Community and Enterprise tiers are measured separately.

**Reference profile:** `perf-box-amd64` — EU-hosted dedicated bare metal (Hetzner AX-class: AMD x86-64, 16 hardware threads, 64 GB RAM, local NVMe; Falkenstein DE / Helsinki FI), Linux, Java 26 GA. Profile contract: `exeris-benchmarks/docs/hardware-profiles.md`; exact CPU model recorded per run.

| Metric                              | Community Limit          | Enterprise Limit          | TCK Enforcement                     |
|:------------------------------------|:------------------------:|:-------------------------:|:------------------------------------|
| **Request latency P99**             | ≤ 200 µs                 | ≤ 50 µs                   | JMH + histogram, `AssertionError`   |
| **TLS hot path heap allocation**    | Bounded (Community `OffHeapTlsEngine` wraps OpenSSL via Core; best-effort off-heap, no hard TCK zero-alloc gate on Community path) | 0 bytes (full path)        | CryptoZeroAllocTck + JFR GC profiler, `AssertionError`   |
| **LoanedBuffer leak**               | 0 unreleased             | 0 unreleased               | `LeakDetectionMode.PARANOID`        |
| **PAQS shed decision latency**      | ≤ 5 µs                   | ≤ 5 µs                     | Nanosecond timer in TCK             |
| **MemoryAllocator complexity**      | O(1)                     | O(1)                       | JMH + PMD rule verification         |
| **Bootstrap cold start P99**        | ≤ 500 ms                 | ≤ 800 ms                   | JFR `BootstrapJfrEvents.KernelBootReadyEvent`          |
| **Saga state transition P99**       | ≤ 5 ms (DB-bound)        | ≤ 1 µs (memory-bound)      | JMH `AbstractFlowParkWakeBenchmark`         |
| **OpenSSL ABI symbol resolution**   | 100% (all bound symbols) | 100% (all bound symbols)   | Planned: ABI symbol TCK (OpenSSL/FFM)       |
| **Throughput (reference profile)**  | ~2,800 RPS/vCPU          | ~8,500 RPS/vCPU            | JMH + flame graph analysis          |

> **Measurement context for throughput:** "RPS/vCPU" measured with synthetic HTTP/1.1 workload,
> no persistence (in-memory mock), 10k concurrent connections. Persistence-bound workloads will
> be lower (Community: JDBC-limited; Enterprise: native driver dependent). Flame graph–based
> profiling was used to validate tier-specific hotspots and throughput characteristics.

---

## 6. Operational Mantras

1. **No Waste Compute:** Every CPU cycle and every byte of RAM must serve the business logic.
2. **Fail-Fast Bootstrap:** Validate memory partitions and native dependencies at T-minus 0, before accepting a single
   byte of traffic.
3. **Hardware Awareness:** The runtime must know the machine it runs on (L1/L2 Cache Lines, NUMA nodes) to properly
   partition off-heap slabs.
