---
title: "ADR-032: HttpClientRequestEnricher SPI — Implicit Context Propagation to Outbound HTTP"
type: adr
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
slug: adr/ADR-032
---

# ADR-032: `HttpClientRequestEnricher` SPI — Implicit Context Propagation to Outbound HTTP

**Status:** Accepted
**Date:** 2026-05-17
**Owner:** kernel/transport
**Visibility:** public
**Scope:** kernel/transport (per-repo)
**Authors:** Arkadiusz Przychocki

> *(amended 2026-09-05)* **The façade this ADR extends is `KernelWebClient`, in `exeris-kernel-core`.** The decision text below says `CommunityWebClient` throughout and is left as written; that name came from ADR-026, which ADR-034 superseded on 2026-05-19. No class of that name was ever committed. The decision itself stands and is implemented — `HttpClientRequestEnricher` is in `exeris-kernel-spi`, and `KernelWebClient` takes it, defaulting to `HttpClientRequestEnricher.noop()`. See `## Amendments`.

## Context

`CommunityWebClient` (ADR-026) ships HTTP verbs + Jackson 3 JSON binding on top of `HttpClientEngine` SPI, but its constructor exposes only `(engine, allocator, mapper)`. Every outbound request therefore ships with whatever headers `CommunityWebClient` hard-codes internally (`Content-Type`, `Accept`, `Content-Length`) and nothing else. Tenant identity, principal identity, and forthcoming W3C trace context — all available in the kernel via `ScopedValue` slots — are invisible to the wire unless the caller manually constructs `HttpHeader` entries on every call site.

The downstream generator `exeris-tooling/KernelClientGenerator` emits typed per-entity clients (e.g., `WidgetClient.findById(id)`) that wrap `CommunityWebClient`. The generated code currently emits zero header logic. Without a propagation seam, any service-to-service call from generated code crosses tenant boundaries silently — the receiving service has no `X-Tenant-Id` from the caller, no `X-Principal-Id`, no correlation context. This blocks multi-tenant generated-client adoption.

The `KernelClientGenerator` emit shape is intentionally minimal — entity-shaped wrappers (`findById` / `findAll` / `create` / `update` / `delete`) calling `client.get(...)` / `client.post(...)`. Pushing the propagation logic into the generator (every emitted client class repeats the same `headers.add("X-Tenant-Id", ...)`) is a leaky abstraction; the dependency on `PrincipalContext` would have to be hard-wired into the codegen template instead of injected at composition time. A propagation seam belongs in the runtime, not in the generator.

Today's symmetric server-side primitive `HttpResponseBodyEncoder` (since 0.5.0) handles typed response encoding for inbound traffic; there is no client-side equivalent for outbound header enrichment.

## Decision

Introduce a small implementation-blind SPI in `eu.exeris.kernel.spi.http`:

```java
@FunctionalInterface
public interface HttpClientRequestEnricher {

    HttpRequest enrich(HttpRequest request);

    static HttpClientRequestEnricher noop();

    static HttpClientRequestEnricher chain(List<HttpClientRequestEnricher> enrichers);
}
```

Ship one default-bundled implementation in `exeris-kernel-community`:

```java
package eu.exeris.kernel.community.http.client;

public final class CommunityKernelContextEnricher implements HttpClientRequestEnricher {
    // Reads KernelProviders.PRINCIPAL_CONTEXT when bound and adds:
    //   - X-Tenant-Id    <PrincipalContext.tenantId().toString()>   (skipped when empty)
    //   - X-Principal-Id <PrincipalContext.principalId().toString()> (skipped when slot unbound)
    // Unbound ScopedValue slots → silently skip (never throw).
}
```

`CommunityWebClient` gains a new constructor `(HttpClientEngine, MemoryAllocator, ObjectMapper, HttpClientRequestEnricher)`; the existing 3-arg constructor stays and delegates with `HttpClientRequestEnricher.noop()` to preserve ADR-026's "no implicit behaviour" surface for callers that do not opt in.

### Contract

