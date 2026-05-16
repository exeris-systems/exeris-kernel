# ADR-026: Client-Side Application API — `ExerisWebClient`

**Status:** Accepted
**Date:** 2026-05-16
**Owner:** kernel/transport
**Visibility:** public
**Scope:** kernel/transport (per-repo)
**Authors:** Arkadiusz Przychocki

## Context

The Exeris ecosystem has so far defined two HTTP surfaces in the kernel:

- **`HttpServerEngine` SPI** (since 0.5.0) — server-side accept loop, exchange dispatch, response writer.
- **`HttpClientEngine` SPI** (since 0.5.0) — outbound `send(HttpRequest) → HttpResponse` with connection pooling, TLS, and lifecycle (`start` / `close` / `isRunning`).

The `HttpClientEngine` SPI is implementation-blind by design — `HttpRequest` carries an off-heap `LoanedBuffer` body, `HttpResponse` likewise — and gives callers no encoding/decoding affordance. Building a `POST` request from a domain object requires manual JSON serialization, manual byte → `LoanedBuffer` adaptation, manual header construction, and explicit memory ownership at every call site. Status-code branching (404 → `Optional.empty()`, 5xx → retry, etc.) is also caller responsibility.

`exeris-tooling` ships `KernelClientGenerator` (`exeris-codegen-java`) which emits typed per-entity REST clients (`WidgetClient.findById(id)`, `WidgetClient.create(widget)`, etc.). The generator's emitted code references a kernel-side `ExerisWebClient` class as its underlying transport and JSON binding façade:

```java
private static final ClassName WEB_CLIENT =
    ClassName.get("eu.exeris.kernel.transport.http3.client", "ExerisWebClient");
private static final ClassName WEB_CLIENT_EXCEPTION =
    ClassName.get("eu.exeris.kernel.transport.http3.client", "ExerisWebClient", "WebClientException");
```

The generated code calls `client.get(path, EntityClass.class)`, `client.post(path, entity, EntityClass.class)`, `client.patch(path, entity, EntityClass.class)`, `client.delete(path, Void.class)` — and inspects `WebClientException.isNotFound()` to map `404` to `Optional.empty()`.

Until the kernel exposes this class, the generator emits code that cannot compile against the kernel artifact. The gap is documented in `exeris-tooling` as *"SPI HTTP/transport client (TBD against the actually exposed client SPI; align with the working benchmark app)"*. This ADR closes that gap.

## Decision

