# ADR-036: Server-Side Request Body Decoder SPI — `HttpRequestBodyDecoder` + Generated-Handler Resolution

**Status:** Accepted
**Date:** 2026-06-03
**Owner:** kernel/transport
**Visibility:** public
**Scope:** kernel/transport (per-repo; lockstep cross-repo coordination with `exeris-tooling`)
**Authors:** Arkadiusz Przychocki
**Driven By:** ADR-034 §187 unaddressed quadrant + build-time Wall breach in `KernelHandlerGenerator`

## Context

The body-codec design space is a 2×2 matrix: `{request, response} × {encode, decode}`. After ADR-034 landed, three of the four quadrants exist as tier-neutral SPI seams in `eu.exeris.kernel.spi.http`:

| Quadrant | SPI type | Since | Origin |
|:---|:---|:---|:---|
| response-encode | `HttpResponseBodyEncoder` | 0.5.0 | ADR-009 (server side) |
| request-encode | `HttpRequestBodyEncoder` | 0.8.0 | ADR-034 (client side) |
| response-decode | `HttpResponseBodyDecoder` | 0.8.0 | ADR-034 (client side) |
| **request-decode** | **— (none) —** | **—** | **this ADR** |

The fourth quadrant — **server-side request-body decode** (wire body → typed handler argument, e.g. `Widget` for `POST /widgets`) — has **no SPI seam at all**. Today it is hard-wired inside generated user code. `exeris-tooling`'s `KernelHandlerGenerator.buildParseBody` (`KernelHandlerGenerator.java:252`) emits a private generic `parseBody(exchange, type)` into every generated controller that:

1. reads `LoanedBuffer body = exchange.request().body()`,
2. `MemorySegment.copy` the segment into a freshly allocated `byte[]`,
3. `new String(bytes, UTF_8)`,
4. calls a static `MAPPER.readValue(json, type)` against a `tools.jackson.databind.ObjectMapper` field declared in the generated class (`KernelHandlerGenerator.java:101`),
5. catches `tools.jackson.core.JacksonException` (`KernelHandlerGenerator.java:63`, `:280`) and rewraps it as `IllegalArgumentException("Invalid JSON")`, which the call sites (`handleCreate`, `handleUpdate`) map to `400 BAD_REQUEST`.

