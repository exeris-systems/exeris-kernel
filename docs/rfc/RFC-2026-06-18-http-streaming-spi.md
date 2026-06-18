# RFC-2026-06-18: Kernel HTTP server-push / streaming SPI

| Field             | Value                                                                                                                                  |
|:------------------|:---------------------------------------------------------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                                                                              |
| **Author(s)**     | Arkadiusz Przychocki                                                                                                                    |
| **Date Opened**   | 2026-06-18                                                                                                                              |
| **Date Closed**   | —                                                                                                                                      |
| **Target ADR(s)** | ADR-043 (reserved — Kernel HTTP streaming SPI; content + impl once accepted)                                                            |
| **Affected Repos**| `exeris-kernel` (SPI + Core framing + Community transport), `exeris-sdk` (`realTimeApi`/`@Action(streaming)` wired), `exeris-tooling` (stream emitters Java/TS), `exeris-kernel-enterprise` (native transport overlay, cross-repo) |
| **Reviewers**     | —                                                                                                                                      |

## Question

How should the kernel expose **server-initiated push over HTTP** — both the **transport** (SSE-only vs SSE + WebSocket) and the **SPI shape** (extend `HttpExchange` with a streaming mode vs a sibling `HttpStreamExchange` that can `emit()` repeatedly until closed) — without breaking the respond-once invariant of the existing request/response path?

## Context

The kernel's HTTP surface has no way to push server-initiated updates to a client. The motivating shape is common and recurs across applications built on the platform: a long-running, multi-step server-side process — an event-sourced aggregate advancing through states, or a projection being rebuilt — whose **incremental results a client should observe live**, often with several clients watching the same stream. The only affordance today is the client re-polling a `GET`, which is the wrong latency/scaling shape for a live view and gives no server-driven update.

Concretely, three layers are dead-ended on a missing kernel primitive:

- **Kernel SPI:** `HttpExchange.respond(...)` is *respond-once* — it writes the outbound response exactly once and throws `IllegalStateException` on a second call. There is no SSE / chunked / flush / WebSocket affordance anywhere in `eu.exeris.kernel.spi.http`. The transport is strictly request/response.
- **SDK:** already advertises the feature — `@ExerisDomain(realTimeApi = true)`, `@Action(streaming = true, streamEventType = …, realTimeUpdates = true)` — but those attributes are **inert**.
- **Tooling:** parses `realTimeApi` into metadata but **no emitter produces a streaming endpoint**; generated clients only do request/response.

So the whole SDK → tooling streaming chain has nothing to bind to. This capability was previously slated for ~v0.12; the recurring need argues to **bring it earlier (v0.10/v0.11)** so the SDK/tooling streaming surface can be exercised. It is the load-bearing piece — nothing downstream can exist without it.

This RFC decides the kernel-side shape only. It is orthogonal to data-scope questions (shared/cross-tenant world modelling), which are a separate concern and do not depend on this transport primitive.

## Investigation

### Current SPI reality

