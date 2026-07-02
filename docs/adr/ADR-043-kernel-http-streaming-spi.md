# ADR-043: Adopt SSE-First Kernel HTTP Streaming via a Sibling `HttpStreamExchange`

| Attribute       | Value                                                                                              |
|:----------------|:--------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                       |
| **Deciders**    | Arkadiusz Przychocki                                                                               |
| **Date**        | 2026-06-21                                                                                         |
| **Scope**       | `kernel/transport` (cross-repo obligations: `exeris-sdk`, `exeris-tooling`, `exeris-kernel-enterprise`) |
| **Owning Repo** | `exeris-kernel`                                                                                    |
| **Driven By**   | [RFC-2026-06-18](../rfc/RFC-2026-06-18-http-streaming-spi.md) (Kernel HTTP server-push / streaming SPI) |
| **Compliance**  | No Waste Compute; The Wall (ADR-006); fail-closed runtime (ADR-012 §5)                             |

## Context and Problem Statement

The kernel's HTTP surface has no way to push server-initiated updates to a client. `HttpExchange.respond(...)` is *respond-once*: it writes the outbound response exactly once and throws `IllegalStateException` on a second call. There is no SSE / chunked-append / flush / WebSocket affordance anywhere in `eu.exeris.kernel.spi.http`. The transport is strictly request/response. Meanwhile the SDK already advertises the feature (`@ExerisDomain(realTimeApi = true)`, `@Action(streaming = true, …)`) and tooling parses it into metadata — but those attributes are inert because no kernel primitive exists to bind to. The whole SDK → tooling streaming chain is dead-ended on this missing primitive.

The dominant requirement is unidirectional **server → client** push (incremental results of a long-running server-side process — an event-sourced aggregate advancing, a projection rebuilding — observed live, often by several clients on the same stream). The client → server direction is already the *request* side (a `POST`). This is a small additive primitive, not a full-duplex protocol.

RFC-2026-06-18 investigated transport (SSE vs WebSocket) and SPI shape (streaming mode on `HttpExchange` vs a sibling exchange), recommended **Option 1 — SSE-first over a sibling `HttpStreamExchange`**, and enumerated eight load-bearing design questions that an ADR must resolve before implementation, because leaving any open would yield ambiguous or contradictory tier bindings. This ADR is the architect sign-off that resolves all eight and fixes the contract for the `Abstract*Tck`.

The question this ADR answers: **what is the exact kernel SPI contract for server-initiated HTTP streaming, such that it is implementation-blind, zero-copy, fail-closed, and leaves the respond-once invariant untouched?**

## 🏁 The Decision

**Introduce a sibling `HttpStreamExchange` SPI in `eu.exeris.kernel.spi.http` that pushes events over Server-Sent Events (SSE), selected per-route from streaming metadata, leaving the respond-once `HttpExchange` invariant intact and unconditional. WebSocket is explicitly deferred to a later, separately-justified decision.**

SSE rides the existing HTTP/1.1 + h2 server with no upgrade handshake and no new frame protocol; framing is tier-blind (Core), held-open transport mechanics are tier-specific (Community NIO, Enterprise native/io_uring). Streaming is an opt-in surface; a handler that never opted in keeps the exact request/response contract it has today.

**Concrete obligations** (each detectable at review time as a yes/no):