- **Immutability.** `enrich` MUST return a new `HttpRequest` (the record is immutable; the enricher constructs one with a copy of the existing headers plus its additions). No in-place mutation seam exists, intentionally.
- **Body ownership.** `enrich` MUST NOT read, retain, or close the request body `LoanedBuffer`. The buffer's lifecycle is owned by the calling site and transferred to the engine on `send`.
- **Header injection rejection.** Any header value containing CR (`\r`, `0x0D`), LF (`\n`, `0x0A`), or NUL (`\0`, `0x00`) MUST cause the enricher to throw `IllegalArgumentException` before returning. This is symmetric with the server-side rejection in `Http1RequestParser` (Security audit S-P0-04, 2026-05-13) and prevents CWE-93 HTTP header injection on the outbound path.
- **Chain semantics.** `chain(...)` returns an enricher that applies its members in list order; each member sees the output of the previous one. An empty list is equivalent to `noop()`.
- **Threading.** `enrich` runs on the caller's virtual thread, synchronously, after `CommunityWebClient` constructs the base `HttpRequest` and before `engine.send(request)`. The enricher MAY read `ScopedValue` slots bound in the calling context. The enricher MUST NOT spawn threads, perform I/O, or block on external resources.
- **Allocation discipline.** A typical enricher allocates one new `HttpRequest` record + one new `List<HttpHeader>` per call. This is acceptable inside the existing `CommunityWebClient.execute` path (already allocates the Jackson byte array and the `LoanedBuffer`); the enricher is not a hot-path primitive.

### Opt-in by default

`CommunityKernelContextEnricher` is **not** bundled implicitly. Applications and the generator-emitted code must pass it explicitly:

```java
CommunityWebClient client = new CommunityWebClient(
        engine, allocator, mapper,
        HttpClientRequestEnricher.chain(List.of(
                new CommunityKernelContextEnricher(),
                new MyBearerTokenForwardingEnricher(scopedTokenStore)
        )));
```

This preserves ADR-026's "no implicit behaviour" surface and avoids magic-by-default headers in test fixtures and on call paths that intentionally cross tenant boundaries (bootstrap, admin tooling, cross-tenant migration jobs).

### What the default enricher cannot do today

- **Bearer token forwarding.** The kernel does not hold the raw `Authorization: Bearer <token>` string — `PrincipalContext` is the *parsed* identity (UUIDv7, roles, scopes) consumed by the JWT validator at the HTTP edge. Applications that need outbound Bearer propagation must hold the token outside kernel SPI (in application memory or via a custom `ScopedValue` of their own) and ship a custom enricher that reads from it.
- **W3C `traceparent` / `tracestate`.** No `TraceContext` `ScopedValue` slot exists in 0.8.0. The consolidated 1.0 GA roadmap (Sprint 0.12, B2 P1-9) places the slot — until then, the default enricher emits no correlation header. After the slot lands, `CommunityKernelContextEnricher` will gain a third header (`traceparent`) read from the new slot.

These are documented limitations, not bugs.

## Consequences

### Positive

- **Generated-client multi-tenancy unblocked.** Generator-emitted code can wire `CommunityKernelContextEnricher` in the construction of `CommunityWebClient` (one-line composition root change), and every outbound call inherits tenant + principal headers without per-method codegen changes.
- **No `HttpRequest` mutability seam.** The record stays immutable; enricher rebuilds.
- **Composable.** Custom enrichers (Bearer forwarding, service-mesh headers, tenant-scoped feature flags) compose via `chain(...)` without a registry or DI container.
- **Symmetric with server-side rejection.** CRLF/NUL guard at the enricher boundary mirrors `Http1RequestParser`; the kernel cannot be used as a header-injection oracle by either direction.
- **No SPI-internal Jackson coupling.** The enricher contract sees `HttpRequest` and `HttpHeader` only — both already in SPI. No JSON-binding concept leaks across the seam.

### Negative / costs

