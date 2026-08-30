# ADR-081: The connection cap and stream shedding are layers, and neither answers with a status

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-08-30                                                                                |
| **Scope**       | `kernel/transport`, `kernel/http`                                                          |
| **Owning Repo** | `exeris-kernel`                                                                            |
| **Driven By**   | v0.12 Stream G / T1-7, the policy half left open when the observability half shipped in v0.11 |
| **Compliance**  | [docs/subsystems/transport.md](../subsystems/transport.md), [docs/subsystems/telemetry.md](../subsystems/telemetry.md), [docs/operations/reference-deployment.md](../operations/reference-deployment.md) |

## Context and Problem Statement

v0.11 made the accept-time connection cap visible: a refusal now emits
`eu.exeris.kernel.transport.CommunityConnectionRefused` and lands in `TransportStats.totalRejected`.
The mechanism question was deliberately left open, and both `transport.md` and the ROADMAP recorded it
in the same words:

> whether an accept-time cap is the right mechanism — as opposed to admitting and shedding at request
> level, **where the response can carry a status** — and whether 1000 is the right default.

**The premise is false in this tree, and that is the first thing this ADR settles.** Request-level
shedding does not carry a status here. `StreamLoadShedder` closes the stream — FIN/RST on TCP,
`STOP_SENDING` on QUIC — emits `StreamShedEvent`, and increments a counter. A search of every
`*/src/main` for a 503 finds two occurrences: the status constant in `HttpStatus`, and
`CommunityHttpRetryPolicy` treating a *peer's* 503 as retryable. **No server path in the kernel
answers a refusal with a status at all.** So the question compared a mechanism against an alternative
that does not exist, and would have been decided on a property neither option has.

Two further facts were measured while checking it, and both change what needs deciding:

- **One field, one enforcement point, two defaults.** `TransportConfig.maxConnections` is enforced in
  exactly one place, `NativeTcpCarrier.tryReserveConnectionSlot`. But the HTTP listener builds its
  `TransportConfig` from `HttpConfig.maxConnections()` (default **1 000**) and the standalone carrier
  from `transport.maxConnections` (default **4 096**). Nothing states the difference, and nothing
  justifies it.
- **The standalone carrier is off unless asked for.** `CommunityTransportSubsystem.resolveMode`
  returns `DISABLED` when `transport.mode` is unset, so in a normal deployment the operative cap is
  the HTTP one, and `transport.maxConnections` does nothing at all.

## Options Considered

**(a) Replace the cap with a status-bearing refusal at request level.** Rejected on layering, not on
preference. A status can only be written to a connection that has been accepted, its buffers
allocated and its request read — that is, after the resources the cap exists to bound have already
been spent. A mechanism that must consume the resource before it can decline cannot be the guard on
that resource. It is also not free of the cap's own criticism: at the point where fds run out, the
status-bearing path fails exactly as bluntly.

**(b) Keep both defaults and document the difference.** Rejected. There is no difference to document:
the same field, enforced by the same code, reached through two constructors. Writing a justification
for an accident is how an accident becomes a contract.

**(c) Unify at 1 000.** Rejected on evidence. 1 000 is the value the project's own benchmark runs had
to raise to get through — a default that fails its first real workload is not a conservative default,
it is a wrong one.

**(d) Chain the keys — let `http.maxConnections` fall back to `transport.maxConnections`.** Rejected.
It leaves two defaults in place and adds a third resolution rule to remember, to save an operator one
key they can simply be told about.

## 🏁 The Decision

1. **Both mechanisms stay, and they are layers rather than alternatives.** The accept-time cap bounds
   *connection slots* — file descriptors and per-connection state — and it decides before any bytes
   are read. PAQS shedding bounds *concurrent stream work* and decides after the stream exists, with
   priority awareness the cap cannot have. Neither can stand in for the other, and a deployment that
   turns one off is unguarded on that axis.

2. **Neither answers with a status, and that is now stated rather than implied.** Both close. A
   status-bearing refusal is a *third* mechanism living above the connection layer, and if it is ever
   wanted it is its own decision — it would sit alongside these two, not replace either. Naming it
   here is what stops the next reader from re-deriving the false comparison.

3. **One default: 4 096.** `HttpConfig.DEFAULT_MAX_CONNECTIONS` moves from 1 000 to 4 096, matching
   the standalone carrier. One field with one enforcement point gets one default.

4. **The key surface is stated, because getting it wrong is silent.** `http.maxConnections` governs
   the HTTP listener — the carrier almost every deployment runs. `transport.maxConnections` governs
   the standalone carrier, which exists only when `transport.mode` is set. Setting the transport key
   on an HTTP deployment changes nothing and reports nothing; the documentation now says so at the
   point where an operator reads about the cap.

5. **A setup fault is counted separately from a refusal.** `TransportStats` gains `acceptFaults`, and
   it is deliberately **not** folded into `totalRejected`, which means work the engine *declined* — a
   setup that broke declined nothing. Folding them removes the single distinction that changes what an
   operator does next: a capacity problem versus a defect. This closes the last open clause of the
   v0.11 merge gate, which asked for the fault count to be exposed alongside the refusal count.

## Consequences

- **The file-descriptor limit is now the thing to check, and the ADR says so rather than assuming it.**
  A cap only refuses cleanly while it is the ceiling reached *first*. Above `ulimit -n` — commonly
  1 024, i.e. below this default — the operating system's limit is reached first, and its failure mode
  is worse than a refusal. This is not created by raising the default; at 1 000 the two ceilings were
  already close enough to race. Raising it widens the band in which the OS wins, which is why
  `reference-deployment.md` now carries the requirement explicitly.

- **And the OS failure mode is currently fatal, which is recorded here as a defect rather than
  smoothed over.** An `IOException` from `accept()` — how `EMFILE` surfaces — reaches
  `runAcceptorLoop`, which calls `handleAsyncFailure` and returns: `running` is cleared and the server
  channel closed. The listener stops accepting **permanently**, from a condition that is transient and
  recoverable. Not fixed in this ADR's change on purpose — distinguishing transient from fatal accept
  errors is a behaviour change needing its own tests, and the plan's own rule for this stream is not to
  change behaviour and observability in one commit. Tracked in the ROADMAP as its own entry.

- **A deployment that relied on the 1 000 default now admits four times as many connections** before
  refusing. That is the intended effect, and per-connection off-heap state scales with it.

- `TransportStats` gains a component. It is appended rather than grouped beside `totalRejected`, and
  the six-argument constructor is retained, so no existing positional call changes meaning. The SPI
  compatibility gate reports an addition on a `stable` surface.

## Dissent recorded

The unification takes 4 096 from one workload's experience. It is the only operational evidence
available, and it points one way, but it is a benchmark rather than a survey of deployments — a
production deployment on a small container may find 4 096 too generous for its memory budget before it
finds it too small for its load. The default is a starting point, not a capacity recommendation; the
key exists precisely so it can be set.

## Cross-references

- ADR-010 (PAQS priority-aware admission), ADR-071 (operational limits get a configuration path — the
  admission ceiling `transport.paqs.maxActiveStreams` is the stream-side sibling of this cap).
- `docs/subsystems/transport.md` §"Connection ceiling" and §"Accept-path failure modes".
- `docs/subsystems/telemetry.md` §Required Events for both accept-path events.
