# Kernel Subsystem: Security (L1 Citadel)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.security.*` (PrincipalContext, StorageContext, SecurityProvider, AuthenticationResult, ImmutablePrincipal, ImmutableStorageContext, KernelIsolationClaims, credentials/KernelPasswordEncoder, credentials/PasswordEncoderConfig, **`@RequiresRole` + `RoleMatch` + `KernelRoles`** since 0.7.0)
  > **Note:** the `@RequiresRole` SPI annotation surface ships in 0.7.0 (Sprint 8a). The APT processor that generates `RoleCheckRegistry` and the runtime admission integration ship in Sprint 8b.
- Community: `eu.exeris.kernel.community.security.*` (`CommunitySecurityProvider`, `Argon2idPasswordEncoder`, `CommunityJwksValidator`)
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

1. Define the `PrincipalContext` interface. Roles are open-form string constants, not a closed enum.
2. Provide the `KernelProviders.PRINCIPAL_CONTEXT` and `KernelProviders.STORAGE_CONTEXT` ScopedValue slots.
3. Expose the `@RequiresRole` annotation type, the `RoleMatch` enum, and the canonical `KernelRoles` system-role bit mapping for declarative RBAC (since 0.7.0). The annotation is `@Retention(SOURCE)` — consumed at compile time only by the APT processor (planned for Sprint 8b).

**What Security Core DOES:**

1. Orchestrate identity authentication at the transport boundary through `SecurityInterceptor` + `SecurityProvider` SPI (provider implementations perform token/JWKS verification).
2. Bind `PrincipalContext` and `StorageContext` via `ScopedValue.where(...)` for the duration of the request.
3. Coordinate with the Persistence subsystem to enforce Row-Level Security (RLS).
4. `CitadelGuard` — sentinel-pool RBAC enforcement gate with `preAllocate(String role)` / `seal()` / `requireRole(String role)`. Sealed at bootstrap READY transition.
5. `StorageContextBridge` — derives a SHARED `StorageContext` from a `PrincipalContext` (SHARED-only derivation contract per ADR-010 §4a; full SEPARATED_SCHEMA/DEDICATED derivation comes from `SecurityProvider.authenticate()`). Fail-closed: when the `PrincipalContext` ScopedValue is unbound at the call site, `StorageContextBridge.derive()` raises `PrincipalContextMissingException` (`EX-SEC-2001`) and the request is rejected at the security boundary before any persistence interaction.

> **TCK status:**
>
> - `CitadelGuard` is now covered by the shared `AbstractCitadelGuardTck` contract plus Community/Core bindings.
> - `StorageContextBridge.derive()` is now covered by a standalone `AbstractStorageContextBridgeTck`, including SHARED derivation and fail-closed handling for missing principal context.

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
    Set<String> roles();
    Set<String> scopes();

    default boolean hasRole(String role) {
        return roles().contains(role);
    }
}
```

> **Note:** Roles are open-form string constants, not a closed enum. `Set<String> scopes()` returns OAuth2 scopes; multiple zero-allocation `hasAnyScope()` overloads are provided. Scopes are distinct from roles — scopes are used for HTTP admission control and Bearer token permission checks.

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

    // intercept(LoanedBuffer rawToken, Runnable operation): boolean
    // runAsSystem(PrincipalContext system, Runnable operation): void
    // bindPreAuthenticated(PrincipalContext principal, Runnable operation): void

    public boolean intercept(LoanedBuffer rawToken, Runnable operation) {
        StorageContext storage = StorageContextBridge.derive(authenticate(rawToken));

        ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal)
                   .where(KernelProviders.STORAGE_CONTEXT, storage)
                   .run(operation);
        return true;
    }
}
```

> The interceptor takes a raw token buffer (not a pre-authenticated `PrincipalContext`) for the normal authentication path. For SEPARATED_SCHEMA / DEDICATED strategies, `SecurityProvider.authenticate()` produces the `StorageContext` directly; `StorageContextBridge.derive()` applies only to the SHARED fallback path.

### 3. Fail-Closed Enforcement at Transport Edge

> **Note:** `TokenValidator` does not exist as a class. Fail-closed token rejection is handled inside `SecurityInterceptor.intercept()`, which catches `SecurityAuthenticationException`, emits `SecurityContextMissingEvent` (JFR), and returns `false` without propagating the exception.

