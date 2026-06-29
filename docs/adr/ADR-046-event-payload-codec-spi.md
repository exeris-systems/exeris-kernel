# ADR-046: Event-Payload Codec SPI — a pluggable serialization seam for domain-event payloads

| Attribute       | Value                                                                                                    |
|:----------------|:---------------------------------------------------------------------------------------------------------|
| **ADR #**       | **046** (reserved 2026-06-29 in `exeris-docs/adr-index.md`). |
| **Status**      | **DRAFT — number reserved; targeted v0.10** (alongside the Events `topic` item). Kernel SPI + Community driver + TCK landing this PR; the `exeris-tooling` generator + bootstrap slot-binding follow lockstep. Flips to **Accepted** once the lockstep consumer lands. |
| **Deciders**    | Arkadiusz Przychocki                                                                                      |
| **Date**        | 2026-06-28                                                                                                |
| **Scope**       | kernel/events (per-repo; lockstep cross-repo coordination with `exeris-tooling` for the generated publisher) |
| **Owning Repo** | `exeris-kernel`                                                                                           |
| **Driven By**   | The EV1 generated-event-payload lever (`@DomainEvent` payload realization) — its runtime half is blocked by a missing kernel seam |
| **Compliance**  | The Wall (ADR-006); No Waste Compute; Java-26 idioms (ScopedValue, immutable carriers); mirrors the proven body-codec shape (ADR-009 / ADR-034 / ADR-036) |

## Context

The EV1 generation lever (`@DomainEvent` payload realization) is blocked at its **runtime** half by a
missing kernel seam. The design-time / metadata half (the payload-field metadata
`DomainEventMetadata.payloadFields` / `sensitiveFields`, processor extraction, `-io` parity, and the
typed-TS-payload fix) is being landed independently in `exeris-sdk` / `exeris-tooling` and does **not**
require this SPI. This ADR is strictly the **runtime serialization seam** that unblocks the Java
`*EventPublisher` emitting real payload bytes.

Verified against the current source tree:

- **The 2×2 codec seam that exists today is HTTP-only.** ADR-009 / ADR-034 / ADR-036 give the kernel a
  complete `{request,response} × {encode,decode}` matrix of HTTP **body** codecs
  (`HttpRequestBodyDecoder` + `HttpRequestBodyDecoderRegistry`, etc.), registry-selected by
  `(targetType, contentType)` with a Community JSON default. It is bound to the HTTP request/response
  path and does **not** cover domain-event payloads.
- **The event bus takes already-serialized bytes, not a typed payload.**
  `EventBus.publish(EventDescriptor, EventPayload)` (`exeris-kernel-spi/.../events/EventBus.java:66`)
  accepts an `EventPayload` that wraps a **read-only** `MemorySegment` of already-serialized bytes
  (`CommunityHeapEventPayload` wraps a `byte[]`); the outbox path persists `byte[]`. Serialization must
  therefore happen **before** the bus is called, and there is **no kernel seam to do it** — so the
  generated `*EventPublisher` ships `EventPayload.empty()` (zero bytes) and generated events carry no
  data.
- **The events subsystem already has a provider-registry channel.** Event components are reached via the
  central `KernelProviders` ScopedValue registry — `EVENT_ENGINE`, and the optional, since-0.7.0
  `EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER` slots (`exeris-kernel-spi/.../context/KernelProviders.java`).
  That is the existing precedent a codec-registry slot rides — **not** `HttpKernelProviders`, which is the
  HTTP-transport-private carrier.

The founder's intent for EV1 is explicit: **payload serialization should be client-selectable
(JSON / gRPC / other), JSON by default** — not baked into generated code. Realizing that for events
requires introducing the event-side analog of the body-codec seam, faithful to how ADR-036 closed the
request-decode quadrant: a tier-neutral SPI, a Community JSON driver, and resolution **in generated
code** (the publisher) rather than inside the kernel dispatch path.

## 🏁 The Decision (proposed)

**Introduce an event-payload codec SPI in `exeris-kernel-spi`, mirroring the proven ADR-036 shape, with a
Community JSON default, resolved by the generated publisher — leaving the `EventBus` / `EventEngine`
runtime API untouched.** The change is strictly additive: SPI types + one Community driver + one optional
`KernelProviders` slot + TCK.

### 1. SPI surface (new, in `eu.exeris.kernel.spi.events.codec`)