`HttpExchange` (`spi.http`) models one request → one response: `request()` + `respond(HttpResponse)` / `respond(HttpTypedResponse)`, the latter guarded so a second call throws. The Community transport (`NativeTcp` + h2/h2c over OpenSSL TLS) writes the framed response once and the reactor coalesces it into a single egress write (ADR-009 codec, egress coalescing #199). There is no notion of an open, long-lived response that emits multiple payloads.

### Use-case shape — one-way dominates

The dominant requirement is **server → client** push (incremental results, projection deltas). The client → server direction — issuing the next command or step — is already the *request* side (a `POST`). So the requirement is a unidirectional server push, not a bidirectional low-latency channel. This is the difference between a small additive primitive and a full duplex protocol.

### Transport candidates

- **SSE (`text/event-stream`)** — a chunked HTTP body the server holds open and appends events to (`data:` framing, optional `event:`/`id:`/`retry:`). Works over HTTP/1.1 and HTTP/2; first-class browser support via `EventSource` (auto-reconnect with `Last-Event-ID`) and trivial RxJS wrapping. Rides the existing HTTP server: one held-open stream, server-driven writes through the existing egress path. No new wire protocol, no upgrade handshake.
- **WebSocket** — full duplex. Needs an upgrade handshake (HTTP/1.1 `Upgrade`, or HTTP/2 Extended CONNECT per RFC 8441), its own frame protocol, ping/pong keepalive, and a close handshake. Justified only when the client must *also* stream to the server at low latency — not the motivating use case.

### SPI shape candidates

- **(A) Streaming mode on `HttpExchange`** — add e.g. `openEventStream()` returning a writer, after which `respond()` becomes illegal. One type, two mutually-exclusive modes. Risk: muddies the respond-once contract every handler relies on; the invariant becomes conditional.
- **(B) Sibling `HttpStreamExchange`** — a distinct handler-facing contract (`emit(StreamEvent)` / `close()`, plus an `onClose`/disconnect signal) that the router hands to a handler only for routes flagged streaming (`realTimeApi` entity / `@Action(streaming=true)`). The respond-once `HttpExchange` is untouched; streaming is a separate, opt-in surface selected by route metadata.

### Cross-cutting constraints

- **Backpressure / flow control.** A server emitting faster than the client drains must respect transport flow control (TLS record / H2 window). `emit()` must be flow-aware (bounded buffer or suspend), reusing the reactor egress path + `LoanedBuffer` ownership — never an unbounded heap queue.
- **Lifecycle / teardown.** A streaming connection stays registered (read interest for disconnect detection, write interest for emit). Its teardown must be clean and abortive on fault — exactly the path hardened by the closed-engine read-spin fix (#202): a dead streaming key must not busy-spin the reactor.
- **The Wall.** SSE framing (event serialization) is tier-blind and belongs in Core; the held-open transport mechanics live in Community (NIO) and Enterprise (native/io_uring overlay, out of repo). No driver/transport detail leaks into the SPI contract.
- **Pairing.** A streaming endpoint is the natural sink for `@EventSourced` + `@Projection`: an aggregate's event stream *is* the payload to push. The SPI should be event-shaped, not byte-shaped.

## Options

| # | Transport | SPI shape | Notes |
|---|---|---|---|
| 1 | **SSE-only** | **sibling `HttpStreamExchange`** | Smallest primitive that satisfies the use case; respond-once untouched. **Preferred.** |
| 2 | SSE-only | streaming mode on `HttpExchange` | Fewer types, but conditions the respond-once invariant. |
| 3 | SSE + WebSocket | sibling exchange(s) | Full duplex now; larger surface (handshake, frame protocol, ping/pong) for a use case that doesn't need it yet. |
| 4 | WebSocket-only | sibling exchange | Heaviest; SSE-style simple push then rides a duplex protocol unnecessarily. |

## Recommendation

**Option 1 — SSE-first over a sibling `HttpStreamExchange`.**

- **SSE-first** because the dominant use case is unidirectional server push; SSE rides the existing HTTP/1.1 + h2 server with no upgrade handshake or new frame protocol, and has first-class browser + RxJS clients. WebSocket is deferred to a **follow-up RFC/ADR** gated on a proven bidirectional/low-latency client-streaming use case — not speculatively built now.
- **Sibling `HttpStreamExchange`** because it keeps the respond-once `HttpExchange` invariant intact and unconditional; streaming is an opt-in surface the router selects from route metadata. The handler receives `emit(event)` / `close()` + a disconnect signal; framing is Core, transport is tier-specific.
- `emit()` is **flow-aware** (reuses the reactor egress + `LoanedBuffer`, bounded), and stream teardown uses the abortive-cancel path so a dead stream cannot spin the reactor (#202).

This is the minimal kernel primitive that lets the SDK (`realTimeApi`/`@Action(streaming)` finally mean something) and tooling (generate a Java stream handler + a TS `EventSource`/RxJS client, Java/TS parity) build on it.

## Consequences

- **Unblocks** the SDK + tooling streaming chain and any live, server-pushed view; pairs with `@EventSourced`/`@Projection`.
- **New SPI surface** → requires architect sign-off + an `Abstract*Tck` for the streaming contract (open / emit-N / close / disconnect, backpressure, no respond-once regression) with a Community binding; the Enterprise overlay is declared as a cross-repo obligation.
- **Roadmap:** brings the streaming primitive from ~v0.12 to **v0.10/v0.11** (v0.10 already holds the IdentityProvider SPI from RFC-2026-06-08). WebSocket remains a later, separately-justified decision.
- **No "breaking change" framing** — pre-1.0, no external SPI consumers; this is additive SPI surface.

## Open design questions for ADR-043

ADR-043 is the architect sign-off + `Abstract*Tck` artifact; it cannot close without resolving these load-bearing design questions (leaving them open would produce ambiguous/contradictory implementations):

1. **`HttpStreamExchange` method surface.** Define `StreamEvent` — a record (`event` / `data` / `id` / optional `retry`) mapping directly to the SSE wire format, implementation-blind and event-shaped (not a raw byte buffer); the `HttpStreamHandler` functional interface (sibling of `HttpHandler`, and its router/ServiceLoader registration); and the **disconnect signal** — preferred is `emit()` throwing `StreamClosedException` on disconnect (Loom-idiomatic: an imperative emit loop that exits on the throw, no parked-VT leak) over a separate `awaitDisconnect()`.
2. **`emit()` flow semantics = park-the-VT.** When the egress window is full, `emit()` blocks the calling VT (exactly as `NativeTcpStream`'s write loop already does for response bodies) — not an on-heap queue (No Waste Compute). The SPI Javadoc must state this.
3. **`emit()` `LoanedBuffer` ownership = transfer-to-engine**, identical to `HttpExchange.respond()` ("the caller MUST NOT close or retain the buffer after this call") — the zero-copy choice.
4. **Streaming-lifecycle JFR events (ADR-005):** stream-opened, graceful-close, abortive-teardown (the #202 reactor path), backpressure-park. ADR-043 defines names/categories/`@StackTrace` policy (open/close = lifecycle; emit/backpressure = hot-path).
5. **`EX-HTTP-*` error taxonomy** in `KernelErrorCodes`: emit-on-closed-stream, stream-open rejected by admission, backpressure timeout (if one is defined).
6. **Router extension:** how `HttpRouter` dispatches `HttpStreamHandler` for streaming-flagged routes (typed `Map<Route, HttpStreamHandler>` vs a route-metadata `streaming` flag) — sketch the SPI contract (Wall review at implementation time).
7. **PAQS / resource accounting for long-lived streams.** `StreamPriority` is fixed at admission and a stream holds its slot for its lifetime (minutes, not ms); N concurrent SSE streams are structurally different from N sub-ms requests. ADR-043 states the `WatermarkManager`/PAQS accounting model.

**Confirmed enablers (no new Core infra):** SSE HTTP/1.1 framing is a thin `data:…\n\n` layer over the existing `Http1ChunkedEncoder` (ADR-009, `core.http.http1`); `Last-Event-ID` reconnect is readable via `HttpRequest`'s case-insensitive header accessor. **Cross-repo obligation:** Enterprise MUST bind `HttpStreamExchange` to its native/io_uring egress path (concrete, not parenthetical). **TCK:** must include a backpressure case asserting the VT parks and resumes on window credit — the path most likely to regress across tiers.

## Decision log
- 2026-06-18 — DRAFT opened; preferred direction SSE-first + sibling `HttpStreamExchange`. Target ADR-043 reserved. transport.md SSE/WebSocket ordering corrected (SSE-first). Open design questions for ADR-043 enumerated per RFC review.