---

## Testing Strategy

### Unit Tests

- `PrincipalContext` implementations (record-based vs adapter-based).
- RBAC logic: role matching, hierarchy, and `@RequiresRole` annotation processor.
- `EX-SEC-2004` is thrown when `STORAGE_CONTEXT` slot is empty at Persistence handover.

### Integration Tests

- Token extraction validation in the Community TCP transport driver.
- `ScopedValue` inheritance during parallel processing: verify `PrincipalContext` is accessible
  in all subtasks forked within a `StructuredTaskScope` without explicit parameter passing.
- RLS enforcement: DB queries are physically restricted to the bound tenant — verified by attempting
  cross-tenant access and asserting row count is zero.
- Fail-Closed: invalid token at transport edge results in `EX-SEC-2002` and zero downstream calls.
- Community HTTP admission semantics (implemented): missing/invalid token maps to HTTP `401`; authenticated principal without required scope maps to HTTP `403`; steady-state scope checks remain membership tests over immutable in-memory scope sets.

---

## Token Lifecycle & Refresh Strategy

JWT tokens carry an expiry (`exp` claim). Long-lived Sagas (L4) or parked Virtual Threads may exceed the
token lifetime. The following contract governs token lifecycle in the Kernel:

| Scenario                                          | Kernel Behaviour                                                                                    |
|:--------------------------------------------------|:----------------------------------------------------------------------------------------------------|
| Token expires while VT is **parked** (saga wait)  | The `PrincipalContext` ScopedValue retains the last-validated principal until the Saga step completes. Re-validation is triggered only at the **next transport boundary** (next incoming stream), not during park/wake cycles. |
| Token expires during **active execution**         | The security layer does NOT interrupt in-flight requests. The token was valid at admission — the Kernel honours admission-time decisions. |
| Token is **revoked** (not merely expired)         | Revocation is enforced only at the next request admission boundary. There is no in-flight revocation mechanism — this is a deliberate trade-off between latency and strict revocation semantics. Operators requiring strict revocation must use short-lived tokens (≤ 60 s) combined with PAQS watermark-driven shedding to limit the revocation window. |
| Vault dynamic secret **rotation** during boot     | Config's `@Dynamic`-annotated secret fields participate in hot-reload. When Vault rotates a secret, the configuration hot-reload watcher (using `inotify`/`WatchService`) invalidates the cached `VarHandle` value. New tokens signed with the rotated signing key are validated against the updated JWKS — no restart required. |
| Vault is **down during boot**                     | `FAIL_FAST` mode (default): the Config subsystem throws `EX-CFG-1001` and the Boot DAG halts. `DEGRADE` mode: last-known configuration is used; this mode is reserved for local development and MUST NOT be deployed to production. The `config.vault.timeoutMs` key controls the connection timeout before fail-fast fires. |

> **Saga Parking Security Contract:** A Saga's `PrincipalContext` is captured at the point the Saga is
> **admitted** through the PAQS gate. The `ScopedValue` is re-bound at `state.wake(event)` using the
> **same admitted principal** — not re-extracted from an incoming event. This means the Saga's security
> context is immutable for its entire lifetime. If a revocation must apply to a parked Saga, the operator
> must use a saga cancellation API (planned for the `FlowEngine` SPI) which will force compensation and drop the Saga.

---

## `@RequiresRole` Processing — No Reflection on Hot Path

> **Status (0.7.0):** SPI annotation surface (`@RequiresRole`, `RoleMatch`, `KernelRoles`) is implemented
> as of Sprint 8a. The APT processor (`eu.exeris.kernel.buildconfig.security.RequiresRoleProcessor`)
> ships in Sprint 8b-i and emits `eu.exeris.kernel.security.generated.RoleCheckRegistry` at compile time.
> The `LazyConstant<RoleCheckRegistry>` loader, the `principal.roleMask()` carrier, the runtime admission
> hook, and the `AbstractRequiresRoleTck` ship in Sprint 8b-ii. Until then, `@RequiresRole` annotations
> emit a generated registry but no runtime check consumes it — use `CitadelGuard.requireRole(...)` for
> runtime checks.

### SPI surface (since 0.7.0)