- One additional SPI surface to maintain (one interface + one default impl + one Abstract TCK).
- Applications wanting full multi-tenant propagation must remember to opt in. The asymmetry between "kernel knows the tenant" and "outbound HTTP is silent unless you pass the enricher" is real; documentation + an example in `community.http.client` package-info is the mitigation.
- `CommunityKernelContextEnricher` is silently no-op outside a `ScopedValue` scope (e.g., a CLI tool, a unit test). This is the correct behaviour (no headers when no identity), but can confuse developers who expect headers to appear in a unit test that does not bind `PRINCIPAL_CONTEXT`.

### Neutral / open

- A future `HttpServerRequestEnricher` symmetric surface (for inbound request decoration before handler dispatch) is **not** introduced by this ADR. Inbound enrichment crosses different boundaries (request parsing → handler resolution → ScopedValue scope construction) and merits its own decision.
- Bearer token forwarding deferred until an application demonstrates a concrete need that cannot be met by a 5-line custom enricher.

## Alternatives considered

### A) Magic auto-enrichment in `CommunityWebClient`

The 3-arg constructor would silently read `PRINCIPAL_CONTEXT` and inject `X-Tenant-Id` / `X-Principal-Id` on every call. Rejected:
- Implicit behaviour contradicts ADR-026's explicit "no implicit retry / no callbacks / no magic" surface.
- Test fixtures that construct `CommunityWebClient` outside a `ScopedValue` scope behave differently from production.
- Bootstrap / admin / cross-tenant jobs cannot opt out without sub-classing.

### B) Generator emits boilerplate per entity

`KernelClientGenerator` would inject `headers.put("X-Tenant-Id", PRINCIPAL_CONTEXT.get().tenantId().orElse(...).toString())` into every emitted method. Rejected:
- Multiplies the same logic across N entity classes (one of the WMC-flagged smells the project explicitly avoids).
- Codegen template hard-couples to `PrincipalContext` SPI — every SPI change forces a generator change.
- Cannot compose with application-specific enrichers (Bearer, service-mesh) without further codegen complexity.

### C) `HttpRequest.Builder` with auto-enricher hook

Introduce a mutable `HttpRequest.Builder` and let `CommunityWebClient` register hooks that fire during build. Rejected:
- `HttpRequest` is a deliberately immutable record (Valhalla-ready, `record` permits no identity ops). Adding a builder would split the SPI into "use the record directly" and "use the builder always" paths.
- Hook registration introduces lifecycle state that `HttpClientEngine` and other SPI consumers would have to discover and respect.

### D) Two-arg enricher contract: `enrich(HttpRequest, HttpClientEngine)`

Pass the engine in so an enricher could short-circuit `send`. Rejected:
- Conflates enrichment with transport — an enricher with engine access could become a request-level retry / circuit-breaker, which ADR-026 explicitly defers.
- Strictly more surface for no current use case.

### E) Reuse the server-side `HttpResponseBodyEncoder` registry pattern

Make `HttpClientRequestEnricher` discoverable via `HttpProvider.requestEnricherRegistry()` and let providers ship default chains. Rejected:
- Discoverability ≠ composability. A provider-supplied default chain hides what runs in production behind ServiceLoader resolution, which is exactly the magic this ADR is trying to avoid.
- Per-application enrichers (Bearer forwarding) have no business living behind a provider; they live where the application owns the credential.

## Implementation plan

### v0.8 Sprint 6 (audit-absorbed sprint — see `docs/v0.8-sprint-and-implementation-map.md`)

Lands as part of the `HTTP-130..136` work stream alongside the other generator-support primitives (`UriTemplate`, `QueryParams`, `WebClientException` predicate expansion). Sequencing within the sprint:

1. `eu.exeris.kernel.spi.http.HttpClientRequestEnricher` interface + `noop()` + `chain(...)`.
2. `AbstractHttpClientRequestEnricherTck` — chain composition, CRLF / NUL rejection, immutability of input `HttpRequest`, `ScopedValue` read-when-bound vs noop-when-unbound semantics, null-input rejection.
3. `eu.exeris.kernel.community.http.client.CommunityKernelContextEnricher` reading `KernelProviders.PRINCIPAL_CONTEXT`.
4. `CommunityWebClient` new constructor `(engine, allocator, mapper, enricher)`; existing 3-arg constructor delegates with `HttpClientRequestEnricher.noop()`.
5. TCK binding: `CommunityHttpClientRequestEnricherTckTest` + `CommunityKernelContextEnricherTest` (real `PrincipalContext` impl bound via `ScopedValue.where(...)`).
6. `CommunityWebClientIntegrationTest` extension: round-trip a tenant-scoped request, assert `X-Tenant-Id` arrives at the handler side.
7. `community/http/client/package-info.java` example showing the four-arg constructor + chain composition with a hypothetical `BearerForwardingEnricher`.