Introduce a kernel-side **`ExerisWebClient`** as a public façade on top of `HttpClientEngine` SPI. The class lives in the **`exeris-kernel-community` module** under package **`eu.exeris.kernel.transport.http3.client`** (matching the generator's expected import path).

### Public API surface

```java
package eu.exeris.kernel.transport.http3.client;

public final class ExerisWebClient {

    public ExerisWebClient(HttpClientEngine engine, MemoryAllocator allocator, ObjectMapper mapper);

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

### Package naming

`eu.exeris.kernel.transport.http3.client` reads as HTTP/3-specific, but the class is **tier-agnostic**: Community runs over HTTP/1 + HTTP/2 (the actual protocol is `HttpClientEngine`'s private concern), Enterprise will run over H3 when the H3 client engine lands. The `http3` package name is historical — it was reserved when the original ADR sketch assumed an H3-first rollout. Renaming would break the `exeris-tooling` generator's in-flight code emission. The name **stays** for v0.8 stability and the generator pipeline; a rename can land alongside a separate codegen-coordinated PR if value justifies the churn.

### Module placement: Community, not SPI

`HttpClientEngine` lives in SPI as it must — it's the implementation-blind contract for inbound/outbound HTTP wire work. `ExerisWebClient` is **a kernel-side façade on top of the SPI**, not an SPI itself. It pulls Jackson 3 as a JSON-binding choice; SPI must remain implementation-blind and cannot host a binding-specific helper without breaking the principle.

The Community module already declares `jackson-databind` as a runtime dependency (used elsewhere for Community-internal JSON paths). Placing `ExerisWebClient` here satisfies "no new dependency" — Jackson 3 is already on Community's classpath. The package namespace `eu.exeris.kernel.transport.http3.client` (vs `eu.exeris.kernel.community.transport.http3.client`) keeps the generator's expected import working — module structure and package structure are independent.

### TCK + binding

`ExerisWebClient` is a **single concrete class** rather than an SPI contract — there is only one implementation today (the Jackson-backed Community one). The TCK abstract-base pattern applies to SPI contracts with multiple implementations (e.g., `HttpClientEngine` → Community + future Enterprise); it does not apply here. The `exeris-kernel-tck` module also cannot import classes from `exeris-kernel-community` (Community depends on TCK, not the reverse), so an abstract TCK base hosted in TCK could not reference `ExerisWebClient` directly.

Instead, ship an **integration test** in the owning module: `ExerisWebClientIntegrationTest` in `exeris-kernel-community/src/test/java/eu/exeris/kernel/transport/http3/client/` exercising every public verb plus error semantics against an in-process `HttpServerEngine` listener + `CommunityHttpClientEngine` round-trip. No external network dependency — fully hermetic. Coverage:

- 2xx round-trip per verb (GET / POST / PATCH / DELETE)
- 4xx → `WebClientException` carries status + body
- 404 → `isNotFound()` returns `true`
- 5xx → `WebClientException` (no implicit retry)
- Null arguments → `NullPointerException` at boundary
- `Void.class` response — returns null cleanly

If/when a second `ExerisWebClient`-shaped class lands (e.g., Enterprise variant with H3 + zero-allocation Panama JSON binding), an abstract TCK in a Community-aware testkit module can be lifted from this integration test and shared between bindings.

## Consequences

### Positive

- **`exeris-tooling` `KernelClientGenerator` unblocked** — emitted client code compiles against kernel artifact starting in 0.8.0.
- **One canonical client surface** for kernel consumers (and SDK callers without the generator) — typed CRUD ergonomics over generic `HttpClientEngine.send`.
- **No new module** — fits into existing Community surface; Jackson 3 is already present.
- **No new SPI tier** — the lower-level engine boundary stays implementation-blind; this is a higher-level façade.

### Negative / costs

- Community module gains a public-facing class outside its `eu.exeris.kernel.community.*` namespace convention. Justified by generator-import stability and ADR-008 capability-licensing taxonomy — `ExerisWebClient` is `community` tier (free, open-core).
- Jackson 3 binding is hardcoded. Alternative JSON libraries (Gson, Moshi, native Jackson 2) would require a parallel client class or an additional dependency-injection seam. Deferred — Jackson 3 covers ≥ 95% of JVM JSON workloads.
- The `http3` package name remains semantically misleading until the H3 client engine actually lands. Acceptance per "Package naming" section above.

### Neutral / open

- A **future H3 client engine** (Enterprise) can plug into `ExerisWebClient` unchanged — the `HttpClientEngine` SPI is protocol-agnostic by design.
- A `KernelProviders.WEB_CLIENT` `ScopedValue` binding could publish a default client for subsystem consumers, but is **out of scope for 0.8.0**. Application code or codegen output constructs `ExerisWebClient` explicitly today. ScopedValue binding deferred to v0.9 or later if a use case emerges.

## Alternatives considered

### A) Layer the typed API on `HttpClientEngine` SPI directly

Add `<T> T sendJson(...)` / similar helper methods to `HttpClientEngine` interface. Rejected — would force every engine implementation to ship Jackson or some binding, breaking SPI implementation-blind invariant.

### B) New `exeris-kernel-client` module

Carve out a dedicated module to host `ExerisWebClient` + its Jackson binding. Rejected — adds a module to the kernel POM tree (CI cycles + Maven Central artifact + module-info bookkeeping) for a single public class. Premature modularisation. Can be promoted later if the client surface grows.

### C) Provide the client via `exeris-sdk` instead

Move `ExerisWebClient` to the SDK module (which already ships annotations + source model + UI kit). Rejected — `exeris-sdk` is build-time and design-time tooling. The web client is a **runtime** concern (it lives behind a live `HttpClientEngine`). Cross-cutting it into the SDK breaks the build-time / runtime split.

### D) Higher-level entity-shaped surface on `ExerisWebClient` (`findById` / `findAll` / `create` / `update` / `delete`)

Earlier sprint-map sketches proposed methods like `<T> Optional<T> findById(String basePath, UUID id, Class<T> entityType)`. Rejected after reading the actual `KernelClientGenerator` emission: the generator already emits the typed entity-shaped wrappers (`UserClient.findById`) on top of HTTP-verb primitives. Putting entity-shaped methods on `ExerisWebClient` would duplicate the abstraction layer and force every non-generator consumer to use the entity API even when raw HTTP verbs suffice. The cleaner split is: **`ExerisWebClient` provides HTTP verbs + JSON; generator emits entity wrappers**.

## Implementation plan (v0.8 Sprint 2)

- **PR #1 (this PR)** — `ExerisWebClient` + `WebClientException` + `AbstractExerisWebClientTck` + `CommunityExerisWebClientTckTest` + ADR-026 + CHANGELOG entry + ROADMAP entry.
- **Follow-up** — register `ADR-026` row in `~/exeris-systems/exeris-docs/adr-index.md` as a separate commit on the docs repo (per global namespace policy).
- **Cross-repo follow-up** — `exeris-benchmarks/community-app` should add a client-variant benchmark validating the round-trip path under load (out of kernel CI scope; tracked on the benchmarks repo's cadence).

## References

- [HttpClientEngine.java](../../exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpClientEngine.java) (SPI surface)
- [CommunityHttpClientEngine.java](../../exeris-kernel-community/src/main/java/eu/exeris/kernel/community/http/CommunityHttpClientEngine.java) (Community impl)
- [KernelClientGenerator.java](https://github.com/exeris-systems/exeris-tooling/blob/main/exeris-codegen-java/src/main/java/eu/exeris/tooling/codegen/java/kernel/KernelClientGenerator.java) (downstream consumer)
- ADR-008 — Open-Core Strategy & Commoditization of Off-Heap TLS
- ADR-009 — HTTP Codec module