```java
@RequiresRole({"ROLE_ADMIN"})                                  // any admin → permitted
@RequiresRole({"ROLE_ADMIN", "ROLE_OPERATOR"})                 // admin OR operator
@RequiresRole(value = {"ROLE_ADMIN", "ROLE_AUDITOR"}, match = RoleMatch.ALL)
                                                               // both required
```

Live in `eu.exeris.kernel.spi.security`. The annotation is `@Retention(SOURCE)` — consumed at compile
time only — so reflection at runtime can never observe it. `KernelRoles` reserves bits `[0, 8)` for
the kernel-owned system roles (`ROLE_SYSTEM`, `ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`); application
roles are assigned bits `[8, 64)` by the APT processor at build time. ADR-014 covers the binding
contract end-to-end.

### APT processor (since 0.7.0, Sprint 8b-i)

`@RequiresRole` annotations are **NOT processed via runtime reflection**. The mechanism is:

1. **Compile-time annotation processor (APT):** `RequiresRoleProcessor` in
   `exeris-kernel-build-config` scans every `@RequiresRole`-annotated METHOD/TYPE
   in a compilation unit and emits a single source file
   `eu.exeris.kernel.security.generated.RoleCheckRegistry`. Method IDs are
   assigned in alphabetical order of the fully-qualified declaring type +
   member name (deterministic across rebuilds). Role bits follow `KernelRoles`
   for the canonical four system roles (bits `[0, 8)`); application-defined
   role names get bits `[8, 64)` assigned alphabetically. The processor reads
   the annotation by FQN string and declares no project-scope dependency on
   `exeris-kernel-spi` — that would create a reactor cycle (SPI consumes
   build-config rulesets via plugin classpath).
2. **Runtime lookup:** At admission time, the transport layer performs a single `long` bitmask AND operation
   between the principal's role bitmask (extracted from the JWT at parse time) and the required role bitmask
   from the registry. This is O(1) and allocation-free.
3. **No `Class.getAnnotation()` on hot path:** Zero reflection calls occur after JVM startup. The APT-generated
   registry is loaded once via `LazyConstant.of(...)` at first access.

```
Hot-path check (RoleMatch.ANY): (principal.roleMask() & registry.requiredMask(methodId)) != 0L
Hot-path check (RoleMatch.ALL): (principal.roleMask() & registry.requiredMask(methodId)) == registry.requiredMask(methodId)
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

**Community tier:** TLS 1.3 server authentication (single-direction) is supported. mTLS (client certificate validation), SPIFFE/SVID identity, and TLS hot-reload without restart are not supported in the Community tier.

For mTLS requirements, operate an mTLS-terminating proxy (e.g., Envoy, Nginx) in front of the Exeris transport layer.

---

## JFR Events

| Event | When Emitted | Key Fields |
|:------|:-------------|:-----------|
| `SecurityContextMissingEvent` | Token rejection in `SecurityInterceptor.intercept()` (EX-SEC-2001) | `errorCode`, `component` |
| `InsufficientPrivilegesEvent` | `CitadelGuard` RBAC gate denial (EX-SEC-2003) | `errorCode`, `requiredRole` |

---

## Summary

The Security subsystem is the impenetrable "Citadel" of the Exeris Kernel. By replacing `ThreadLocal` with JEP 506
`ScopedValues`, it eliminates both the GC churn of thread-local cleanup and the risk of context leakage between
Virtual Threads. The Fail-Closed architecture guarantees that unauthorized requests are terminated at the transport
edge — before they consume a single CPU cycle of business logic — and the dual `ScopedValue` binding
(`PrincipalContext` + `StorageContext`) ensures that Row-Level Security is enforced automatically at the database
tier, regardless of whether the developer remembered to filter manually.

---

## Credential Hashing

`KernelPasswordEncoder` (SPI: `eu.exeris.kernel.spi.security.credentials`) defines the Argon2id hash/verify contract.

`PasswordEncoderConfig` (record) carries OWASP-minimum Argon2id tuning parameters.

Community implementation: `Argon2idPasswordEncoder` (Bouncy Castle Argon2id, PHC format, constant-time comparison, secret-zeroing on completion).

---

> **Note:** ADR-012 §13 specified a required documentation update to this file. The following ADR-012 normative additions have been applied: `KernelIsolationClaims` contract, isolation claim resolution pipeline (SHARED/SEPARATED_SCHEMA/DEDICATED/fail-closed), and `StorageContextBridge` SHARED-only scope.
