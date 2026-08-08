# Kernel Subsystem: Security (L1 Citadel)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.security.*` (PrincipalContext, StorageContext, SecurityProvider, AuthenticationResult, ImmutablePrincipal, ImmutableStorageContext, KernelIsolationClaims, credentials/KernelPasswordEncoder, credentials/PasswordEncoderConfig, **`@RequiresRole` + `RoleMatch` + `KernelRoles` + `RoleRegistry` + `PrincipalContext.roleMask()`** since 0.7.0)
  > **Note:** the SPI surface for compile-time RBAC ships in 0.7.0 — annotation + role-bit catalogue (Sprint 8a), `RoleRegistry` interface + `roleMask()` carrier (Sprint 8b-ii). The APT processor in `exeris-kernel-build-config` ships in Sprint 8b-i; the Core `RoleCheckEnforcer` runtime decision helper ships in Sprint 8b-ii.
  >
  > **Corrected 2026-08-05.** This note previously said that wiring the generated registry into a bootstrap loader and auto-binding the enforcer into the transport admission path "remain operator concerns until the auto-bind landing". Both halves of that were wrong by 0.8.0 and the sentence misled a later audit, so it is replaced rather than amended. Registry wiring **shipped** in Sprint 4 (SEC-080): `GeneratedRoleRegistryLoader.load()` is called in production from `CommunityHttpRequestProcessor`, and `SecurityInterceptor` binds a `MaskedPrincipal` carrying the precomputed `roleMask()`. Enforcer auto-binding is not pending either — kernel-edge `methodId` enforcement was **descoped**, for the reason recorded in §"`@RequiresRole` Processing" below, which is the authoritative statement.
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
3. Expose the `@RequiresRole` annotation type, the `RoleMatch` enum, the canonical `KernelRoles` system-role bit mapping (Sprint 8a), the read-only `RoleRegistry` runtime contract and the `PrincipalContext.roleMask()` carrier (Sprint 8b-ii) for declarative RBAC. The annotation is `@Retention(SOURCE)` — consumed at compile time only by the APT processor.

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
- **Which routes require what is declared by the application, not by the driver (since 0.11, ADR-061).** `CommunityHttpRequestDispatcher` no longer gates on a `/secure` path prefix with the literal scopes `security:read` / `security:write` compiled into it. It resolves the route through `HttpRoutePolicy` (bound at `HttpKernelProviders.HTTP_ROUTE_POLICY`) and hands the resulting `RouteRequirement` to `RouteAuthorizationEnforcer` in Core, so every transport shares one decision layer. With no policy bound the requirement is permit-all and the kernel behaves as it did before — declaring nothing changes nothing, which also means an application that never declares a policy has **no edge authorization**. The unmatched route is the application's call: `HttpRoutePolicy.unmatched()` is fail-closed, and a policy returning `null` is treated as a defect and denies outright rather than being read as unmatched.
- **The token grants what it claims, and nothing else (since 0.11).** `CommunityClaimsMapper` reads
  `scope` (OAuth 2.0 space-delimited, RFC 6749 §3.3), `scp` (the array form some identity providers
  emit, unioned with the former) and `roles`. Until 0.11 it handed every authenticated principal a
  hardcoded `security:read` and no roles regardless of the token — the deferral ADR-040 left open,
  although that ADR already specifies this mapper as "sub → principalId, roles/scopes claims →
  PrincipalContext". Two consequences: no route could tell one caller's permissions from another's,
  and `security:write` was a scope **nothing could grant**, so any policy requiring it denied
  everyone. There is deliberately **no fallback** — a token carrying no scope claim yields an empty
  scope set and a route requiring any scope denies it. Roles now reach `PrincipalContext.roles()`,
  so the `roleMask` behind `@RequiresRole` is no longer permanently `0L`.
- **The mapping is substitutable (since 0.11).** `ClaimsMapper` is documented as the only application-customisable point in the identity pipeline, but `CommunityOidcIdentityProvider` constructed the default inline, so there was no way to supply one — an application needing a different subject or scope shape had to reimplement `IdentityProvider` outright. `withClaimsMapper(ClaimsMapper)` returns a provider using the supplied mapping, mirroring `enforcingSharedScope()`: a new provider rather than a mutation, because the mapping takes part in a security decision on every request and must be fixed at construction, never swapped behind a live provider. The two withers compose in either order. Declaring nothing still gets `CommunityClaimsMapper`.
- **And it is substitutable from a booted kernel, not only from the API (since 0.11).** The wither
  above was reachable only by constructing the provider yourself, which the ServiceLoader boot path
  does not do — so until this landed, a deployment that registered a mapping had no way to make the
  kernel use it, and the bullet above was true of the API and false of a running process.
  `CommunityClaimsMapperResolver` discovers a `ClaimsMapper` through `ServiceLoader` on the context
  class loader, and `CommunitySecurityProvider` assembles through it. **Exactly one may be
  registered**: `ClaimsMapper` declares no `priority()`, so two would leave classpath order deciding
  how every principal in the deployment is identified, and that is refused at construction with both
  class names rather than resolved by accident. None registered keeps `CommunityClaimsMapper`, so an
  unconfigured deployment is unchanged.
- **Substituting the mapper cannot widen isolation.** A custom mapper produces a `PrincipalContext` only. Tenant routing stays with `IdentityStorageMapping`, which every provider passes through (ADR-012 §4a), so a mapper cannot reach a tenant the token does not entitle it to — the customisable surface is identity shape, the non-negotiable surface is isolation deny semantics. This is also the seam a host-runtime binding uses: kernel scopes and a framework's own authority objects are **two mappings of the same verified token**, derived independently from the same claims, not one derived from the other. Neither is the source of truth for the other, and a kernel-side decision never consults framework authorities.
- `KernelProviders.SECURITY_PROVIDER` is bound by `CommunitySecuritySubsystem` (since 0.11). Before that it was bound by nothing, so the interceptor was never constructed and the whole Citadel path was unreachable in a default boot.

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

