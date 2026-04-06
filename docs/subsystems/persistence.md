# Kernel Subsystem: Persistence (L1 Data & Integrity)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.persistence.*` (Contracts, StorageContext, Interceptors)
- Core: `eu.exeris.kernel.core.persistence.*` (Transaction Manager, Initializers, Outbox Poller)
- Drivers: `exeris-kernel-community` (JDBC-Native / VT-Optimized) / `exeris-kernel-enterprise` (Native Wire
  optimizations)

**Layer:** L1 (Data & Integrity)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Persistence subsystem** is a high-performance, protocol-agnostic data access engine. It acts as the
**"Invisible Wall"** between business logic and physical storage, prioritizing **Virtual Threads + JDBC** over
complex reactive streams.

- **Tenant-Atheist Design:** The Core engine is blind to security contexts. It knows nothing about "Users",
  "Tenants", or authentication. It operates exclusively on a generic `StorageContext`, resolving isolation
  (RLS, schemas) as a transparent side-effect via `ConnectionInterceptor` plugins. This is the physical
  enforcement of "The Wall" between the Security (L1 Citadel) and Persistence subsystems.
- **Loom-First JDBC:** Java 26 Virtual Threads make blocking I/O cheap. Simple, imperative code with full stack
  traces and minimal object churn — no reactive pipeline overhead, no `Mono<T>` wrappers, no callback hell.
- **Plug-and-Play Isolation:** Supports RLS (Shared Schema), Dedicated Schemas, and Dedicated Databases via the
  `ConnectionInterceptor` API — switched transparently by the `StorageContext` without any change in business code.
- **Transactional Outbox:** Guaranteed at-least-once delivery of domain events, atomically bound to the same
  database transaction as the triggering entity mutation.

---

## Core Philosophy: "Blocking is the New Non-Blocking"

### 1. Loom-First Architecture

We explicitly prefer standard, blocking JDBC drivers. Virtual Threads make blocking I/O cheap, allowing imperative
code with deep stack traces while avoiding the massive object allocation rate inherent in reactive frameworks like
R2DBC.

### 2. Tenant-Atheist Design (The Wall Enforcement)

Persistence Core MUST NOT import anything from the Security SPI. It treats isolation as a **side-effect**:

- **Global Mode (Default):** No context, zero overhead, direct SQL.
- **Isolated Mode (Plugin):** Activated only when a `StorageContextProvider` is registered via `ServiceLoader`.

The Security subsystem owns the `PrincipalContext`. The Persistence subsystem owns the `StorageContext`. The bridge
between them (extracting `tenantId` from `PrincipalContext` and constructing a `StorageContext`) lives in Core's
orchestration layer — never inside either SPI.

### 3. Trust the Storage Engine

We delegate data integrity to PostgreSQL. Constraints, RLS policies, and JSONB binary storage are used to minimize
Java-side logic and serialization waste.

---

## Responsibilities

**What Persistence SPI DOES:**

1. Define `BaseRepository<T, ID>` and `EventStore` contracts.
2. Provide the `StorageContext` and `ConnectionInterceptor` interfaces.
3. Define the `PersistenceException` hierarchy with `EX-PERS-` codes.

**What Persistence Core DOES:**

1. Orchestrate the transaction lifecycle and `ScopedValue` propagation.
2. Manage a registry of `ConnectionInterceptors`.
3. Provide the `ConnectionInitializer` that prepares connection-level SQL for RLS and schema selection.
4. Translate database-specific errors into standardized Kernel codes.

---

## Multi-Tenancy Isolation Strategies

Exeris supports three levels of physical isolation, resolved transparently through the `StorageContext`:

| Strategy                | Mechanism                     | Target Use-Case                        |
|:------------------------|:------------------------------|:---------------------------------------|
| **Shared Schema (RLS)** | `SET LOCAL exeris.tenant_id`  | Standard SaaS, High-Density            |
| **Dedicated Schema**    | `SET search_path TO [schema]` | Professional Tier, easier migrations   |
| **Dedicated Database**  | Dynamic DataSource Routing    | Enterprise, maximum physical isolation |

