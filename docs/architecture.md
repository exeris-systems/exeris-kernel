# Exeris Kernel: Architecture Overview
**Version:** 0.5.0-SNAPSHOT  
**Last Updated:** February 2026  
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Executive Summary

The **Exeris Kernel** is a next‑generation, zero‑copy runtime for cloud‑native, high‑performance applications.  
Built on **Java 26**, it leverages:

- **Virtual Threads** (Project Loom) for 1:1 request‑to‑thread mapping.
- **Panama FFM** for zero‑copy I/O and deterministic off‑heap memory management.
- **Scoped Values** (JEP 506) for strict, ThreadLocal-free context propagation.

**No Waste Compute** is the core principle:
> Every byte allocated must serve a purpose. Every CPU cycle must add value.

---

## 🏗️ Physical Architecture ("The Wall")

The Kernel enforces strict **vertical separation of concerns** at the Maven module level.  
Modules are not divided by domain, but by **trust and execution tier**.

```
exeris-kernel-parent
├── exeris-kernel-spi        (The Constitution: Pure contracts, Value Records)
├── exeris-kernel-core       (The Brain: Orchestration, Bootstrap, Context)
├── exeris-kernel-community  (The Engine: Standard Java 26 FFM adapters)
├── exeris-kernel-enterprise (The Accelerator: Off-Heap drivers, io_uring, QUIC)
└── exeris-kernel-tck        (The Judge: Technology Compatibility Kit)
```

### The "Mix & Match" Rule (Opt-In Architecture)

Exeris is an **À la carte** execution engine. Subsystems are loaded dynamically via the SPI. You can mix providers across tiers. For example, you can use the free **Community Transport** (TCP/NIO) while plugging in the **Enterprise Persistence** driver (`io_uring` DB), or disable higher-level features entirely.

### Rules
1. **core**, **community**, and **enterprise** depend **only** on **spi**.
2. **community** and **enterprise** never depend on each other.
3. Applications depend on **core** and **one** selected driver (community *or* enterprise).

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

### L2 — Data Synthesis (Graph, Transport)

```
┌──────────────────────────────────────────────────────────────┐
│  L2: Data Synthesis                                          │
│  ┌────────────────────┐ ┌──────────────────────────────────┐ │
│  │  Graph Service     │ │  Transport (I/O)                 │ │
│  │  - Path Finding    │ │  - Protocol-Agnostic SPI         │ │
│  │  - Native Queries  │ │  - Priority-Aware Scheduler      │ │
│  └────────────────────┘ └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### L1 — Data & Integrity (Security, Persistence)

```
┌──────────────────────────────────────────────────────────────┐
│  L1: Data & Integrity                                        │
│  ┌─────────────────────┐ ┌────────────────────────────────┐  │
│  │  Security (Citadel) │ │  Persistence (Repositories)    │  │
│  │  - ScopedValues     │ │  - Zero-Copy DB Handover       │  │
│  │  - Role checking    │ │  - Optimistic concurrency      │  │
│  └─────────────────────┘ └────────────────────────────────┘  │
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
- Buffers are leased from the `GlobalMemoryArbiter` and passed by reference directly from the NIC to the Database

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

## 📚 Related Documentation

To understand how these concepts map to actual code, read the subsystem definitions:

**Physical Modules (The Wall):**
- [SPI Module](modules/01-spi.md) – The Constitution & Contracts
- [Core Module](modules/02-core.md) – The Brain & Orchestration
- [Community Module](modules/03-community.md) – Standard Java 26 Drivers
- [Enterprise Module](modules/04-enterprise.md) - High-Performance Native Drivers
- [TCK Module](modules/05-tck.md) - Technology Compatibility Kit

**Logical Subsystems:**
- [Bootstrap](subsystems/bootstrap.md) | [Config](subsystems/config.md) | [Memory](subsystems/memory.md) | [Security](subsystems/security.md)
- [Transport](subsystems/transport.md) | [Persistence](subsystems/persistence.md) | [Graph](subsystems/graph.md) | [Flow](subsystems/flow.md)

---

## 🎯 Summary

The **Exeris Kernel** is an orchestrator designed to eliminate the **Object‑Relational Impedance Mismatch** at the transport layer.  
By leveraging **Project Valhalla**, **Panama**, and **Loom**, it bypasses the JVM heap to deliver:

- extreme throughput
- microsecond latency
- deterministic, zero‑waste compute