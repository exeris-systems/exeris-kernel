# ADR-026: Client-Side Application API — `CommunityWebClient`

**Status:** Accepted
**Date:** 2026-05-16
**Amended:** 2026-05-17 — corrected placement, class name, and rationale (see "Amendment 2026-05-17" below); original Sprint 2 PR #129 decision is superseded by this amendment.
**Owner:** kernel/transport
**Visibility:** public
**Scope:** kernel/transport (per-repo)
**Authors:** Arkadiusz Przychocki

## Amendment 2026-05-17

The original ADR (committed 2026-05-16 in PR #129) made three compounding placement errors that this amendment corrects:

| Axis | Original (wrong) | Corrected |
|:--|:--|:--|
| Class name | `ExerisWebClient` | `CommunityWebClient` |
| Package | `eu.exeris.kernel.transport.http3.client` | `eu.exeris.kernel.community.http.client` |
| Module | `exeris-kernel-community` | `exeris-kernel-community` (unchanged) |
| Rationale | "http3 package retained for generator stability" | three-tier alignment (HTTP/3 enterprise-only per ADR-016) + Community-module `Community*`-prefix convention; generator updated in coordinated cross-repo PR |

Reasons the original placement was wrong:

1. **Tier violation.** HTTP/3 is enterprise-only per ADR-016 ("HTTP/3 Benchmarking — Enterprise-Only Track"). The Community module ships HTTP/1.1 + HTTP/2 (h2c) — never HTTP/3. Putting an `http3` package inside `exeris-kernel-community` contradicts the open-core boundary documented in ADR-020.
2. **Namespace deviation.** Every other class in `exeris-kernel-community` lives under the `eu.exeris.kernel.community.*` namespace. The original package skipped the `.community.` segment, creating a single-file outlier that diverges from the visual + grep convention every other class follows.
3. **Class-name deviation.** Every other concrete class in the Community module uses the `Community*` prefix (`CommunityHttpClientEngine`, `CommunityTlsEngine`, `CommunityMemoryProvider`, etc.). `ExerisWebClient` was an outlier that broke the convention.

The "generator stability" rationale in the original was inverted thinking — cross-repo hardcoded import paths should align to kernel-side naming convention, not the other way around. The corrected approach co-coordinates both ends in a single multi-repo release (kernel rename + `exeris-tooling/KernelClientGenerator` FQN update, see Implementation plan below).

## Context

The Exeris ecosystem has so far defined two HTTP surfaces in the kernel:

- **`HttpServerEngine` SPI** (since 0.5.0) — server-side accept loop, exchange dispatch, response writer.
- **`HttpClientEngine` SPI** (since 0.5.0) — outbound `send(HttpRequest) → HttpResponse` with connection pooling, TLS, and lifecycle (`start` / `close` / `isRunning`).

The `HttpClientEngine` SPI is implementation-blind by design — `HttpRequest` carries an off-heap `LoanedBuffer` body, `HttpResponse` likewise — and gives callers no encoding/decoding affordance. Building a `POST` request from a domain object requires manual JSON serialization, manual byte → `LoanedBuffer` adaptation, manual header construction, and explicit memory ownership at every call site. Status-code branching (404 → `Optional.empty()`, 5xx → retry, etc.) is also caller responsibility.

`exeris-tooling` ships `KernelClientGenerator` (`exeris-codegen-java`) which emits typed per-entity REST clients (`WidgetClient.findById(id)`, `WidgetClient.create(widget)`, etc.). The generator's emitted code references a kernel-side `CommunityWebClient` class as its underlying transport and JSON binding façade. The hardcoded `ClassName.get(...)` constants in `KernelClientGenerator` are updated in lockstep with this ADR's amendment to the corrected FQN:

```java
private static final ClassName WEB_CLIENT =
    ClassName.get("eu.exeris.kernel.community.http.client", "CommunityWebClient");
private static final ClassName WEB_CLIENT_EXCEPTION =
    ClassName.get("eu.exeris.kernel.community.http.client", "CommunityWebClient", "WebClientException");
```

The generated code calls `client.get(path, EntityClass.class)`, `client.post(path, entity, EntityClass.class)`, `client.patch(path, entity, EntityClass.class)`, `client.delete(path, Void.class)` — and inspects `WebClientException.isNotFound()` to map `404` to `Optional.empty()`.

Until the kernel exposes this class, the generator emits code that cannot compile against the kernel artifact. The gap is documented in `exeris-tooling` as *"SPI HTTP/transport client (TBD against the actually exposed client SPI; align with the working benchmark app)"*. This ADR closes that gap.

## Decision

Introduce a kernel-side **`CommunityWebClient`** as a public façade on top of `HttpClientEngine` SPI. The class lives in the **`exeris-kernel-community` module** under package **`eu.exeris.kernel.community.http.client`** — tier-correct (Community ships HTTP/1.1 + HTTP/2), namespace-aligned (`.community.` segment present), and `Community*`-prefixed (matches every other class in the module).

### Public API surface

```java
package eu.exeris.kernel.community.http.client;

public final class CommunityWebClient {

    public CommunityWebClient(HttpClientEngine engine, MemoryAllocator allocator, ObjectMapper mapper);

    public <T> T get(String path, Class<T> responseType);
    public <T> T post(String path, Object body, Class<T> responseType);
    public <T> T patch(String path, Object body, Class<T> responseType);
    public <T> T delete(String path, Class<T> responseType);

    public static final class WebClientException extends RuntimeException {
        public int status();
        public String responseBody();
        public boolean isNotFound();
    }
}
```

### Constructor inputs

- **`HttpClientEngine engine`** — pre-configured (host + port + TLS + connection pool). The engine targets a single host; the web client adds path + JSON binding on top.
- **`MemoryAllocator allocator`** — required to materialise outbound JSON bytes into the off-heap `LoanedBuffer` body required by `HttpRequest`. Uses `allocator.allocateInfrastructure(sizeBytes)` (same pattern as `NativeTcpSocketProbe`).
- **`ObjectMapper mapper`** — Jackson 3 (BOM-managed at 3.1.1). The mapper is application-owned — callers can configure modules, naming strategies, date handlers.

### Status-code mapping

- **2xx** → deserialise body via Jackson into the requested `responseType`. If `responseType == Void.class`, return `null` (intended for `delete`, but legal anywhere).
- **404** → throw `WebClientException` with `status() == 404`; the generated code uses `isNotFound()` to translate this back to `Optional.empty()` at the entity-level wrapper.
- **Any other non-2xx** → throw `WebClientException` with the full status + response body.

The web client deliberately performs **no implicit retry**. Retry policy is the caller's concern; failures map to `WebClientException` exactly once, with full diagnostic context.

### Threading model

Every method call **blocks the calling virtual thread** through the underlying `HttpClientEngine.send` (which itself is documented as blocking until the response is fully buffered). No implicit async wrapping. No callbacks. No `CompletableFuture`. Virtual threads are the concurrency primitive — callers parallelise by spawning more VTs.

### Memory ownership

- **Request body** (`post` / `patch`): the web client allocates a `LoanedBuffer` from the `MemoryAllocator`, serialises the body via Jackson, and passes ownership to `HttpRequest`. The engine is responsible for the buffer's lifetime after `send` is invoked (per `HttpClientEngine` Javadoc).
- **Response body**: returned by `HttpClientEngine.send` as a `LoanedBuffer`. The web client deserialises bytes via Jackson **and then closes the buffer** in a `try`-with-resources or equivalent `finally`. Callers never see the buffer.

### Package + class naming

`eu.exeris.kernel.community.http.client` is tier-correct (Community module ships HTTP/1.1 + HTTP/2; HTTP/3 is enterprise-only per ADR-016 and never co-located with Community sources), namespace-aligned (`.community.` segment matches every other class in the module), and reads as a coherent client-side subpackage (mirrors `community.http.server` if/when the server-side surface gets the same subpackage split).

`CommunityWebClient` follows the `Community*` prefix convention used across the module (`CommunityHttpClientEngine`, `CommunityHttpProvider`, `CommunityHttpServerEngine`, `CommunityTlsEngine`, `CommunityMemoryProvider`, `CommunityFlowSubsystem`, ...). Brand-prefixed variants (`ExerisWebClient`) belong at module-agnostic kernel surfaces (SPI, Core public API) — not inside a tier-specific module.

The protocol surface remains agnostic regardless of package name: `HttpClientEngine` is implementation-blind. Community implementations of the engine speak HTTP/1.1 + HTTP/2; future Enterprise implementations can speak HTTP/3. `CommunityWebClient` itself does not pin a wire version — it composes with whichever `HttpClientEngine` the caller injects. (A separate `EnterpriseWebClient` could be introduced under `exeris-kernel-enterprise` if Enterprise needs a distinct binding-or-feature surface; today the Community façade is the only canonical client class.)

### Module placement: Community, not SPI

`HttpClientEngine` lives in SPI as it must — it's the implementation-blind contract for inbound/outbound HTTP wire work. `CommunityWebClient` is **a kernel-side façade on top of the SPI**, not an SPI itself. It pulls Jackson 3 as a JSON-binding choice; SPI must remain implementation-blind and cannot host a binding-specific helper without breaking the principle.

The Community module already declares `jackson-databind` as a runtime dependency (used elsewhere for Community-internal JSON paths). Placing `CommunityWebClient` here satisfies "no new dependency" — Jackson 3 is already on Community's classpath. The module structure and package structure are co-consistent: Community module → `community.*` namespace → `Community*`-prefixed concrete classes.

### TCK + binding

`CommunityWebClient` is a **single concrete class** rather than an SPI contract — there is only one implementation today (the Jackson-backed Community one). The TCK abstract-base pattern applies to SPI contracts with multiple implementations (e.g., `HttpClientEngine` → Community + future Enterprise); it does not apply here. The `exeris-kernel-tck` module also cannot import classes from `exeris-kernel-community` (Community depends on TCK, not the reverse), so an abstract TCK base hosted in TCK could not reference `CommunityWebClient` directly.

Instead, ship an **integration test** in the owning module: `CommunityWebClientIntegrationTest` in `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/http/client/` exercising every public verb plus error semantics against an in-process `HttpServerEngine` listener + `CommunityHttpClientEngine` round-trip. No external network dependency — fully hermetic. Coverage:

- 2xx round-trip per verb (GET / POST / PATCH / DELETE)
- 4xx → `WebClientException` carries status + body
- 404 → `isNotFound()` returns `true`
- 5xx → `WebClientException` (no implicit retry)
- Null arguments → `NullPointerException` at boundary
- `Void.class` response — returns null cleanly

If/when a second `WebClient`-shaped class lands (e.g., `EnterpriseWebClient` over H3 with zero-allocation Panama JSON binding), an abstract TCK in a Community-aware testkit module can be lifted from this integration test and shared between bindings.

## Consequences

### Positive

- **`exeris-tooling` `KernelClientGenerator` unblocked** — emitted client code compiles against kernel artifact starting in 0.8.0.
- **One canonical client surface** for kernel consumers (and SDK callers without the generator) — typed CRUD ergonomics over generic `HttpClientEngine.send`.
- **No new module** — fits into existing Community surface; Jackson 3 is already present.
- **No new SPI tier** — the lower-level engine boundary stays implementation-blind; this is a higher-level façade.
- **Three-tier alignment** — `CommunityWebClient` correctly sits in the Community tier (HTTP/1.1 + HTTP/2). An `EnterpriseWebClient` is a possible future symmetric addition in `exeris-kernel-enterprise` for HTTP/3 + Panama JSON; today no such class is needed.
- **Naming + namespace coherence** — class prefix, package namespace, and module name all agree (`Community*` / `community.*` / `exeris-kernel-community`), restoring visual + grep consistency.

### Negative / costs

- Jackson 3 binding is hardcoded. Alternative JSON libraries (Gson, Moshi, native Jackson 2) would require a parallel client class or an additional dependency-injection seam. Deferred — Jackson 3 covers ≥ 95% of JVM JSON workloads.
- **One-time cross-repo coordination** to fix the original placement: kernel rename + `exeris-tooling/KernelClientGenerator` FQN update + ADR amendment + canonical ADR registry update must land in lockstep. Captured in the implementation plan below.
- Pre-amendment generator output (any client code emitted between 2026-05-16 and the amendment landing) imports the old FQN and will not compile against amended kernel. Pre-1.0 has no external SPI consumers (per project memory) so the blast radius is bounded to in-flight feature branches in `exeris-tooling` itself.

### Neutral / open

- A `KernelProviders.WEB_CLIENT` `ScopedValue` binding could publish a default client for subsystem consumers, but is **out of scope for 0.8.0**. Application code or codegen output constructs `CommunityWebClient` explicitly today. ScopedValue binding deferred to v0.9 or later if a use case emerges.

## Alternatives considered

### A) Layer the typed API on `HttpClientEngine` SPI directly

Add `<T> T sendJson(...)` / similar helper methods to `HttpClientEngine` interface. Rejected — would force every engine implementation to ship Jackson or some binding, breaking SPI implementation-blind invariant.

### B) New `exeris-kernel-client` module

Carve out a dedicated module to host `CommunityWebClient` + its Jackson binding. Rejected — adds a module to the kernel POM tree (CI cycles + Maven Central artifact + module-info bookkeeping) for a single public class. Premature modularisation. Can be promoted later if the client surface grows.

### C) Provide the client via `exeris-sdk` instead

Move `CommunityWebClient` to the SDK module (which already ships annotations + source model + UI kit). Rejected — `exeris-sdk` is build-time and design-time tooling. The web client is a **runtime** concern (it lives behind a live `HttpClientEngine`). Cross-cutting it into the SDK breaks the build-time / runtime split.

### D) Higher-level entity-shaped surface on `CommunityWebClient` (`findById` / `findAll` / `create` / `update` / `delete`)

Earlier sprint-map sketches proposed methods like `<T> Optional<T> findById(String basePath, UUID id, Class<T> entityType)`. Rejected after reading the actual `KernelClientGenerator` emission: the generator already emits the typed entity-shaped wrappers (`UserClient.findById`) on top of HTTP-verb primitives. Putting entity-shaped methods on `CommunityWebClient` would duplicate the abstraction layer and force every non-generator consumer to use the entity API even when raw HTTP verbs suffice. The cleaner split is: **`CommunityWebClient` provides HTTP verbs + JSON; generator emits entity wrappers**.

### E) Keep original `ExerisWebClient` placement under `eu.exeris.kernel.transport.http3.client`

Rejected (Amendment 2026-05-17) — three compounding violations: tier (HTTP/3 enterprise-only per ADR-016), namespace (`.community.` segment missing), class-prefix convention (`Community*`). "Generator import stability" rationale inverts the correct dependency direction: kernel-side naming convention drives generator FQN, not the reverse. Amendment co-coordinates both ends in a single cross-repo release.

## Implementation plan

### Sprint 2 (v0.8) — original placement, PR #129 (2026-05-16, merged)

- `ExerisWebClient` + `WebClientException` + integration test landed in `exeris-kernel-community` under the original (wrong) package `eu.exeris.kernel.transport.http3.client`. Superseded by Sprint 3 amendment below.

### Sprint 3 (v0.8) — amendment, coordinated cross-repo release

- **`exeris-kernel`** — rename `ExerisWebClient` → `CommunityWebClient`, relocate from `eu.exeris.kernel.transport.http3.client` → `eu.exeris.kernel.community.http.client`, amend ADR-026 (this document), update integration test path + class name.
- **`exeris-tooling`** — update `KernelClientGenerator` (and any e2e fixtures) hardcoded `ClassName.get(...)` constants to the new FQN. Lands as `feat/kernel-webclient-rename-from-exeris-to-community` PR.
- **`exeris-docs`** — register kernel ADR-026 row in `adr-index.md` (the original Sprint 2 registration step was missed, leading to the ADR-026 number collision with the Spring-runtime EventBus ADR — separate renumbering coordination handled by user).
- **Optional follow-up** — `community.http` flat-package reorganization into `shared` / `client` / `server` / `h2` subpackages. Decoupled from this amendment; tracked as a separate PR if/when WMC or readability pressure justifies the churn.

### Cross-repo follow-up

- `exeris-benchmarks/community-app` should add a client-variant benchmark validating the round-trip path under load (out of kernel CI scope; tracked on the benchmarks repo's cadence).

## References

- [HttpClientEngine.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpClientEngine.java) (SPI surface)
- [CommunityHttpClientEngine.java](../../exeris-kernel-community/src/main/java/eu/exeris/kernel/community/http/CommunityHttpClientEngine.java) (Community impl)
- [KernelClientGenerator.java](https://github.com/exeris-systems/exeris-tooling/blob/main/exeris-codegen-java/src/main/java/eu/exeris/tooling/codegen/java/kernel/KernelClientGenerator.java) (downstream consumer)
- ADR-008 — Open-Core Strategy & Commoditization of Off-Heap TLS
- ADR-009 — HTTP Codec module
- ADR-016 — HTTP/3 Benchmarking — Enterprise-Only Track (basis for tier-violation rejection of original `http3` package placement)
- ADR-020 — Visibility taxonomy (public / enterprise-private) governing open-core boundaries
