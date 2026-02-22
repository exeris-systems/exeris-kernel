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

The **Persistence subsystem** is a high-performance, protocol-agnostic data access engine. It provides the "Invisible
Wall" between application logic and physical storage. In the era of Java 26, it prioritizes **Virtual Threads + JDBC**
synergy, rejecting the complexity and memory overhead of reactive streams.

- **Tenant-Atheist Design:** The core engine knows nothing about "Tenants" or "Security". It operates on a generic
  `StorageContext`.
- **Loom-First JDBC:** Leverages Virtual Threads to handle blocking I/O, which is more memory-efficient (less object
  churn) than R2DBC pipelines.
- **Plug-and-Play Isolation:** Supports RLS (Shared Schema), Dedicated Schemas, and Dedicated Databases via an
  Interceptor API.
- **Transactional Outbox:** Guaranteed at-least-once delivery of domain events, physically bound to the entity
  transaction.

---

## Core Philosophy

### 1. "Blocking is the New Non-Blocking"

We explicitly prefer standard, blocking JDBC drivers. Virtual Threads make blocking "cheap," allowing us to write
simple, imperative code with deep stack traces, while avoiding the massive object allocation rate inherent in reactive
frameworks like R2DBC.

### 2. The Agnostic Data Principle

Persistence Core MUST NOT import anything from the Security SPI. It treats isolation as a **Side-Effect**.

- **Global Mode (Default):** No context, zero overhead, direct SQL.
- **Isolated Mode (Plugin):** Activated only when a `StorageContextProvider` is registered.

### 3. Trust the Storage Engine

We delegate data integrity to PostgreSQL. Constraints, RLS, and JSONB binary excellence are used to minimize Java-side
logic and serialization waste.

---

## Responsibilities

**What Persistence SPI DOES:**

1. Define `BaseRepository<T, ID>` and `EventStore` contracts.
2. Provide the `StorageContext` and `ConnectionInterceptor` interfaces.
3. Define the `PersistenceException` hierarchy with `EX-PRST-` codes.

**What Persistence Core DOES:**

1. Orchestrate the Transaction Lifecycle and ScopedValue propagation.
2. Manage a registry of `ConnectionInterceptors`.
3. Provide the `ConnectionInitializer` that prepares connection/session initialization SQL for RLS and schema selection.
4. Translate database-specific errors into standardized Kernel codes.

---

## Multi-Tenancy Isolation Strategies

Exeris Kernel supports three levels of physical isolation, resolved transparently through the `StorageContext`:

| Strategy                | Mechanism                     | Target Use-Case                        |
|:------------------------|:------------------------------|:---------------------------------------|
| **Shared Schema (RLS)** | `SET LOCAL exeris.tenant_id`  | Standard SaaS, High-Density            |
| **Dedicated Schema**    | `SET search_path TO [schema]` | Professional Tier, easier migrations   |
| **Dedicated Database**  | Dynamic DataSource Routing    | Enterprise, maximum physical isolation |

---

## Error Codes (Black Box Telemetry)

| Code           | Meaning                          | Action                                          |
|:---------------|:---------------------------------|:------------------------------------------------|
| `EX-PRST-1001` | Connection Pool Exhausted        | Trigger Load Shedding (Transport backpressure). |
| `EX-PRST-1002` | Optimistic Lock Conflict         | Notify Flow layer for potential retry.          |
| `EX-PRST-1003` | Isolation Violation (RLS/Schema) | Security breach attempt - kill connection.      |
| `EX-PRST-1004` | Serialization Failure            | Automatic transaction retry.                    |
| `EX-PRST-1005` | Interceptor Initialization Error | Halt connection checkout.                       |

---

## Code Examples

### 1. Agnostic Interceptor API (SPI)

Persistence doesn't know about Security. It only provides the "slot".

```java
package eu.exeris.kernel.spi.persistence;

import java.sql.Connection;

@FunctionalInterface
public interface ConnectionInterceptor {
    void onConnectionCreated(Connection connection) throws Exception;
}
```

### 2. Storage Context (The Bridge)

The context that drives isolation without knowing business details.

```java
package eu.exeris.kernel.spi.persistence;

public interface StorageContext {
    Optional<String> isolationKey(); // Can be tenantId, schemaName, etc.

    Map<String, Object> attributes();
}
```

### 3. Using the Repository (L2/L3 Layer)

Clean, imperative code. No reactive wrappers.

```java
public class OrderService {
    private final OrderRepository repository;

    @Transactional
    public void placeOrder(Order order) {
        // RLS/Schema isolation happens invisibly in the Core layer
        repository.save(order);
    }
}
```

## Testing Strategy

### Unit Tests

StorageContext resolution logic.

ConnectionInitializer interceptor execution order.

### Integration Tests (TCK)

Isolation Leak Test: Verify that isolationKey A cannot see data from isolationKey B.

VT Pinning Check: Verify that the JDBC driver does not pin the Carrier Thread during heavy I/O.

Outbox Durability: Ensure events are committed only if the main transaction succeeds.

## Summary

The Persistence subsystem is the ultimate "Atheist" engine of the Exeris Kernel. By decoupling data access from business
identity and leaning into Virtual Threads, it provides a simple, hyper-fast, and extremely flexible storage layer that
scales from single-user CLI tools to massive multi-tenant Enterprise platforms.