That places a **concrete-driver dependency — a static Jackson `MAPPER` plus a `JacksonException` import — into every generated controller class an application ships.** The generator is tier-neutral by definition (it emits code that compiles against whichever kernel artifact the application's POM selects), yet its output pins Jackson into application source. This is precisely the build-time analogue of The Wall (ADR-006) that ADR-034 §39 named when it lifted the client facade out of the Community tier: *the kernel boundary visible to applications must be implementation-blind, at build time as much as at runtime.* ADR-036 completes that same principle in the one quadrant ADR-034 did not reach.

**This ADR does not overturn ADR-034's deferral.** ADR-034 §187 deferred unifying the *server-side encode* path — refactoring the working 0.5.0 `JsonBodyEncoder` so it subscribes a content-type the way the new client decoder does — as a "v1.0 cleanup" (ADR-034 Alternative B). That is a refactor of an existing, functioning SPI seam. Request-*decode* is a different quadrant entirely: it has *no* SPI seam today, only a hard-wired Jackson call inside generated code. The deferral ADR-034 recorded and the gap ADR-036 closes are orthogonal — one postpones tidying a seam that exists; the other introduces the seam that is missing.

The question this ADR answers: **how does a generated request handler decode a typed request body without baking a concrete codec driver into application source?**

## 🏁 The Decision

**Introduce the fourth and final body-codec quadrant — server-side request-body decode — as a tier-neutral SPI triplet in `eu.exeris.kernel.spi.http`, resolved by the generated handler (not the kernel router), wire it through `HttpProvider` + `HttpKernelProviders` exactly as ADR-034 wired the client decoder, ship a Jackson Community driver, and rewrite `KernelHandlerGenerator.buildParseBody` in lockstep so generated handlers carry no concrete-codec symbol.**

The SPI triplet mirrors the response-decoder triplet (`HttpResponseBodyDecoder` / `HttpResponseBodyDecoderRegistry` / `HttpResponseDecodingContext`) verbatim in shape, swapping only the response→request axis (status → method + path). Reviewers familiar with the client decoder read this side with zero ramp-up; the symmetry is a grep-checkable invariant.

**Concrete obligations:**

### 1. SPI surface (new, in `eu.exeris.kernel.spi.http`)

Three new types in `exeris-kernel-spi`, added at 0.8.0. Shape mirrors the response-decoder triplet exactly.

```java
// Request decoder — wire body → typed handler argument.
public interface HttpRequestBodyDecoder {
    boolean supports(Class<?> targetType, String contentType);
    Object decode(LoanedBuffer body, Class<?> targetType, HttpRequestDecodingContext context);
    default int priority() { return 0; }
}

// Request decoding context: method + path + headers + allocator.
public record HttpRequestDecodingContext(
        HttpMethod method,
        String path,
        List<HttpHeader> headers,
        MemoryAllocator allocator
) {
    public HttpRequestDecodingContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }
}

// Functional registry: resolve decoder by target type + content type.
@FunctionalInterface
public interface HttpRequestBodyDecoderRegistry {
    HttpRequestBodyDecoder resolve(Class<?> targetType, String contentType);
    static HttpRequestBodyDecoderRegistry empty() { return (type, ct) -> null; }
    // Descending-priority resolver; ties by registration order (copy of HttpResponseBodyDecoderRegistry.of).
    static HttpRequestBodyDecoderRegistry of(List<HttpRequestBodyDecoder> decoders) { /* … */ }
}
```

Contract clauses inherited **verbatim** from `HttpResponseBodyDecoder` (see `HttpResponseBodyDecoder.java:13-49`), substituting the request direction where relevant:

- **Generics-free by design.** `decode` returns `Object` and accepts `Class<?>`, not `<T>`. The single `@SuppressWarnings("unchecked")` cast lives at the resolution call-site (the generated handler — §2), exactly as the client side confines it to `KernelWebClient.decodeSuccessBody` (`KernelWebClient.java:234`, ADR-034 §3). A generics-free SPI surface eases alternative bindings (Jackson `TypeReference`, Protobuf descriptors) without `<T>` propagation.
- **Never sees `Void.class`.** The resolution site short-circuits bodyless / `Void` requests before invoking the decoder (the generated handler only calls `parseBody` for verbs that carry an entity body — `handleCreate` / `handleUpdate`).
- **Content-type tolerance.** Implementations MUST tolerate `contentType == null` or empty (client omitted the header); the registry may still route a decoder claiming support for `targetType`.
- **Driver exception wrapping.** Implementations MUST wrap binding-specific exceptions (Jackson `JacksonException`) into a generic `RuntimeException` (typically `IllegalStateException`) before returning. No driver-specific exception type crosses the SPI boundary.
- **Body ownership.** Implementations MUST NOT close, retain, or otherwise extend the lifetime of the `LoanedBuffer`. The caller (the generated handler) owns the buffer's lifecycle — the request body buffer is owned by the transport/codec and released when the exchange ends, per the existing `HttpRequest` contract the generator already documents (`KernelHandlerGenerator.java:261`).

`HttpRequestDecodingContext` carries `(method, path, headers, allocator)` — the server-side mirror of `HttpResponseDecodingContext`'s `(status, headers, allocator)` (see `HttpResponseDecodingContext.java:29`). The status field is replaced by `method` + `path` because the decoder runs on the inbound request, not on a response. The context carries **no Core types** — no `HttpExchange`, no router carrier — so the SPI surface stays implementation-blind (The Wall, ADR-006).

`HttpRequestBodyDecoderRegistry.of(List<…>)` is a byte-for-byte copy of `HttpResponseBodyDecoderRegistry.of` (`HttpResponseBodyDecoderRegistry.java:62`): snapshot, stable descending sort on `priority()`, ties preserved by insertion order, first `supports(...) == true` candidate wins, `null` when none match.

### 2. Resolution site (B): the GENERATED handler resolves — not the kernel router

The generated handler reads a kernel-provided `HttpRequestBodyDecoderRegistry` and resolves per call:

```java
HttpRequestBodyDecoder decoder = requestBodyDecoderRegistry.resolve(type, contentType);
if (decoder == null) {
    // No decoder registered for this target type / content type. This is a
    // server-side configuration gap (the application failed to register a
    // decoder its own generated handler needs), NOT a client error — it must
    // not become a 400. Unreachable in the default-wired path: the Community
    // JSON decoder tolerates null/empty content-type and supports any bean type.
    throw new IllegalStateException("No request body decoder for " + type + " / " + contentType);
}
try {
    return (T) decoder.decode(body, type, ctx);   // single @SuppressWarnings("unchecked") cast here
} catch (RuntimeException e) {
    // Decode failure on a present decoder = the client sent an undecodable body.
    // Re-wrap into the IllegalArgumentException the call sites already map to 400.
    throw new IllegalArgumentException("Invalid request body", e);
}
```

replacing the static `MAPPER.readValue(json, type)` call. Two candidate sites were weighed:

- **Site (A) — the kernel `HttpRouter` pre-resolves and pre-decodes.** Rejected. The router dispatches `(method, path) → opaque HttpHandler` and does **not** know per-route payload target types. Only the generated handler holds the type — it calls `parseBody(exchange, Widget.class)` with the concrete class literal (`KernelHandlerGenerator.java:171`, `:200`). Threading payload types into the router would require a new route→type SPI carrier and would force the router to pre-decode bodies for routes that may legitimately short-circuit before reading the body (auth rejection, 404). That is a larger, worse-shaped change that pulls type knowledge across the router boundary for no benefit.
- **Site (B) — the generated handler resolves.** Accepted. The type literal already lives in generated code; the only change is *what* `parseBody` delegates to. The router stays type-blind; bodies are decoded only when a handler actually consumes them.

**Status mapping is deliberate, and it is the generated handler's job — not the SPI's.** The SPI stays HTTP-agnostic: a decoder throws a generic `RuntimeException` on undecodable input (§1) and carries no notion of status codes. The generated `parseBody` owns the mapping, exactly as it does today, and the rewrite preserves the observable contract by an explicit `try/catch`:

- **Decode failure on a resolved decoder → `400 BAD_REQUEST`.** A malformed body is a *client* error. `parseBody` catches the decoder's wrapped `RuntimeException` and re-throws `IllegalArgumentException`, which `handleCreate` / `handleUpdate` already map to 400 (`KernelHandlerGenerator.java:172`, `:281`) — byte-for-byte the observable result of today's `JacksonException` → `IllegalArgumentException("Invalid JSON")` path. Without this explicit catch the mapping would silently flip to 500, because the driver wraps into `IllegalStateException` (§1, §5), which the 400 arm does not match.
- **No decoder resolved (`resolve(...) == null`) → server-side error (5xx).** Distinct from a decode failure: the client is not at fault for the server lacking a codec for a type its own generated handler declares. `parseBody` throws `IllegalStateException`, which is NOT on the 400 arm. This is unreachable on the default-wired path (the Community JSON decoder tolerates null/empty content-type and supports any bean type), so it surfaces only a genuine bootstrap misconfiguration. ADR-036 deliberately does **not** introduce HTTP content-type negotiation (`415 Unsupported Media Type`) in 0.8.0 — that would be new observable behaviour beyond closing the quadrant; it is noted as a possible v0.9 refinement in §"What is NOT in scope".

Net: the observable HTTP contract of generated handlers is unchanged on every path that exists today; only the codec seam moves behind the SPI.

### 3. No-Waste-Compute: consume the segment directly

The SPI decoder receives the `LoanedBuffer` / `MemorySegment` directly. The current generated path performs a double allocation on the server ingress hot path — `byte[]` copy of the segment, then `new String(bytes, UTF_8)` — before handing a `String` to Jackson (`KernelHandlerGenerator.java:273-279`). Routing through the SPI lets a driver read the off-heap segment without the intermediate `String`.

This is recorded as a **contract expectation on the Community driver**: it MUST read the segment (or a single `byte[]` copy) directly and MUST NOT re-introduce the `byte[] + String` double-allocation internally — otherwise the No-Waste-Compute win is lost at the driver and the SPI move buys only Wall hygiene. The Community driver decoding from `body.segment()` straight into `mapper.readValue(bytes, targetType)` mirrors `CommunityJsonResponseBodyDecoder.decode` (`CommunityJsonResponseBodyDecoder.java:78-95`), which already consumes the segment with a single copy and no `String`.

### 4. Binding / wiring — mirror ADR-034

One default method on `HttpProvider`, one `ScopedValue` slot plus `Optional` accessor on `HttpKernelProviders`, identical in shape to the response-decoder wiring (`HttpProvider.java:154`, `HttpKernelProviders.java:119` / `:188`):

```java
// HttpProvider
default Optional<HttpRequestBodyDecoderRegistry> requestBodyDecoderRegistry() {
    return Optional.empty();
}

// HttpKernelProviders
public static final ScopedValue<HttpRequestBodyDecoderRegistry> HTTP_REQUEST_BODY_DECODER_REGISTRY =
        ScopedValue.newInstance();

public static Optional<HttpRequestBodyDecoderRegistry> httpRequestBodyDecoderRegistry() {
    return HTTP_REQUEST_BODY_DECODER_REGISTRY.isBound()
            ? Optional.of(HTTP_REQUEST_BODY_DECODER_REGISTRY.get())
            : Optional.empty();
}
```

Bootstrap source = `CommunityHttpProvider.requestBodyDecoderRegistry()`. The generated handler reads the registry via `HttpKernelProviders.httpRequestBodyDecoderRegistry()`. This **closes the request-decode half of ADR-034 §322's deferred open item** ("a future ADR may opt to read the `ScopedValue` slot") — the server side is exactly where the generated handler has no construction seam of its own, so reading the slot at handler-construction time is the natural binding. (The client-decode half of §322 — auto-binding `KernelWebClient` from the slot — remains deferred; this ADR does not touch it.)

The slot is **optional**: applications without typed request-body binding still bootstrap HTTP. Generated handlers that never decode a body (read-only resources) do not require the slot to be bound.

### 5. Community driver

`CommunityJsonRequestBodyDecoder` in `eu.exeris.kernel.community.http`, byte-for-byte the `CommunityJsonResponseBodyDecoder` shape (`CommunityJsonResponseBodyDecoder.java`):

- constructor `(ObjectMapper mapper)`;
- `supports(Class<?>, String)` matches `application/json` plus `application/*+json` structured-syntax suffix per RFC 6838 §4.2.8, tolerating `null` / empty content-type;
- `decode` copies the segment once and calls `mapper.readValue(bytes, targetType)`, wrapping `JacksonException → IllegalStateException`;
- never closes or retains the buffer.

Jackson 3 descends to the driver; the SPI never sees `ObjectMapper` (The Wall — ADR-034 §171 / §226 precedent). A future Enterprise driver (Panama-native JSON, CBOR, Protobuf) ships its own implementation behind the same SPI without touching SPI, Core, or the generator.

### 6. Cross-repo split (lockstep)

- **`exeris-kernel` (load-bearing):** 3 SPI types + `HttpKernelProviders` slot/accessor + `HttpProvider.requestBodyDecoderRegistry()` default + `CommunityHttpProvider.requestBodyDecoderRegistry()` override + `CommunityJsonRequestBodyDecoder` + `AbstractHttpRequestBodyDecoderTck` + Community binding test + this ADR.
- **`exeris-tooling`:** `KernelHandlerGenerator.buildParseBody` rewrite (drop the `MAPPER` field, the `OBJECT_MAPPER` / `JACKSON_EXCEPTION` `ClassName` constants, and the `byte[] + String` path; resolve via `HttpKernelProviders.httpRequestBodyDecoderRegistry()`), e2e fixture update, and a `docs/adr/ADR-036.link.md` stub. This is a **generated-output contract change** and is **lockstep** — without it the SPI ships unused and the generated Wall breach persists.
- **`exeris-kernel-enterprise`:** one-line `docs/adr/ADR-036.link.md` stub confirming the Enterprise tier is unaffected beyond the link-stub courtesy.

### 7. TCK

`AbstractHttpRequestBodyDecoderTck`, mirroring `AbstractHttpResponseBodyDecoderTck` (`exeris-kernel-tck/src/test/java/eu/exeris/kernel/tck/contract/http/AbstractHttpResponseBodyDecoderTck.java`). It asserts:

- `supports()` content-type matrix — `application/json`, `application/*+json`, and `null`/empty tolerance;
- round-trip decode of a known type;
- empty-body tolerance;
- null-content-type tolerance;
- Jackson-exception-wrapped-as-`RuntimeException` opacity (no driver type escapes);
- priority ordering via two stub decoders.

Bound in Community against `CommunityJsonRequestBodyDecoder` and **actually registered in CI** — no orphan `Abstract*Tck` (memory: `project_v080_coverage_audit` — unbound `Abstract*Tck` bases are a standing P0; this one ships bound).

## Consequences

### ✅ Positive Outcomes

- **[+] Build-time Wall breach removed.** Generated controllers no longer declare a static Jackson `MAPPER` field or import `JacksonException`. The kernel boundary visible in generated application source becomes implementation-blind — completing the ADR-034 §39 / ADR-006 principle in the last quadrant.
- **[+] The body-codec matrix is complete.** All four `{request,response}×{encode,decode}` quadrants are now tier-neutral SPI seams. The grep-symmetry property (response triplet ↔ request triplet) holds on both sides of the wire.
- **[+] No-Waste-Compute on server ingress.** The decoder consumes the off-heap segment directly; the `byte[] + String` double-allocation on the hot path is eliminated (subject to the §3 driver contract).
- **[+] Jackson stays a driver detail.** The generator emits no concrete-codec symbol; a future Enterprise request decoder (Panama JSON, CBOR, Protobuf) drops in behind the same SPI with no generator, Core, or SPI change.
- **[+] ADR-034 §322 request-decode open item closed.** The generated handler reads `HttpKernelProviders.httpRequestBodyDecoderRegistry()` — the slot is no longer a dormant bootstrap channel on the request-decode direction.

### ⚠️ Trade-offs

- **[-] One-time lockstep across three repos.** `exeris-kernel` (SPI + driver + TCK + ADR), `exeris-tooling` (generator rewrite + e2e fixtures + link stub), `exeris-kernel-enterprise` (link stub). The generator change is mandatory, not optional — shipping the SPI without it leaves the breach in place and the SPI unused.
- **[-] Pre-rewrite generated handlers depend on the old `MAPPER` path.** Any controller emitted before the generator rewrite still carries the static Jackson field. Pre-1.0 (TRL-3), no external SPI consumers — blast radius is in-flight feature branches and local fixtures; re-running the generator against the new kernel artifact regenerates clean output.
- **[-] Three more SPI types to TCK against.** One new Abstract TCK base + one Community binding test, mirroring the existing response-decoder TCK pattern. Not a new pattern, but new lines of TCK surface.

### 📋 What is NOT in scope

- **Server-side encode unification (ADR-034 §187 / Alt B) stays deferred.** ADR-036 closes the missing request-*decode* quadrant; it does not refactor the working 0.5.0 `JsonBodyEncoder` server-encode path. The two are orthogonal (see Context).
- **Client-side auto-binding (ADR-034 §322).** ADR-036 reads the request-decode `ScopedValue` slot from the generated handler; it does not change how `KernelWebClient` acquires its registries on the client side.
- **A unified `HttpBodyDecoder` for both request and response.** Rejected for the same reason ADR-034 Alt B kept the types separate: the decoding contexts (request `method`+`path` vs response `status`) carry materially different fields. The symmetric *pattern* is preserved; the *types* stay distinct.
- **`HttpRouter`-level type resolution (site A).** Rejected in §2; no route→type SPI carrier is introduced.
- **HTTP content-type negotiation (`415 Unsupported Media Type`).** ADR-036 preserves the current behaviour: a present-but-failing decoder → `400`, an unregistered decoder → `5xx` server misconfiguration (§2). Returning `415` when a request's content-type matches no registered decoder is correct REST semantics but is *new* observable behaviour — deferred to a possible v0.9 refinement once content-type-driven routing has a use case.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall) — the build-time analogue this ADR enforces in generated handler output; no concrete-codec symbol crosses into application source.
- ADR-009 — HTTP Codec module — establishes `eu.exeris.kernel.core.http.*` and the 0.5.0 `HttpResponseBodyEncoder` server-encode quadrant that anchors the matrix; the symmetric server-side precedent this ADR's request-decode quadrant sits beside.
- ADR-034 — Client-Side Body Codec SPI (`HttpRequestBodyEncoder` / `HttpResponseBodyDecoder` + `KernelWebClient`) — the symmetric client-codec sibling; ADR-036's request-decode triplet mirrors ADR-034's response-decode triplet verbatim. §39 (build-time Wall reasoning), §187 (server-encode deferral — explicitly NOT overturned), §322 (`ScopedValue` slot open item — request-decode half closed here), §3 (generics-free SPI + confined cast).
- ADR-020 — Open-Core Documentation Boundary & Cross-Repo Mirror Policy — public visibility classification and the `.link.md` stub convention for the two consuming repos.
- `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpResponseBodyDecoder.java` — the response-side contract this ADR's `HttpRequestBodyDecoder` mirrors clause-for-clause.
- `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpResponseBodyDecoderRegistry.java` — `of(List)` priority-ordering implementation copied verbatim.
- `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/http/HttpResponseDecodingContext.java` — context record the request-side record mirrors (status → method+path).
- `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/http/CommunityJsonResponseBodyDecoder.java` — driver shape `CommunityJsonRequestBodyDecoder` copies byte-for-byte.
- `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/http/client/KernelWebClient.java:234` — the symmetric client-side confined-cast resolution site.
- `exeris-tooling/exeris-codegen-java/src/main/java/eu/exeris/tooling/codegen/java/kernel/KernelHandlerGenerator.java:252` (`buildParseBody`) — downstream consumer; rewritten in lockstep to resolve via the SPI registry.

## Engineering Protocol

This ADR is a corrective decision (it removes an existing breach), not purely descriptive — a migration lands with it.

1. **Lockstep release across three repos** (v0.8 Sprint 7). PR-A `exeris-kernel` (load-bearing: SPI + driver + TCK + ADR). PR-B `exeris-tooling` (generator rewrite + e2e fixtures + link stub — generated output references the SPI registry, not Jackson). PR-C `exeris-kernel-enterprise` (link stub). PR-A and PR-B are co-dependent: PR-A alone ships the SPI unused; PR-B alone references a non-existent registry.
2. **TCK gate.** `AbstractHttpRequestBodyDecoderTck` is bound in Community against `CommunityJsonRequestBodyDecoder` and registered in CI — not an orphan `Abstract*Tck`.
3. **Generator-output assertion.** The `exeris-tooling` e2e fixture asserts the generated handler contains no `tools.jackson.*` symbol and resolves via `HttpKernelProviders.httpRequestBodyDecoderRegistry()` — the build-time Wall check that encodes this ADR's central obligation.
4. **Driver No-Waste-Compute contract.** `CommunityJsonRequestBodyDecoder` reads the segment with a single copy and no intermediate `String`; reviewers reject any driver that re-introduces the `byte[] + String` double-allocation (§3).
