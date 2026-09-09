---
title: "Exeris Kernel: Architecture Overview"
type: explanation
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Exeris Kernel: Architecture Overview
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Executive Summary

The **Exeris Kernel** is a next‑generation, zero‑copy runtime for cloud‑native, high‑performance applications.  
Built on **JDK 25 LTS** (distributed preview-clean, ADR-066), it leverages:

- **Virtual Threads** (Project Loom, JEP 444) for 1:1 request‑to‑thread mapping.
- **Panama FFM** (JEP 454) for zero‑copy I/O and deterministic off‑heap memory management.
- **Scoped Values** (JEP 506) for strict, ThreadLocal-free context propagation.
- **Flexible Constructor Bodies** (JEP 513, Closed/Delivered in JDK 25) for pre-initialising fields before `super()` in value-ready types — a claim currently asserted only in this document; it is not cross-checked against `docs/glossary.md`, `docs/whitepaper.md`, or `docs/ROADMAP.md`, which do not mention JEP 513.
- **Compute-once config caches** via the Supplier + `AtomicReference` CAS pattern, mirroring the `LazyConstant` semantic (JEP 526) without depending on it — JEP 526 is not on the JDK 25 baseline.
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
├── exeris-kernel-community  (The Engine: NIO.2-backed subsystem drivers, plus an FFM/Panama socket path)
├── exeris-kernel-enterprise (The Accelerator: Off-Heap drivers, io_uring, QUIC)
└── exeris-kernel-tck        (The Judge: Technology Compatibility Kit)
```

> `exeris-kernel-enterprise` is **not** a module of this Maven reactor — it is a separate, closed-source
> repository (per the trust-tier boundary below), shown here only to complete the trust-tier picture.
> This repository's actual `pom.xml` reactor, as of `0.12.0`, is: `exeris-kernel-build-config`,
> `exeris-kernel-bom`, `exeris-kernel-parent`, `exeris-kernel-spi`, `exeris-kernel-tck`,
> `exeris-kernel-core`, `exeris-kernel-community-testkit`, `exeris-kernel-community`, plus two modules
> outside the trust-tier tree above: `exeris-kernel-community-kafka` (isolates the Kafka/Redpanda
> `EventEngine` binding and its transitive dependencies so single-node operators don't carry a Kafka
> client on their classpath — see ADR-008) and `exeris-kernel-diagnostics-cli` (a standalone executable
> exposing the `KernelDiagnostics` SPI over stdio JSON for out-of-process consumers, per ADR-033).
> `exeris-kernel-build-config` (PMD/Checkstyle rules) and `exeris-kernel-bom` sit outside the trust tier
> too; `exeris-kernel-community-testkit` provides reusable kernel-boot, HTTP, persistence, and
> security test fixtures — not HTTP-only — for consumers outside this repository.

### The "Mix & Match" Rule (Opt-In Architecture)

Exeris is an **À la carte** execution engine. Subsystems are loaded dynamically via the SPI. You can mix providers across tiers. For example, you can use the **Community Transport** (NIO.2-backed TCP) while plugging in the **Enterprise Persistence** driver (`io_uring` DB), or disable higher-level features entirely.

### Rules

> **Note:** The rules below describe the **target architecture**. Rules 1–3 and 5–6 are verified
> against this repository's own reactor at `0.12.0` (see the compile-dependency check below each).
> Rule 4 (Enterprise) cannot be checked from here: `exeris-kernel-enterprise` is a separate,
> closed-source repository not present in this tree, so its dependency shape is stated as documented
> intent, not as something this repository can confirm.

1. **spi** has zero Exeris dependencies — it is the immutable foundation.
2. **core** depends only on **spi**.
3. **community** depends on **spi** and **core**, for two distinct reasons: shared TLS/memory infrastructure (`AbstractLoanedBuffer`, `CoreOpenSslLoader`, `TlsStateMachine`) and the **driver-agnostic decision layer** a driver must not re-implement per transport (`SecurityInterceptor`, `RouteAuthorizationEnforcer`, `SecurityJfrEvents`). The second is the placement ADR-061 obligation 2 fixed: one decision layer every transport inherits, rather than each driver growing its own and disagreeing. As of `0.12.0`, `exeris-kernel-community`'s source tree has a package per subsystem — bootstrap, config, crypto, diagnostics, events, flow, graph, health, HTTP, JSON, memory, metrics, persistence, scheduling, security, storage, telemetry, transport, and WebSocket (the "Logical Subsystems" links further down list the subset with a dedicated subsystem doc — WebSocket does not yet have one) — and its `pom.xml` declares compile dependencies on `exeris-kernel-spi`, `exeris-kernel-core`, `slf4j-api`, and `jctools-core`, plus provider-specific libraries for individual drivers: HikariCP and an optional PostgreSQL JDBC driver (persistence), the Neo4j Java Driver (graph), and Nimbus JOSE+JWT, Jackson Databind, and Bouncy Castle (crypto/security).
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

### L2 — Data Synthesis (Graph, Transport, HTTP, WebSocket)

```
┌──────────────────────────────────────────────────────────────┐
│  L2: Data Synthesis                                          │
│  ┌────────────────────┐ ┌──────────────────────────────────┐ │
│  │  Graph Service     │ │  Transport (I/O)                 │ │
│  │  - Path Finding    │ │  - Protocol-Agnostic SPI         │ │
│  │  - Native Queries  │ │  - Priority-Aware Scheduler      │ │
│  └────────────────────┘ │ - Community: NIO.2 + FFM         │ │
│  ┌────────────────────┐ │   POSIX-hybrid backend (auto)    │ │
│  │  HTTP              │ └──────────────────────────────────┘ │
│  │  - HTTP/2 + HPACK  │                                      │
│  │    codec in Core   │                                      │
│  │  - Dispatch in     │                                      │
│  │    Community       │                                      │
│  └────────────────────┘                                      │
└──────────────────────────────────────────────────────────────┘
```

> **WebSocket** is a fourth L2 protocol surface not pictured above: a full `spi.websocket.*` contract
> (`WebSocketProvider`, `WebSocketServerEngine`, `WebSocketExchange`, handshake and close-code types)
> with a Community driver implementation exists in this repository as of `0.12.0`. It has no dedicated
> subsystem doc yet — see the source directly under `eu.exeris.kernel.spi.websocket` and
> `eu.exeris.kernel.community.websocket` — so it is omitted from the diagram to avoid documenting a
> contract this file cannot yet point a reader at in detail.

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
- Managed within a structured scope that owns their lifetime — never spawned unstructured

### 5. Structured Concurrency (JEP 505 — fifth `StructuredTaskScope` preview, current as of JDK 25)
- All parallel operations run inside a structured scope — never raw `ExecutorService` — but the two
  distribution lines use different concrete types, and their failure semantics are **not** the same:
  - **Distributed line (this repository, `0.12.0`):** `core.concurrent.StructuredScope`, a GA-APIs-only
    class (virtual threads + `ScopedValue`, no `--enable-preview`). It is deliberately **await-all,
    never fail-fast**: `join()` returns `void` and waits for every forked task regardless of outcome;
    each `fork()` call returns a typed `ForkedTask<T>` whose `state()`/result the caller inspects
    afterward. Any "abort the rest on first failure" behavior — e.g. bootstrap's `FailurePolicy.FAIL_FAST`
    in `SubsystemOrchestrator` — is application logic layered on top of that always-await-all primitive,
    not a scope-level cancellation the primitive provides.
  - **`preview` branch only (ADR-066):** the JDK's own `java.util.concurrent.StructuredTaskScope` with
    its `Joiner` policies — `Joiner.awaitAllSuccessfulOrThrow()` (one failure cancels the scope) and
    `Joiner.anySuccessfulResultOrThrow()` (first result wins, rest cancelled) — are real APIs there, but
    they are preview-only and are not what the distributed line ships or uses.

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
│  2. Dispatcher opens a structured scope (1 per stream)         │
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
- Nanosecond-resolution timestamps (JFR's own clock). A sub-1% CPU overhead tax is claimed in the whitepaper and glossary but is not backed by a benchmark in this repository.

---

## 🖥️ Platform Support Matrix

Not all kernel capabilities are available on all operating systems. The table below defines the
**supported feature set per platform** as of TRL-3. Capabilities marked `[Enterprise only]` require
the closed-source `exeris-kernel-enterprise` module.

| Capability                              | Linux (x86-64 / ARM64) | macOS (ARM64 / x86-64) | Windows (x86-64)       |
|:----------------------------------------|:----------------------:|:----------------------:|:----------------------:|
| **Virtual Threads (Loom)**              | ✅ Full                | ✅ Full                | ✅ Full                |
| **Panama FFM / OpenSSL TLS**            | ✅ `libssl.so.3`       | ✅ `libssl.3.dylib`    | ✅ `libssl-3-x64.dll`  |
| **Community TCP transport** (NIO.2 base, FFM POSIX-hybrid socket backend auto-selected at boot) | ✅ NIO.2 + FFM backend when syscall validation succeeds | ⚠️ NIO.2 confirmed; the FFM backend's own loopback round-trip integration test (`SyscallLoopbackRoundTripIT`) does not run on macOS — see [Transport subsystem](subsystems/transport.md) | ✅ NIO.2 only — backend always falls back (Winsock socket model) |
| **`io_uring` kernel-bypass** `[Ent.]`  | ✅ modern kernels (exact floor set by the Enterprise module, not verifiable from this repository) | ❌ Not available       | ❌ Not available       |
| **QUIC / UDP transport** `[Ent.]`       | ✅                     | ✅                     | ⚠️ Partial (no io_uring)|
| **L0 Glass-Box crash buffer**           | 🚧 Planned (TRL-4) — not implemented in this repository; see the Cloud Native Observability table below | 🚧 Planned (TRL-4) | 🚧 Planned (TRL-4) |
| **NUMA-aware slab allocation** `[Ent.]` | ✅ libnuma             | ❌ Not available       | ❌ Not available       |
| **Huge Pages (mmap)** `[Ent.]`          | ✅ `MAP_HUGETLB`       | ⚠️ Superpage (limited) | ❌ Not available       |
| **TCK full suite (FFM tests)**          | ✅                     | ✅                     | ⚠️ FFM tests skipped   |

> **Production recommendation:** Linux x86-64 or ARM64 is the only fully-supported production target
> for the Enterprise tier. macOS is the primary development platform. Windows support is limited to
> the Community tier and development builds.

> **`io_uring` minimum kernel version:** not verifiable from this repository. `io_uring` support lives
> entirely in `exeris-kernel-enterprise`, a separate closed-source repository not present in this
> reactor, so neither the minimum kernel version nor the epoll-fallback behavior claimed by earlier
> drafts of this document could be confirmed against source for this pass — treat any specific number
> as unverified until the Enterprise module's own docs are checked.

---

## 🌐 Cloud Native Observability (OpenTelemetry)

The Exeris Kernel is designed for CNCF-native deployment (ADR-001). The JFR-First telemetry
mandate covers in-process observability. For cross-service, cluster-level observability in
Kubernetes environments, the following strategy applies:

| Observability Layer     | Mechanism                                   | Status         |
|:------------------------|:--------------------------------------------|:---------------|
| **In-process events**   | JFR (mostly `Exeris Kernel/*` event categories; a minority — Events/Outbox/Projection, HTTP routing, Config reload, WebSocket lifecycle — use a divergent `Exeris/*` top-level category) | ✅ TRL-3       |
| **Crash diagnostics**   | Glass-Box binary crash buffer (`.ring` files, shared `exeris-telemetry-spec` wire format); the kernel is producer-only and ships no decoder — decoding is a separate tool, per [ADR-039](adr/ADR-039-open-core-observability-boundary.md) | 🚧 TRL-4 planned |
| **Metrics (Prometheus)**| binary metrics sink → OTLP exporter (mechanism name not yet fixed in code) | 🚧 TRL-4 planned |
| **Distributed tracing** | `ExerisKernelException.traceId` (a dedicated field, not part of `rawArgs`); OTLP span export | 🚧 TRL-4 planned |
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

**Getting started:**
- [Developer Guides](guides/) – task-oriented paths: platform and dependencies, building an application, implementing a provider

**Physical Modules (The Wall):**
- [SPI Module](modules/01-spi.md) – The Constitution & Contracts
- [Core Module](modules/02-core.md) – The Brain & Orchestration
- [Community Module](modules/03-community.md) – NIO.2-backed JDK 25 subsystem drivers (OSS)
- [Enterprise Module](modules/04-enterprise.md) - High-Performance Native Drivers
- [TCK Module](modules/05-tck.md) - Technology Compatibility Kit
- [Testkit Module](modules/06-testkit.md) - Fixtures that boot the real kernel for consumers

**Logical Subsystems:**
- [Bootstrap](subsystems/bootstrap.md) | [Config](subsystems/config.md) | [Memory](subsystems/memory.md) | [Security](subsystems/security.md)
- [Transport](subsystems/transport.md) | [Persistence](subsystems/persistence.md) | [Graph](subsystems/graph.md) | [Flow](subsystems/flow.md)
- [Crypto](subsystems/crypto.md) | [Telemetry](subsystems/telemetry.md) | [Events](subsystems/events.md)
- [HTTP](subsystems/http.md) | [WebSocket](subsystems/websocket.md) | [Scheduling](subsystems/scheduling.md) | [Storage](subsystems/storage.md)
- [Diagnostics](subsystems/diagnostics.md) | [Exceptions](subsystems/exceptions.md)

---

## 🎯 Summary

The **Exeris Kernel** is an orchestrator designed to eliminate the **Object‑Relational Impedance Mismatch** at the transport layer.  
By leveraging **Project Valhalla**, **Panama**, and **Loom**, it bypasses the JVM heap to deliver:

- extreme throughput
- microsecond latency
- deterministic, zero‑waste compute