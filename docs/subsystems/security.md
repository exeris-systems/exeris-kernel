---
title: "Kernel Subsystem: Security (L1 Citadel)"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Kernel Subsystem: Security (L1 Citadel)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.security.*` (PrincipalContext, StorageContext, SecurityProvider, AuthenticationResult, ImmutablePrincipal, ImmutableStorageContext, KernelIsolationClaims, credentials/KernelPasswordEncoder, credentials/PasswordEncoderConfig, **`@RequiresRole` + `RoleMatch` + `KernelRoles` + `RoleRegistry` + `PrincipalContext.roleMask()`** since 0.7.0; `identity/` sub-package — `IdentityProvider`, `IdentityProviderRegistry`, `TokenValidator`, `ClaimsMapper`, `IdentityStorageMapping`, `KeyRotationPolicy`, `VerifiedClaims` — since 0.10)
  > **Note:** the SPI surface for compile-time RBAC ships in 0.7.0 — annotation + role-bit catalogue (Sprint 8a), `RoleRegistry` interface + `roleMask()` carrier (Sprint 8b-ii). The APT processor in `exeris-kernel-build-config` ships in Sprint 8b-i; the Core `RoleCheckEnforcer` runtime decision helper ships in Sprint 8b-ii.
  >
  > Registry wiring shipped in Sprint 4 (SEC-080): `GeneratedRoleRegistryLoader.load()` is called in production from `CommunityHttpRequestProcessor`, and `SecurityInterceptor` binds a `MaskedPrincipal` carrying the precomputed `roleMask()`. Enforcer auto-binding is not pending — kernel-edge `methodId` enforcement is **descoped**, for the reason recorded in §"`@RequiresRole` Processing" below, which is the authoritative statement.
- Community: `eu.exeris.kernel.community.security.*` (`CommunitySecurityProvider`, `Argon2idPasswordEncoder`, `CommunityOidcIdentityProvider`, `CommunityOidcTokenValidator`, `CommunityClaimsMapper` + `CommunityClaimsMapperResolver`, `CommunityJwksHttpKeySetSource` + `CommunityRotatingKeySet` + `StaticJwksKeyResolver`, `JwsVerificationKey` + `PinnedJwsAlgorithm`, `CommunityEd25519JwsVerifier` + `Ed25519PublicKeys`)
- Core: `eu.exeris.kernel.core.security.*` (`SecurityInterceptor`, `StorageContextBridge`, `CitadelGuard`, `RouteAuthorizationEnforcer`, `RoleCheckEnforcer`, `GeneratedRoleRegistryLoader`, `MaskedPrincipal`)

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
3. **Fail-Closed Architecture:** If a security context cannot be established, the request is dropped before the
   handler runs and a `401` is returned. The connection's virtual thread is *not* terminated — one virtual thread
   serves a whole keep-alive connection, so `CommunityHttpRequestProcessor`'s loop continues with the next request
   on the same thread. What fails closed is the request, not the carrier. This still prevents work on unauthorized
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
4. `CitadelGuard` — sentinel-pool RBAC enforcement gate with `preAllocate(String role)` / `seal()` / `requireRole(String role)`. It is a standalone Core utility class: neither `CommunitySecuritySubsystem` nor any other bootstrap path in this repository constructs a `CitadelGuard` or calls `seal()` — an application that wants dynamic (non-`@RequiresRole`) RBAC decisions instantiates and seals its own instance during its own warm-up. The "sealed at bootstrap READY" pattern is the class's documented usage contract, not something the kernel does automatically today.
5. `StorageContextBridge` — derives a SHARED `StorageContext` from a `PrincipalContext` (SHARED-only derivation contract per ADR-012 §4a / ADR-040 §2.4; full SEPARATED_SCHEMA/DEDICATED derivation comes from `SecurityProvider.authenticate()`). Fail-closed: `derive(PrincipalContext)` raises `PrincipalContextMissingException` (`EX-SEC-2001`) when handed a `null` principal, and the convenience overload `deriveFromActivePrincipal()` raises the same exception when the `PrincipalContext` ScopedValue is unbound at the call site — either way the request is rejected at the security boundary before any persistence interaction.

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
into the DB connection state. A missing `StorageContext` is equivalent to a missing `WHERE tenant_id = ?`, so
`KernelProviders.storageContext()` raises `EX-SEC-2004` rather than returning a default.

> **The request-scoped path does not use that accessor, and this is the gap to know about.**
> `KernelProviders` also exposes `storageContextOrSystem()`, which returns `ImmutableStorageContext.GLOBAL`
> when the ScopedValue is unbound, raising nothing. `PersistenceEngine.openConnection()` — the zero-arg
> overload — resolves its context through that one by contract, and it is the overload
> `PersistenceSessionBox` uses for every non-`LONG_RUNNING` HTTP request. `TransactionOrchestrator`'s
> default constructor documents the same fallback.
>
> A `permitAll()` route never runs the interceptor at all (`CommunityHttpRequestDispatcher` returns early on
> `RouteRequirement.Kind.PERMIT_ALL`), so `STORAGE_CONTEXT` is never bound for it. If such a handler touches
> persistence it receives a `GLOBAL`-scoped connection — not an aborted query and not an `EX-SEC-2004`.
>
> **What the database does with that connection.** `GLOBAL` declares no tenant, so
> `RlsConnectionInterceptor` publishes `exeris.tenant_id` as `''`. Under the reference `FORCE`'d policy
> (`tenant_id = (SELECT public.exeris_required_setting('exeris.tenant_id'))`, the canonical example in
> `RlsConnectionInterceptor`'s javadoc and in `docs/subsystems/persistence.md`) an absent tenant key
> raises, so a read or a write that touches a scoped table fails with `EX-PERS-5003` (SQLSTATE `42501`)
> and a message naming the key: a conforming deployment fails closed at the database, and a handler on a
> public route that touches a scoped table gets an error, not an empty result. The plain form,
> `tenant_id = current_setting('exeris.tenant_id', true)`, is not conforming: it returns an empty result
> for a read, and its `WITH CHECK` admits a row whose `tenant_id` is `''`, which every later
> tenant-less request reads back. Rows are exposed only where the deployment departs from the reference
> policy — a policy with an arm that admits the empty key (a "system" arm), or one that compares the
> key without raising, or a scoped table that is not `FORCE`'d while the application connects as its
> owner, or an application that connects as a role with `SUPERUSER` or `BYPASSRLS` (refused at boot
> since 0.13 unless `persistence.rlsAllowBypassRole` is set).
>
> **What the kernel reports.** When a `permitAll()` route's request session was opened for a storage
> context that declares no tenant — an absent or blank isolation key, the case in which the tenant key is
> published as `''` — the dispatcher emits the JFR event `eu.exeris.kernel.security.UnscopedRequestSession`
> before the session is released, carrying `method`, `path` and `readOnly`. What counts is the context the
> connection was opened for, not whether the `STORAGE_CONTEXT` slot was bound: a handler that passes a
> tenant context to `openConnection(StorageContext)`, or binds one, is not reported; a handler that chooses
> the system context on a public route, by passing it or binding it, is — a deliberate system scope on a
> public route is what an operator should be able to see. Only `permitAll()` routes are reported: an
> authenticated route whose security provider resolved a context without a tenant is a tenant-less
> deployment's own answer, and stays silent. The event fires on every matching request, so a health check
> on a `permitAll()` route that reads the database emits it on every probe — that is the case an operator
> is meant to see, and a deployment that has accepted it disables the event in its JFR settings. The event
> carries the request path, with the same trade `eu.exeris.kernel.http.RouteExecution` states.
>
> **The residual.** A `LONG_RUNNING` `permitAll()` route has no request session, so each persistence call
> acquires through the engine directly and this event cannot see it. Nothing reports that case.

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

The `ScopedValue` bound here is visible to code running inside the binding scope on the thread that
established it. A thread started with `Thread.ofVirtual()` or `Thread.ofPlatform()` inherits nothing,
and a `StructuredTaskScope` fork (a preview API) inherits the bindings in effect when its scope opened.
The kernel's own forks on the default line go through `eu.exeris.kernel.core.concurrent.StructuredScope`
(ADR-066), whose subtasks see only the carrier passed to `open(ScopedValue.Carrier)`, and nothing after
`openWithoutBindings()`. Within a request, the handler reads `PrincipalContext` without a method
parameter; a handler that forks work through `StructuredScope` or a plain thread re-binds what that work
needs.

> **Citadel Contract:** `SecurityInterceptor.interceptPreAuthenticated(PrincipalContext, Runnable)` does
> **not** accept a `StorageContext` parameter from the caller.
> The Citadel derives the database identity itself, cryptographically, from the verified `PrincipalContext`
> (e.g., JWT claims). Never trust upper layers with storage routing — doing so opens a
> Privilege Escalation / Cross-Tenant Data Leak vector where a developer in L3 could couple a token
> from tenant A with the database of tenant B.

```java
package eu.exeris.kernel.core.security;