---

## Admission Control & Backpressure Integration

### Overview

The Persistence subsystem provides **SPI-level admission control** to prevent thread starvation in high-concurrency scenarios. This is enforced via the `PersistenceEngine.canServiceRequest()` method, which is called by the HTTP layer before creating a session box.

### Design: No Starvation Contract

```java
/**
 * Query whether this engine can service a new request without thread starvation.
 * Called by HTTP layer to implement admission control.
 *
 * Returns false if:
 * - Pool has no idle connections AND queue is forming (idle==0 && queued>0)
 * - Active connections >= 90% of maxPoolSize (proactive buffer)
 * - Engine is shutting down
 */
boolean canServiceRequest() throws PersistenceProviderException;
```

### HTTP Integration: 503 Service Unavailable

When `canServiceRequest()` returns false, the HTTP processor responds with:
```
HTTP/1.1 503 Service Unavailable
Retry-After: 1
Content-Length: 0
```

This prevents:
- Unbounded thread creation
- Connection pool queue buildup
- Cascading latency spikes (fairness inversion)

### Performance Impact

**Problem Fixed** (Benchmark 2026-03-28):
- ThreadPark events: 40,793 → <5,000 (87% reduction)
- p50 latency: 52.57ms → ≤5ms (10x improvement)
- Fairness index: 0.1268 → ≥0.95 (fair distribution)

**Root Cause Addressed:**
- Before: HTTP created 428k session boxes per 52k requests (8.2:1 ratio)
- After: Admission gating limits creation to available pool capacity

### Tier Implementations

| Aspect | Community | Enterprise |
|--------|-----------|------------|
| **Pool Query** | HikariCP `getNumIdle()` / `getNumActive()` | Native io_uring SQE availability + metric poll |
| **Rejection Threshold** | idle==0 && queued>0 OR active>=90% max | Adaptive exponential backoff + native telemetry |
| **Latency** | <1ms | <500µs |
| **Overhead** | O(1) state query | O(1) native probe |

### TCK Compliance

All implementations must satisfy `AbstractPersistenceEngineAdmissionControlTck`:
- Returns true when pool has idle capacity
- Returns false when idle==0 && queue forming
- Returns false when active >= 90% max
- Returns false after engine shutdown
- Latency guarantee: ≤5ms p50 per call (authoritative sub-millisecond bound enforced by JMH benchmarks)
- Zero-allocation on hot path (JFR verified)

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. The `rawArgs` binary layout is defined
> in the Javadoc of each constant and must not diverge from this table.

| Code           | Meaning                          | Glass-Box Payload (`rawArgs`)                                     |
|:---------------|:---------------------------------|:------------------------------------------------------------------|
| `EX-PERS-5001` | Provider Bootstrap Failure       | `[0] String providerName, [1] String sanitizedConnectionUrl`      |
| `EX-PERS-5002` | Connection Pool Exhausted        | `[0] String providerName, [1] long timeoutMs, [2] int activeConns`|
| `EX-PERS-5003` | Query Execution Failure          | `[0] String sqlState, [1] String detail`                          |
| `EX-PERS-5004` | Authentication Failure           | `[0] String authMechanism, [1] String serverMessage`              |
| `EX-PERS-5005` | Persistence Transport Failure    | `[0] String transportName, [1] long fd, [2] int errno`            |
| `EX-PERS-5006` | Interceptor Initialization Error | `[0] String interceptorClass, [1] String isolationKey`            |
| `EX-PERS-5007` | No Provider on Classpath         | `[0] String message` — **Fatal:** add `community` or `enterprise` jar |

