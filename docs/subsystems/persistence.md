# Kernel Subsystem: Persistence (L1 Data & Integrity)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.persistence.*` (Contracts, ConnectionInterceptor — StorageContext is in Security SPI)
  > Key SPI contracts: `PersistenceEngine`, `PersistenceProvider`, `PersistenceConfig`,
  > `PersistenceHealthStatus`, `BaseRepository`, `EventStore`, `ConnectionInterceptor`, `TransactionalExecutor`, 
  > `RowCursor`, `QueryResult`, `PersistenceStatement`, `BulkInserter`, `EngineStats`, 
  > `TransactionIsolation`; codec sub-package: `codec.EntityEncoder`, `codec.EntityDecoder` 
  > (zero-copy off-heap encode/decode contract, TCK: `AbstractEntityCodecTck`).
  > Since v0.8 (ADR-022): additive `PersistenceStatement.bindInstant(int, Instant)` and
  > `RowCursor.getInstant(int)` for typed timestamp binding/reading on `TIMESTAMP WITH TIME ZONE`
  > columns. `null` maps to SQL NULL on the binder; `getInstant` returns `null` for SQL NULL
  > (reference-typed opt-out from the primitive-getter NPE convention).
- Core: `eu.exeris.kernel.core.persistence.*` (Transaction Manager, Initializers)
  > The Outbox Poller resides in the Events subsystem (`eu.exeris.kernel.core.events.outbox.*`). 
  > Persistence provides the `EventStore` SPI consumed by the Outbox Orchestrator.
- Drivers: `exeris-kernel-community` (JDBC-Native / VT-Optimized)

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
- **Isolated Mode:** Activated when a `StorageContext` is bound to `KernelProviders.STORAGE_CONTEXT` (ScopedValue) 
- by the Security layer before the persistence boundary is crossed. Zero `ServiceLoader` involvement.

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
4. Define `RowCursor` and `QueryResult` as the zero-copy flyweight row accessor contracts. `RowCursor` is a single shared instance advanced by `QueryResult.next()` — callers must not retain references across calls.
5. Define `BulkInserter` for batch-insertion paths.
6. Define `PersistenceHealthStatus` as the observable engine state contract.
7. Define the `codec` sub-package (`EntityEncoder`, `EntityDecoder`) for binary entity serialisation. TCK: `AbstractEntityCodecTck`.

**What Persistence Core DOES:**

1. Orchestrate the transaction lifecycle and `ScopedValue` propagation.
2. Manage a registry of `ConnectionInterceptors`.
3. Manage a registry of `ConnectionInterceptor` instances via `InterceptorRegistry` (`eu.exeris.kernel.core.persistence.InterceptorRegistry`). Interceptors handle RLS injection, schema switching, and audit setup.
4. Translate database-specific errors into standardized Kernel codes.

---

## Multi-Tenancy Isolation Strategies

Exeris supports three levels of physical isolation, resolved transparently through the `StorageContext`:

| Strategy                | Mechanism                     | Target Use-Case                        |
|:------------------------|:------------------------------|:---------------------------------------|
| **Shared Schema (RLS)** | `SET LOCAL exeris.tenant_id`  | Standard SaaS, High-Density            |
| **Dedicated Schema**    | `SET search_path TO [schema]` | Professional Tier, easier migrations   |
| **Dedicated Database**  | Dynamic DataSource Routing    | Maximum physical isolation             |

The Mechanism column is what each strategy adds, not all it issues. **Every strategy publishes both
`exeris.tenant_id` and `exeris.shared_scope` on connection acquisition**, in one `set_config` statement,
whether or not its own isolation reads them — and publishes `""` when the context declares none. Both
names are published as constants on the persistence SPI (`ConnectionInterceptor.SESSION_KEY_TENANT_ID`,
`SESSION_KEY_SHARED_SCOPE`) so a generator or migration tool can reference them instead of retyping
them: a policy naming a key the runtime never publishes returns zero rows and refuses every write,
which is a hard failure with nothing pointing at its cause.

