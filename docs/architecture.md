# Exeris Kernel: Architecture Overview
**Version:** 0.6.0  
**Last Updated:** May 2026  
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Executive Summary

The **Exeris Kernel** is a next‑generation, zero‑copy runtime for cloud‑native, high‑performance applications.  
Built on **Java 26**, it leverages:

- **Virtual Threads** (Project Loom, JEP 444) for 1:1 request‑to‑thread mapping.
- **Panama FFM** (JEP 454) for zero‑copy I/O and deterministic off‑heap memory management.
- **Scoped Values** (JEP 506) for strict, ThreadLocal-free context propagation.
- **Flexible Constructor Bodies** (JEP 513, Closed/Delivered in JDK 25) for pre-initialising fields before `super()` in value-ready types.
- **Lazy Constants** (JEP 526, Closed/Delivered in JDK 26) for JVM constant-folding of singleton config caches.
- **Valhalla Readiness (JEP 401):** All data carriers (`record`, `final class`) avoid `synchronized`, `System.identityHashCode()`, and identity `==` so they scalarise via C2 JIT Escape Analysis today. Migration to `value record`/`value class` will be performed once JEP 401 reaches mainline GA.

**No Waste Compute** is the core principle:
> Every byte allocated must serve a purpose. Every CPU cycle must add value.

---

## 🏗️ Physical Architecture ("The Wall")

The Kernel enforces strict **vertical separation of concerns** at the Maven module level.  
Modules are not divided by domain, but by **trust and execution tier**.

```
exeris-kernel-parent
├── exeris-kernel-spi        (The Constitution: Pure contracts, Value Records)
├── exeris-kernel-core       (The Brain: Orchestration, Bootstrap, Context + HTTP/2 wire codec)
├── exeris-kernel-community  (The Engine: NIO.2-backed Java 26 subsystem drivers)
├── exeris-kernel-enterprise (The Accelerator: Off-Heap drivers, io_uring, QUIC)
└── exeris-kernel-tck        (The Judge: Technology Compatibility Kit)
```

> **Supporting modules** (not part of the trust tier Wall): `exeris-kernel-bom` (BOM), `exeris-kernel-build-config` (PMD/Checkstyle rules), `exeris-kernel-community-testkit` (reusable HTTP test fixtures for Community).

### The "Mix & Match" Rule (Opt-In Architecture)

Exeris is an **À la carte** execution engine. Subsystems are loaded dynamically via the SPI. You can mix providers across tiers. For example, you can use the **Community Transport** (NIO.2-backed TCP) while plugging in the **Enterprise Persistence** driver (`io_uring` DB), or disable higher-level features entirely.

### Rules

> **Note:** The rules below describe the **target architecture**. The current `0.6.0` release may be a partial implementation of this structure.

1. **spi** has zero Exeris dependencies — it is the immutable foundation.
2. **core** depends only on **spi**.
3. **community** depends on **spi** and **core** (for shared TLS/memory infrastructure — `AbstractLoanedBuffer`, `CoreOpenSslLoader`, `TlsStateMachine`). As of `0.6.0`, `exeris-kernel-community` contains full subsystem driver implementations (bootstrap, crypto, events, flow, graph, HTTP dispatch, memory, persistence, security, telemetry, transport) and declares compile dependencies on `exeris-kernel-spi`, `exeris-kernel-core`, `slf4j-api`, and `jctools-core`.
4. **enterprise** depends on **spi** and **core** (same shared infrastructure).
5. **community** and **enterprise** never depend on each other.
6. Applications depend on **core** and **one** selected driver (community *or* enterprise).

> **HTTP Codec placement (ADR-009, ACCEPTED 2026-03-13):** The HTTP/2 wire codec (HPACK encoder, HTTP/2 frame parser/codec, flow controller) is embedded directly in `exeris-kernel-core` under `eu.exeris.kernel.core.http.*`. No separate HTTP codec module exists. This keeps the codec accessible to both Community and Enterprise tiers without cross-tier dependencies.

---

## 🧠 Logical Subsystems (L0–L4)

