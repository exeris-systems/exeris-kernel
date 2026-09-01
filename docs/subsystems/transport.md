# Kernel Subsystem: Transport (L2 Native I/O)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.transport.*` (TransportStream, TransportConnection, TransportProvider, TransportEngine)
- Core: `eu.exeris.kernel.core.transport.*` (PAQS Scheduler, Load Shedding, Stream Lifecycle)
- Drivers:
    - **`community`**: **Exeris Native TCP Carrier** — Custom off-heap TCP/TLS engine built on
      Panama FFM and OpenSSL, designed for Virtual Thread density on standard POSIX networking.

**Layer:** L2 (Native I/O)  
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Transport subsystem** is the Kernel's high-density gateway. It rejects legacy reactive event-loops
in favor of a **Virtual Thread-per-Stream** architecture, mapping data from the wire directly into
off-heap `LoanedBuffer` slabs via Panama FFM — no JVM heap contact on the ingress path.

- **PAQS Scheduler:** The **Priority-Aware Queue Scheduler** injects business context at the network edge.
  Low-priority traffic (e.g., telemetry) is shed before any heap state is allocated, preserving resources
  for critical flows (e.g., payments).
- **Native Everywhere:** The Community tier rejects Netty and Tomcat entirely. It uses a custom off-heap
  TCP carrier with Panama FFM and OpenSSL, delivering zero object churn on the network hot-path.
- **Protocol Blindness:** Business logic operates on abstract `Stream` objects — oblivious to whether data
  flows over TCP on Windows or QUIC/`io_uring` on Linux.

---

## Connection ceiling

`TransportConfig.maxConnections()` is a hard cap on **concurrent connections across all reactors**,
enforced at accept time in one place, `NativeTcpCarrier.tryReserveConnectionSlot`. When it is reached,
the carrier accepts the connection at TCP level and immediately closes it.

**Which key sets it depends on which carrier you are running, and setting the wrong one is silent**
(ADR-081):

| Carrier | Key | Default | When it exists |
|---|---|---|---|
| HTTP listener | `http.maxConnections` | 4096 | whenever the HTTP subsystem is enabled — the carrier almost every deployment runs |
| Standalone transport | `transport.maxConnections` | 4096 | only when `transport.mode` is set; the subsystem resolves to `DISABLED` otherwise |

The two defaults were 1000 and 4096 until v0.12 — one field, one enforcement point, two numbers, no
stated reason. ADR-081 unified them at 4096, the value the project's own benchmark runs had to raise
the HTTP side to.

**Check `ulimit -n` against this number.** The cap can only refuse cleanly while it is the ceiling
reached *first*. Above the process file-descriptor limit — commonly 1024, below this default — the
operating system's limit is reached first, and its failure mode is worse: see §"Accept-path failure
modes".

What a client observes: the socket opens and then dies, with no HTTP response — a transport-level
failure, not a status code, and indistinguishable from a network fault. **The stream admission
ceiling below behaves the same way**: a shed stream is closed (FIN/RST on TCP, `STOP_SENDING` on
QUIC), not answered. No server path in the kernel refuses with a status; if that is ever wanted it is
a third mechanism above the connection layer, not a replacement for either of these (ADR-081 §2).

Two properties make the ceiling easy to reach at a request rate that feels comfortable. It counts
**concurrent connections, not rate**, so a client whose connection pool overlaps with a draining
previous pool can cross it while neither pool alone comes close. And it is enforced before anything
else — no queue, no shed decision, no response.

Refusals are visible in two places, and were visible in neither before v0.11:

- `TransportStats.totalRejected` includes them. It previously counted only PAQS load-sheds, so it read
  zero during a total accept-time refusal — a value an operator reasonably reads as "this server is
  healthy, look elsewhere".
- `eu.exeris.kernel.transport.CommunityConnectionRefused` is emitted per refusal, carrying the active
  count, the configured ceiling, and a cumulative total whose slope distinguishes a blip from a wall.

## Stream admission ceiling

`TransportConfig.maxActiveStreams()` (`transport.paqs.maxActiveStreams`, default
`TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS` = 5000) is a hard cap on **concurrently admitted
streams per engine**, enforced by `AdmissionController.admit` after the memory-pressure decision and
before a virtual thread is spawned. Over the cap the decision becomes `SHED_CAPACITY` whatever the
priority and whatever the watermark says.

This is a different bound from the connection ceiling above, and one is not derivable from the
other: an HTTP/1 connection carries one stream, an HTTP/2 connection carries many.

What a client observes: `StreamLoadShedder` closes the stream and emits
`eu.exeris.kernel.transport.StreamShed` carrying the priority, the decision (`SHED_CAPACITY` rather
than `SHED_MEMORY`) and the active count — so a capacity shed is distinguishable in JFR from a
memory-pressure shed, which is the distinction that says whether raising the ceiling is the right
response.

