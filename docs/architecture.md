# Exeris Kernel: Architecture Overview
**Version:** 0.5.0-SNAPSHOT 
**Last Updated:** February 2026  
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Executive Summary

The **Exeris Kernel** is a next‑generation, zero‑copy runtime for cloud‑native, high‑performance applications.  
Built on **Java 26**, it leverages:

- **Virtual Threads** (Project Loom) for 1:1 request‑to‑thread mapping without event loops
- **Panama FFM** for zero‑copy I/O and off‑heap memory management
- **Scoped Values** (JEP 506) for Virtual Thread‑safe context propagation
- **SQL/PGQ** (PostgreSQL 18) for native graph queries
- **Event Sourcing** with optimistic concurrency control
- **Saga Orchestration** for distributed transactions

**No Waste Compute** is the core principle:
> Every byte allocated must serve a purpose. Every CPU cycle must add value.

---

## 🏗️ Physical Architecture (“The Wall”)

The Kernel enforces strict **vertical separation of concerns** at the Maven module level.  
Modules are not divided by domain (e.g., `kernel-memory`, `kernel-security`) but by **tier**.

```
exeris-kernel-parent
├── exeris-kernel-spi        (The Constitution: Pure interfaces, Value Records)
├── exeris-kernel-core       (The Brain: Orchestration, Watermarks, Load Shedding)
├── exeris-kernel-community  (The Muscle: Standard Java 26 FFM adapters)
├── exeris-kernel-enterprise (The Heavy Muscle: io_uring, QUIC, C-Interop - External / Closed Source)
└── exeris-kernel-tck        (The Judge: Technology Compatibility Kit)
```

### Rules
1. **core**, **community**, and **enterprise** depend **only** on **spi**.
2. **community** and **enterprise** never depend on each other.
3. Applications depend on **core** and **one** selected driver (community *or* enterprise).

---

## 🧠 Logical Subsystems (L0–L4)

Physical structure is tiered, but logical features are organized into **Subsystem Layers**.  
Contracts live in **spi**, orchestration in **core**, execution in the **drivers**.

### L4 — Orchestration (Flow / Sagas)

```
┌──────────────────────────────────────────────────────────────┐
│  L4: Orchestration (Flow - Sagas)                            │
│  - Saga Engine & Step Actions (compensating transactions)    │
│  - Dead Letter Queue (failed steps)                          │
└──────────────────────────────────────────────────────────────┘
```

### L3 — Logic Engines (Events)

```
┌──────────────────────────────────────────────────────────────┐
│  L3: Logic Engines (Events)                                  │
│  - Event Sourcing (append-only log)                          │
│  - Partitioned Event Store (monthly, 50k writes/sec)         │
│  - Transactional Outbox (at-least-once delivery)             │
└──────────────────────────────────────────────────────────────┘
```

### L2 — Data Synthesis (Graph, Transport)

```
┌──────────────────────────────────────────────────────────────┐
│  L2: Data Synthesis (Graph, Transport)                       │
│  ┌────────────────────┐ ┌──────────────────────────────────┐ │
│  │  Graph Service     │ │  Transport (QUIC/HTTP/3)         │ │
│  │  - SQL/PGQ         │ │  - RFC 9000 / RFC 9114           │ │
│  │  - Path Finding    │ │  - Virtual Threads per stream    │ │
│  │  - Dual-write      │ │  - Priority-Aware Scheduler      │ │
│  └────────────────────┘ └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### L1 — Data & Integrity (Security, Persistence)

```
┌──────────────────────────────────────────────────────────────┐
│  L1: Data & Integrity (Security, Persistence)                │
│  ┌─────────────────────┐ ┌────────────────────────────────┐  │
│  │  Security (Citadel) │ │  Persistence (Repositories)    │  │
│  │  - JWT extraction   │ │  - RLS enforcement             │  │
│  │  - ScopedValues     │ │  - Optimistic concurrency      │  │
│  │  - Role checking    │ │  - Entity serialization        │  │
│  └─────────────────────┘ └────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### L0 — Foundation (Config, Memory, Telemetry)

```
┌──────────────────────────────────────────────────────────────┐
│  L0: Foundation (Config, Memory, Telemetry)                  │
│  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │  Config        │ │  Memory      │ │  Telemetry       │    │
│  │  - Hot-reload  │ │  - Loan      │ │  - JFR Events    │    │
│  │  - VarHandle   │ │    pattern   │ │  - Trace ID      │    │
│  │  - No deps     │ │  - Arenas    │ │  - RFC 9114 Err  │    │
│  └────────────────┘ └──────────────┘ └──────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

---

## 💡 Core Concepts

### 1. Virtual Threads (JEP 444/491)
- Lightweight, scheduler‑managed threads
- Memory cost: ~100 bytes (vs ~1 MB for OS threads)
- Enables **1 thread per request**, no pools, no callbacks

### 2. Panama FFM (JEP 454)
- Safe native interop
- Used for QUIC parsing, OpenSSL TLS, zero‑copy operations
- Eliminates JNI overhead

### 3. Scoped Values (JEP 506)
- Immutable, inherited context
- Used for tenant, security, trace ID
- Safe for Virtual Threads, unlike ThreadLocal

### 4. Row‑Level Security (Tenant Isolation)
- Enforced at PostgreSQL storage layer
- Automatic tenant filtering via `SET LOCAL exeris.tenant_id`
- Immune to application‑side query bugs

---

## 🔄 Request Flow (End‑to‑End)

```
┌─────────────────────────────────────────────────────────────┐
│  1. Packet Arrives -> Carrier Thread parses (Panama FFM)    │
│     - Zero-copy via MemorySegment slices (LoanedBuffer)     │
├─────────────────────────────────────────────────────────────┤
│  2. Dispatcher Creates Virtual Thread (1 per stream)        │
├─────────────────────────────────────────────────────────────┤
│  3. Priority-Aware Scheduler applies load-shedding          │
├─────────────────────────────────────────────────────────────┤
│  4. HTTP/3 Handler Binds ScopedValue (TenantContext)        │
├─────────────────────────────────────────────────────────────┤
│  5. Business Logic executes via Kernel Providers (SPI)      │
├─────────────────────────────────────────────────────────────┤
│  6. Database Query executes (RLS automatically enforced)    │
├─────────────────────────────────────────────────────────────┤
│  7. Event Appended + Transactional Outbox triggered         │
├─────────────────────────────────────────────────────────────┤
│  8. Response Sent (LoanedBuffer ref-count reaches 0 -> pool)│
└─────────────────────────────────────────────────────────────┘
```

---

## 🛡️ Observability & Failure Handling

### Graceful Degradation & Backpressure
- High Watermark breach → **H3_EXCESSIVE_LOAD**
- Prevents crashes, enforces fairness
- Virtual Thread pinning >50ms → logged as `EX-RUN-3002`

### JFR‑First Telemetry (“Glass Box”)
- No heavy agents
- Every major kernel operation emits a **JFR event**
- Nanosecond precision, minimal overhead

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