```java
// Encodes a typed/structured payload to bytes, and decodes back. JSON default; gRPC/other pluggable.
public interface EventPayloadCodec {
    boolean supports(Class<?> payloadType, String contentType);   // mirrors HttpRequestBodyDecoder.supports
    EventPayload encode(Object payload, EventCodecContext ctx);    // → bytes-backed EventPayload (RAII-owned)
    Object decode(EventPayload payload, Class<?> targetType, EventCodecContext ctx);
    default int priority() { return 0; }
}

// Functional registry: resolve a codec by payload type + content type, descending priority.
@FunctionalInterface
public interface EventPayloadCodecRegistry {
    EventPayloadCodec resolve(Class<?> payloadType, String contentType);   // first supports() wins; null if none
    static EventPayloadCodecRegistry empty() { return (t, ct) -> null; }
    static EventPayloadCodecRegistry of(List<EventPayloadCodec> codecs) { /* copy of HttpRequestBodyDecoderRegistry.of */ }
}

// Minimal, Valhalla-ready carrier: the requested content-type (+ the event-type name for diagnostics).
public record EventCodecContext(String contentType, String eventTypeName) { /* null-checks */ }
```

- **Selection** is by `(payloadType, contentType)` against registered codecs by descending `priority()`,
  first `supports(...) == true` wins, `null` when none match — **byte-for-byte the
  `HttpRequestBodyDecoderRegistry.of(...)` contract** (snapshot, stable descending sort, ties by
  registration order). Default content-type `application/json`.
- **The context is format-only.** `EventCodecContext` carries the requested content-type and the
  event-type name (diagnostics) — **no** `sensitiveFields`, no domain types, no framework types.
  Redaction (whitelist `payloadFields` / drop `sensitiveFields`) is the **generated publisher's** job,
  applied when it builds the payload object, *before* encode (see §4). Pushing redaction into the codec
  context would couple a domain concern into the serialization seam and break the grep-symmetry with the
  HTTP body codecs.
- **Driver-exception opacity (The Wall).** Implementations MUST wrap binding-specific exceptions (Jackson
  `JacksonException`) into a JDK-standard `java.*` `RuntimeException` before returning — no driver-package
  exception type crosses the SPI boundary (testable-as-written, exactly as `AbstractHttpRequestBodyDecoderTck`
  asserts).

### 2. Wiring — one optional `KernelProviders` slot (the events precedent, not HTTP)

The registry is exposed at kernel scope via a new **optional** slot on the central provider registry,
mirroring the since-0.7.0 `EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER` shape:

```java
// KernelProviders
public static final ScopedValue<EventPayloadCodecRegistry> EVENT_PAYLOAD_CODEC_REGISTRY =
        ScopedValue.newInstance();

public static Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
    return EVENT_PAYLOAD_CODEC_REGISTRY.isBound()
            ? Optional.of(EVENT_PAYLOAD_CODEC_REGISTRY.get())
            : Optional.empty();
}
```