**Privacy note for `EX-PERS-5001`:** The `sanitizedConnectionUrl` field MUST have the `user:password@` userinfo
segment stripped before capture. Emitting raw credentials constitutes a CWE-532 violation. See
`KernelErrorCodes.EX_PERS_5001` Javadoc for the canonical enforcement comment.

**Lifecycle note for `EX-PERS-5006`:** A connection that triggered this code MUST NOT be returned to the pool.
The engine must discard it immediately to prevent a poisoned connection from serving future requests.

---

## Code Examples

### 1. Agnostic Interceptor API (SPI)

Persistence does not know about Security. It only provides the "slot" for isolation logic.

```java
package eu.exeris.kernel.spi.persistence;

@FunctionalInterface
public interface ConnectionInterceptor {
    void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext);
}
```

### 2. StorageContext — The Atheist Bridge (SPI)

The context that drives isolation without knowing any business or security details.

```java
package eu.exeris.kernel.spi.persistence;

public interface StorageContext {
    Optional<String> isolationKey();
    Map<String, Object> attributes();
}
```

### 3. Clean Imperative Repository (L2/L3 Layer)

No reactive wrappers. Pure, thread-safe, and debuggable. The `StorageContext` is injected into the DB connection
automatically by Core — the business layer never touches it.

```java
public class OrderService {
    private final OrderRepository repository;

    @Transactional
    public void placeOrder(Order order) {
        repository.save(order);
    }
}
```

### 4. Fail-Fast on Missing Provider (Core Bootstrap)

```java
PersistenceProvider provider = ServiceLoader.load(PersistenceProvider.class)
        .findFirst()
        .orElseThrow(() -> new PersistenceBootstrapException(
                KernelErrorCodes.EX_PERS_5007,
                "No PersistenceProvider found on classpath. Add exeris-kernel-community or exeris-kernel-enterprise."
        ));
```
---


## Connection Pool Sizing — Loom-First Guidance

Virtual Threads decouple concurrency from OS threads, but JDBC connection pools must remain
**physically bounded** — a connection represents a server-side resource on PostgreSQL, not a JVM
thread. The following guidance applies:

| Factor                         | Recommendation                                                                                                 |
|:-------------------------------|:---------------------------------------------------------------------------------------------------------------|
| **Pool size formula**          | `cores × 2 + effective_spindle_count` (PgBouncer rule). For NVMe-backed PostgreSQL: `cores × 2`. Ignore VT count entirely — pool size is a DB resource limit, not a thread limit. |
| **Typical range**              | 10–50 connections per JVM instance. Do NOT scale pool size proportionally to VT count (millions of VTs with 50 connections = correct; 1M connections = PostgreSQL crash). |
| **Overflow behaviour**         | When pool is exhausted, the JDBC driver blocks the VT (parking, not pinning — see VT Pinning Check below). If the acquisition timeout fires, `EX-PERS-5002` is thrown with `rawArgs[1]=timeoutMs`. |
| **HikariCP minimum config**    | `maximumPoolSize`: bounded (see formula); `connectionTimeout`: 5 000 ms; `idleTimeout`: 600 000 ms; `keepaliveTime`: 30 000 ms; `validationTimeout`: 5 000 ms. |
| **Connection validation**      | Stale connections (idle timeout, PostgreSQL failover, `tcp_keepalive_time` breach) are detected via HikariCP's `keepaliveTime` heartbeat. A new connection is acquired transparently. If validation fails at acquisition time, the pool discards the stale connection and retries up to `initializationFailTimeout`. On repeated failure: `EX-PERS-5003` (sqlState `08006` = connection failure). |

> **Anti-pattern:** `maximumPoolSize=1000` in a 10-core Kubernetes pod. This creates 1000 PostgreSQL
> backend processes, each consuming ~5 MB RAM. On a 3-replica deployment that is 3000 backends —
> PostgreSQL will reject connections before any query runs. Always size the pool to the DB server,
> not to the concurrency level of the application.

---