public final class SecurityInterceptor {

    // intercept(LoanedBuffer rawToken, Runnable requestHandler): boolean
    // interceptPreAuthenticated(PrincipalContext principal, Runnable requestHandler): boolean
    // runAsSystem(PrincipalContext system, Runnable task): void

    public boolean intercept(LoanedBuffer rawToken, Runnable requestHandler) {
        AuthenticationResult result = provider.authenticate(rawToken); // throws on failure

        ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, result.principal())
                   .where(KernelProviders.STORAGE_CONTEXT, result.storage())
                   .run(requestHandler);
        return true;
    }
}
```

> `intercept()` — the normal, raw-token path — never calls `StorageContextBridge.derive()`: `SecurityProvider.authenticate()` returns an `AuthenticationResult` carrying both the `PrincipalContext` and the `StorageContext` (SHARED, SEPARATED_SCHEMA or DEDICATED, decided by the provider from verified token claims), and the interceptor binds `result.storage()` directly. `StorageContextBridge.derive()` is used only by `interceptPreAuthenticated()`, which accepts a caller-supplied `PrincipalContext` with no paired `StorageContext` and derives one (SHARED-only — see the Isolation Bridge note below).

### 3. Fail-Closed Enforcement at Transport Edge

`eu.exeris.kernel.spi.security.identity.TokenValidator` (since 0.10) is the SPI interface for the cryptographic-verification half of an `IdentityProvider` (`kid → key → algorithm → signature → issuer → audience → time`), implemented by Community's `CommunityOidcTokenValidator`, which then types the isolation claims. Fail-closed token rejection at the transport edge is handled inside `SecurityInterceptor.intercept()`: a `SecurityAuthenticationException` (raised by `TokenValidator` and by the `SecurityProvider`/`IdentityProvider` chain above it) emits `SecurityContextMissingEvent` (JFR) with reason `TOKEN_INVALID`, any other provider failure with `PROVIDER_ERROR`, and `intercept()` returns `false` without propagating the exception; an `Error` is recorded with `PROVIDER_ERROR`, then rethrown.

### Signing algorithms (Community OIDC)

The Community validator verifies two JWS algorithms: RS256 over an RSA key, and EdDSA over an Ed25519 key. The
key decides the algorithm, never the token:

- **The pin is fixed when the key is installed.** A JWKS entry is pinned to the `alg` it declares when it declares
  one, and otherwise to what its `kty` and `crv` verify: an RSA key to RS256, an `OKP` key on `Ed25519` to EdDSA. A
  header spelling the algorithm `Ed25519` is accepted as EdDSA.
- **The header must agree before the signature is checked.** A token whose header names any algorithm other than its
  key's is denied with `EX-SEC-2002` (reason `signature-invalid`) without reaching a verifier, so a token cannot move
  a key onto an algorithm it was not published for: not RS512 or PS256 under an RSA key, not RS256 or HS256 under an
  Ed25519 key, not EdDSA under an RSA key. A `crit` header parameter is denied on both algorithms.
- **Some JWKS entries are not installed**, and the rest of the document still loads: an entry without a `kid`, with
  a `use` other than `sig`, of a key type or curve this validator does not verify (EC, `oct`, `X25519`, `X448`,
  `Ed448`), or declaring an `alg` that its key type does not verify (for example an RSA key declaring `RSA-OAEP`). A
  `kid` that two installable entries publish is installed for neither; a skipped entry does not count. A supported key whose material is invalid, an
  Ed25519 `x` that is not 32 bytes for one, fails the load as `jwks-key-invalid`; a document with no installed key
  fails it as `jwks-no-usable-keys`.
- **A map of `RSAPublicKey` values is RS256.** The constructors and the `initialKeys` of `overJwksEndpoint` take RSA
  keys and pin each to RS256; an Ed25519 key reaches the validator through a JWKS document.
- **Ed25519 is verified by the JDK's default provider**, as Core's licence verifier does. No optional Nimbus crypto
  dependency is added.
- **There is no allow-list of algorithms.** The set is what the key types above verify, not a configuration; ES\*, PS\*
  and Ed448 are out of scope.

The contract is ADR-040 Amendment A1; `AbstractSecurityProviderTck.PerKeyAlgorithmPinContract` pins it.

### Token verification keys from configuration

The `ServiceLoader` instance of `CommunitySecurityProvider` holds no verification key, so on its own it denies
every token. Since 0.13.1 the Community security subsystem arms it from three keys ([config.md](config.md)):

| Key | Meaning |
|:--|:--|
| `security.jwks.uri` | The JWKS document tokens are verified against: an absolute `http` or `https` URI with a host. |
| `security.jwt.issuer` | The `iss` claim a token must present. |
| `security.jwt.audience` | The `aud` claim a token must present. |

- **All three or none.** With none set the subsystem binds the key-less provider, as before. With all three it binds a
  provider over that endpoint. Any other combination fails the boot in `initialize()` with `EX-BOOT-0002`, naming the
  first key that is missing, as does a `security.jwks.uri` that is not an absolute `http` or `https` URI with a host.
- **The key set is built at `start()`, not when the provider is discovered.** The JWKS client engine needs the
  memory allocator and, for an `https` endpoint, a crypto provider, and neither is bound while subsystems
  initialise. Until `start()` the bound provider denies every token. A key set that cannot be built — an `https`
  endpoint with no crypto provider, or under `exeris.transport.tls=false` (`EX-NET-4004`, see *Outbound* below) —
  fails `start()` with `EX-BOOT-0002`, the refusal kept as its cause.
- **The key set is fetched on the first token and rotated** under `KeyRotationPolicy.defaults()`, on the kernel's
  time source. `stop()` releases the client engine, and the provider then denies every token.
- **Only the Community provider reads the keys.** A `SecurityProvider` that outranks it is bound unchanged, and the
  keys are not read.

An embedding host that builds the provider itself uses `CommunitySecurityProvider.overJwksEndpoint(URI,
KeyRotationPolicy, Clock, String, String)`, which returns an `AutoCloseable` provider over the same rotating key set.
It builds the engine when called, so it needs the same bindings as `start()`.

---

## Testing Strategy

### Unit Tests

- `PrincipalContext` implementations (record-based vs adapter-based).
- RBAC logic: `RoleMatch.ANY`/`RoleMatch.ALL` bitmask matching and the `@RequiresRole` annotation processor. Roles are flat — there is no role-hierarchy concept anywhere in the SPI or Core (e.g. holding `ROLE_ADMIN` does not imply `ROLE_OPERATOR`); each `@RequiresRole` declaration lists exactly the roles it accepts.
- `EX-SEC-2004` is thrown when `STORAGE_CONTEXT` slot is empty at Persistence handover.

### Integration Tests

- Token extraction and validation in the Community HTTP request processor (`CommunityHttpRequestProcessor` / `CommunityHttpRequestDispatcher`, layered over the native TCP carrier) — `SecurityInterceptor` is wired at the HTTP layer, not inside the raw TCP transport driver itself.
- `ScopedValue` propagation during parallel processing: verify `PrincipalContext` is accessible in a
  `StructuredScope` subtask only when the opener passes the binding, accessible in a `StructuredTaskScope`
  fork, and unbound in a plainly started thread.
- RLS enforcement: DB queries are physically restricted to the bound tenant — verified by attempting
  cross-tenant access and asserting row count is zero.
- Fail-Closed: invalid token at transport edge results in `EX-SEC-2002` and zero downstream calls.
- Community HTTP admission semantics (implemented): missing/invalid token maps to HTTP `401`; authenticated principal without required scope maps to HTTP `403`; steady-state scope checks remain membership tests over immutable in-memory scope sets.
- **Which routes require what is declared by the application, not by the driver (since 0.11, ADR-061).** `CommunityHttpRequestDispatcher` no longer gates on a `/secure` path prefix with the literal scopes `security:read` / `security:write` compiled into it. It resolves the route through `HttpRoutePolicy` (bound at `HttpKernelProviders.HTTP_ROUTE_POLICY`) and hands the resulting `RouteRequirement` to `RouteAuthorizationEnforcer` in Core, so every transport shares one decision layer. With no policy bound the requirement is permit-all, which means an application that never declares a policy has **no edge authorization**. Read that as written: it is not "unchanged behaviour". Up to 0.10 the driver enforced `/secure` by convention, so an application that declares nothing does not keep what it had — it serves those routes to anonymous callers. Migrating means declaring the routes the prefix used to cover. A route that wants an identity established without demanding a scope declares `authenticated()`; `permitAll()` runs no interceptor, so its handler sees no `PrincipalContext` even when the caller presented a valid token. The unmatched route is the application's call: `HttpRoutePolicy.unmatched()` is fail-closed, and a policy returning `null` is treated as a defect and denies outright rather than being read as unmatched. **Since 0.12 (ADR-061 amendment A2) a policy may also abstain** — `RouteRequirement.abstain()` states that this policy does not describe the route, so `HttpRoutePolicy.firstDeclared` can fold an ordered list of policies (a generated one and a hand-written one, say) and take the first that answers. Abstention is not permit-all: an abstention that reaches `RouteAuthorizationEnforcer` unfolded is a defect and denies in the same shape `null` does, and the fold's all-abstain fallback is a required argument so the unmatched stance stays the application's single call. See [`http.md`](http.md) for the fold's ordering rule.
- **The token grants what it claims, and nothing else (since 0.11).** `CommunityClaimsMapper` reads
  `scope` (OAuth 2.0 space-delimited, RFC 6749 §3.3), `scp` (the array form some identity providers
  emit, unioned with the former) and `roles`, as ADR-040 specifies the mapper: "sub → principalId,
  roles/scopes claims → PrincipalContext". Two consequences: a route can tell one caller's permissions
  from another's, and `security:write` is a scope a token can grant. There is deliberately **no
  fallback** — a token carrying no scope claim yields an empty scope set and a route requiring any
  scope denies it. Roles reach `PrincipalContext.roles()`, so the `roleMask` behind `@RequiresRole`
  is populated from the token's `roles` claim whenever a generated registry is loaded.
- **The mapping is substitutable (since 0.11).** `ClaimsMapper` is the only application-customisable point in the identity pipeline: an application needing a different subject or scope shape supplies one instead of reimplementing `IdentityProvider`. `withClaimsMapper(ClaimsMapper)` returns a provider using the supplied mapping, mirroring `enforcingSharedScope()`: a new provider rather than a mutation, because the mapping takes part in a security decision on every request and must be fixed at construction, never swapped behind a live provider. The two withers compose in either order. Declaring nothing still gets `CommunityClaimsMapper`.
- **And it is substitutable from a booted kernel, not only from the API (since 0.11).** The wither
  above is reachable only by constructing the provider yourself, which the ServiceLoader boot path
  does not do, so a deployment that registers a mapping relies on the resolver below.
  `CommunityClaimsMapperResolver` discovers a `ClaimsMapper` through `ServiceLoader` on the context
  class loader, and `CommunitySecurityProvider` assembles through it. **Exactly one may be
  registered**: `ClaimsMapper` declares no `priority()`, so two would leave classpath order deciding
  how every principal in the deployment is identified, and that is refused at construction with both
  class names rather than resolved by accident. None registered keeps `CommunityClaimsMapper`, so an
  unconfigured deployment is unchanged.
- **Substituting the mapper cannot widen isolation.** A custom mapper produces a `PrincipalContext` only. Tenant routing stays with `IdentityStorageMapping`, which every provider passes through (ADR-012 §4a), so a mapper cannot reach a tenant the token does not entitle it to — the customisable surface is identity shape, the non-negotiable surface is isolation deny semantics. This is also the seam a host-runtime binding uses: kernel scopes and a framework's own authority objects are **two mappings of the same verified token**, derived independently from the same claims, not one derived from the other. Neither is the source of truth for the other, and a kernel-side decision never consults framework authorities.
- `KernelProviders.SECURITY_PROVIDER` is bound by `CommunitySecuritySubsystem` (since 0.11); the interceptor is constructed from it, so the Citadel path is reachable in a default boot.

---

## Token Lifecycle & Refresh Strategy

JWT tokens carry an expiry (`exp` claim). Long-lived Sagas (L4) or parked Virtual Threads may exceed the
token lifetime. The following contract governs token lifecycle in the Kernel:

| Scenario                                          | Kernel Behaviour                                                                                    |
|:--------------------------------------------------|:----------------------------------------------------------------------------------------------------|
| Token expires while a Saga step is **parked**     | Re-validation happens only at the **next transport boundary** (the next incoming request that gets a fresh `SecurityInterceptor.intercept()` call), not during park/wake — see the correction below for why there is no `PrincipalContext` to hold onto in the meantime. |
| Token expires during **active execution**         | The security layer does NOT interrupt in-flight requests. The token was valid at admission — the Kernel honours admission-time decisions. |
| Token is **revoked** (not merely expired)         | The Community `TokenValidator` checks signature, issuer, audience and expiry against the JWKS — it has no revocation-list or introspection check, so a self-contained JWT that is still cryptographically valid and unexpired is accepted regardless of revocation. Revocation therefore only takes effect once the token itself expires or a rotated JWKS makes its signature invalid. Operators requiring tighter revocation windows must issue short-lived tokens. |

> **There is no Vault integration anywhere in the kernel** and no `config.vault.timeoutMs` property,
> so no secret-rotation or Vault boot-failure contract exists; `Config` is an L0 FOUNDATION
> subsystem, so a boot-time Config failure is always `FAIL_FAST` — `SubsystemOrchestrator`'s `DEGRADE`
> policy cannot apply to a FOUNDATION subsystem.
>
> **A parked Saga holds no captured identity.** A Saga (`RuntimeFlowInstance`) runs each schedule or wake on a **freshly created,
> bare virtual thread that inherits no `ScopedValue` binding at all** — not `PrincipalContext`, not
> `StorageContext`. `CoreFlowRuntime` captures only an `EventEngine`, an `IdempotencyGuard` and a
> `TimeSource` at schedule/wake time and holds them as fields for exactly this reason; there is no
> equivalent capture of a security identity anywhere in the flow engine, and `FlowScheduler.wake(...)`
> takes no principal parameter. A running or parked Saga step therefore has **no bound
> `PrincipalContext`** unless the step's own business logic explicitly re-establishes one (e.g. via
> `SecurityInterceptor.runAsSystem(...)`) — RLS enforcement and role checks that depend on the
> ScopedValue slots do not apply inside a Saga step by default. A saga cancellation API on the
> `FlowEngine` SPI is not implemented.

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
> Dynamic role decisions continue to use `CitadelGuard.requireRole(...)`. Both paths raise
> `InsufficientPrivilegesException` and so carry `EX-SEC-2003`, but the telemetry is not uniform:
> `SecurityJfrEvents.emitInsufficientPrivileges(...)` has exactly one call site in the tree,
> `CitadelGuard.requireRole()`. `RoleCheckEnforcer.check()` — the compile-time `@RequiresRole` path this
> section describes — throws directly and commits no JFR event, so an operator watching
> `InsufficientPrivilegesEvent` sees nothing for a `@RequiresRole` denial.

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
2. **Runtime lookup:** wherever a caller invokes `RoleCheckEnforcer` with a compile-time `methodId`, the
   check is a single `long` bitmask AND (or EQ) operation between the principal's precomputed
   `roleMask()` and the required role bitmask from the registry — O(1) and allocation-free. As the
   "Kernel-edge `methodId` enforcement is descoped" note above states, the Community HTTP dispatcher
   does **not** call this automatically at request admission; nothing currently maps a request URL to
   a `methodId`, so this hot-path check applies wherever an application (or a future codegen layer)
   calls `RoleCheckEnforcer` itself with a resolved `methodId`.
3. **No `Class.getAnnotation()` on hot path:** Zero reflection calls occur after JVM startup. The APT-generated
   registry is resolved once at bootstrap by `eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader`
   (reflective `Class.forName` by FQN, then `MethodHandle` binding of the five static accessors). The
   per-request accessors invoke the bound handles via `invokeExact` — no `Method.invoke`, allocation-free.
   When the class is absent the loader returns a fail-closed empty registry (Sprint 4 SEC-080).
4. **Native-image reachability metadata (ADR-103 obligation 5):** When annotated methods exist,
   `RequiresRoleProcessor` also emits reachability metadata at
   `META-INF/native-image/eu.exeris.kernel.security.generated/role-check-registry/reachability-metadata.json`
   (configurable via the `exeris.requiresrole.nativeImageMetadataDir` processor option, which must
   start with `META-INF/native-image/`). The metadata registers `RoleCheckRegistry` and the five
   static accessors reflectively bound by `GeneratedRoleRegistryLoader`; without it, a native image
   loads the fail-closed empty registry (every `@RequiresRole` denies).

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

**Community tier:** TLS server authentication (single-direction) is supported, with a TLS 1.3 floor by default and a TLS 1.2 floor when `crypto.tls.minVersion` is `TLSv1.2` ([config.md](config.md)). mTLS (client certificate validation), SPIFFE/SVID identity, and TLS hot-reload without restart are not supported in the Community tier.

For mTLS requirements, operate an mTLS-terminating proxy (e.g., Envoy, Nginx) in front of the Exeris transport layer.

**Outbound.** Since 0.12 the Community TLS client verifies the server it dials: the chain against
`crypto.tls.client.trustFile` or OpenSSL's default trust, and the subject alternative names against
the authority's host (ADR-074 Amendment A1; [transport.md](transport.md#client-tls)). It presents no
client certificate and consults no CRL or OCSP. A client is armed only where a crypto provider is
bound when its engine is built and `exeris.transport.tls` is not `false`; an engine built anywhere
else dials plaintext. Two clients are the exception, because what they fetch decides what the
kernel trusts: their endpoint's scheme decides, so an `http://` endpoint is plaintext even where a
crypto provider is bound, and an `https://` endpoint is verified TLS or nothing is built. The S3 blob
client is one ([storage.md](storage.md#s3-compatible-driver-notes)). The other is the JWKS fetch of
`CommunityOidcIdentityProvider.overJwksEndpoint`, which takes the JWKS URI and builds its own engine:
a key in that document authenticates every token signed under its `kid`, so a key set is installed
only from a server the connection authenticated (ADR-012 §4). An `https` URI with no crypto provider
bound, under `exeris.transport.tls=false`, or with a provider that cannot verify an outbound peer
builds no provider (`EX-NET-4004`). An `http` URI is plaintext by the application's request, for an
identity provider on a trusted network or in development. The provider owns that engine and
`close()` releases it. The carrier's `eu.exeris.kernel.transport.TransportTlsClientPosture` event
records the outcome: `VERIFIED`, a `PLAINTEXT_*` value (`PLAINTEXT_REQUIRED` where the owner asked for plaintext), or a `REFUSED_*` reason.

---

## JFR Events

| Event | When Emitted | Key Fields |
|:------|:-------------|:-----------|
| `PrincipalBoundEvent` | `SecurityInterceptor` successfully authenticates a request and binds `PrincipalContext`/`StorageContext` | `providerId`, `isolationStrategy`, `hasTenant`, `durationMicros` |
| `SecurityContextMissingEvent` | Any denial where no security context could be established | `errorCode`, `dropReason` |
| `InsufficientPrivilegesEvent` | `CitadelGuard` RBAC gate denial (EX-SEC-2003) | `requiredRole`, `principalIdHash` |
| `StorageContextDerivedEvent` | `StorageContextBridge` derives a `StorageContext` from the active `PrincipalContext` | `isolationStrategy`, `hasTenant` |
| `RoleRegistryLoadedEvent` | Once at bootstrap, when `GeneratedRoleRegistryLoader` resolves (or fails to find) the generated `RoleCheckRegistry` | `generatedClassFound`, `methodCount` |

### Why a denial names its reason (since 0.12)

Three unrelated situations produce the same `401`, and the response has to stay the same for all
three — telling an unauthenticated caller *which* one applies hands them a probe oracle. The reason
therefore lives in telemetry, where the operator can read it and the caller cannot.

`dropReason` is a `SecurityDenialReason`, and the code travels with it rather than beside it:

| Reason | Error code | Meaning |
|:---|:---|:---|
| `NO_PROVIDER` | `EX-SEC-2001` | No security provider bound — the whole authenticated surface denies |
| `TOKEN_MISSING` | `EX-SEC-2001` | A provider is bound; the request carried no bearer credential |
| `TOKEN_INVALID` | `EX-SEC-2002` | A credential was presented and rejected |
| `PROVIDER_ERROR` | `EX-SEC-2002` | The provider failed without calling it a rejection |
| `PRE_AUTH_BRIDGE_ERROR` | `EX-SEC-2002` | Storage-context derivation failed after identity was accepted |

The split between the two codes is the point: **`2001` means there was nothing to validate, `2002`
means validation was attempted and failed.** That is what separates a misconfigured deployment from a
rejected caller.

`NO_PROVIDER` is the one worth alerting on. A deployment that never bound a provider denies every
authenticated route, and on the wire that is indistinguishable from a fleet of clients that all
forgot their tokens — so it gets diagnosed against the clients. Read `NO_PROVIDER` against
`TOKEN_MISSING`: the second rising is a client change, the first rising is yours.

`PROVIDER_ERROR` carries the same warning in smaller print. An unreachable JWKS endpoint degrades to
"every token fails", which reads as a credential problem and is an outbound-connectivity one.

**Emitting the reason is a per-transport obligation.** The decision layer is shared — Core owns
`SecurityInterceptor` and `RouteAuthorizationEnforcer`, so every transport inherits one admission
rule — but the two reasons a *dispatcher* determines (`NO_PROVIDER`, `TOKEN_MISSING`) are known only
where the request is decoded, and each transport has to emit them at its own sites. A transport that
does not is not wrong on the wire and is dark in the recording, which is exactly the failure this
section describes.

**Every reason the event's description names is emitted.** `NO_PROVIDER` and `TOKEN_MISSING` are
emitted by `CommunityHttpRequestDispatcher`, the site that knows them; the other three come from
`SecurityInterceptor`. An operator filtering on `NO_PROVIDER` who gets an empty result can conclude
that no deployment on an instrumented transport was misconfigured.

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

## Owning ADRs

- [ADR-012](../adr/ADR-012-security-trust-model-upgrade-for-resource-server-validation-and-fail-closed-runtime.md) — Security Trust Model Upgrade for Resource-Server Validation and Fail-Closed Runtime
- [ADR-014](../adr/ADR-014-requiresrole-compile-time-rbac-generation.md) — `@RequiresRole` Compile-Time RBAC Generation
- [ADR-040](../adr/ADR-040-identity-provider-spi.md) — Identity Provider SPI — Pluggable Token Validation + `SecurityProvider` Dispatcher
- [ADR-061](../adr/ADR-061-declarable-http-route-authorization-policy.md) — Replace the hardcoded `/secure` prefix with a declarable HTTP route-authorization policy

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.security.*`) and `eu.exeris.kernel.spi.security.identity.*` are **stable** from 0.13.0 in the
[SPI Stability Matrix](../stability-matrix.md) ([ADR-100](../adr/ADR-100-spi-surfaces-stable-at-1-0.md)).
`SecretProvider` is outside the surface; adding that seam later is additive. See the matrix for the
semver policy and TCK coverage status.
