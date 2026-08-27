# ADR-076: A route declares how it executes, and the dispatcher draws the connection consequence

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-08-27                                                                                |
| **Scope**       | `kernel/http`, `kernel/persistence`                                                       |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-08-26](../rfc/RFC-2026-08-26-request-connection-lifetime.md); v0.12 Stream Q / T1-15, 1.0-critical |
| **Compliance**  | [docs/subsystems/persistence.md](../subsystems/persistence.md), [docs/subsystems/http.md](../subsystems/http.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

`CommunityHttpRequestDispatcher.handleWithinRequestSession` binds a `PersistenceSessionBox` around
every non-streaming request. The binding is unconditional and no configuration key disables it
anywhere in SPI or Community. For a handler that returns promptly this is a good promise, and
`docs/subsystems/persistence.md` states it in those words: *"One HTTP request is one connection."*

For a handler that **blocks**, it is hold-and-wait on a single pool. The handler holds its pooled
connection across the block, and the work it waits on draws from that same pool — flow steps run on
bare `Thread.ofVirtual()` in `CoreFlowRuntime.launch`, inherit no `ScopedValue`, and acquire through
`CommunityPersistenceEngine.openPhysicalConnection` independently of any request box. This is the
first cross-runtime benchmark result where the kernel loses **categorically** rather than by a
margin: an availability collapse, reachable from an ordinary application shape with nothing unusual
configured.

RFC-2026-08-26 compared four options and recommends building the per-route seam now while gating any
default flip on a measurement that does not exist. Its six constraints were re-read at their
declaration sites before this ADR was written, because the RFC itself records that two of them were
corrections to its own first draft. All six hold, with one correction of its own:

1. **A shorter lifetime does not cost RLS correctness.** `RlsConnectionInterceptor.onConnectionAcquired`
   republishes the session keys on **every** acquire — isolation is re-established per acquisition,
   not per request. What a shorter lifetime costs is round-trips.
2. **`PersistenceSessionBox.release()` is terminal** — afterwards the box emits
   `RequestSessionLifecycleEvent("REJECTED_RELEASED")` and throws.
3. **The box is Community-tier, not SPI.**
4. **The per-route declaration seam already exists, is SPI, and is resolved on every dispatch** —
   `HttpRoutePolicy.requirementFor(method, path)` returns a `RouteRequirement`, a `public final class`
   with private constructors and static factories, on the `preview` stability surface since 0.11.0
   (ADR-061).
5. **A shipped integration test pins the current promise by physical identity** —
   `CommunityRequestScopeBypassIsolationIT` asserts the same PostgreSQL backend PID across two
   acquires inside one request.
6. **The no-op `close()` is load-bearing** — and the correction: `NonOwningPersistenceConnection` is
   a **private static nested class inside `PersistenceSessionBox`**, not the top-level type the RFC
   names. Nothing outside the box can reference it, which is why release ownership cannot migrate to
   the handle without making a private type public.

## Options Considered

### Option 1 — The route declares its **execution shape**; the dispatcher draws the persistence consequence *(chosen)*

`RouteRequirement` gains an execution facet — `PROMPT` (default) or `LONG_RUNNING`. `spi.http` learns
nothing about connections; the Community dispatcher, which already knows both sides, is what decides
that a `LONG_RUNNING` route gets no request session.

Costs one carrier extension on a `preview`-tier type, no new SPI type in a subsystem 1.0 commits to,
and no second policy resolution per dispatch. The default reproduces today's behaviour exactly, so an
implementation that never names the facet is unaffected.

### Option 2 — A persistence-named lifetime facet on `RouteRequirement`

Same mechanism, but the facet says `ConnectionLifetime.REQUEST | UNIT_OF_WORK`. Rejected: it puts
persistence vocabulary on an `spi.http` carrier, so the HTTP SPI would name a concept it must stay
blind to. The Wall is the reason, and the cost of honouring it is a rename.

### Option 3 — A second method on `HttpRoutePolicy` (`lifetimeFor(method, path)`)

Rejected on two counts: a second policy call and a second path match on every dispatch, and two
independent resolutions that can disagree about what a "route" is.

### Option 4 — Flip the default to transaction-scoped now (RFC Option C)

Right shape, wrong order. It retracts a written promise in `persistence.md`, inverts a live RLS
integration test guarding a tenant-isolation property, and needs a release-ownership design — all
spent on a default whose affordability is unmeasured. The acquire-rate multiplier is bounded at
`[1.0×, 3.17×]` and nobody has narrowed it.

### Option 5 — A detach seam on the box (RFC Option B)

Cheaper than the RFC first priced it, since the box is Community-tier. Still opt-in to correctness
with a failure mode that appears only under concurrency, and it needs box re-entrancy that neither
Option 1 nor Option 4 does.

### Option 6 — Do nothing

Ships a documented availability cliff in two 1.0-core subsystems and requires one pooled connection
per concurrent blocked request.

## 🏁 The Decision

**1. `RouteRequirement` gains an execution facet, defaulting to today's behaviour.**
`PROMPT` is the default and every existing static factory keeps returning it, so an
`HttpRoutePolicy` implementation that never names execution keeps exactly the current semantics. The
facet is additive on a `preview`-tier type; `stability-matrix.md` and
`tools/spi-api-diff/stability-surfaces.conf` are updated in the same commit, because ADR-065's gate
fails the build on an unclassified SPI surface.

**2. `spi.http` does not name connections.** The facet describes how the *route* executes, not what
it does to a pool. The Community dispatcher makes the inference: `LONG_RUNNING` ⇒ no
`PersistenceSessionBox` bound. Any driver may draw a different consequence, or none.

**3. The request-scoped default is unchanged.** *"One HTTP request is one connection"* remains the
promise for `PROMPT` routes. `CommunityRequestScopeBypassIsolationIT` keeps its backend-PID assertion
unedited, and gains a `LONG_RUNNING` counterpart asserting the opposite — two acquires on such a
route must **not** share a backend.

**4. Release ownership never moves to the handle.** Inside a request session the box owns release and
handles stay non-owning. On a `LONG_RUNNING` route there is no box, so `openConnection` returns an
owning handle whose `close()` is real — which is not a new ownership model but **the one the kernel
already runs everywhere outside a request box**, flow threads included. A missed close is a leak
there today and would be a leak here, under the same rule and the same detector.

**5. What gates a future default flip is named, not deferred vaguely.** Flipping `PROMPT` to
unit-of-work scoping requires: the real acquire-rate multiplier inside `[1.0×, 3.17×]`, measured as a
count of reuses that cross a transaction boundary; the cost of the interceptor's session-key
statements at that rate; and a re-run of the saga benchmark with
`eu.exeris.kernel.persistence.ConnectionHold` enabled, so the request-side and flow-side holds are
apportioned rather than assumed. Until all three exist the default does not move.

**Non-goals.** No admission control is touched — no reject arm fires at the measured queue depth, and
re-opening admission here would confound the fix against measurements that already exist. No
`ServiceResolver`-shaped naming. `dispatchStream` still binds no session and is out of scope, though
its own comment's "short-lived session per emit" stops being a description once this facet exists.

## Consequences

- An application can express the hazard away today, per route, without a global setting and without
  the kernel guessing which handlers block.
- A `LONG_RUNNING` route pays more acquires and therefore more interceptor round-trips. That is the
  trade being offered explicitly rather than imposed globally, which is the whole reason the default
  does not move.
- **A declaration can go stale.** A route marked `LONG_RUNNING` that stops blocking silently pays the
  extra acquires forever. Mitigation: the dispatcher emits a JFR event when a `LONG_RUNNING` route
  completes within a threshold, so the mismatch is detectable. The RFC records this mitigation as
  unproven and it remains unproven until someone runs it.
- Two concerns now ride one carrier. `RouteRequirement` is named for a requirement, and "this route
  blocks" is one — but a security-focused policy implementation now returns a value about execution.
  The default is what makes that safe: silence means today's behaviour.
- The seam may need a second migration if the measurement later moves the default. Cheaper than the
  reverse order, not free.

## Dissent recorded

The strongest case against Option 1 is that it is **opt-in to correctness**: a hazard the kernel
knows about stays armed until an application names it, and applications that never read this ADR
never name it. Option 4 fixes it for everyone at once. The counter relied on is sequencing rather
than merit — Option 4 is the same seam with a different default, and its cost is a documented
contract, a live RLS test and a release-ownership design spent before the measurement that says
whether the default is affordable. If the multiplier lands near 1.0×, this ADR's clause 5 is the
clause to execute, not to defend.

The weaker part of this decision is the **naming axis**. `PROMPT` / `LONG_RUNNING` describes the
handler's behaviour, while the hazard is narrower: blocking *on work that draws from the same pool*.
A route can be long-running without touching persistence, and will pay for the declaration anyway. A
more precise axis was rejected because an application author reliably knows "this handler waits" and
does not reliably know what the work it waits on acquires — but if the JFR staleness signal shows
routes paying for nothing, this is the clause to revisit first.

## Cross-references

- [RFC-2026-08-26](../rfc/RFC-2026-08-26-request-connection-lifetime.md) — the option comparison this
  ADR closes; its Decision Record is filled in with this number.
- [ADR-061](ADR-061-declarable-http-route-authorization-policy.md) — introduced `HttpRoutePolicy` /
  `RouteRequirement` and put both on the `preview` surface, which is what makes this extension
  affordable.
- [ADR-065](ADR-065-spi-compatibility-gate.md) — the surface classification that must land in the
  same commit as the carrier change.
- [ADR-012](ADR-012-security-trust-model-upgrade-for-resource-server-validation-and-fail-closed-runtime.md)
  — fixes the isolation keys `RlsConnectionInterceptor` publishes. The interceptor republishes them on
  every acquire, which is why a shorter lifetime costs round-trips and not correctness.
- `docs/subsystems/persistence.md` — the *"One HTTP request is one connection"* promise, which this
  ADR narrows to `PROMPT` routes rather than retracts.

## Engineering Protocol

- The carrier change, the `stability-matrix.md` row and the `stability-surfaces.conf` entry land in
  **one commit**. ADR-065's gate fails on an unclassified SPI class, so splitting them means a red
  build between two green ones.
- The `LONG_RUNNING` backend-PID assertion must be **mutation-checked**: bind the session
  unconditionally again and the new test must redden while the existing `PROMPT` one stays green. A
  pair of tests that both pass under the mutation has tested the dispatcher's plumbing, not its
  decision.
- The existing `CommunityRequestScopeBypassIsolationIT` assertion is **not edited**. If a change
  makes it fail, the change is wrong, not the test.
- No default is flipped in this slice. A commit in this slice that changes what a `PROMPT` route does
  is out of scope by construction.