No `KernelClientGenerator` change is required in `exeris-tooling` for this enricher alone — the generator continues to emit a `CommunityWebClient` field and constructor injection; the composition root chooses whether to construct with `noop()` or with a `chain(...)`. The generator changes for `UriTemplate` and `QueryParams` are tracked separately under `TOOL-136` in the same sprint.

### Cross-repo follow-up

- `exeris-spring-runtime` may add a Pure-Mode integration that auto-bundles `CommunityKernelContextEnricher` when an application registers a `CommunityWebClient` bean — out of scope for this ADR, owned by spring-runtime.
- `exeris-benchmarks/community-app` should add a tenant-scoped client benchmark validating that the enricher's per-call allocation (one record + one list) stays within the documented hot-path budget — out of scope for kernel CI.

## References

- [HttpRequest.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpRequest.java) — immutable record carrying the headers list this enricher rebuilds
- [HttpKernelProviders.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpKernelProviders.java) — `HTTP_CLIENT_ENGINE` ScopedValue slot
- [KernelProviders.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/context/KernelProviders.java) — `PRINCIPAL_CONTEXT` ScopedValue slot the default enricher reads
- [PrincipalContext.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/security/PrincipalContext.java) — identity model exposing `principalId()` and `tenantId()`
- [KernelWebClient.java](../../exeris-kernel-core/src/main/java/eu/exeris/kernel/core/http/client/KernelWebClient.java) — the façade the enricher is wired into *(2026-09-05: this row linked a `CommunityWebClient.java` under `exeris-kernel-community`, a path that has never existed in this repository)*
- ADR-026 — Client-Side Application API (`CommunityWebClient`) — establishes the typed-façade surface this ADR extends
- ADR-014 — `@RequiresRole` Compile-Time RBAC Generation — same `PrincipalContext.roleMask()` path the enricher reads from
- ADR-006 — Spring-Free Kernel Boundary (The Wall) — enricher contract sees only SPI types
- Security audit S-P0-04 (2026-05-13, `docs/release/security-privacy-audit-v0.8.md`) — server-side CRLF/NUL rejection; enricher mirrors on outbound
- Consolidated 1.0 GA roadmap row #63 (`docs/release/1_0-gA-roadmap-consolidated.md`) — Sprint 0.12 W3C `traceparent` ScopedValue slot that extends this enricher's default emission set after it lands

## Amendments

- **2026-09-05 — the façade is named `KernelWebClient` and lives in `exeris-kernel-core`.** This
  ADR's context, decision and implementation plan all name `CommunityWebClient`, taken from ADR-026,
  and its References row linked
  `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/http/client/CommunityWebClient.java`.
  No file of that name exists, and none ever did — there is no add or delete for it anywhere in this
  repository's history, and no commit ever contained `class CommunityWebClient`. ADR-026 was
  superseded by ADR-034 on 2026-05-19, which introduced `KernelWebClient`; ADR-026's own header
  records that the `CommunityWebClient` façade is removed. This ADR, accepted 2026-05-17, was
  written against a name that was retired eight weeks later and never revisited.
  **Nothing about the decision changes.** `HttpClientRequestEnricher` is in
  `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/`, and `KernelWebClient` accepts one,
  delegating to `HttpClientRequestEnricher.noop()` where none is supplied — which is obligation 4 of
  the implementation plan, satisfied on a differently-named façade. Only the broken link is
  corrected; the decision text is marked, not rewritten (`adr-conventions.md` rule 7). Found by the
  shared link check on its first run against this repository. (PR pending)