**Publishing the keys is the kernel's half; enforcing them is the deployment's, and `ENABLE` alone
does not enforce.** The kernel ships no RLS policy and cannot introspect one. A conforming policy —
the canonical example is in `RlsConnectionInterceptor`'s javadoc — must `ALTER TABLE … ENABLE ROW LEVEL
SECURITY` **and** `FORCE ROW LEVEL SECURITY`, because PostgreSQL exempts a table's owner from its own
policies unless the table is forced. An application connecting as the role that owns its tables (the
default in every quick-start) otherwise gets a policy that is enabled, listed in `pg_policies`, and
never applied: no error, and other tenants' rows in every read.

Placement decides which connection a request lands on; it does not decide what the previous borrower
left in the session. `set_config(..., false)` is session-scoped, so with `persistence.perTenantPooling`
at its default of `false` a Dedicated-Schema request is served from the pool a Shared-Schema request
last used. Skipping the tenant key there leaves the predecessor's value in place for any RLS policy on
a table the request touches. The same reasoning is why Shared and Dedicated Database both `RESET
search_path`: a setting a strategy never writes is not a setting it is free to ignore.

---

## Request Session and the Scope Key

One HTTP request is one connection. `CommunityHttpRequestDispatcher` binds a
`PersistenceSessionBox` for the request; the first persistence call acquires a
connection, every later call reuses it, and `box.release()` in the handler's
`finally` returns it to the pool.

### Measuring how long a connection is actually held (since 0.12)

Two JFR events cover the pool, and it is worth knowing which question each answers.
`eu.exeris.kernel.persistence.ConnectionAcquire` carries `acquireLatencyNs` — the time spent
*getting* a connection. `eu.exeris.kernel.persistence.ConnectionHold` is emitted when the connection
goes back to the pool and carries `holdDurationNs` — the time it was *kept*.

The second exists because the first has no counterpart on the release side, and the only hold
measurement the kernel had came from `RequestSessionLifecycleEvent`, which is emitted from
`PersistenceSessionBox` and nowhere else. A caller with no request session — a flow step on a bare
virtual thread, a scheduled job, the migration runner — produced an acquire event and then nothing.
That made pool residency for background work unmeasurable, and it made the *absence* of session
events on those threads look like a finding when it is a property of where the instrument sits.
`ConnectionHold` is emitted at the pool return itself, so every acquire has a matching hold whoever
asked.

Two fields make the residency apportionable, and both are sampled **at acquire**, not at release —
the returning thread is not necessarily the acquiring one:

| Field | Meaning |
|---|---|
| `withinRequestScope` | A request session was in scope on the acquiring thread. **Not an ownership claim** — a deliberate `openPhysical()` inside a request reads `true`, which is right for apportioning residency and wrong as a statement about who owns the connection. |
| `acquiredOnVirtualThread` | The acquiring thread was virtual. Background work in this kernel runs virtual, so `withinRequestScope=false` with `acquiredOnVirtualThread=true` is the flow-step signature. |
| `discarded` | The connection was **evicted, not returned**. A pool eviction reaches the same `close()` a healthy return does, so without this flag the two are the same event. The eviction path this kernel actually takes is `discardAfterInterceptorFailure` — a `ConnectionInterceptor` threw, so the connection is thrown away because the RLS session keys could not be published. Reported as an ordinary hold, a burst of those reads as a burst of very short *healthy* returns. |

Both events are single-phase and hand their `commit()` to a platform thread through `JfrCommitGate`:
a hold spans arbitrary caller work, so the release nearly always follows a park/remount, which is
exactly the condition that makes a carrier-bound `EventWriter` flush a stale buffer.

The box keys that session by a **scope key** taken from the request's
`StorageContext`: `isolationKey`, `schemaName`, or `:dedicated:<ds>` per
strategy, and `shared` when none is declared. A call whose scope key does not
match the session's is refused (`BYPASS_SCOPE_MISMATCH`) and takes its own
connection. That refusal is deliberate: a request genuinely addressing a
second tenant must not be served the first tenant's session.

**Both `openConnection()` overloads MUST derive that key from the same source.**
The no-arg overload resolves `KernelProviders.storageContextOrSystem()` and
delegates to the context overload; it does not key the session `shared` on its
own. Until v0.12 it did, and the damage was not confined to connection counts:

- Any request touching both overloads mismatched by construction. A Saga does:
  the flow snapshot store, the outbox adapter and the event log all call the
  no-arg overload while repositories arrive through the context one. Measured
  on a v0.11 benchmark, 2.0 bypasses per request session.
- The fallback ran **no** `ConnectionInterceptor`. `RlsConnectionInterceptor`
  publishes its keys with session-scoped `set_config(..., false)`, which
  survives pool checkin, so that connection arrived carrying the *previous
  borrower's* tenant: a cross-tenant read under RLS, and a write judged by the
  wrong `WITH CHECK`.

The second point is why this is an isolation rule rather than a pooling
optimisation. `CommunityRequestScopeBypassIsolationIT` pins it: two pooled
connections primed under `tenant-a`, one `tenant-b` request, and the connection
its second call receives must report `tenant-b`, see only `tenant-b` rows, and
run on the same backend as its first call.

## Admission Control & Backpressure Integration

### Overview

The Persistence subsystem provides **SPI-level admission control** to prevent thread starvation in high-concurrency scenarios. This is enforced via the `PersistenceEngine.canServiceRequest()` method, which is called by the HTTP layer before creating a session box.

### Design: No Starvation Contract

```java
/**
 * Query whether this engine can service a new request without thread starvation.
 * Called by the HTTP layer to implement admission control.
 *
 * Sheds (returns false) when admitting would exceed the No-Waste-Compute latency
 * bound — i.e. under a queue deep enough that the expected acquire wait is unbounded —
 * or when the engine is shutting down. The exact shed trigger is tier/config-defined.
 */