The slot ships **defined-but-unbound** in this kernel PR (its accessor + the Community driver + TCK land here),
then is **bound by the bootstrapper** before `EventEngine.start()` in the lockstep step that brings the
consumer online — exactly the `EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER` precedent (slots wired for
hand-off in 0.7.0, bound when a driver/consumer lands). **ScopedValue, never ThreadLocal.** The slot is
optional and additive — a kernel without a codec binding still bootstraps events (the publisher falls back to
`EventPayload.empty()`, i.e. today's behaviour) — and once bound is inherited by every virtual thread in the
kernel scope, including the publish-path threads where the generated publisher runs.

### 3. Core / Community

- A `CommunityJsonEventPayloadCodec` (the default JSON provider) — reuses the same Jackson path the HTTP
  JSON codecs use (`JsonBodyCodecs`); `supports(any, "application/json")` plus `application/*+json`,
  tolerant of `null`/empty content-type. Shipped this PR with the `AbstractEventPayloadCodecTck` Community
  binding; gathered into a registry and populated into the `EVENT_PAYLOAD_CODEC_REGISTRY` slot at scope init
  in the lockstep bootstrap step (per §2). **The Wall holds:** SPI in `-spi`, JSON impl in `-community`,
  Core stays codec-agnostic (no provider import).
- **No `EventBus` / `EventEngine` change.** The runtime bus still takes `EventPayload` bytes. Encoding
  happens upstream, in the generated publisher (§4) — exactly the ADR-036 "site B" decision (the
  *generated handler* resolves, not the kernel router).

### 4. Resolution site (B): the GENERATED publisher resolves — not the kernel bus

Mirroring ADR-036 §2 (rejected "site A" = kernel resolves; accepted "site B" = generated code resolves),
`KernelEventGenerator`'s `*EventPublisher.publish<Event>(…)`:

1. builds a payload object (a generated per-event payload record, or `Map<String,Object>`) from
   `DomainEventMetadata.payloadFields`, **already redacting** `sensitiveFields` here;
2. resolves a codec via `KernelProviders.eventPayloadCodecRegistry()` (default content-type
   `application/json`) — **naming no concrete codec**;
3. encodes to an `EventPayload` and calls the existing `bus.publish(descriptor, payload)`.

If the slot is unbound (no codec on the classpath), the publisher keeps today's `EventPayload.empty()`
fallback. The codec is chosen at runtime by the registered providers (JSON default; an app adds a
gRPC/Avro codec without regenerating). This is exactly the EV1 founder intent. **The generator change is a
separate, lockstep `exeris-tooling` PR gated on this ADR** (the SPI ships unused without it).

## Alternatives considered

- **Reuse the HTTP codec SPI (ADR-036) for events.** Rejected: it is semantically the HTTP
  request/response body path (different `ScopedValue` slots on `HttpKernelProviders`,
  `HttpRequestDecodingContext`, `LoanedBuffer` carriers). Overloading it onto events couples two unrelated
  transport concerns and breaks the contract boundary — the same reason ADR-036 kept request and response
  decoders as distinct types despite identical shape.
- **Resolve the codec inside `EventBus` / a new `EventEngine` publish facade (site A).** Rejected for the
  ADR-036 §2 reason: the typed payload + event-type already live in the generated publisher; threading the
  payload type into the bus would force a new API surface and pull type knowledge across the bus boundary
  for no benefit. Keeping resolution in generated code leaves the hot publish path (`EventBus.publish`)
  byte-for-byte unchanged.
- **Hardcode JSON in generated publishers.** Rejected: bakes a format into generated code (against the
  client-selectable intent) and re-creates the build-time Wall breach ADR-036 just removed for HTTP.
- **No payload (status quo).** Rejected: generated events carry no data — the EV1 gap.

## Consequences

### ✅ Positive

- **[+] Closes the EV1 runtime gap.** The generated publisher emits real payload bytes through a
  tier-neutral seam; events finally carry data.
- **[+] Faithful mirror of a 4×-proven shape.** Registry + driver + optional slot + generated-code
  resolution reuse the ADR-009/034/036 body-codec pattern verbatim; reviewers read it with zero ramp-up
  and the grep-symmetry is an invariant.
- **[+] The Wall holds at build time too.** No concrete-codec symbol enters generated application source;
  a future Enterprise codec (Panama JSON, CBOR, Protobuf, gRPC) drops in behind the same SPI.
- **[+] Strictly additive, hot path untouched.** No change to `EventBus` / `EventEngine`; `EventPayload`'s
  existing RAII/refcount contract is preserved (the codec returns an owned `EventPayload`; the bus manages
  broadcast lifetime as today).

### ⚠️ Trade-offs

- **[-] Lockstep with `exeris-tooling`.** The SPI ships unused until the `KernelEventGenerator` rewrite
  lands; the two are co-dependent (as ADR-036's kernel/tooling pair were).
- **[-] One more SPI surface to TCK.** A new `AbstractEventPayloadCodecTck` base + Community binding.
- **[-] Encode on the publish path.** Keep it allocation-lean; registry resolution is O(providers)
  descending priority — cache per `(type, contentType)` only if JFR shows it on a hot path.

### 📋 What is NOT in scope

- **Per-event content-type on the `EventDescriptor`.** `EventDescriptor` / `EventTypeSpec` are
  **primitive-only by contract** (Valhalla scalarization + O(1) zero-alloc dispatch — `events.md` §1), so
  a content-type cannot be a `String` field on the descriptor. v0.10 default is kernel-wide
  `application/json`, overridable by config. Per-event selection, **if ever** wanted, must be
  ordinal-interned via `EventRegistry` (the same constraint the `topic` roadmap item carries) — deferred.
- **Decode-side consumption (handler wiring).** The codec interface carries `decode(...)` for symmetry (a
  JSON codec is naturally bidirectional), but **wiring** the decode half into event *handlers* /
  projections (the `@EventHandler` consumers) is deferred to that consumer work — this ADR wires only the
  encode (publish) direction.
- **Redaction policy.** Whitelist/sensitive-field handling stays in the generated publisher (built from
  `DomainEventMetadata`), not in the codec.
- **Topic routing.** Orthogonal; covered by the separate v0.10 events "binding-agnostic `topic`" roadmap
  item (K1 / `@DomainEvent.topic`).

## Consequences & obligations (kernel discipline)

- **TCK-first:** `AbstractEventPayloadCodecTck` with semantic (not happy-path-only) assertions —
  round-trip per content-type, registry priority/resolution + tie-by-registration, unknown-type /
  unknown-content-type behaviour, RAII ownership of the returned `EventPayload`, and driver-exception
  opacity (`startsWith("java.")` / `doesNotStartWith("tools.jackson")`). Bound in Community against the
  JSON driver and **registered in CI** — no orphan `Abstract*Tck`.
- **The Wall / layering:** SPI in `-spi`, JSON impl in `-community`, Core codec-agnostic.
- **Java-26 idioms:** `ScopedValue` slot; immutable record `EventCodecContext`; no
  `ThreadLocal` / `ExecutorService` / `CompletableFuture` on the path; no off-heap copy beyond the existing
  `EventPayload` `MemorySegment` lifecycle.
- **JFR:** a codec-resolution / encode-failure JFR event on the cold/error path (secret-safe — no payload
  bytes), consistent with the events subsystem's JFR-first posture.

## Milestone & sequencing (decided)

- **Targeted v0.10**, sequenced with the sibling Events `topic` item (both touch the publish path and the
  SDK→codegen→kernel population chain). The ROADMAP entry is written alongside
  (§"Events: Event-Payload Codec SPI"). Rationale: a clean, low-risk, additive SPI with a fully-proven
  shape, and the EV1 metadata half has already shipped while the generated publisher still emits empty
  payloads — a real, live gap.
- **PR split:** the kernel SPI + Community driver + `AbstractEventPayloadCodecTck` + Community binding +
  this ADR + the `events.md` / ROADMAP updates land **this PR**. The bootstrap slot-binding and the
  `exeris-tooling` `KernelEventGenerator` publisher rewrite follow **lockstep** (the slot ships
  defined-but-unbound here, per the `EVENT_STREAM_*` precedent — §2).

## Open questions (for the founder)

1. **Typed payload vs `Map<String,Object>`** in the generated publisher — a generated per-event payload
   record (type-safe, more emitted code) vs a map (simpler, less type-safe). (Leaning: a generated payload
   record, consistent with the typed TS interface the metadata slice now emits.)
2. **Decode wiring** — confirm the symmetric `decode(...)` ships on the interface now (encode-only wired)
   vs deferring the method entirely until the `@EventHandler` consumer work. (Leaning: ship the method,
   wire only encode — as implemented.)

## Cross-references

- ADR-036 — Server-Side Request Body Decoder SPI — the shape this mirrors clause-for-clause (registry
  `of(...)`, driver-exception opacity, **site-B generated-code resolution**, TCK discipline).
- ADR-034 — Client-Side Body Codec SPI — the symmetric client-codec family the events seam parallels.
- ADR-009 — HTTP Codec module — the original server-encode quadrant anchoring the body-codec matrix.
- ADR-006 — The Wall — SPI implementation-blindness at runtime **and** build time (generated source carries
  no concrete-codec symbol).
- `exeris-kernel-spi/.../events/EventBus.java` / `EventPayload.java` — the publish API and RAII payload
  contract this seam feeds, left unchanged.
- `exeris-kernel-spi/.../context/KernelProviders.java` — the `EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER`
  optional-slot precedent the codec-registry slot mirrors.
- `docs/subsystems/events.md` — the events contract doc to update if this is accepted (descriptor
  primitive-only constraint; codec seam placement).
- ROADMAP §"Events: Binding-Agnostic `topic` Concept" (v0.10) — the sibling events item to sequence with.

## Engineering Protocol (if accepted)

1. **Reserve the global ADR number** in `exeris-docs/adr-index.md` and flip Status to Accepted (register
   discipline — number before content claims).
2. **Lockstep across repos.** PR-A `exeris-kernel` (load-bearing: SPI types + `CommunityJsonEventPayloadCodec`
   + `KernelProviders` slot/accessor + `AbstractEventPayloadCodecTck` + Community binding + this ADR + an
   `events.md` update). PR-B `exeris-tooling` (`KernelEventGenerator.*EventPublisher` rewrite: build the
   redacted payload object, resolve via `KernelProviders.eventPayloadCodecRegistry()`, encode, publish —
   carrying no concrete-codec symbol) + a `docs/adr/ADR-0xx.link.md` stub. PR-A alone ships the SPI unused;
   PR-B alone references a non-existent registry.
3. **TCK gate.** `AbstractEventPayloadCodecTck` bound in Community and registered in CI (not an orphan base).
4. **Generator-output assertion.** The `exeris-tooling` e2e fixture asserts the generated publisher contains
   no `tools.jackson.*` symbol and resolves via `KernelProviders.eventPayloadCodecRegistry()` — the
   build-time Wall check that encodes this ADR's central obligation.
5. **ROADMAP entry** written for the chosen milestone.