Physical structure is tiered, but logical features are organized into **Subsystem Layers**.  
Contracts live in **spi**, orchestration in **core**, and execution in the **drivers**.

**L3 and L4 are strictly OPTIONAL.** You use them only if your architecture requires them.

### L4 — Orchestration `[OPTIONAL]`

```
┌──────────────────────────────────────────────────────────────┐
│  L4: Flow (Sagas & Workflows)                                │
│  - Saga Engine & Step Actions (compensating transactions)    │
│  - Off-Heap State Machine Cache (Enterprise)                 │
└──────────────────────────────────────────────────────────────┘
```

### L3 — Logic Engines `[OPTIONAL]`

```
┌──────────────────────────────────────────────────────────────┐
│  L3: Events (Streaming & Messaging)                          │
│  - Event Sourcing (append-only log) & Projections            │
│  - Transactional Outbox (at-least-once delivery)             │
└──────────────────────────────────────────────────────────────┘
```

### L2 — Data Synthesis (Graph, Transport, HTTP)

```
┌──────────────────────────────────────────────────────────────┐
│  L2: Data Synthesis                                          │
│  ┌────────────────────┐ ┌──────────────────────────────────┐ │
│  │  Graph Service     │ │  Transport (I/O)                 │ │
│  │  - Path Finding    │ │  - Protocol-Agnostic SPI         │ │
│  │  - Native Queries  │ │  - Priority-Aware Scheduler      │ │
│  └────────────────────┘ │  - Community: NIO.2 TCP (FFM     │ │
│  ┌────────────────────┐ │    socket path in progress)      │ │
│  │  HTTP              │ └──────────────────────────────────┘ │
│  │  - HTTP/2 + HPACK  │                                      │
│  │    codec in Core   │                                      │
│  │  - Dispatch in     │                                      │
│  │    Community       │                                      │
│  └────────────────────┘                                      │
└──────────────────────────────────────────────────────────────┘
```

### L1 — Data & Integrity (Security, Persistence, Crypto)

```
┌──────────────────────────────────────────────────────────────┐
│  L1: Data & Integrity                                        │
│  ┌─────────────────────┐ ┌────────────────────────────────┐  │
│  │  Security (Citadel) │ │  Persistence (Repositories)    │  │
│  │  - ScopedValues     │ │  - Zero-Copy DB Handover       │  │
│  │  - Role checking    │ │  - Optimistic concurrency      │  │
│  └─────────────────────┘ └────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐   │
│  │  Crypto (TLS Engine — shared Core/Community/Enterprise)│   │
│  │  - Zero-Alloc TLS 1.3 (OpenSSL via Panama FFM)        │   │
│  │  - NativeCipherContext RAII lifecycle (LoanedBuffer)   │   │
│  │  - Shared by both tiers via exeris-kernel-core         │   │
│  └────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

### L0 — Foundation (Config, Memory, Telemetry)

```
┌──────────────────────────────────────────────────────────────┐
│  L0: Foundation                                              │
│  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │  Config        │ │  Memory      │ │  Telemetry       │    │
│  │  - Hot-reload  │ │  - Loan      │ │  - JFR Native    │    │
│  │  - Dynamic SPI │ │    pattern   │ │  - Sub-1% Tax    │    │
│  │  - No deps     │ │  - Arenas    │ │  - Trace ID      │    │
│  └────────────────┘ └──────────────┘ └──────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

---

## 💡 Core Concepts

### 1. Virtual Threads (JEP 444/491)
- Lightweight, scheduler‑managed threads
- Memory cost: ~100 bytes (vs ~1 MB for OS threads)
- Enables **1 thread per request** on Carrier Threads
- Managed within `StructuredTaskScope` — never spawned unstructured

### 5. Structured Concurrency (JEP 525 / JDK 25 Joiner API)
- All parallel operations use `StructuredTaskScope.open(Joiner)` — never raw `ExecutorService`
- `Joiner.awaitAllSuccessfulOrThrow()` for bootstrap: one failure cancels the entire scope
- `Joiner.anySuccessfulResultOrThrow()` for competitive I/O: first result wins, rest cancelled immediately
- `join()` returns a typed result `R` — zero-cast `LoanedBuffer` handover between subtasks