**What the values mean** (the classification is ADR-071's):

| Value | Meaning |
|:--|:--|
| `> 0` | the ceiling, as given |
| `-1` (`TransportConfig.UNBOUNDED_ACTIVE_STREAMS`) | no stream-count ceiling |
| `0`, other negatives | **refused at startup**, by `TransportConfig` and again by `AdmissionController` |

`-1` removes the count cap, not admission control: the memory-pressure arbiter still decides every
stream first, so an engine configured this way still sheds under watermark pressure. What it gives
up is the ceiling that sheds *regardless* of memory pressure — the point for a JVM-controlled
deployment measuring where saturation actually falls, and a foot-gun for a shared one. `0` is
refused rather than read as "off" because an engine that admits nothing serves nothing.

Both construction sites read the key: the transport subsystem and the HTTP listener, which builds
its own `TransportConfig`. A key honoured on only one of them would apply to a standalone carrier
and not to the server most deployments run.

**Not a knob:** `PaqsScheduler.SPIN_THRESHOLD` has twice been catalogued as a PAQS operational
limit. It is reachable only from `close()`, decides nothing about which streams are served, and
bounds only how the drain spends CPU while it waits — under a drain deadline that is itself
deliberately fixed. It stays a constant.

## Idle connection reclamation

`TransportConfig.idleTimeoutMillis()` (`transport.idleTimeoutMillis`, and `http.idleTimeoutMillis`
reaching the same carrier through `HttpConfig`, default `HttpConfig.DEFAULT_IDLE_TIMEOUT_MS` =
30 000) closes a connection that has moved no bytes for that long.

**What the values mean** (ADR-071's capacity/timeout class):

| Value | Meaning |
|:--|:--|
| `> 0` | reclaim after this many milliseconds without activity |
| `0` | reclamation disabled — connections are held until the peer or the application closes them |
| negatives | **refused at startup** by `HttpConfig` validation |

**What counts as activity is deliberately asymmetric between the two directions**, and the
asymmetry is the point rather than an oversight:

| Direction | Refreshes the stamp | Does not |
|:--|:--|:--|
| ingress | the application calling `read()` | bytes merely *arriving* in the inbound queue |
| egress | `write()` / `queueWrite()`, **and** bytes actually leaving the socket — the direct write, the reactor's deferred drain, and the TLS drain | — |

On ingress, only an attempted read counts. A peer that opens a connection and sends nothing never
causes one, so the same stamp that expires an idle keep-alive also bounds a slow-loris hold: a
handler parked in `read()` for a body that never arrives has not refreshed its stamp since the read
began. **Stamping on arrival would defeat exactly that** — dribbling one byte per second is what a
slow-loris does, and a connection nobody is reading is not doing work.

On egress the opposite is required: bytes leaving *are* the server doing work for this connection,
so a large response draining to a slow client keeps its own connection alive rather than being cut
off mid-transfer. `write()` reaching `queueWrite` only on the TLS branch is what made this wrong in
the first cut — plaintext responses, which is `CommunityHttpExchange`'s path and the default one,
moved bytes without counting, and idle reclamation became a property of whether TLS was configured.

**The sweep is per reactor and rides a wake-up that was going to happen anyway.**
`NativeTcpIdleReaper` runs inside the reactor's select loop after the selected keys are dispatched
— no timer thread, no scheduled task, and no state shared between reactors. It is gated to
`idleTimeout / 4` clamped to [250 ms, 5 s], because a reactor returns from `select` thousands of
times a second under load and an ungated O(keys) walk would put a scan of the whole connection set
on the hot path to enforce a limit measured in seconds. The same cadence shape, for the same
reason, governs `CommunityTenantPoolRegistry`'s pool reclaim.

**Teardown is abortive**, through the same `closeKeyStream` path as a dispatch fault. A graceful
close waits for queued egress to drain, and a peer quiet enough to be reclaimed is exactly the peer
that may never drain it.

What an operator observes: `eu.exeris.kernel.transport.CommunityConnectionIdleTimeout`, carrying
the stream id, the reactor index, the observed idle span and the configured timeout. Both spans are
on the event because the ratio is the interesting quantity — idle spans clustered just past the
limit mean a fleet being trimmed on a threshold, spans minutes past it mean genuinely dead peers.

> **This limit did nothing before 0.12.0.** It was read from configuration, validated, carried
> through `HttpConfig` into `TransportConfig` and rendered by `toString()`, and never compared to
> anything: no code read `idleTimeoutMillis()` outside construction. A knob that is carried but
> unconsumed is worse than a missing one, because the missing one is discoverable — the operator
> who sets it sees it echoed back and concludes it works.

## Accept-path failure modes

There are two ways an accepted connection ends without being served, and they look identical from the
client: the socket opens and dies. They are different in kind, and the kernel now distinguishes them.

| | Refusal | Setup fault |
|---|---|---|
| Event | `CommunityConnectionRefused` | `CommunityAcceptFault` |
| Cause | the connection ceiling was reached | something threw while configuring the channel, resolving the peer, building the stream, or registering it |
| Nature | a **policy** decision at a known limit | a **defect** — allocator failure, TLS engine that would not initialise, registry inconsistency |
| In `totalRejected` | yes | **no** |
| Counted on `TransportStats` | `totalRejected` | `acceptFaults` (since 0.12.0) |

A setup fault is deliberately **not** counted as a refusal. `totalRejected` means work the engine
*declined*, and a setup that broke declined nothing. Folding the two together would leave an operator
reading a spike unable to tell a capacity problem from a bug — the one distinction that changes what
they do next. Until 0.12.0 the fault count reached an operator only inside the JFR payload, so the two
modes were documented together but not *observable* together; `TransportStats.acceptFaults` closes
that (ADR-081 §5). A driver with no accept-setup path reports `0`.

Both paths recover: the accept loop continues. That is correct for a per-connection failure, and
making a setup fault fatal would trade a silent drop for an outage. What changed is only that neither
is silent.

**A third mode is neither of these, and since 0.12.0 it recovers.** An `IOException` from `accept()`
itself — how file-descriptor exhaustion (`EMFILE`) surfaces — is the *listener* failing to produce a
connection, not one connection failing to be set up. Until 0.12.0 it was fatal: `runAcceptorLoop`
called `handleAsyncFailure` and returned, so a process that touched its `ulimit -n` once needed a
restart to serve again, from a condition that clears on its own as connections close.

| | Accept retry |
|---|---|
| Event | `CommunityAcceptRetry` |
| Cause | `accept()` itself threw — descriptor exhaustion, an aborted connection |
| Nature | a **transient** condition affecting the listener rather than a connection |
| Response | pause and try again; `25ms × streak`, capped at 1s |
| Bound | 64 consecutive failures are retried; the 65th takes the fatal path |

**Every `IOException` is retried rather than a list of transient ones**, and that is deliberate. Java
offers no typed signal — `EMFILE` arrives as a plain `IOException` — so classifying means matching on
a message, and a message that does not match on some JDK, OS or locale reinstates the defect. The two
mistakes are not symmetric: retrying a fatal condition costs a bounded delay before the same outcome,
while declining to retry a transient one loses the listener. The ceiling does the work classification
would have done, and the event's streak is what separates one retry from a process that is not
recovering.

**What ends a streak is an accept that returned a socket**, counted at the accept itself rather than
inferred from how a pass finished. The distinction is the one descriptor pressure actually produces:
descriptors free one at a time, so a pass accepts a connection and then throws probing for the next.
It leaves by the throw, so a streak built from what the pass *returned* would climb to the ceiling
while the listener was demonstrably still serving. An accept the connection cap then refuses counts
too — what it witnesses is the listener working, which is a different question from whether the
connection was served.

The connection ceiling above still asks you to check `ulimit -n`. The cap refusing is the good
outcome, and the OS limit winning the race is now survivable rather than terminal — but it is still
the worse of the two.

The fault event carries the exception **class** and never its message, matching
`CommunityReactorDispatchFault` — a message can carry request-derived text.

## Core Philosophy

### 1. Carrier Loop Architecture

Transport logic centers around dedicated **Carrier Loops**. Each loop manages native sockets, task queues
(`MpscArrayQueue`), and local memory pools (`SlabPool`) in a tight, non-blocking execution cycle.

The relationship between Carrier Loops and Virtual Threads is deliberate:

- **Carrier Loop:** Owns the native I/O rings. Wakes Virtual Threads when data is ready. Never executes
  business logic.
- **Virtual Thread:** Executes business logic imperatively. Parks on I/O waits. Carrier Loop resumes it
  at zero OS context-switch cost.

This separation gives you the simplicity of blocking, imperative code with the performance of a native
event loop.

### 2. PAQS: Business Context at the Network Edge

PAQS is the enforcement arm of the `ResourceArbiter`. When `WatermarkManager` signals memory pressure,
PAQS raises the shedding threshold — streams below that priority are dropped at the transport edge before
a single byte enters the application stack. This prevents GC pressure from cascading into business logic.

### 3. Zero-Copy Ingress

The Community carrier transfers bytes from the network socket directly into off-heap `LoanedBuffer` slabs via Panama FFM. No intermediate `byte[]`, no `ByteBuffer.allocate()`, no heap serialization.

---

## Responsibilities

**What Transport SPI DOES:**

1. Define the `TransportStream` and `TransportConnection` lifecycle interfaces.
2. Provide `TransportProvider` and `TransportEngine` for native driver registration via `ServiceLoader`.
3. 🚧 **Planned (TRL-4):** Define `StreamHeaders` SPI interface for protocol-blind header access. Not yet implemented — current header access is via `TransportStream` implementation directly.
4. Define `StreamPriority` enum consumed by the PAQS Scheduler.

**What Transport Core DOES:**

1. Implement the **PAQS Scheduler** and global Load Shedding logic.
2. Coordinate with `ResourceArbiter` to monitor `SlabPool` exhaustion and trigger backpressure.
3. Manage Virtual Thread lifecycle (one per incoming stream). The PAQS spawns per-stream virtual threads
   via `Thread.ofVirtual().start()` — the sole deliberate exception to the `StructuredTaskScope` mandate.
   `StructuredTaskScope.fork()` enforces `WrongThreadException` for any caller that did not open the scope;
   since `schedule()` is called concurrently by multiple carrier threads (NIO selectors, io_uring rings),
   a shared long-lived STS is architecturally incompatible with the multi-carrier ingress model. These
   unstructured VTs act as roots of the Request Tree. All subsequent concurrent operations within
   the stream handler MUST use `StructuredTaskScope`.
4. Expose cross-platform POSIX / Winsock socket symbol loading via `CoreSyscallLoader` (Panama FFM). The POSIX half of that seam is exercised end-to-end since 0.11 (`SyscallLoopbackRoundTripIT`, below); the Winsock half has never run, because CI carries no Windows runner. Migration of the active Community carrier onto this shared socket path is planned but gated on that coverage; the current in-repo carrier remains NIO-backed, and NIO is retained as the explicit fallback path for portability and degraded-operation scenarios.

---

## Provider Selection — Descending Priority + First-Available

`CommunityTransportSubsystem` resolves a `TransportProvider` at bootstrap via the shared
`BootstrapProviderSelector.loadHighestPriority(...)` helper with the availability filter
`TransportProvider::isAvailable`. Selection semantics:

1. **Discovery:** all `TransportProvider` implementations on the classpath are loaded via `ServiceLoader`.
2. **Availability filter:** providers for which `isAvailable()` returns `false` are removed from the
   candidate set. Platform-conditional providers (e.g., io_uring on Linux only, IOCP on Windows only)
   gate themselves here. The default `isAvailable()` returns `true`.
3. **Descending priority + deterministic tie-break:** remaining candidates are ranked by
   `priority()` (higher wins). On ties, the implementation class name (alphabetical, last wins under
   `Stream.max`) breaks the tie deterministically across JVMs and ServiceLoader orderings.
4. **First-available wins:** the highest-priority available provider is selected. If the candidate
   set is empty, no transport engine is created and the subsystem stays in `DISABLED` mode.

This means an unavailable higher-priority provider (e.g., io_uring on a non-Linux host) does NOT
shadow an available lower-priority provider (e.g., NIO Community baseline). The selection contract
is exercised by `BootstrapProviderSelectorTest` and validated end-to-end through the
`CommunityTransportSubsystemLifecycleTckTest` binding.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Meaning                  | Glass-Box Payload (`rawArgs`)                                               |
|:--------------|:-------------------------|:----------------------------------------------------------------------------|
| `EX-NET-4001` | Bind / Handshake Failure | `[0] String transportName, [1] int port`                                    |
| `EX-NET-4002` | Send Failure             | `[0] String transportName, [1] long bytesSent`                              |
| `EX-NET-4003` | Receive Timeout          | `[0] String transportName, [1] long timeoutMs`                              |
| `EX-NET-4004` | Engine Bootstrap Failure | `[0] String transportName, [1] String reason`                               |
| `EX-NET-4005` | Port Already in Use      | `[0] String transportName, [1] int port` — **Fatal:** check OS process list |
| `EX-NET-4006` | PAQS Load Shedding       | `[0] String transportName, [1] int streamPriority, [2] int thresholdPriority`|
| `EX-NET-4007` | Buffer Exhaustion        | `[0] String transportName, [1] int poolCapacity, [2] int activeSlabs`       |

**Operational note for `EX-NET-4006`:** This is a deliberate, non-fatal policy decision — not a hardware
failure. No connection state or heap object is allocated for the shed stream. It MUST emit a JFR event
(`eu.exeris.kernel.core.transport.jfr.StreamShedEvent`) but must not increment error counters used for alerting.

**Operational note for `EX-NET-4007`:** When thrown, the `WatermarkManager` detects slab exhaustion and elevates the watermark level. `ResourceArbiter.decide(Context.TRANSPORT_IO)` then returns `Action.SHED_LOAD`, signaling PAQS to reject new streams.

---

## Code Examples

### 2. PAQS Priority Gate (Core)

```java
public void onStreamArrival(TransportStream stream, StreamPriority priority) {
    if (resourceArbiter.currentThreshold().isAbove(priority)) {
        stream.close();
        // stream is closed and StreamShedEvent is emitted — no exception propagated
        return;
    }
    Thread.ofVirtual().start(() -> streamHandler.handle(stream));
}
```

> The priority check is O(1) — a single `int` comparison against the `WatermarkManager` threshold.
> No heap allocation occurs for shed streams. The Virtual Thread is never created.

### 3. Protocol-Blind Stream Handler (SPI)

```java
package eu.exeris.kernel.spi.transport;

public interface StreamHandler {
    void handle(Stream stream);
}
```

---

## PAQS Decision Flow (Full Sequence)

The diagram below shows the complete path of an incoming stream through the PAQS admission gate —
from NIC arrival through the WatermarkManager check to either Virtual Thread fork or shed.

```mermaid
sequenceDiagram
    autonumber
    participant NIC   as NIC / Transport Carrier
    participant PAQS  as PAQS Scheduler (Core)
    participant WM    as WatermarkManager (L0)
    participant RA    as ResourceArbiter (L0)
    participant VT    as Virtual Thread (Loom)

    NIC->>PAQS: onStreamArrival(stream, rawHeaders)
    PAQS->>PAQS: extractStreamPriority(stream)<br/>HTTP header / JWT claim / config default

    PAQS->>WM: currentWatermarkLevel()
    WM-->>PAQS: NORMAL | WARNING | CRITICAL | SHEDDING

    alt NORMAL watermark
        PAQS->>RA: reserveSlabSlot()
        RA-->>PAQS: ResourceArbiter.Action (ALLOW — slot granted)
        PAQS->>VT: Thread.ofVirtual().start(streamHandler)
        Note over VT: Request processed imperatively.<br/>ScopedValues bound. StructuredTaskScope used downstream.
    else WARNING watermark — priority gate active
        PAQS->>PAQS: streamPriority.ordinal() <= threshold?
        alt priority sufficient
            PAQS->>RA: reserveSlabSlot()
            RA-->>PAQS: ResourceArbiter.Action (THROTTLE — slot granted)
            PAQS->>VT: Thread.ofVirtual().start(streamHandler)
        else priority insufficient
            PAQS->>NIC: stream.close()<br/>[TCP FIN (graceful close) / QUIC CONNECTION_CLOSE]
            PAQS->>PAQS: emit StreamShedEvent (JFR, @StackTrace false)
            Note over PAQS: Stream shed via close() + StreamShedEvent.<br/>No exception thrown back to carrier VT.<br/>No VT spawned, no heap allocation, no log.
        end
    else CRITICAL or SHEDDING watermark — shed all
        PAQS->>NIC: stream.close()
        PAQS->>PAQS: emit StreamShedEvent (JFR)
        Note over PAQS: All streams shed (close + StreamShedEvent),<br/>no exception propagated to the carrier VT.
    end
```

---

## `StreamPriority` — Origin and Assignment

`StreamPriority` is not self-declared by the client. It is **assigned by the Kernel** at the transport
edge based on verifiable attributes. The priority assignment chain:

| Source                             | Mechanism                                                                                         | Trust Level        |
|:-----------------------------------|:--------------------------------------------------------------------------------------------------|:-------------------|
| **Authenticated principal attribute** | Derived from the validated `PrincipalContext` provided by `SecurityProvider` before PAQS admission (for example, from a token claim or directory attribute). Cannot be client-forged once authentication succeeds. | High (verified) |
| **HTTP/QUIC stream header `x-priority`** | Mapped to `StreamPriority` enum by transport carrier. Treated as **untrusted input** — capped at `NORMAL` unless an authenticated principal attribute (see above) overrides. | Low (untrusted) |
| **Endpoint configuration**         | Static mapping via `network.paqs.endpointPriority.<path>` config key. Overrides header. | High (operator) |
| **Default (no source)**            | `StreamPriority.NORMAL`                                                                         | N/A               |

**Priority enum (SPI):**

```
enum StreamPriority {
    CRITICAL,   // payments, health checks, ops — never shed except at SHEDDING watermark
    HIGH,       // premium tier, authenticated business flows
    NORMAL,     // default — most application traffic
    LOW,        // lower-value application traffic, batch jobs, analytics
    TELEMETRY   // observability-only streams, fire-and-forget metrics/logs
}
```

> **PAQS invariant:** Priority is evaluated **once** at stream admission. It cannot change mid-stream.
> This ensures O(1) shed decisions with zero re-evaluation overhead.

---

## Connection Draining Policy

When PAQS sheds a stream or the Kernel initiates graceful shutdown:

| Scenario               | Community (TCP)                                     |
|:-----------------------|:----------------------------------------------------|
| **PAQS load shed**     | `FIN` (graceful close) — client receives `HTTP 503` |
| **Graceful shutdown**  | `FIN` after drain timeout — no forced `RST`         |
| **Hard shutdown timeout** | `RST` after 60 s hard timeout                   |
| **`TransportStream.reset(long)`** | `RST` (abortive) — `SO_LINGER 0` then close; queued writes abandoned (no drain wait) |
| **Unrecoverable outbound-write failure** | `RST` (abortive) — queued writes abandoned so teardown cannot hang |

> **Why `FIN` not `RST` for load shedding?** `RST` causes immediate connection teardown on the client
> side, which may interrupt in-flight retries and force the client to reconnect. `FIN` allows the client
> to receive the `HTTP 503` response body, which is machine-readable and enables intelligent backoff.
> `RST` is reserved for hard timeout scenarios only.
>
> **`reset(long)` vs `close()`:** `close()` is a graceful end-of-stream (`FIN`, drains queued writes);
> `reset(long)` is the deliberate abortive primitive (`RST`, abandons queued writes) — the SPI's
> transport-agnostic stream abort (an Enterprise QUIC binding maps it to `RESET_STREAM`). A failed
> outbound write self-aborts via the same path. **Telemetry follow-up:** a secret-safe, single-phase
> `StreamLifecycleEvent` for the reset transition is deferred to the security/transport
> validation-stage JFR pass (Phase 4 N1) — the reset call sites are the intended emission points.

### Graceful-shutdown phase order (since 0.11)

`NativeTcpCarrier.stop()` runs three phases, and the order is load-bearing rather than incidental:

1. **Close ingress** — the listening channel closes and the acceptor joins. No new connections; the
   reactors keep serving the ones already admitted.
2. **Drain** — `PaqsScheduler.close()` marks the engine draining and waits for the count of **busy**
   streams to reach zero, under its 60-second hard deadline. The reactors are deliberately still
   looping here, so a handler that completes during the drain can still have its response flushed.
3. **Teardown** — reactors are woken and joined, then the selector and all remaining channels close.

**Observing zero and committing to teardown are one step.** `DrainCoordinator.sealIfIdle()` is a
compare-and-set on the busy count, not a read followed by a decision. Read as two steps, a request that
arrived while the drain was looking flips the count back to one on an engine already past its commit
point, and phase 3 severs the stream serving it — the failure phase ordering exists to prevent, reached
from the other side. Asking protocols to check `isDraining()` first does not close it: a request already
sitting in the socket buffer when the drain began has nobody left to ask.

After the seal, `StreamWork.markBusy()` answers `false` and the protocol layer closes rather than
serves — `CommunityHttpRequestProcessor` returns from its keep-alive loop. That is the honest reading of
a connection the peer was already told to close: a request arriving after that is racing teardown, not
being dropped by it. On the 60-second deadline the coordinator seals unconditionally, because once
shutdown proceeds regardless, letting a late arrival believe it is protected is worse than closing on it.

**The per-stream handle is not thread-confined.** `StreamWork` reaches protocol code through the
`STREAM_WORK` `ScopedValue` — chosen precisely so it survives down the stack and across a task
boundary. The reachable route is track-independent and does not rest on `StructuredTaskScope` (which
the default line must not ship for 1.0 GA): `StreamExecutionBackend`, the seam at which an admitted
stream's root task is started, is contractually required to preserve `ScopedValue` bindings across
whatever thread a backend chooses, and its forward consumers — an Enterprise locality-aware backend,
the post-1.0 DST simulation scheduler — are exactly the ones that run the task on a thread the
scheduler did not create. On the `preview` artifact `StructuredTaskScope.fork()`'s binding inheritance
is a second route to the same place. Both the shared count and the handle's flag are therefore
compare-and-set. A double release drops the
count below the truth and ends the drain early; a double acquire means it never ends. Neither is
reachable by anything short of a stress harness, so the guarantee is a property of the type rather than
an assumption about callers.

Until 0.11 the drain ran *after* teardown, which made it inert: handlers were waited on while their
sockets were already closed, so an in-flight request completed and its response went nowhere. The peer
observed a connection closed with no reply, and an idempotent client retried into a listener that was
already gone. `AbstractTransportEngineTck$GracefulDrain` now pins the contract — it holds a stream
mid-exchange across `stop()` and asserts the response still arrives.

**Busy, not open — and busy by default (since 0.11, issue #282).** The drain waits for streams being
*served*, not for streams being *open*. Until this was separated it waited on the admission
controller's active-stream count, which counts connections: a keep-alive connection holds a slot for
its whole life, so an idle one burned the entire 60-second deadline. Every connection-pooling client,
load balancer and service mesh holds connections idle, and a container runtime's default grace period
is shorter than the deadline — so the normal outcome was SIGKILL mid-shutdown on every rollout.

A stream is **busy from the moment it starts** and stays busy unless its protocol reports otherwise
(`DrainCoordinator.StreamWork`). The default is load-bearing in the opposite direction from the
obvious one: counting only work a protocol explicitly declares means a protocol that declares nothing
looks finished, so teardown severs a handler still writing its response — the very regression the
phase order above exists to prevent. A raw `StreamHandler` therefore keeps the full drain; only a
codec that knows it is parked between requests — the HTTP/1.1 keep-alive loop — reports idle.

**The 60-second deadline is longer than the platform's, on purpose.** A container runtime's default
grace period is shorter — thirty seconds on Kubernetes — so a drain that ran to this deadline would be
SIGKILLed before reaching it. That is the intended ordering, not an oversight: the orchestrator's timer
decides how long the *platform* waits, while this one decides how long the *kernel* is willing to
strand a request still being served. A shorter kernel deadline would make the kernel sever live work
while the platform still had time to spare, and it would do so silently. Above it, the platform stays
the authority.

Since the busy/open separation above, a normal shutdown ends in milliseconds and this bound is reached
only when a handler will not return — an application defect, for which SIGKILL is the right answer.
It is therefore **not configurable**; a knob here would be tuned in place of fixing the handler. An
idle-connection reaper, which would let the drain close connections parked past a threshold rather
than waiting on them, is a separate question and is not implemented.

**`Connection: close` while draining.** A response encoded after the drain has begun carries it,
whatever the request asked for. Without it a well-behaved peer has no way to learn it should release a
pooled connection, and the next shutdown waits on the same idle connection again.

The question is asked when the response is written, not when the request was parsed — and the
distinction is the whole point. The request that most needs to tell its peer to let go is the one
already in flight when shutdown began; answered at parse time it always gets the pre-shutdown answer,
so the peer keeps a connection it will never be asked about again.

**Telemetry.** `CommunityTransportDrainEvent` (JFR `eu.exeris.kernel.transport.CommunityTransportDrain`)
records `busyAtStart`, `busyRemaining`, `openAtStart` and duration. A non-zero `busyRemaining` means
the deadline fired while work was still in flight — the signal that in-flight work was severed, and
the number to tune `terminationGracePeriodSeconds` against. `openAtStart` is reported for context: it
is the number the drain waited on before 0.11, and the gap between it and `busyAtStart` is exactly the
idle connections that used to hold shutdown open.

---

## WebSocket / SSE — Design Stance

**Server-Sent Events (SSE) is the kernel's first server-push primitive — decided in [ADR-043](../adr/ADR-043-kernel-http-streaming-spi.md) (ACCEPTED), implemented in v0.10.** WebSocket remains unsupported and is a separately-justified follow-up. No server-push existed at TRL-3; this was a deliberate scope constraint, now being lifted SSE-first.

| Protocol     | Status      | Rationale                                                                                              |
|:-------------|:------------|:-------------------------------------------------------------------------------------------------------|
| **SSE**       | ✅ Shipped v0.10 — ratified by [ADR-043](../adr/ADR-043-kernel-http-streaming-spi.md) (ACCEPTED) | One-directional server push, surfaced as the sibling `HttpStreamExchange` SPI (respond-once `HttpExchange` untouched). **SSE-first** — the minimal server-push primitive. The delivered wire framing is a close-delimited HTTP/1.1 response, not chunked transfer as sketched here before it landed; per-event chunked framing and an HTTP/2 `DATA` path are follow-ups. See [http.md](http.md) for the SPI surface and the per-item delivery status. |
| **WebSocket** | Deferred — separately-justified follow-up (not milestone-pinned) | Full duplex; requires an HTTP Upgrade (H1) / Extended CONNECT (H2 RFC 8441) handshake + frame protocol. Decided separately once a bidirectional/low-latency client-streaming use case is proven; may precede 1.0 but is not pinned to a release milestone (see ADR-043 §What is NOT in scope). |
| **gRPC streaming** | 🚧 Planned TRL-5 | Modelled as HTTP/2 streams — follows transport carrier maturity. |

Full-duplex traffic still has no kernel primitive: until WebSocket is decided, a bidirectional
use case pairs SSE downstream with ordinary requests upstream, or falls back to the **Events
subsystem (L3)** with a Kafka/Redpanda backend and a polling client. The PAQS scheduler handles
priority-based delivery.

---

## Proxy Protocol v2 — Client IP Preservation

**Status:** 🚧 Planned (no runtime support in current TRL-3 prototype). Configuration keys are defined in `docs/subsystems/config.md` (`network.proxyProtocolEnabled`, `network.proxyProtocolRequired`). This section describes the **target behavior**.

The Community transport driver will support **Proxy Protocol v2** (HAProxy specification)
for client IP preservation behind load balancers (HAProxy, NGINX, AWS NLB, GCP LB).

| Feature                            | Community                           |
|:-----------------------------------|:-----------------------------------:|
| Proxy Protocol v2 parsing          | 🚧 Planned TRL-4                    |
| Real client IP in `StreamHeaders`  | 🚧 Planned via `remoteAddress()`    |
| TLV extension support              | 🚧 Planned TRL-4                    |

> **Target behavior:** when Proxy Protocol is enabled (`network.proxyProtocolEnabled=true`), the transport carrier
> reads the PPv2 header before any TLS handshake attempt. The real client IP is extracted and stored in the
> `Stream` metadata. If the PPv2 header is malformed or absent while `network.proxyProtocolRequired=true`, the
> connection is dropped immediately with `EX-NET-4001` to prevent forged client IPs when Proxy Protocol is
> required by the network topology.

---

## JFR Events

| Event | JFR Name | Purpose |
|---|---|---|
| `StreamShedEvent` | `eu.exeris.kernel.transport.StreamShed` | Emitted by PAQS when stream is rejected under high load |
| `StreamAcceptedEvent` | `eu.exeris.kernel.transport.StreamAccepted` | Emitted by PAQS when stream is admitted |
| `StreamLifecycleEvent` | `eu.exeris.kernel.transport.StreamLifecycle` | State transitions within a stream lifetime |
| `TransportIngressQueueDepthEvent` | `eu.exeris.kernel.transport.IngressQueueDepth` | Current queue depth metric |
| `TransportQueueBackpressureAlertEvent` | `eu.exeris.kernel.transport.QueueBackpressureAlert` | Alert when queue exceeds threshold |
| `CommunityConnectionIdleTimeoutEvent` | `eu.exeris.kernel.transport.CommunityConnectionIdleTimeout` | Connection reclaimed after `transport.idleTimeoutMillis` without activity; carries the observed idle span and the configured limit |

---

## Testing Strategy

### Unit Tests

- PAQS shedding logic: verify streams below threshold are rejected before `scope.fork()`.
- `SlabPool` exhaustion: `EX-NET-4007` thrown with correct `rawArgs` when all slots are active.
- Priority ordering: `StreamPriority` enum ordinals enforce correct relative ordering.

### Integration Tests (TCK)

- **`io_uring` Validation (Linux):** SQ/CQ ring submission integrity under concurrent streams.
- **Zero-Allocation Hot-Path:** JFR baseline shows zero heap allocations during `processIngress()`.
- **PAQS Integration:** `WatermarkManager` pressure increase correctly raises PAQS threshold and
  triggers `EX-NET-4006` for low-priority streams. 🚧 Planned — not yet implemented. `AbstractPaqsIntegrationTck` covering WatermarkManager→PAQS→EX-NET-4006 chain does not yet exist.
- **Berkeley socket seam round-trip (since 0.11):** `SyscallLoopbackRoundTripIT` (Core) drives the handles resolved by `CoreSyscallLoader` through a real loopback connection — bind, listen, connect, accept, send, recv, byte-exact comparison — plus a refusal case that pairs a successful connect with a refused one so neither can pass alone. Runs in the default build via Failsafe; Linux and Windows only, since BSD `sockaddr_in` carries a leading `sin_len` byte that the shared 16-byte layout does not model. Integer-width entries in the C-type→`ValueLayout` table stay reviewed-not-tested: the x86-64 SysV ABI zero-extends, so a `size_t` mis-declared as `JAVA_INT` passes this gate.

**Full TCK abstract class set (all have full Community bindings):** `AbstractTransportEngineTck`, `AbstractTransportProviderTck`, `AbstractTransportConnectionTck`, `AbstractTransportStreamTck`, `TransportZeroAllocTck`, `TransportCarrierPinningTck`.

### Load Tests

- **Carrier Stress:** reactor-handoff queue throughput under sustained network events with zero
  `CarrierPinnedEvent` emissions — `NativeTcpTransportStressTest`, behind the `transport-stress-gate`
  CI job. The queue is JCTools `MpscUnboundedArrayQueue` (PERF-063); the Agrona attribution this line
  carried was never what the code used.

---

## Implementation Notes — Class Decomposition Assessment (HEUR-061)

`NativeTcpCarrier` (~1.4k LOC, 146 declared members) and `NativeTcpStream` (~1.2k LOC, 133 declared members) cross the CLAUDE.md "≈5 collaborators" heuristic threshold. A v0.6 PR-review carry-over (HEUR-061) asked whether they should be decomposed along reactor / FD-owner / PAQS-dispatch responsibility lines.

**Assessment outcome (v0.7 Sprint 2): keep current single-file shape; do not refactor in this sprint.**

**Responsibility lines (informational map, not extraction targets):**

`NativeTcpCarrier`:

- **Engine lifecycle + config** — `TransportConfig` / `MemoryAllocator` / `KernelCryptoProvider` injection, `running` / `closed` AtomicBooleans, acceptor thread, stream/connection counters.
- **FFM socket backend selection + validation** — `SocketBackendMode` enum, `SocketBackendSelection` record, PosixHybrid / NIO fallback, `validateServerSocketBootstrap*` / `validateClientSocketBackend*` probes, Windows `bestEffortWsaCleanup`.
- **Reactor loop** — `ReactorLoop` inner class: `Selector`, `pendingRequests` MPSC queue (PERF-063 — `MpscUnboundedArrayQueue`), `drainPendingRequests`, key dispatch.
- **Acceptor / connection bootstrap** — `runAcceptorLoop`, `acceptPendingConnections`, `tryReserveConnectionSlot`, `buildAcceptedStream`, `registerAndHandshakeConnection`.
- **Client-side connection bootstrap** — `connect` flips the connected channel to non-blocking and `registerClientChannel` registers it on a single client-side reactor (SERVER/DUAL keep `config.reactorCount()` reactors; CLIENT/DUAL outbound uses one). Client ingress and egress are reactor-driven exactly like accepted server channels — there is no separate client ingress/writer Virtual-Thread pump (removed in v0.8 Sprint 7 / TCK-064, which eliminated the blocking-`recv()` carrier-pinning stall).
- **PAQS-dispatch read/flush path** — `readIngress`, `flushStream`, `adaptTlsIfNeeded`, `closeKeyStream`.

`NativeTcpStream`:

- Stream open/close state machine and FD-owner integration.
- Outbound queue (`MpscUnboundedArrayQueue`) and write loop with TLS layering.
- Inbound queue (`SpscArrayQueue` / `SpscUnboundedArrayQueue`) and read loop with TLS layering.
- Backpressure / queue-depth bookkeeping for PAQS interaction.

**Why not extract now:**

1. **Tight coupling cost**: `ReactorLoop` is an inner class deliberately — its operational surface (`channelRuntimeRegistry`, `streamByChannel`, `closeKeyStream`, `readIngress`, `flushStream`) is intimate with carrier state. Promoting it to a top-level class requires ~10 callback / port references injected through the constructor, which trades an inner class for a callback-heavy delegate without measurable readability gain. Same risk applies to FD-owner / PAQS-dispatch extraction.
2. **Active development collision risk**: v0.7 Sprint 5 (Kafka EventEngine), Sprint 6 (distributed integration), and PERF-061 / PERF-062 (HTTP/2 frame writer + TLS ingress slab) all touch transport hot paths. Decomposition during this window adds merge surface to every concurrent PR.
3. **Sibling precedent**: SQ-006 raised the analogous question for `CoreFlowRuntime` and was deferred with a design note rather than a refactor PR. The same disposition applies here.
4. **Heuristic vs hard rule**: the ">5 collaborators" rule is a signal, not a hard gate (CLAUDE.md §C heuristics). Class size alone does not justify forced decomposition when the cost outweighs the benefit.

**When to revisit:**

- If a future sprint introduces a second carrier (e.g., io_uring) that needs to share reactor/FD-owner code — at that point extraction yields a real reuse benefit.
- If profiling data shows that the current class layout regresses inlining or escape analysis on the hot path (no current evidence).
- If the file size exceeds ~2k LOC after Sprint 5/6 changes — at that scale revisit with profiling data and a measured refactor PR.

Tracking: revisit alongside any io_uring / FFM-native carrier work (currently planned post-v0.7).

---

## Summary

The Transport subsystem is the high-performance gateway of the Exeris Kernel. With a Panama FFM TCP carrier and PAQS-enforced shedding, it delivers a zero-object-churn ingress path on every platform. The PAQS Scheduler makes this deterministic under load — shed decisions
are made at the network edge in O(1) time, before a single byte of unauthorized or low-priority traffic
consumes heap, CPU, or a Virtual Thread.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.transport.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md). See the matrix for the semver policy and TCK
coverage status.