boolean canServiceRequest();
```

### Recalibrated semantics (ADR-035)

Prior to ADR-035 the Community gate shed on the *first* queued acquire (`idle==0 && queued>0`)
or at `active/max ≥ 0.90`. On a CPU-constrained profile the adaptive pool collapses to ~2
connections, so a modest client burst tripped the gate and shed the overwhelming majority of an
otherwise trivial workload as `503` (a real benchmark regression vs v0.5, which queued briefly
and returned 0 errors).

The gate now admits while pending acquires stay within a **pool-size-scaled allowance** and sheds
only once the queue is genuinely deep:

```
allowance = ceil(maxPoolSize × queueDepthAllowanceRatio)   // default ratio 8.0
shed (full/saturated pool) ⇔ pendingAcquires > allowance
```

A full pool with no (or a shallow) queue is **admitted** — the request queues briefly on the
connection-acquire path. This restores availability on small pools while keeping backpressure for a
genuinely deep queue. Setting `queueDepthAllowanceRatio=0` recovers the strict pre-035 behavior.

### Tunable thresholds (`persistence.admission.*`)

| Key | Default | Meaning |
|---|---|---|
| `persistence.admission.queueDepthAllowanceRatio` | `8.0` | pending acquires tolerated per unit of pool size before shedding; `0` = strict pre-035 |
| `persistence.admission.hardSaturationThreshold` | `0.90` | `active/max` at which a queued pool sheds |
| `persistence.admission.guardBandThreshold` | `0.85` | early-fairness band entry ratio |
| `persistence.admission.fairnessStressThreshold` | `0.90` | fairness ratio declaring sustained stress |
| `persistence.admission.fairnessQueueDepthThreshold` | `1` | queue depth considered for fairness stress |
| `persistence.admission.earlyGuardBandHeadroomRatio` | `0.15` | headroom fraction for early guard-band reject |
| `persistence.admission.earlyGuardBandHeadroomCap` | `3` | absolute cap on the early guard-band window |

**Tier split (per the `@Dynamic` contract):** Community resolves these once at bootstrap and does
not hot-reload (`ConfigProvider.watch` is a no-op); Enterprise swaps the live config atomically on
file change. The thresholds live entirely in Community (`CommunityAdmissionConfig`) — no SPI field
is added, so The Wall is untouched.

### HTTP Integration: 503 Service Unavailable

When `canServiceRequest()` returns false, the HTTP processor responds with:
```
HTTP/1.1 503 Service Unavailable
Retry-After: 1
Content-Length: 0
```

This prevents:
- Unbounded thread creation
- Connection pool queue buildup beyond the allowance
- Cascading latency spikes (fairness inversion)

### TCK Compliance

`AbstractPersistenceEngineAdmissionControlTck` verifies the **cross-tier** invariants only
(shedding on a forming queue is a tunable tier policy, not a universal contract):
- Returns true when the pool has idle capacity
- Returns false (or throws) after engine shutdown
- Decision is non-blocking and consistent in the same state
- Decision flips to admit when capacity is released
- Zero-allocation on hot path (JFR verified)

Tier-specific shed thresholds (strict reject machine; recalibrated small-pool admit / deep-queue
shed) are covered by the Community admission tests.

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
| `EX-PERS-5007` | No Provider on Classpath         | `[0] String message` — **Fatal:** add a persistence provider implementation jar |

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
package eu.exeris.kernel.spi.security;

public interface StorageContext {
    IsolationStrategy strategy();
    Optional<String> schemaName();
    Optional<String> dataSourceKey();
    Map<String, String> attributes();
}
```