1. **Sibling surface, not a mode.** `HttpStreamExchange` is a *distinct* interface in `eu.exeris.kernel.spi.http`. No streaming method is added to `HttpExchange`; its respond-once contract is unchanged. A PR that adds `openEventStream()` (or any emit/flush affordance) to `HttpExchange` violates this ADR.
2. **Event-shaped, implementation-blind carrier.** Handlers emit a `StreamEvent` record — `record StreamEvent(String event, String data, String id, long retryMillis)` — mapping directly to the SSE wire fields (`event:` / `data:` / `id:` / `retry:`), where `data` is required (non-null) and `event`/`id` are nullable and `retryMillis <= 0` means *absent*. It carries no wire/transport types (no chunk headers, no frame buffers, no `text/event-stream` literal in the SPI). The carrier is an immutable `record` (Valhalla-ready, no identity ops).
3. **Disconnect = `emit()` throws `StreamClosedException` (unchecked).** The handler interface is `@FunctionalInterface HttpStreamHandler { void handle(HttpStreamExchange exchange); }`. The handler body is an imperative emit loop; on client disconnect or dead key, the *next* `emit()` throws `StreamClosedException extends RuntimeException` — the loop unwinds naturally and the engine runs teardown. There is no `awaitDisconnect()` and no parked-VT leak. `StreamClosedException` is **unchecked** (CLAUDE.md bans checked exceptions on hot state-machine paths).
4. **`emit()` parks the VT under backpressure — never an on-heap queue.** When the egress window (TLS record / H2 window) is full, `emit()` blocks the calling virtual thread until window credit is available, exactly as `NativeTcpStream`'s response-body write loop already does. It MUST NOT buffer to an unbounded heap queue (No Waste Compute). The SPI Javadoc MUST state this and that callers run on a virtual thread (one VT per stream, mirroring "1 VT per request").
5. **`emit()` `LoanedBuffer` ownership = transfer-to-engine.** Where a `StreamEvent` payload is carried by / framed into a `LoanedBuffer`, the engine takes ownership and releases it after the write completes; the caller MUST NOT close or retain it — identical wording to `HttpExchange.respond(HttpResponse)`. The zero-copy choice; no defensive copy on the emit path.
6. **Fail-closed on JWT expiry mid-stream (ADR-012 §5).** A stream authenticated at open captures the token's `exp` (from the validated claims established at open via `ScopedValue` — the Community JWT path today, the SPI `Claims`/`PrincipalContext` carrier once ADR-040 lands; until then the deadline derivation is Community-internal, which keeps The Wall intact). When `exp` passes, the engine **deterministically closes the stream** with `EX-HTTP-4012` (stream-auth-expired) — never a silent drop, never fail-open continuation. Re-validation model is **open-time validation + an expiry deadline** (not a per-emit JWKS re-fetch, which would be wasteful — the impl MUST NOT substitute a per-emit re-fetch and call it conformant). This is the binding point with the IdentityProvider SPI (ADR-040, reserved/RFC-driven); both ship in the same release.
7. **Distinct router registration + PAQS accounting for long-lived slots.** This *introduces* a new typed stream registration in the **Core** router (`eu.exeris.kernel.core.http.routing.HttpRouter`) — distinct from today's static `(method, path) → HttpHandler` dispatch — so a streaming-flagged route (derived from `realTimeApi` / `@Action(streaming=true)`) resolves to an `HttpStreamHandler`; a streaming route never receives a respond-once `HttpExchange` and vice-versa. (The new SPI types are `HttpStreamHandler`/`HttpStreamExchange`; the *registration/resolution* is Core — no metadata plumbing exists in Core today.) A stream is admitted once at open with a fixed `StreamPriority` and holds its slot for its lifetime (minutes, not ms); it is accounted against a streaming-occupancy ceiling distinct from sub-ms request accounting. Under `ResourceArbiter.decide(Context.TRANSPORT_IO) == SHED_LOAD`, **new** stream-opens are rejected through the existing PAQS shed path — they reuse `EX-NET-4006` and emit the existing `StreamShedEvent` (inheriting its no-alert-counter + zero-alloc contract; transport.md §EX-NET-4006), **not** a new HTTP-layer shed code; already-open streams continue emitting.
8. **JFR-first lifecycle + single-phase commit.** Streaming emits four JFR events (ADR-005): `StreamOpenedEvent` (lifecycle, `@StackTrace(false)`), `StreamClosedEvent` (graceful close, lifecycle, `@StackTrace(false)`), `StreamAbortiveTeardownEvent` (the dead-key reactor path hardened by #202, lifecycle, `@StackTrace(false)`), and `StreamBackpressureParkEvent` (hot-path, `@StackTrace(false)`). All use **single-phase commit** — never `begin()` → blocking emit/fetch → `commit()` on a virtual thread (carrier-bound `EventWriter` straddle → SIGSEGV; see the `ConnectionAcquireEvent` precedent). `emit-on-closed-stream` is surfaced as `StreamClosedException` and carries diagnostic code `EX-HTTP-4011` (stream-emit-after-close).

**Error taxonomy (new in `KernelErrorCodes`, contiguous with the existing `EX-HTTP-4001…4010`):**

| Code           | Condition                                                  | Surfaced as                          |
|:---------------|:----------------------------------------------------------|:-------------------------------------|
| `EX-HTTP-4011` | `emit()` after the stream is closed / client disconnected | `StreamClosedException`              |
| `EX-HTTP-4012` | JWT expired mid-stream → deterministic fail-closed close   | stream closed; structured code       |

**Reuses existing codes (no new code minted):** stream-open rejected by admission rides the existing PAQS shed path — `EX-NET-4006` + `StreamShedEvent` (transport.md §EX-NET-4006), the single source of truth for load-shedding; this ADR does **not** mint a parallel HTTP shed code. The client-facing HTTP status (503) is a wire mapping, separate from the kernel error code.

**Deliberately undefined in v0.10:** a backpressure *timeout* code. `emit()` parks until credit or disconnect; a park-deadline is a separate policy decision, not part of this contract.

## Consequences

### ✅ Positive Outcomes

- **[+] Unblocks the SDK + tooling streaming chain.** `realTimeApi` / `@Action(streaming)` finally have a kernel primitive to bind to; tooling can emit a Java stream handler + a TS `EventSource`/RxJS client (Java/TS parity). Natural sink for `@EventSourced` / `@Projection`.
- **[+] Respond-once invariant stays unconditional.** Every existing handler's reasoning about `HttpExchange` is unchanged; streaming is a separate opt-in surface, not a conditional mode.
- **[+] Zero new Core transport infra.** SSE HTTP/1.1 framing is a thin `data:…\n\n` layer over the existing `Http1ChunkedEncoder` (ADR-009); `Last-Event-ID` reconnect is readable via the existing case-insensitive `HttpRequest` header accessor.
- **[+] No-Waste-Compute preserved end-to-end.** Park-the-VT backpressure + `LoanedBuffer` transfer-to-engine mean no heap queue and no defensive copy on the hot emit path.
- **[+] Fail-closed under ADR-012.** A held-open authenticated stream cannot outlive its token; the one auth failure mode the respond-once path never has is closed deterministically.

### ⚠️ Trade-offs

- **[-] A new long-lived resource class.** A stream slot held for minutes is structurally different from a sub-ms request and needs its own occupancy ceiling; mis-tuning it can starve request capacity or over-admit streams. Mitigated by distinct PAQS accounting (obligation 7) but it is genuinely new operational surface.
- **[-] Unidirectional only.** Clients that need low-latency client→server streaming are not served; they must wait for the deferred WebSocket decision. Accepted: not the motivating use case.
- **[-] Cross-tier binding obligation.** Every transport tier (Community NIO, Enterprise native/io_uring) MUST bind `HttpStreamExchange` to its egress path; the backpressure-park behaviour is the path most likely to regress across tiers and must be TCK-pinned.
- **[-] Expiry-deadline coupling to ADR-040.** Obligation 6 cannot be fully exercised until the IdentityProvider SPI lands; the two are release-coupled (both v0.10).

### 📋 What is NOT in scope

- **WebSocket / full-duplex.** Deferred to a later, separately-justified RFC/ADR gated on a proven bidirectional low-latency client-streaming use case. **Not pinned to a release milestone** — it may well land before 1.0; this ADR neither schedules nor precludes it, it only declines to build it speculatively alongside SSE here.
- **Backpressure park-timeout policy.** No timeout code in v0.10 (see above).
- **The Enterprise native/io_uring streaming binding itself** — a concrete cross-repo obligation tracked in `exeris-kernel-enterprise`, not implemented here.
- **Data-scope / shared-world modelling** (cross-tenant streams) — orthogonal; tracked by the Multi-Tenancy Shared-World RFC.

## Cross-references

- [RFC-2026-06-18](../rfc/RFC-2026-06-18-http-streaming-spi.md) — the investigation and recommendation this ADR ratifies.
- ADR-006 (Spring-Free Kernel Boundary / The Wall) — SSE framing is tier-blind Core; transport mechanics are tier-specific; no wire types in the SPI.
- ADR-009 (HTTP Codec Module) — `Http1ChunkedEncoder` is the SSE framing substrate.
- ADR-005 (JFR-First Telemetry Strategy) — the four streaming-lifecycle events.
- ADR-012 (Security Trust Model / fail-closed runtime) §5 — the JWT-expiry-mid-stream deny is governed here.
- [RFC-2026-06-08](../rfc/RFC-2026-06-08-identity-provider-spi-shape.md) → ADR-040 (Identity Provider SPI — **reserved, content pending**) — will supply the SPI `Claims`/`PrincipalContext` (and `exp`) the expiry deadline derives from; release-coupled (both v0.10). Until ADR-040 lands the `exp` source is the Community JWT path (ADR-012).
- `exeris-kernel/docs/subsystems/transport.md` — `StreamPriority`, PAQS scheduler, `WatermarkManager`, the #202 abortive-teardown path.
- `eu.exeris.kernel.spi.http.HttpExchange` — the respond-once sibling whose ownership-transfer wording obligation 5 mirrors.

## Engineering Protocol

1. **`AbstractHttpStreamExchangeTck`** (new) pins the contract: open → emit-N → graceful close; disconnect surfaces as `StreamClosedException` (`EX-HTTP-4011`); `LoanedBuffer` zero-leak across the stream lifecycle; **a backpressure case asserting the VT parks and resumes on window credit** (the most regression-prone path across tiers); JWT-expiry-mid-stream closes with `EX-HTTP-4012` (fail-closed, not silent); stream-open shed reuses `EX-NET-4006` + `StreamShedEvent` (no new shed code); a respond-once-regression guard asserting `HttpExchange` is untouched. Community binding is mandatory in CI; the Enterprise binding is the declared cross-repo obligation.
2. **ArchTest** pins: `HttpStreamExchange` / `StreamEvent` / `HttpStreamHandler` carry no wire-format or transport types (The Wall); `StreamEvent` is an immutable `record` with no identity-sensitive operations (Valhalla-ready); context propagation is `ScopedValue`-only (no `ThreadLocal`).
3. **`KernelErrorCodes`** gains `EX-HTTP-4011` and `EX-HTTP-4012` with the conditions above (stream-open shed reuses the existing `EX-NET-4006`, no new code); the TCK asserts the structured codes are present on the corresponding failure paths.
4. **JFR `RecordingStream`** assertions verify the four events fire on open / graceful-close / abortive-teardown / backpressure-park, all single-phase (no VT-straddle).
5. **Cross-repo `.link.md` stubs** for ADR-043 land in `exeris-sdk`, `exeris-tooling`, and `exeris-kernel-enterprise` per ADR-020; the SDK/tooling stream emitters and the Enterprise native binding are tracked as their own follow-ups.
6. **Migration:** none — additive SPI surface, pre-1.0, no external SPI consumers; no "breaking change" framing.