### 2. Panama FFM (JEP 454)
- Safe native interop
- Used for zero‑copy operations, OpenSSL TLS, and eliminating JNI overhead

### 3. Scoped Values (JEP 506)
- Immutable, inherited context
- Used for tenant, security, trace ID
- Safe for Virtual Threads, strictly bypassing ThreadLocal leaks

### 4. LoanedBuffer Pattern
- Data never copies, it **loans**
- Buffers are leased from the `MemoryAllocator` / `ResourceArbiter` (Community) or `GlobalMemoryArbiter` (Enterprise) and passed by reference directly from the NIC to the Database
- `AbstractLoanedBuffer` in Core provides lock-free reference counting via `VarHandle` CAS

---

## 🔄 Request Flow (End‑to‑End Zero-Copy)

```
┌─────────────────────────────────────────────────────────────┐
│  1. Packet Arrives -> Transport parses into Arena (Panama)  │
├─────────────────────────────────────────────────────────────┤
│  2. Dispatcher opens StructuredTaskScope with Joiner (1/stream)│
├─────────────────────────────────────────────────────────────┤
│  3. Priority-Aware Scheduler applies load-shedding          │
├─────────────────────────────────────────────────────────────┤
│  4. Security Handler Binds ScopedValue (TenantContext)      │
├─────────────────────────────────────────────────────────────┤
│  5. Business Logic executes via Kernel Providers (SPI)      │
├─────────────────────────────────────────────────────────────┤
│  6. Database Query executes via PersistenceProvider         │
├─────────────────────────────────────────────────────────────┤
│  7. [Optional] Event Appended + Transactional Outbox fired  │
├─────────────────────────────────────────────────────────────┤
│  8. Response Sent (LoanedBuffer ref-count reaches 0 -> pool)│
└─────────────────────────────────────────────────────────────┘
```

---

## 🛡️ Observability & Failure Handling

### Graceful Degradation & Backpressure
- High Watermark breach → **ExcessiveLoad** exceptions
- Prevents crashes, enforces fairness
- Virtual Thread pinning >50ms → logged and isolated

### JFR‑First Telemetry ("Glass Box")
- No heavy agents
- Every major kernel operation emits a **strongly-typed JFR event**
- Microsecond precision, minimal overhead

---

## 🖥️ Platform Support Matrix

Not all kernel capabilities are available on all operating systems. The table below defines the
**supported feature set per platform** as of TRL-3. Capabilities marked `[Enterprise only]` require
the closed-source `exeris-kernel-enterprise` module.

