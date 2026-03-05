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

## Error Codes (Black Box Telemetry)

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. The `rawArgs` binary layout is defined
> in the Javadoc of each constant and must not diverge from this table.

| Code           | Meaning                          | Black-Box Payload (`rawArgs`)                                     |
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
                "No PersistenceProvider found — add exeris-kernel-community or exeris-kernel-enterprise"));
```

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
