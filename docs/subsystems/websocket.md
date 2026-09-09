---
title: "WebSocket Subsystem — A Duplex Wire the Platform's Own Tools Can Embed"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
---

# WebSocket Subsystem — A Duplex Wire the Platform's Own Tools Can Embed

The HTTP subsystem answers a request and is done. Studio, the LSP server and a diagnostics adapter
need the opposite shape: a connection that stays open and carries messages in both directions for
as long as the peer is there. That is what this subsystem is, and it is a **sibling of HTTP rather
than a mode of it** — the two share a port and a handshake, and nothing else.

It shipped in v0.12.0, and it has **two entry points**: embedded without a kernel boot, or
bootstrapped alongside one. That distinction comes first below, because the embedded one is the
reason the SPI has the shape it has.

## Two modes

**Embedded, without a kernel boot.** `createServerEngine(config)`, then `setHandler(...)`, then
`start()` — no `KernelBootstrap`, no DI container, no configuration key:

```java
WebSocketServerEngine engine = ServiceLoader.load(WebSocketProvider.class)
        .findFirst()
        .orElseThrow()
        .createServerEngine(
                WebSocketConfig.defaultServer("127.0.0.1", 0, List.of("http://localhost")));
engine.setHandler(exchange -> {
    String message;
    while ((message = exchange.receive()) != null) {
        exchange.send(message);
    }
});
engine.start();
```

ADR-084 §1 treats this as the deciding constraint rather than a convenience: **the platform must get
an endpoint without booting the kernel.** An LSP server starts per editor session, and requiring a
runtime boot to open a socket would make a developer tool pay for a runtime it does not use. HTTP and
persistence already behave this way; the WebSocket provider deliberately mirrors
`HttpProvider.createServerEngine` so the property is shared rather than re-argued.

When ADR-084 §1 says "no `ServiceLoader`", it rules discovery out as a *requirement*, not as an
option — and discovery is the better route here, because it keeps a Community type off a consumer's
compile classpath, which is what the Wall exists to prevent.

**The engine covers its own dependency on this path.** `MEMORY_ALLOCATOR` is typically unbound when
nothing was booted — exactly the embedded case — so the engine creates a private allocator instead of
refusing, and `close()` releases it **only when the engine created it**. An ambient allocator belongs
to whoever bound it, and closing another component's allocator is the failure that ownership flag
exists to prevent.

**Bootstrapped, alongside the kernel.** The `websocket` subsystem constructs the same engine during
boot and wires the handler from the ambient binding. This mode is **off unless you turn it on**:
`websocket.enabled` defaults to `false`, and a boot with it unset brings up no engine and binds no
port. The embedded mode never reads that key — nothing reads configuration on that path.

## Contract

`WebSocketProvider` yields an engine, mirroring `HttpProvider` exactly so that a reader who knows
one knows the other:

```java
WebSocketServerEngine createServerEngine(WebSocketConfig config);
String providerId();
String providerName();
default int priority();
```

`WebSocketServerEngine` is `AutoCloseable` and carries `setHandler`, `setHandshakeHandler`,
`start()`, `stop()`, `boundPort()` and `close()`. Both handlers are set **before** `start()`; an
engine that has started has already read them.

**The application surface is `WebSocketExchange`**, and it is deliberately small:

```java
WebSocketSession session();
String receive();                                  // null once the peer has closed
void send(String message);
void close();
void close(WebSocketCloseCode code, String reason);
```

**Text frames only.** A binary frame is refused rather than delivered — an implementer owes that
refusal, and `AbstractWebSocketExchangeTck` pins it. `receive()` returning `null` is the loop's exit
condition, not an error: a handler is written as `while ((msg = exchange.receive()) != null)`.

`WebSocketSession` is `(UUID id, Optional<String> subprotocol, Optional<String> isolationKey)`.
**Identity is per connection.** It is stable while the connection lives and distinct across
connections; a reconnect is a new session and resumption is the consumer's problem, not the
kernel's.

**The handshake is visible, refusable, and refuses by default.**
`WebSocketHandshakeHandler.decide(HttpRequest)` receives the request — headers, path, authority —
and returns `WebSocketHandshake.accept()`, `accept(subprotocol)` or `refuse(HttpStatus)`. Origin
checking happens **before** the callback and cannot be overridden by it: an origin outside
`allowedOrigins` is refused with the callback never written, and refused even when a callback would
have accepted. Two TCK cases pin exactly that ordering, because a callback that could widen the
origin set would make the configuration advisory.

**Under a boot**, an implementer binds through `WebSocketKernelProviders`: `WEBSOCKET_PROVIDER`,
`WEBSOCKET_SERVER_ENGINE`, `WEBSOCKET_SERVER_HANDLER`, `WEBSOCKET_HANDSHAKE_HANDLER`. As with HTTP,
the handler is bound **around** `boot()` and not inside it — the subsystem reads the `ScopedValue`
during `start()`, and a binding established inside the boot lambda arrives after the subsystem that
needed it. An embedded caller binds nothing and hands the handler to `setHandler` directly.

## Hot path

**Both directions work on a `LoanedBuffer`'s segment; neither copies through the heap to move
bytes.**

