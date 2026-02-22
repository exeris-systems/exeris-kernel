# Kernel Subsystem: Security (L1 Citadel)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.security.*` (PrincipalContext, Role Enums, Annotations)
- Core: `eu.exeris.kernel.core.security.*` (Token Extractors, ScopedValue Orchestration)
  **Layer:** L1 (Data & Integrity)  
  **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Security subsystem** implements mandatory, invisible security enforcement using **JEP 506 ScopedValues** for
Virtual Thread-safe context propagation. It abandons legacy `ThreadLocal` patterns entirely to ensure zero-leak,
hyper-density concurrency. It provides:

- **Virtual Thread-Safe Context:** Extreme concurrency support without `ThreadLocal` memory overhead or thread-pinning
  risks.
- **Protocol Agnostic Auth:** Extracts identity from transport-level tokens (JWT/Opaque) regardless of the protocol (
  TCP, HTTP/2, QUIC).
- **Immutable Identity:** Uses the `PrincipalContext` SPI for a flexible, type-safe identity representation.
- **Tenant Isolation (RLS):** Physical separation at the storage level via automatic connection state injection.

### Core Philosophy: "The Invisible Wall & Clean Domain"

1. **Clean Domain (No Context Pollution):** Domain Entities do NOT contain security metadata. Context is propagated
   immutably via `ScopedValues`.
2. **Invisible Enforcement:** Developers do not manually filter by tenant; the Kernel injects the context directly into
   the Persistence layer (RLS).
3. **Fail-Closed:** If a security context cannot be established, the Kernel drops the request immediately at the
   transport edge.
4. **Framework Ready:** The `PrincipalContext` is an interface, allowing seamless integration with external frameworks
   like Spring Security via adapters.

---

## Responsibilities

**What Security SPI DOES:**

1. Define the `PrincipalContext` interface and `Role` enums.
2. Provide the `KernelProviders.PRINCIPAL_CONTEXT` ScopedValue placeholder.
3. Expose `@RequiresRole` annotations for declarative security.

**What Security Core DOES:**

1. Extract and verify identity tokens from the incoming transport stream.
2. Bind the `PrincipalContext` via `ScopedValue.where(...)` for the duration of the request.
3. Coordinate with the Persistence subsystem to enforce Row-Level Security (RLS).

---

## Error Codes (Black Box Telemetry)

| Code          | Meaning                        | Action                                           |
|:--------------|:-------------------------------|:-------------------------------------------------|
| `EX-SEC-2001` | Principal Context Missing      | Request dropped silently (Security boundary).    |
| `EX-SEC-2002` | Invalid/Expired Security Token | Transport layer returns an authentication error. |
| `EX-SEC-2003` | Insufficient Privileges (RBAC) | Request rejected before reaching business logic. |

---

## Code Examples

### 1. The Flexible Identity Interface (SPI)

Designed for both standalone Exeris usage and Spring Security integration.

```java
package eu.exeris.kernel.spi.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PrincipalContext {
    UUID principalId();

    Optional<UUID> tenantId();

    Set<Role> roles();

    default boolean hasRole(Role role) {
        return roles().contains(role);
    }
}
```

### 2. Context Binding (Core)

```java
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.PrincipalContext;

public class SecurityInterceptor {

    public void runInContext(PrincipalContext context, Runnable task) {
        // Bind the context to the current Virtual Thread (and its children)
        ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, context).run(task);
    }
}
```

## Testing Strategy

### Unit Tests

PrincipalContext implementations (Record-based vs Adapter-based).

RBAC logic (role matching and hierarchy).

### Integration Tests

Token extraction from different transport drivers (Community vs Enterprise).

ScopedValue inheritance during complex parallel processing (StructuredTaskScope).

RLS enforcement verification (ensuring DB queries are physically restricted).

## Summary

The Security subsystem acts as the impenetrable "Citadel" of the Exeris Kernel. By utilizing JEP 506 ScopedValues and an
extensible SPI, it guarantees that every Virtual Thread operates strictly within its boundaries—be it in a standalone
high-performance environment or a complex enterprise Spring integration.