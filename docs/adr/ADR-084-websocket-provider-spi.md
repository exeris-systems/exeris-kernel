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

### 4. Session identity is per connection

Each accepted connection carries a `WebSocketSession` with a stable identity for the connection's
lifetime. The platform's model is one server instance per session, so the identity is what the
consumer keys its instance on; without it the model has to reconstruct session affinity from a
socket the SPI does not expose.

Scoping across tenants rides the existing mechanism rather than inventing one: a connection's
isolation key comes from the established `StorageContext`, so a socket opened under one tenant is
not addressable from another.

### 5. A configurable maximum message size, defaulted from a measurement

`WebSocketConfig.maxMessageBytes`, with a default chosen against the payloads this actually carries
rather than against a round number.

The measurement comes from the consumer, on Jetty: **the 8 KB default is orders of magnitude too
small.** `exeris/applyMutation` carries a serialised `DomainMetadata` baseline and `domainDescribe`
returns a full projection — LSP payloads are documents, not commands. A limit that a normal request
exceeds is a limit that gets raised in a hurry by whoever hits it first, which is the failure ADR-071
exists to prevent: every operational limit carries a configuration path, and the default is chosen,
not inherited.

### 6. Backpressure parks the virtual thread — never an on-heap queue

ADR-043 obligation 4, verbatim, extended to the duplex direction: when the egress window is full,
`send()` blocks the calling virtual thread until credit is available. A queue would convert
backpressure into unbounded memory, which for a long-lived connection with a slow reader is a leak
with a timer on it.

As in ADR-043, a backpressure *timeout* is deliberately undefined here. A park-deadline is a policy
decision, not part of this contract.

### 7. Close codes are surfaced

RFC 6455 close codes reach the handler and can be sent by it. The driving case is specific: the
consumer needs an exit without a prior shutdown to be reportable as a protocol error, the way a
stdio-transported LSP reports it with exit code 1. A transport that can only say "closed" cannot
distinguish a client that went away from a client that violated the protocol, and those need
different operator responses.

Errors extend the existing `EX-HTTP-` family rather than minting a new one, contiguous with the
streaming and request-decode faults that already live at `EX-HTTP-4007…4013`.

### 8. Placement across the Wall

SPI carries the contracts — provider, engine, exchange, session, config — and no wire detail. The
RFC 6455 frame codec lives in **Core**, next to the HTTP/1 codec that is already there, because a
frame codec is driver-agnostic parsing. The **Community** binding supplies the transport and the
handshake over the existing TCP carrier.

### 9. It ships `preview` at v0.12, and that is a decision

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
- A Community binding test drives it over a real loopback socket.
- **The TCK is explicitly not the promotion gate.** The benchmark campaign named in §9 is, and until
  it exists the matrix row reads `preview`.
