﻿# Kernel Subsystem: Security (L1 Citadel)

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

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Meaning                    | Action                                              | Glass-Box Payload                                    |
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

> **Citadel Contract:** `runInContext` does **not** accept a `StorageContext` parameter from the caller.
> The Citadel derives the database identity itself, cryptographically, from the verified `PrincipalContext`
> (e.g., JWT claims). Never trust upper layers with storage routing — doing so opens a
> Privilege Escalation / Cross-Tenant Data Leak vector where a developer in L3 could couple a token
> from tenant A with the database of tenant B.

```java
package eu.exeris.kernel.core.security;

public class SecurityInterceptor {

    public void runInContext(PrincipalContext principal, Runnable task) {
        StorageContext storage = CitadelClaims.deriveStorageContext(principal);

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

> If `validateOrDrop` throws, the dispatching Virtual Thread is terminated immediately via controlled
> exception bubbling. This ensures deterministic socket teardown and memory release in the core PAQS
> `finally` block, preventing Slowloris DoS attacks. The native `close()` on the socket descriptor is
> **always** reached — the exception never escapes the transport boundary unhandled.

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

## Token Lifecycle & Refresh Strategy

JWT tokens carry an expiry (`exp` claim). Long-lived Sagas (L4) or parked Virtual Threads may exceed the
token lifetime. The following contract governs token lifecycle in the Kernel:

| Scenario                                          | Kernel Behaviour                                                                                    |
|:--------------------------------------------------|:----------------------------------------------------------------------------------------------------|
| Token expires while VT is **parked** (saga wait)  | The `PrincipalContext` ScopedValue retains the last-validated principal until the Saga step completes. Re-validation is triggered only at the **next transport boundary** (next incoming stream), not during park/wake cycles. |
| Token expires during **active execution**         | The security layer does NOT interrupt in-flight requests. The token was valid at admission — the Kernel honours admission-time decisions. |
| Token is **revoked** (not merely expired)         | Revocation is enforced only at the next request admission boundary. There is no in-flight revocation mechanism — this is a deliberate trade-off between latency and strict revocation semantics. Operators requiring strict revocation must use short-lived tokens (≤ 60 s) combined with PAQS watermark-driven shedding to limit the revocation window. |
| Vault dynamic secret **rotation** during boot     | Config's `@Dynamic`-annotated secret fields participate in hot-reload. When Vault rotates a secret, the `ConfigWatcher` (using `inotify`/`WatchService`) invalidates the cached `VarHandle` value. New tokens signed with the rotated signing key are validated against the updated JWKS — no restart required. |
| Vault is **down during boot**                     | `FAIL_FAST` mode (default): the Config subsystem throws `EX-CFG-1001` and the Boot DAG halts. `DEGRADE` mode: last-known configuration is used; this mode is reserved for local development and MUST NOT be deployed to production. The `exeris.config.vault.timeout-ms` key controls the connection timeout before fail-fast fires. |

> **Saga Parking Security Contract:** A Saga's `PrincipalContext` is captured at the point the Saga is
> **admitted** through the PAQS gate. The `ScopedValue` is re-bound at `state.wake(event)` using the
> **same admitted principal** — not re-extracted from an incoming event. This means the Saga's security
> context is immutable for its entire lifetime. If a revocation must apply to a parked Saga, the operator
> must use the `SagaEngine.cancel(sagaId)` API which forces compensation and drops the Saga.

---

## `@RequiresRole` Processing — No Reflection on Hot Path

`@RequiresRole` annotations are **NOT processed via runtime reflection**. The mechanism is:

1. **Compile-time annotation processor (APT):** An annotation processor in `exeris-kernel-build-config`
   generates a static `RoleCheckRegistry` class at compile time. For each annotated method, a `long` bitmask
   encoding the required roles is emitted into the registry.
2. **Runtime lookup:** At admission time, the transport layer performs a single `int` bitmask AND operation
   between the principal's role bitmask (extracted from the JWT at parse time) and the required role bitmask
   from the registry. This is O(1) and allocation-free.
3. **No `Class.getAnnotation()` on hot path:** Zero reflection calls occur after JVM startup. The APT-generated
   registry is loaded once via `LazyConstant.of(...)` at first access.

```
Hot-path check: (principal.roleMask() & registry.requiredMask(methodId)) != 0
```

If this check fails, `EX-SEC-2003` is thrown before any business logic executes.

---

## Rate Limiting — Design Stance

The Kernel's PAQS scheduler provides **priority-based load shedding** — not **per-principal rate limiting**.
These are distinct mechanisms:

| Mechanism              | Owner          | Enforcement Point                  | Scope              |
|:-----------------------|:---------------|:-----------------------------------|:-------------------|
| PAQS Watermark Shed    | Transport (L2) | Before Virtual Thread spawn        | Global (all traffic) |
| Per-principal rate limit | **Not in Kernel** | Application tier or API Gateway | Per authenticated identity |

**Kernel design decision:** Per-principal rate limiting requires state shared across requests (counter per
principal per time window). Maintaining this state in the Kernel would require either a heap-allocated
`ConcurrentHashMap` (GC pressure) or an off-heap hash table with per-principal atomic counters (Kernel
complexity). Neither is acceptable at TRL-3.

**Recommended operator pattern:** Deploy an API Gateway (e.g., Envoy, Nginx) in front of the Exeris
transport layer and enforce per-principal rate limits there. For internal service-to-service communication
where rate limiting is required at the Kernel boundary, use PAQS `StreamPriority` mapping from JWT claims
to enforce admission tier — not a counter-based approach.

This decision will be revisited at TRL-5 if a zero-allocation per-principal counter design can be validated
under the Performance Contract.

---

## mTLS — Service-to-Service Authentication

Mutual TLS (`mTLS`) is **supported** in the Enterprise tier via the `NativeCipherContext` lifecycle
extension. Community tier supports TLS 1.3 server authentication only (single-direction).

| Feature                              | Community           | Enterprise                               |
|:-------------------------------------|:--------------------|:-----------------------------------------|
| TLS 1.3 (server cert validation)     | ✅ Full              | ✅ Full                                   |
| mTLS (client cert validation)        | ❌ Not supported     | ✅ `SSL_CTX_set_verify(SSL_VERIFY_PEER)`  |
| SPIFFE/SVID certificate identity     | ❌ Not supported     | 🚧 Planned (TRL-4)                        |
| TLS certificate hot-reload (no restart) | ❌ Not supported  | ✅ `SSL_CTX_use_certificate_file()` hot-swap on SIGUSR1 |

**Certificate rotation (Enterprise):** When a new TLS certificate is available, the operator sends `SIGUSR1`
to the Exeris process. The signal handler calls `SSL_CTX_use_certificate_file()` and
`SSL_CTX_use_PrivateKey_file()` on the existing `SSL_CTX*` (held in `Arena.global()` in `CoreOpenSslLoader`).
New connections use the updated certificate immediately. In-flight connections continue with the old certificate
until they close naturally — there is no forced connection teardown.

---

## Summary

The Security subsystem is the impenetrable "Citadel" of the Exeris Kernel. By replacing `ThreadLocal` with JEP 506
`ScopedValues`, it eliminates both the GC churn of thread-local cleanup and the risk of context leakage between
Virtual Threads. The Fail-Closed architecture guarantees that unauthorized requests are terminated at the transport
edge — before they consume a single CPU cycle of business logic — and the dual `ScopedValue` binding
(`PrincipalContext` + `StorageContext`) ensures that Row-Level Security is enforced automatically at the database
tier, regardless of whether the developer remembered to filter manually.