| Capability                              | Linux (x86-64 / ARM64) | macOS (ARM64 / x86-64) | Windows (x86-64)       |
|:----------------------------------------|:----------------------:|:----------------------:|:----------------------:|
| **Virtual Threads (Loom)**              | ✅ Full                | ✅ Full                | ✅ Full                |
| **Panama FFM / OpenSSL TLS**            | ✅ `libssl.so.3`       | ✅ `libssl.3.dylib`    | ✅ `libssl-3-x64.dll`  |
| **Community TCP transport (NIO.2-backed; FFM socket path in progress — Sprint 3–4)** | ⚠️ In progress | ⚠️ In progress | ⚠️ In progress |
| **`io_uring` kernel-bypass** `[Ent.]`  | ✅ kernel ≥ 5.11       | ❌ Not available       | ❌ Not available       |
| **QUIC / UDP transport** `[Ent.]`       | ✅                     | ✅                     | ⚠️ Partial (no io_uring)|
| **L0 Glass-Box crash buffer**           | `/tmp/exeris-crash/`   | `/tmp/exeris-crash/`   | `%TEMP%\exeris-crash\` |
| **NUMA-aware slab allocation** `[Ent.]` | ✅ libnuma             | ❌ Not available       | ❌ Not available       |
| **Huge Pages (mmap)** `[Ent.]`          | ✅ `MAP_HUGETLB`       | ⚠️ Superpage (limited) | ❌ Not available       |
| **TCK full suite (FFM tests)**          | ✅                     | ✅                     | ⚠️ FFM tests skipped   |

> **Production recommendation:** Linux x86-64 or ARM64 is the only fully-supported production target
> for the Enterprise tier. macOS is the primary development platform. Windows support is limited to
> the Community tier and development builds.

> **`io_uring` minimum kernel version:** 5.11 (for `IORING_OP_PROVIDE_BUFFERS` and multishot RECVMSG).
> Kernels below 5.11 will fall back to `epoll`-based transport at boot and emit a JFR warning during kernel bootstrap (concrete event type is implementation-specific and may live outside `exeris-kernel-core`).

---

## 🌐 Cloud Native Observability (OpenTelemetry)

The Exeris Kernel is designed for CNCF-native deployment (ADR-001). The JFR-First telemetry
mandate covers in-process observability. For cross-service, cluster-level observability in
Kubernetes environments, the following strategy applies:

| Observability Layer     | Mechanism                                   | Status         |
|:------------------------|:--------------------------------------------|:---------------|
| **In-process events**   | JFR (`Exeris Kernel/*` event categories)    | ✅ TRL-3       |
| **Crash diagnostics**   | Glass-Box binary crash buffer + `exeris-decoder` | 🚧 TRL-4 planned |
| **Metrics (Prometheus)**| `DeterministicBinarySink` → OTLP exporter   | 🚧 TRL-4 planned |
| **Distributed tracing** | `traceId` in `ExerisKernelException.rawArgs`; OTLP span export | 🚧 TRL-4 planned |
| **Log aggregation**     | `Slf4jTelemetrySink` → structured JSON → Loki/Fluent Bit | ✅ TRL-3 (Community) |

> **TRL-4 obligation:** A `PrometheusOtlpTelemetrySink` implementing the `TelemetrySink` SPI must be
> delivered in `exeris-kernel-community` before TRL-4 certification. It must export the standard
> `exeris_kernel_*` metric namespace in OTLP format without allocating on the emission hot-path.

---

## 🚀 Deployment Topology

Exeris Kernel is a **library embedded in your application JVM process** — not a sidecar, not a standalone
server. The Kernel bootstraps within your JVM, owns the network socket, and exposes the data-plane port.
In the current TRL-3 prototype, Kubernetes liveness/readiness probes MUST target your host application's own
HTTP health endpoint or an external sidecar. An embedded lightweight HTTP endpoint on port `9090` for
Kernel-centric health probes is **planned for TRL-4** (see [Bootstrap subsystem](subsystems/bootstrap.md)).

For the complete deployment diagram, infrastructure requirements, and SLA/SLO baseline table, see:
→ **[Whitepaper](whitepaper.md)** — Sections 4 (Deployment Topology) and 5 (SLA/SLO Baseline Table)

---

## 📚 Related Documentation

To understand how these concepts map to actual code, read the subsystem definitions:

**Physical Modules (The Wall):**
- [SPI Module](modules/01-spi.md) – The Constitution & Contracts
- [Core Module](modules/02-core.md) – The Brain & Orchestration
- [Community Module](modules/03-community.md) – NIO.2-backed Java 26 subsystem drivers (OSS)
- [Enterprise Module](modules/04-enterprise.md) - High-Performance Native Drivers
- [TCK Module](modules/05-tck.md) - Technology Compatibility Kit

**Logical Subsystems:**
- [Bootstrap](subsystems/bootstrap.md) | [Config](subsystems/config.md) | [Memory](subsystems/memory.md) | [Security](subsystems/security.md)
- [Transport](subsystems/transport.md) | [Persistence](subsystems/persistence.md) | [Graph](subsystems/graph.md) | [Flow](subsystems/flow.md)
- [Crypto](subsystems/crypto.md) | [Telemetry](subsystems/telemetry.md) | [Events](subsystems/events.md)

---

## 🎯 Summary

The **Exeris Kernel** is an orchestrator designed to eliminate the **Object‑Relational Impedance Mismatch** at the transport layer.  
By leveraging **Project Valhalla**, **Panama**, and **Loom**, it bypasses the JVM heap to deliver:

- extreme throughput
- microsecond latency
- deterministic, zero‑waste compute