*Ingress* (`CommunityWebSocketFrameStream`) holds one inbound buffer and grows it to the largest
frame seen so far. A `fill()` that does not need to grow **allocates nothing**. Growth copies with
`MemorySegment.copy` into a larger loan and releases the old one; partial frames are compacted to
the front of the same segment rather than into a new one.

*Egress* (`CommunityWebSocketEgress`) holds one outbound buffer and a `ReentrantLock`. The lock is
the concurrency contract: **any thread may call `writeText`, `writeFrame` or `sendCloseOnce`**, and
the lock orders them to one writer at a time. A write against sufficient existing capacity
allocates nothing.

**Backpressure parks the virtual thread; it never queues on the heap.** A slow peer therefore costs
a parked carrier-free thread rather than unbounded memory, which is the whole reason a duplex wire
is affordable here (ADR-084 §7).

The message-size ceiling is enforced, not advisory: a message over `maxMessageBytes` closes the
connection with `MESSAGE_TOO_BIG` and is **never truncated and delivered**. Truncation would hand a
handler a message that looks complete.

## Failure modes

| What happens | What the caller sees |
|:--|:--|
| The peer closes | `receive()` returns `null`; the handler falls out of its loop |
| The client sends a close code | The server observes **that** code, not a substituted one |
| A message exceeds `maxMessageBytes` | Connection closes with `MESSAGE_TOO_BIG` (1009); no partial delivery |
| A binary frame arrives | Refused — the application surface is text-only |
| `send` after close | `WebSocketClosedException`, **carrying no message content** |
| Origin not in `allowedOrigins` | Handshake refused before the callback runs |
| The callback refuses | The client receives the status the callback chose |
| `websocket.enabled=true` and no provider on the classpath | Boot fails, naming that exact condition — not a silent no-op |
| Embedded, with no allocator bound | The engine creates one it owns; `close()` releases it, and a second `close()` does not double-release |

`WebSocketCloseCode` distinguishes what may be **sent** from what may only be **observed**:
`NO_STATUS_RECEIVED` (1005) and `ABNORMAL_CLOSURE` (1006) report `sendable() == false`, because RFC
6455 forbids putting them on the wire. Sending one would produce a frame a conforming peer rejects.

## Owning ADRs

- [ADR-084](../adr/ADR-084-websocket-provider-spi.md) — the whole subsystem: provider shape, the sibling
  exchange, text-only, per-connection identity, the defaulted size ceiling, the refusable handshake,
  parking backpressure, surfaced close codes, and placement across the Wall.
- [ADR-006](../adr/ADR-006.link.md) — why the SPI names no driver and the Community binding names no
  Core type it does not go through.
- [ADR-043](../adr/ADR-043-kernel-http-streaming-spi.md) — the streaming decision this one is a sibling of; SSE
  is one-way and stays the right answer where one way is enough.

## Configuration and bootstrap

**This section is the bootstrapped mode.** An embedded caller constructs `WebSocketConfig` itself
and no key below is consulted.

Two keys, resolved the way every Community key is — system property `exeris.<key>`, then
environment `EXERIS_<KEY>`, then the compiled default:

| Key | Default |
|:--|:--|
| `websocket.enabled` | `false` |
| `websocket.port` | see `WebSocketConfig.defaultServer` |

The rest of `WebSocketConfig` — `bindHost`, `maxConnections`, `idleTimeoutMillis`,
`keepAliveIntervalMillis`, `maxMessageBytes`, `allowedOrigins` — is constructed rather than
key-driven today. Compiled defaults: **1 MiB** per message, **60 s** idle, **20 s** keep-alive,
**1024** connections.

The subsystem is named `websocket`, sits in phase `RUNTIME` and declares `dependsOn("memory")` — it
needs an allocator for the two buffers above. `BootstrapSelector.forNames("websocket")` expands that
closure for you.

## Verification

`AbstractWebSocketExchangeTck` and `AbstractWebSocketProviderTck` are the executable half of the
contract above. They pin the round trip, fragment reassembly, the size ceiling closing rather than
truncating, the binary refusal, `receive()` returning `null` on peer close, close-code fidelity,
`send`-after-close carrying no content, both origin-refusal orderings, callback refusal and
subprotocol acceptance, and session identity within and across connections.

`CommunityWebSocketEmbeddedBootlessTest` pins the embedded mode itself: an engine that starts with no
`ScopedValue` bound and nothing booted, a provider reachable through `ServiceLoader`, and a repeated
`close()` that does not double-release an allocator the engine owns. The requirement had been stated
in ADR-084 and asserted nowhere, while the code refused an unbound allocator outright — the class
named the scenario and the method rejected it.

**What they do not yet cover**: nothing opens a real client socket against the engine. Handler and
handshake are proven through the TCK's fixture, not through a socket-level handshake — recorded in
the v0.12.0 release notes as carry-over, and it is the gap to close before 1.0.

## Telemetry

One JFR event, `eu.exeris.kernel.websocket.Lifecycle`, carrying the phase and the port.
**Single-phase commit** — never `begin()`, then a blocking call, then `commit()` on a virtual
thread; that straddle has crashed the JVM in this repository.

## Not in scope

`permessage-deflate` is deferred, not rejected (ADR-084 §5): it is negotiated at the handshake and
does not touch the handler surface, so it stays additive. Binary frames are not a deferral — the
application surface is text by decision. Session resumption across reconnects belongs to the
consumer, because the kernel holds no session store.