> **Note:** StorageContext is defined in the Security SPI by design (The Wall) — Persistence only consumes it; it has no definitional role in the Persistence SPI.

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
                "No PersistenceProvider found on classpath. Add a persistence provider jar."
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
> tables (`exeris_outbox`, `exeris_outbox_dlq`, `exeris_saga_state` since 0.7). This applies only to
> Kernel-owned tables and is explicitly disabled by default. The migration list is maintained in
> `CommunityPersistenceEngine.MIGRATION_RESOURCES`; new internal tables follow the
> `db/migration/V{version}__{name}.sql` naming convention. The runner is intentionally minimal
> (string split on `;`); it is not a Flyway substitute. Application schemas continue to be the
> operator's responsibility (see the recommendation table below).

### Apply-once is the runner's property, not the SQL's (since 0.12, ADR-073)

Until v0.12 the runner executed **every** script on **every** boot and recorded nothing. Re-running
was harmless only because every author had written `CREATE TABLE IF NOT EXISTS` — counted, not
assumed: 14 DDL statements across six scripts, all 14 guarded. The first migration that cannot be
written that way breaks it, and the shapes are ordinary: a data backfill re-applies **silently**
(healthy boot, wrong data), a constraint tightening fails the second boot outright.

`exeris_schema_history` now records one row per applied migration — version, script, checksum,
timestamp. Three rules follow:

| Rule | Behaviour |
|---|---|
| **Apply-once** | A version already in the ledger is skipped, not re-run. |
| **Fail closed on drift** | A version in the ledger whose file now hashes differently **refuses the boot**. The database no longer matches the code, and warning-and-continuing is what makes a drifted database look healthy. Remedy: restore the file, or delete the row if the change is known to be applied already. |
| **One transaction per migration** | The ledger row is committed with the migration's own statements, so the ledger cannot claim something the database lacks. A failure at migration 4 keeps 1–3 applied *and recorded*, and the next boot resumes at 4. |

The checksum folds `\r\n` to `\n` and normalises nothing else — without that, a checkout on Windows
refuses every boot with nothing wrong; with more, an edit could slip past.

The ledger table is created with `IF NOT EXISTS` and is deliberately not recorded in itself: it is
the one piece of schema whose creation must stay idempotent.

**Existing databases baseline themselves on first boot.** With no ledger the runner sees every
version as unapplied and runs the set; the guards make it a no-op and the ledger is then correct.
That works *because* the current scripts are idempotent — the property being retired — so it is a
one-time debt, not a mechanism.

**Not covered:** concurrent boot of multiple nodes (two kernels can both see an empty ledger; no lock
is taken, unchanged from before and tracked with the cross-node coordination seam), repair/baseline
tooling, out-of-order versions, and down-migrations.

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
- `InterceptorRegistry` execution order (registration, sealing, call order).
- `EX-PERS-5006` is thrown on interceptor failure and the connection is NOT returned to the pool.

### Integration Tests (TCK)

- **Isolation Leak Test:** Verify that `isolationKey` A cannot see data belonging to `isolationKey` B.
- **VT Pinning Check:** Verify that the JDBC driver does not pin the Carrier Thread under heavy I/O
  (`CarrierPinnedEvent` must not fire during standard query execution).
- **Outbox Durability:** Events are committed only if the main transaction succeeds; rolled back otherwise.
- **Pool Exhaustion:** `EX-PERS-5002` is thrown with correct `rawArgs` when all connections are in use.
- **No Provider:** `EX-PERS-5007` is thrown at bootstrap when no `PersistenceProvider` is on the classpath.

**Full TCK abstract class set:** `AbstractPersistenceEngineTck`, `AbstractPersistenceProviderTck`, `AbstractPersistenceEngineAdmissionControlTck`, `AbstractOutboxGuaranteeTck`, `AbstractEventStoreTck`, `AbstractEntityCodecTck`, `PersistenceCarrierPinningTck`, `PersistenceIsolationLeakTck`, `PersistenceZeroAllocTck`, `AbstractRowCursorThroughputBenchmark` (JMH).

---

## Summary

The Persistence subsystem is the "Atheist" engine of the Exeris Kernel. By decoupling data access from business
identity via `StorageContext`, leaning into Virtual Threads for lock-free blocking I/O, and delegating integrity
enforcement to PostgreSQL's native RLS, it provides a simple, hyper-fast, and extremely flexible storage layer.
Together with the Security subsystem (Citadel), it forms the **L1 Integrity Barrier** — the boundary that guarantees
no unauthorized data ever reaches the application layer and no unguarded mutation ever reaches persistent storage.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.persistence.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md). See the matrix for the semver policy and TCK
coverage status.