## Database Schema Management — Migration Strategy

The Exeris Kernel **does not manage application schemas**. Schema creation, versioning, and migration
are the responsibility of the application layer or a dedicated migration tool.

> **Exception — internal kernel tables:** The Community tier includes an opt-in migration path
> (`persistence.run.migrations=true`) that executes built-in SQL scripts to create internal kernel
> tables (`exeris_outbox`, `exeris_outbox_dlq`). This applies only to Kernel-owned tables and is
> explicitly disabled by default.

| Concern                             | Recommendation                                                                                           |
|:------------------------------------|:---------------------------------------------------------------------------------------------------------|
| **Initial schema creation**         | Use Flyway or Liquibase, executed before the Exeris Kernel boots (K8s `initContainer` pattern).          |

---

## Transactional Outbox — Poison Message Handling

The Transactional Outbox guarantees at-least-once delivery. "Poison messages" — events that consistently
fail delivery to the broker regardless of retries — must be handled explicitly.

### Retry Policy

| Attempt | Delay (exponential backoff)  | Action                                                   |
|:--------|:-----------------------------|:---------------------------------------------------------|
| 1       | 0 ms (immediate)             | First delivery attempt                                   |
| 2–5     | `2^n × 100 ms` (capped 16 s) | Retry with exponential backoff                           |
### Dead Letter Queue (DLQ)
After `exeris.persistence.outbox.max-retries` (default: 10) failed delivery attempts, the record
is moved atomically to the `exeris_outbox_dlq` table and removed from the main outbox. A `JFR` event
(`OutboxDlqEvent`) is emitted with `rawArgs[0]=String eventType, rawArgs[1]=long outboxRecordId`.

**DLQ schema (auto-created on first boot):**

```sql
CREATE TABLE IF NOT EXISTS exeris_outbox_dlq (
    id            BIGSERIAL PRIMARY KEY,
    original_id   BIGINT NOT NULL,
    event_type    TEXT NOT NULL,
    payload       BYTEA NOT NULL,
    failure_reason TEXT,
    moved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id     TEXT
);
```

**Operator recovery:** Inspect `exeris_outbox_dlq` and either re-queue records manually
(`INSERT INTO exeris_outbox SELECT ... FROM exeris_outbox_dlq WHERE id = ?`) or discard them after
root cause analysis. There is no automatic re-queue from DLQ — this is intentional to prevent
infinite retry storms.

---

## Testing Strategy

### Unit Tests

- `StorageContext` resolution logic (key extraction from `PrincipalContext` bridge in Core orchestration).
- `ConnectionInitializer` interceptor execution order.
- `EX-PERS-5006` is thrown on interceptor failure and the connection is NOT returned to the pool.

### Integration Tests (TCK)

- **Isolation Leak Test:** Verify that `isolationKey` A cannot see data belonging to `isolationKey` B.
- **VT Pinning Check:** Verify that the JDBC driver does not pin the Carrier Thread under heavy I/O
  (`CarrierPinnedEvent` must not fire during standard query execution).
- **Outbox Durability:** Events are committed only if the main transaction succeeds; rolled back otherwise.
- **Pool Exhaustion:** `EX-PERS-5002` is thrown with correct `rawArgs` when all connections are in use.
- **No Provider:** `EX-PERS-5007` is thrown at bootstrap when no `PersistenceProvider` is on the classpath.

---

## Summary

The Persistence subsystem is the "Atheist" engine of the Exeris Kernel. By decoupling data access from business
identity via `StorageContext`, leaning into Virtual Threads for lock-free blocking I/O, and delegating integrity
enforcement to PostgreSQL's native RLS, it provides a simple, hyper-fast, and extremely flexible storage layer.
Together with the Security subsystem (Citadel), it forms the **L1 Integrity Barrier** — the boundary that guarantees
no unauthorized data ever reaches the application layer and no unguarded mutation ever reaches persistent storage.
