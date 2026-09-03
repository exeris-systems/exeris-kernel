# ADR-084: A duplex wire the platform's own tools can embed

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-09-02                                                                                |
| **Scope**       | `kernel/http` + `kernel/transport`                                                        |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | v0.12; supersedes the WebSocket half of [RFC-2026-06-18](../rfc/RFC-2026-06-18-http-streaming-spi.md), whose SSE ruling stands as [ADR-043](ADR-043-kernel-http-streaming-spi.md) |
| **Compliance**  | [docs/subsystems/http.md](../subsystems/http.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

The kernel has no duplex wire. Measured on `development/0.12.0`: no WebSocket type exists in SPI,
Core or Community, so this is new surface rather than a widening.

That was a decision, not an omission. RFC-2026-06-18 chose SSE-only and said why — *"the dominant
use case is unidirectional server push"* — listing full duplex as *"a larger surface for a use case
that doesn't need it yet"*, and recording WebSocket as *"a later, separately-justified addition"*.
ADR-043 implemented that ruling.

**Platform LSP is that separate justification, and it differs structurally rather than by
preference.** The Language Server Protocol is bidirectional request/response; SSE is one-directional
by construction. This is not "SSE would be awkward for LSP" — it is that the client cannot speak.
Studio sits on the same connection. Both are first-party consumers, which is what moves this from a
capability we might add into the release those tools depend on.

## Decision

### 1. A provider that yields an engine, mirroring `HttpProvider`

`WebSocketProvider.createServerEngine(WebSocketConfig)` returns a not-yet-started engine; the caller
calls `setHandler(...)` then `start()`. Deliberately the same shape as
`HttpProvider.createServerEngine(HttpConfig)`, and for one reason: **the platform must get an
endpoint without booting the kernel.**

That property is not aspirational — it is how the HTTP and persistence stacks already behave, and it
was confirmed from outside: a consumer stood the whole persistence path up headless in a plain JUnit
test from two public calls, no `KernelBootstrap`, no DI, no `ServiceLoader`. An LSP server is a
developer tool that starts per session; requiring a kernel boot to open a socket would make the tool
pay for a runtime it does not use.

The alternative — an upgrade negotiated on the existing HTTP stream seam — is rejected on exactly
that constraint. It would drag in the HTTP subsystem, its router and its boot, to obtain a socket
that carries no HTTP semantics past the handshake.

### 2. A sibling exchange, not a mode

`WebSocketExchange` is a distinct interface, as `HttpStreamExchange` is. This follows ADR-043
obligation 1 for the same reason it was written: a mode flag on an existing exchange conditions an
invariant that is currently unconditional. Respond-once stays respond-once and SSE stays
one-directional; duplex is its own surface or it is a qualifier on two others.

### 3. Text frames only, on the application surface

The handler sends and receives `String`. There is no binary opcode on the SPI, because LSP is JSON
carried as text and a binary surface with no consumer is surface we would have to keep.

**This constrains the application contract, not the wire.** RFC 6455 control frames — ping, pong,
close — and continuation frames for fragmented messages are handled by the codec regardless; a peer
that fragments a large message is speaking the protocol correctly. What the SPI declines is the
*binary* opcode: a binary frame is a protocol error the connection closes on, not a payload the
handler is offered.

### 4. Session identity is per connection, and resumption is the consumer's

Each accepted connection carries a `WebSocketSession` with a stable identity for the connection's
lifetime. The platform's model is one server instance per session, so the identity is what the
consumer keys its instance on; without it the model has to reconstruct session affinity from a
socket the SPI does not expose.

Scoping across tenants rides the existing mechanism rather than inventing one: a connection's
isolation key comes from the established `StorageContext`, so a socket opened under one tenant is
not addressable from another.

**The identity does not survive a reconnect, and that is a decision rather than a limitation.** A
browser tab that sleeps or a network that flaps produces a new connection and a new identity. The
consumer that wants continuity across that builds it on §6: the handshake is visible, so a returning
client presents its own token and the consumer maps it back to its own session object.

The reason the kernel does not own this is that **the cost of resumption concentrates in one place —
buffering — and it is the one place we have already ruled out.** Resumption without buffering resumes
*identity, not the stream*: messages sent during the disconnect window are gone, so the consumer
still has to reconcile state on return. It would therefore buy a session store, an expiry policy, a
grace window and the question of who may claim a session, in exchange for only part of the work it
was meant to remove. Buffering the gap is what would make it whole, and that is the on-heap queue
ADR-043 obligation 4 forbids — unbounded, on a connection held for the length of an editing session.

This is also the house pattern rather than a new one. ADR-013 gives durable saga state a *seam*
(`FlowSnapshotStore`) and leaves the store to the application, explicitly rejecting a coordinator
process; a kernel-owned session store would be that coordinator under another name. And LSP and
Studio want different continuity semantics — an editor reconnecting to a language server is not the
same event as a second browser tab attaching to a model — so one kernel-owned policy would be a
compromise for both rather than a fit for either.

### 5. A configurable maximum message size, defaulted from a measurement

`WebSocketConfig.maxMessageBytes`, with a default chosen against the payloads this actually carries
rather than against a round number.

The measurement comes from the consumer, on Jetty: **the 8 KB default is orders of magnitude too
small.** `exeris/applyMutation` carries a serialised `DomainMetadata` baseline and `domainDescribe`
returns a full projection — LSP payloads are documents, not commands. A limit that a normal request
exceeds is a limit that gets raised in a hurry by whoever hits it first, which is the failure ADR-071
exists to prevent: every operational limit carries a configuration path, and the default is chosen,
not inherited.

### 6. The handshake is visible, refusable, and refuses by default

The handler receives a handshake callback carrying the request as an `HttpRequest` and returns either
acceptance — optionally naming the negotiated subprotocol — or refusal with an `HttpStatus`.

**Reused rather than minted.** A WebSocket handshake *is* an HTTP GET: `HttpRequest` already carries
the headers, path and authority a consumer needs, it is a `stable` carrier, and a refusal is an HTTP
response, so the status comes from the same set. The wart is stated rather than hidden: `body` is
meaningless for a handshake and will be null. A parallel carrier duplicating headers and path to
avoid one null component would be the worse trade.

**Why this is not optional for a browser client.** A WebSocket handshake is not subject to CORS, so a
server that ignores `Origin` is open to cross-site WebSocket hijacking — any page the user visits can
open a connection that carries their cookies. LSP over a local socket never meets this; Studio does,
on every page its users have open. A browser also cannot set request headers on a WebSocket, so the
only channels a consumer has for authenticating are `Origin`, cookies, `Sec-WebSocket-Protocol` and
the query string — all of which are in the request and none of which are reachable without this
callback.

**The default decides the failure mode, so the default refuses.** `WebSocketConfig` carries an origin
allowlist, the engine rejects an unlisted origin **before** the callback runs, and the callback can
therefore only **narrow** it. An earlier draft said the callback could "widen or narrow", which
cannot both be true of a pre-filter; writing the SPI is what surfaced it, because the javadoc could
not be written without picking one. The fail-closed reading is the one worth keeping — a consumer
that genuinely needs a wider set widens the allowlist, a visible and reviewable act, rather than
re-opening it inside a callback nobody re-reads. The alternative — a callback defaulting to acceptance — means a consumer who
never writes one is open, and the cost of forgetting lands on their users rather than on their build.
Inverted this way, forgetting produces a refusal somebody notices immediately.

Fail-closed-by-default is this kernel's existing idiom, not a new posture for this SPI:
`SecurityInterceptor` drops an unauthenticated request with no anonymous fallback, a blob store
terminally denies a context with no isolation key, and a stream whose principal expires mid-stream is
closed fail-closed (`EX-HTTP-4012`).

This callback is also where §4's session identity is established, and where the ambient
`StorageContext` is captured into the session for the connection's lifetime. It is a capture, not a
second source: §4 remains where the isolation key comes from, and the handshake is only the point at
which it stops being ambient and becomes the connection's. That is why the two questions resolve
together — consumer-side resumption is possible only because the handshake is visible.

### 7. Backpressure parks the virtual thread — never an on-heap queue

ADR-043 obligation 4, verbatim, extended to the duplex direction: when the egress window is full,
`send()` blocks the calling virtual thread until credit is available. A queue would convert
backpressure into unbounded memory, which for a long-lived connection with a slow reader is a leak
with a timer on it.

As in ADR-043, a backpressure *timeout* is deliberately undefined here. A park-deadline is a policy
decision, not part of this contract.

### 8. Close codes are surfaced

RFC 6455 close codes reach the handler and can be sent by it. The driving case is specific: the
consumer needs an exit without a prior shutdown to be reportable as a protocol error, the way a
stdio-transported LSP reports it with exit code 1. A transport that can only say "closed" cannot
distinguish a client that went away from a client that violated the protocol, and those need
different operator responses.

Errors extend the existing `EX-HTTP-` family rather than minting a new one, contiguous with the
streaming and request-decode faults that already live at `EX-HTTP-4007…4013`.

### 9. Placement across the Wall

SPI carries the contracts — provider, engine, exchange, session, config — and no wire detail. The
RFC 6455 frame codec lives in **Core**, next to the HTTP/1 codec that is already there, because a
frame codec is driver-agnostic parsing. The **Community** binding supplies the transport and the
handshake over the existing TCP carrier.

### 10. It ships `preview` at v0.12, and that is a decision

The merge gate is a TCK and a binding. **A contract test proves a shape is honoured, not that it
survives.** A duplex, long-lived, per-connection protocol is precisely where those diverge: the TCK
opens a connection, exchanges messages and closes, and says nothing about a thousand of them, or a
reader that stops reading, or a peer that dies without a close frame.

Promotion to `stable` is therefore gated on benchmark evidence in `exeris-benchmarks` — concurrent
connection count, frame throughput, backpressure under a slow reader, teardown of a dead peer — not
on the TCK going green. Until that exists the stability matrix says `preview` and means it.

**The consequence is recorded rather than discovered later:** LSP and Studio are first-party
consumers of a `preview` surface for at least one release. That is the situation
[RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) catalogues for seven Tier 2
capabilities, and it is worse here only in that the consumers are ours — which also makes any
migration ours to absorb rather than a customer's.

### 11. Configuration, not contract: TLS, keepalive, and what is left additive

Three items the consumers need that do not shape the handler surface, so they are settled as
configuration and can move without a contract change:

- **TLS.** A browser on `https://` cannot open `ws://`, so `wss://` has to work. `HttpConfig` carries
  no TLS flag — termination lives in the crypto subsystem — so `WebSocketConfig` needs the same seam
  rather than inheriting one that does not exist. Whether a deployment terminates in the kernel or at
  a proxy in front is the deployment's call; the config has to admit both.
- **Keepalive is the kernel's job, not the handler's.** Intermediaries drop idle connections, commonly
  around a minute, and a handler that must remember to ping is a handler that forgets. The engine
  sends pings on a configurable interval; `idleTimeoutMillis` follows the shape `HttpConfig` already
  established rather than inventing a second spelling.
- **`permessage-deflate` is deferred, not rejected.** It is negotiated at the handshake and does not
  touch the handler surface, so it is additive later. Worth measuring rather than assuming: the
  payload argument that set §5's limit — `domainDescribe` returning a full projection — weighs
  differently over the internet to a browser than it did over a local socket.

## Amendment A2 (v0.12) — the no-boot path is not the only path

§1 decided that a provider yields an engine **without** booting the kernel, and that decision stands
unchanged: `WebSocketProvider.createServerEngine` works with nothing bound, and a per-session LSP
server still pays for no runtime it does not use.

What §1 did not decide, and was read as deciding, is that this is the *only* way in. An application
that **does** boot the kernel — written by hand or generated by the tooling — could reach an endpoint
only by naming `CommunityWebSocketProvider` itself, which puts a driver on its compile classpath. That
is the coupling the Wall exists to prevent, and it left the two deployments unequal: one had a
supported path and the other had a workaround.

**Both are supported from v0.12.** A `websocket` subsystem publishes `WEBSOCKET_PROVIDER` and
`WEBSOCKET_SERVER_ENGINE` through `WebSocketKernelProviders`, and an application supplies its handler
and handshake policy into the boot through the same class. Neither path is preferred; they serve
different deployments.

Three things this amendment does not change, stated because each was checked rather than assumed:

- **It is off unless asked for.** `websocket.enabled` defaults to `false`. The subsystem ships on every
  Community classpath, so a deployment that merely upgraded must not gain a listener — a socket that
  appears because a dependency was bumped is a security regression delivered as a feature. `http` may
  infer its mode from a configured port; a duplex endpoint is not what an application boots the kernel
  for, so inference is the wrong default here.
- **The refusing handshake default of §6 is unchanged.** `websocket.allowedOrigins` defaults to empty,
  which `WebSocketConfig` documents as accepting no browser origin. The safe posture is what you get by
  leaving configuration alone.
- **The engine is built at `start()`, not at `initialize()`.** Bootstrap composes `providerBindings()`
  after every subsystem has initialised, so `MEMORY_ALLOCATOR` is not bound while a subsystem is
  initialising — `dependsOn("memory")` orders the phases without making the binding visible earlier.
  Constructing the engine early would either fail or open a second memory budget beside the kernel's.
  `DeferredWebSocketServerEngine` is the same answer `DeferredHttpServerEngine` already gives to the
  identical ordering problem.

The state of §1's own implementation is a separate matter and is recorded in Amendment A1, which
lands with the change that fixes it. This amendment deliberately makes no claim about it: asserting
another pull request's outcome here would put a fix in the record before it is in the code, and the
merge order of the two is not guaranteed.

## Consequences

- The platform gets an embeddable duplex endpoint without a kernel boot, which is the requirement
  that shaped the provider rather than a convenience that fell out of it.
- A new long-lived resource class, as ADR-043 already noted for streams: a connection held for the
  length of an editing session is structurally unlike a sub-millisecond request, and accounting for
  it belongs with the stream-slot accounting that exists.
- Every transport tier that wants duplex must bind it. Community does so in v0.12; the enterprise
  tier is a separate scope and gates nothing here.
- `preview` for at least one release, with named promotion criteria rather than a milestone.

## Alternatives Considered

**A kernel-owned resumable session.** Rejected in §4: the cost concentrates in buffering the
disconnect window, which is the on-heap queue obligation 4 forbids, and resumption without it
resumes identity rather than the stream — so the consumer reconciles anyway and the session store
buys only part of the work it was meant to remove.

**A handshake with no callback — an origin allowlist and nothing more.** Rejected: it solves the
cross-site half and leaves the rest. A token is per-request, not per-configuration, so a
config-only surface cannot authenticate, cannot negotiate a subprotocol, and cannot let the consumer
recognise a returning client — which would make §4's consumer-side resumption impossible.

**A callback that accepts by default.** Rejected on the failure mode rather than the mechanism. It
is the same callback; the difference is who pays when somebody forgets it, and with an
accepting default that is the consumer's users rather than their build.

**A purpose-built handshake carrier.** Rejected as duplication: `HttpRequest` already carries
headers, path and authority, and a WebSocket handshake is an HTTP GET. One meaningless `body`
component is a smaller cost than a parallel carrier that has to be kept in step with it.

**Upgrade on the existing HTTP stream seam.** Rejected on requirement 1: it makes a socket cost a
kernel boot and an HTTP router for a protocol that stops being HTTP after the handshake.

**Binary frames on the SPI surface.** Rejected as surface with no consumer. It is additive later if
one appears, whereas removing it would not be.

**Wait for benchmarks before shipping anything.** Rejected because it inverts the dependency: the
benchmark needs an implementation to measure. Shipping `preview` with named promotion criteria is
what lets the measurement happen against real code.

**A heap queue for egress.** Rejected by ADR-043 obligation 4, and more sharply here — a long-lived
connection turns a bounded-looking queue into an unbounded one.

## Compliance and Verification

- `AbstractWebSocketExchangeTck` pins the contract: handshake, text round-trip, fragmented message
  reassembly, oversize message refused with the configured limit, binary frame refused, close code
  round-trip, disconnect surfacing on `send`.
- The handshake contract is pinned in both directions, because §6's value is in the refusal: an
  unlisted origin is refused **with no callback written**, a callback may refuse with a status the
  client receives, a callback may accept and name a subprotocol, and the session identity established
  at handshake is the one the exchange reports. A test that only proves acceptance would pass against
  an engine that accepts everything.
- A Community binding test drives it over a real loopback socket.
- **The TCK is explicitly not the promotion gate.** The benchmark campaign named in §10 is, and until
  it exists the matrix row reads `preview`.
