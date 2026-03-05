# Kernel Subsystem: Security (L1 Citadel)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.security.*` (PrincipalContext, Role Enums, Annotations)
- Core: `eu.exeris.kernel.core.security.*` (Token Extractors, ScopedValue Orchestration)

**Layer:** L1 (Data & Integrity)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Security subsystem** implements mandatory, invisible security enforcement using **JEP 506 ScopedValues** for
Virtual Thread-safe context propagation. By abandoning legacy `ThreadLocal` patterns entirely, Exeris ensures
zero-leak, hyper-density concurrency with immutable identity at every layer. It provides:

- **Virtual Thread-Safe Context:** Extreme concurrency without `ThreadLocal` memory overhead or thread-pinning risks.
  With millions of Virtual Threads, a `ThreadLocal`-based approach would trigger massive GC churn on cleanup — `ScopedValue` eliminates this entirely.
- **Protocol-Agnostic Auth:** Extracts identity from transport-level tokens (JWT/Opaque) regardless of protocol
  (TCP, HTTP/2, QUIC).
- **Immutable Identity:** `PrincipalContext` is bound once via `ScopedValue.where(...)` and cannot be mutated or
  intercepted by any downstream code.
- **Tenant Isolation (RLS):** Physical separation at the storage level via automatic `StorageContext` injection — no
  `WHERE tenant_id = ?` required in business logic.

---

## Core Philosophy: "The Invisible Wall"

1. **Immutable Identity:** Every request carries a `PrincipalContext` bound via `ScopedValue`. It cannot be altered
   or bypassed once the request enters the Kernel.
2. **Clean Domain (No Context Pollution):** Domain Entities do NOT contain security metadata. Context is propagated
   invisibly to the Persistence layer to enforce Row-Level Security (RLS).
3. **Fail-Closed Architecture:** If a security context cannot be established at the transport edge, the Virtual Thread
   is terminated immediately — before any business logic executes. This prevents wasted CPU cycles on unauthorized
   work and is the Kernel's first line of defense.
4. **Framework Ready:** `PrincipalContext` is an interface, allowing seamless integration with external frameworks via
   adapters (e.g., Spring Security) without leaking framework types into the SPI.

---

## Responsibilities

**What Security SPI DOES:**

1. Define the `PrincipalContext` interface and `Role` enums.
2. Provide the `KernelProviders.PRINCIPAL_CONTEXT` and `KernelProviders.STORAGE_CONTEXT` ScopedValue slots.
3. Expose `@RequiresRole` annotations for declarative RBAC.

**What Security Core DOES:**

1. Extract and verify identity tokens from the incoming transport stream.
2. Bind `PrincipalContext` and `StorageContext` via `ScopedValue.where(...)` for the duration of the request.
3. Coordinate with the Persistence subsystem to enforce Row-Level Security (RLS).

---

## Error Codes (Black Box Telemetry)

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Meaning                    | Action                                              | Black-Box Payload                                    |
|:--------------|:---------------------------|:----------------------------------------------------|:-----------------------------------------------------|
| `EX-SEC-2001` | PrincipalContext Missing   | Silent drop at security boundary                    | *(no rawArgs)*                                       |
| `EX-SEC-2002` | Token Invalid/Expired      | Immediate authentication failure at transport edge  | `[0] String tokenType, [1] String failureReason`     |
| `EX-SEC-2003` | Insufficient Privileges    | Request rejected before reaching business logic     | `[0] String requiredRole`                            |
| `EX-SEC-2004` | StorageContext Missing     | Prevent DB access to avoid RLS leakage              | *(no rawArgs)*                                       |

**RLS integrity note for `EX-SEC-2004`:** The `StorageContext` ScopedValue carries the tenant identifier injected
into the DB connection state. If it is absent when the Persistence layer is reached, the Kernel must abort the
query immediately — a missing `StorageContext` is equivalent to a missing `WHERE tenant_id = ?`, which would
expose cross-tenant data.

---

## Code Examples

### 1. The Flexible Identity Interface (SPI)

Designed for both standalone Exeris usage and Spring Security integration.

```java
package eu.exeris.kernel.spi.security;

public interface PrincipalContext {
    UUID principalId();
    Optional<UUID> tenantId();
    Set<Role> roles();

    default boolean hasRole(Role role) {
        return roles().contains(role);
    }
}
```

### 2. Context Binding with Scope Inheritance (Core)

The `ScopedValue` bound here is automatically inherited by every child task forked within a
`StructuredTaskScope`. There is no need to pass `PrincipalContext` as a method parameter — it flows
invisibly into all subtasks for the lifetime of the scope.

```java
package eu.exeris.kernel.core.security;

public class SecurityInterceptor {

    public void runInContext(PrincipalContext principal, StorageContext storage, Runnable task) {
        ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal)
                   .where(KernelProviders.STORAGE_CONTEXT, storage)
                   .run(task);
    }
}
```

### 3. Fail-Closed Enforcement at Transport Edge

```java
package eu.exeris.kernel.core.security;

public class TokenValidator {

    public PrincipalContext validateOrDrop(String rawToken) {
        return tokenExtractor.extract(rawToken)
                .orElseThrow(() -> new PrincipalContextMissingException(
                        KernelErrorCodes.EX_SEC_2001));
    }
}
```

> If `validateOrDrop` throws, the dispatching Virtual Thread is terminated before any subsystem below L1 is
> ever reached. No business logic, no DB connection, no CPU waste.

---

## Testing Strategy

### Unit Tests

- `PrincipalContext` implementations (record-based vs adapter-based).
- RBAC logic: role matching, hierarchy, and `@RequiresRole` annotation processor.
- `EX-SEC-2004` is thrown when `STORAGE_CONTEXT` slot is empty at Persistence handover.

### Integration Tests

- Token extraction from different transport drivers (Community TCP vs Enterprise QUIC).
- `ScopedValue` inheritance during parallel processing: verify `PrincipalContext` is accessible
  in all subtasks forked within a `StructuredTaskScope` without explicit parameter passing.
- RLS enforcement: DB queries are physically restricted to the bound tenant — verified by attempting
  cross-tenant access and asserting row count is zero.
- Fail-Closed: invalid token at transport edge results in `EX-SEC-2001` and zero downstream calls.

---

## Summary

The Security subsystem is the impenetrable "Citadel" of the Exeris Kernel. By replacing `ThreadLocal` with JEP 506
`ScopedValues`, it eliminates both the GC churn of thread-local cleanup and the risk of context leakage between
Virtual Threads. The Fail-Closed architecture guarantees that unauthorized requests are terminated at the transport
edge — before they consume a single CPU cycle of business logic — and the dual `ScopedValue` binding
(`PrincipalContext` + `StorageContext`) ensures that Row-Level Security is enforced automatically at the database
tier, regardless of whether the developer remembered to filter manually.