> **Status (0.7.0):**
>
> - SPI annotation surface (`@RequiresRole`, `RoleMatch`, `KernelRoles`) — Sprint 8a.
> - APT processor (`eu.exeris.kernel.buildconfig.security.RequiresRoleProcessor`) emitting
>   `eu.exeris.kernel.security.generated.RoleCheckRegistry` — Sprint 8b-i.
> - SPI runtime carriers (`RoleRegistry` interface, `PrincipalContext.roleMask()` default) and
>   the Core `RoleCheckEnforcer` decision helper plus `AbstractRequiresRoleTck` —
>   Sprint 8b-ii.
> - Runtime wiring (Sprint 4 SEC-080): the Core `GeneratedRoleRegistryLoader` resolves the
>   generated `RoleCheckRegistry` reflectively (by FQN string, no compile edge — preserves the
>   processor's reactor-cycle avoidance) and binds its five static accessors to `MethodHandle`s
>   once at bootstrap, exposed as a `RoleRegistry`. When no `@RequiresRole` is compiled anywhere
>   the loader returns a **fail-closed empty registry** (`methodCount() == 0`, all masks `0L`),
>   never allow-all. A one-shot `eu.exeris.kernel.security.RoleRegistryLoaded` JFR event records
>   whether the generated class was found and its `methodCount`, so operators distinguish
>   "no annotations" from "load failed". `SecurityInterceptor` consumes the loaded registry: when
>   `methodCount() > 0` it resolves the authenticated principal's role names through
>   `registry.roleNameToMask(...)` and binds a Core-internal `MaskedPrincipal` carrying the
>   precomputed `roleMask()`; when the registry is empty the original principal is bound unchanged
>   (no allocation, mask stays `0L`). `RoleCheckEnforcer` is unchanged — Sprint 4 only made its
>   inputs live (loadable registry + masked principal in scope).
>
> **Loader mechanism note:** the brief originally anticipated a `LazyConstant`-backed load. The
> loader is eager-at-bootstrap instead (constructed once on the `CommunityHttpRequestProcessor`
> construction path, a platform thread) — there is no per-request lazy access to amortise, and
> eager binding keeps the JFR bootstrap signal deterministic.
>
> **Kernel-edge methodId enforcement is descoped from the kernel (Sprint 4 finding).** The
> Community HTTP dispatcher remains scope/path-based; it does **not** map a request URL to a
> compile-time `methodId`. Calling `RoleCheckEnforcer.check(methodId, ...)` at the edge for a
> path-routed handler requires a generated URL→methodId routing table, which is a **codegen
> concern owned by `exeris-tooling` (cross-repo)**, not the kernel dispatcher. Sprint 4 delivers
> the loader, the `roleMask` population seam, the live `RoleCheckEnforcer` inputs, and TCK
> coverage (`AbstractGeneratedRoleRegistryLoaderTck`, `AbstractRoleMaskPopulationTck`); it
> deliberately does not add URL→methodId routing to `CommunityHttpRequestDispatcher`.
>
> Dynamic role decisions continue to use `CitadelGuard.requireRole(...)` — both paths emit
> `EX-SEC-2003` so operators see uniform telemetry.

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
   registry is resolved once at bootstrap by `eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader`
   (reflective `Class.forName` by FQN, then `MethodHandle` binding of the five static accessors). The
   per-request accessors invoke the bound handles via `invokeExact` — no `Method.invoke`, allocation-free.
   When the class is absent the loader returns a fail-closed empty registry (Sprint 4 SEC-080).

```text
Hot-path check (RoleMatch.ANY): (principal.roleMask() & registry.requiredAny(methodId)) != 0L
Hot-path check (RoleMatch.ALL): (principal.roleMask() & registry.requiredAll(methodId)) == registry.requiredAll(methodId)
```

If this check fails, `EX-SEC-2003` is thrown before any business logic executes.

### Runtime decision (since 0.7.0, Sprint 8b-ii)

`eu.exeris.kernel.core.security.RoleCheckEnforcer` is the canonical Core helper that
performs the per-request RBAC decision. Both call sites are allocation-free on the
accept path:

```java
// 1. Predicate form — useful for guard expressions
boolean ok = RoleCheckEnforcer.isAllowed(methodId, principal, registry);

// 2. Throwing form — used at admission boundaries (HTTP, command handlers, ...)
RoleCheckEnforcer.check(methodId, principal, registry);
// raises InsufficientPrivilegesException (EX-SEC-2003) on deny
```

`registry` is any `RoleRegistry` implementation — typically the generated
`RoleCheckRegistry` adapted into the SPI interface, or any operator-supplied source.
`principal.roleMask()` is the precomputed long that the authentication layer wires
by translating the principal's claim role names through `registry.roleNameToBit(...)`.
The TCK contract is pinned by `AbstractRequiresRoleTck`; the Community binding is
`CommunityRequiresRoleTckTest` in `exeris-kernel-community`.

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

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.security.*`) is classified **preview** in the
[SPI Stability Matrix](../stability-matrix.md): JWKS rotation in v0.9 Sprint 4 changes the contract,
and the `IdentityProvider` SPI arrives in v0.10. See the matrix for the semver policy and TCK
coverage status.
