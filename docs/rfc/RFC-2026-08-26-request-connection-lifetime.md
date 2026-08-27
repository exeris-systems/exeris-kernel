# RFC-2026-08-26: What connection lifetime does the kernel promise a request handler?

| Field             | Value                                                                                          |
|:------------------|:-----------------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                                   |
| **Author(s)**     | Arkadiusz Przychocki                                                                           |
| **Date Opened**   | 2026-08-26                                                                                     |
| **Date Closed**   | 2026-08-27                                                                                     |
| **Target ADR(s)** | [ADR-077](../adr/ADR-077-route-declared-connection-lifetime.md)                                |
| **Affected Repos**| `exeris-kernel`                                                                                |
| **Reviewers**     | —                                                                                              |

## Question

`CommunityHttpRequestDispatcher` binds a `PersistenceSessionBox` around every non-streaming request, so
a pooled connection acquired by the first persistence call is held until the handler returns. Should
the kernel keep promising **request** lifetime, promise **transaction** lifetime and make the request
session opt-in, or keep request lifetime and give a route a declared way out of it?

## Context

The binding is unconditional. `handleWithinRequestSession` constructs the box, binds it as
`REQUEST_SESSION`, and releases it in the handler's `finally`. There is no configuration key anywhere
in SPI or Community that disables it. The connection handed to application code is a
`NonOwningPersistenceConnection` whose `close()` is a no-op, so the pool gets the connection back only
from `box.release()`.

Unlike most gaps this repo tracks, this one **is** governed by a written promise:
`docs/subsystems/persistence.md:129` opens with "One HTTP request is one connection." No ADR fixes it,
but a shipped integration test does — see constraint 5. Any option that shortens the lifetime is
retracting a documented contract, not filling a documentation gap, and that distinction is what the
first draft of this RFC missed.

For a handler that returns promptly the promise is a good one: one connection per request, RLS session
keys published once, reuse across every persistence touch. For a handler that **blocks**, it is
hold-and-wait on a single pool, because the work the handler waits on draws from that same pool. Flow
steps run on bare `Thread.ofVirtual()` (`CoreFlowRuntime.launch`), inherit no `ScopedValue`, and so
acquire through `CommunityPersistenceEngine.openPhysicalConnection` independently of any request box.

The cost of leaving this unanswered is not hypothetical. A cross-runtime saga benchmark run in August
2026 has the kernel returning 286 orders where four comparison runtimes return 6,273–6,275 on identical
load. That is an availability collapse reachable from an ordinary application shape with nothing
unusual configured, and it is the first cross-runtime result where this kernel loses categorically
rather than by a margin.

**This RFC is deliberately narrow.** It settles connection lifetime. It does **not** settle why the
benchmark's handler was blocking: `FlowScheduler` exposes no completion surface, so an application
wanting request/response over a saga resolves it inline in the handler. That is the route in, not the
defect — this binding punishes *any* handler that blocks long enough, saga or not, and an application
returning `202` and polling would never hold the connection. Tracked separately; neither fix
substitutes for the other.

## Investigation

### Prior art

The four comparison runtimes in §Data gathered are the most directly relevant evidence available,
because they are the same workload under a different lifetime promise. Their behaviour here is stated
from published documentation and configuration, **not** read from their source as part of this
investigation, and should be re-checked before the ADR leans on it:

- **Spring (`DataSourceTransactionManager`)** binds a connection to a transaction, not to a request.
  Outside a transaction each `JdbcTemplate` operation acquires and releases around the single call.
  Request-scoped connection binding exists (`OpenSessionInView`) but is a JPA-specific, *opt-in*,
  widely-warned-against pattern — and it is off by default in a plain JDBC stack.
- **Quarkus (Agroal + Narayana)** is the same shape: the transaction owns the connection.
- **Restate** does not hold a connection across its durable-execution suspension at all; suspension is
  the mechanism, so nothing spans it.

The generalisation worth taking is narrow but real: **none of the three JDBC arms promises
request-scoped connection lifetime by default, and all three return offered-load parity on the workload
where the kernel collapses.** That is one data point about the default, not proof that request-scoped
lifetime is wrong — Spring's own OSIV shows the pattern exists deliberately where reuse matters.

### Constraints

Six facts from the tree bound the option space. Each was read at its declaration site. Four of the six
contradict what the shape of the problem suggests, and two of those four are corrections to this RFC's
own first draft.

1. **A shorter lifetime does not cost RLS correctness.**
   `RlsConnectionInterceptor.onConnectionAcquired` republishes the tenant and shared-scope session keys
   on **every** acquire, and resets `search_path` on the arms that do not set their own. Isolation is
   re-established per acquisition, not per request. What a shorter lifetime costs is round-trips.

2. **`PersistenceSessionBox.release()` is terminal.** Afterwards `getOrAcquireInternal` emits
   `RequestSessionLifecycleEvent("REJECTED_RELEASED")` and throws. A handler cannot release and resume,
   so a detach seam is a re-entrancy change to the box rather than a method addition.

3. **The box is Community-tier, not SPI.** `PersistenceSessionBox` and its `REQUEST_SESSION` carrier
   live in `eu.exeris.kernel.community.persistence`. A detach seam shaped there binds this repository's
   driver, **not** every `PersistenceEngine` implementation. (The first draft priced Option B as an SPI
   obligation binding out-of-repo implementations. That was wrong and made B look more expensive than
   it is.)

4. **The per-route declaration seam already exists, is SPI, and is already resolved on every request.**
   `HttpRoutePolicy.requirementFor(method, path)` is called at
   `CommunityHttpRequestDispatcher:153-155` for every dispatch, returning a `RouteRequirement`. Both
   types are on the **preview** stability surface (`stability-matrix.md:112`, since 0.11.0, ADR-061),
   so extending `RouteRequirement` with a lifetime facet is affordable rather than a stable-surface
   change. `RouteRequirement` is a `final class` with private constructors and static factories, which
   is the easy shape to extend. (The first draft costed and rejected Option A without naming any of
   this, which made A look like a mechanism that had to be invented.)

5. **A shipped integration test pins the current promise, and pins it by physical identity.**
   `CommunityRequestScopeBypassIsolationIT` asserts `backendPidOf(second)` equals `backendPidOf(scoped)`
   — the *same PostgreSQL backend PID* across two acquires inside one request. Any option that shortens
   the lifetime inverts that assertion. It is a live RLS test guarding a tenant-isolation property, so
   it is not a test to edit casually.

6. **`NonOwningPersistenceConnection.close()` is a no-op, and that no-op is currently load-bearing.**
   Application code that forgets to close a handle costs nothing today, because `box.release()` in the
   handler's `finally` returns the connection regardless. Under any shorter lifetime **whose release is
   driven by the handle**, every missed close becomes a real pool leak. This does not doom a shorter
   lifetime — it constrains its design: release must be owned by the transaction or the dispatcher, not
   by the handle. A handler doing a bare `openConnection()` outside any transaction is exactly where
   that design has to have an answer.

### Data gathered

Cross-runtime saga benchmark. Park 1000 ms, pool 32, ~38 sagas in flight, ~165 s, identical load:

| arm | exit | orders | conn-exhaustion |
|---|---|---|---|
| quarkus-lra-jdbc | 0 | 6273 | 0 |
| spring-axon-jdbc | 0 | 6275 | 0 |
| spring-axon-embedded | 0 | 6273 | 0 |
| restate | 0 | 6275 | 0 |
| **exeris-community** | **5** | **286** | **386** |

**What the request-session telemetry does establish.** On a healthy run (park 250 ms, 6,273 orders):
37,626 `ACQUIRE`, 37,626 `RELEASE`, 81,523 `REUSE`; p50 3 ms, p90 273 ms; **6,271 sessions ≥200 ms
against 6,273 orders**. With a 250 ms park, a p90 of 273 ms and one long session per order means
request-thread sessions are being held across the park. That is positive evidence and it is the load-
bearing part.

**What it does not establish, contrary to this RFC's first draft.**
`RequestSessionLifecycleEvent.emit` appears in exactly one class — `PersistenceSessionBox` — and a flow
virtual thread never binds `REQUEST_SESSION`, so `openConnection(StorageContext)` finds
`currentOrNull() == null` and goes straight to `openPhysicalConnection`, emitting nothing. **Zero events
on flow threads is what the instrument is built to produce, not a finding.** It cannot distinguish "flow
takes no connections" from "flow is uninstrumented on this path", and this RFC's own §Context asserts
the latter. The first draft's conclusion "the park itself pins nothing" is therefore **withdrawn**: flow
threads demonstrably do acquire (the park checkpoint write is a store write), and how much they hold is
**unmeasured**. Closing that is a work item, not a footnote — see §Open questions.

**`RELEASE_NO_SESSION` = 15,434 is not a timeout count.** It is emitted in `release()` when
`session == null`, i.e. the box was bound and never acquired anything — which covers both a request that
never touched persistence and a request whose acquire threw. Reading it as "requests whose first acquire
timed out" was an over-reading, and the ~40× gap against 386 conn-exhaustions is unexplained rather than
corroborating. It is reported here and drawn on for nothing.

**It is a deadlock, not a ceiling** — this survives review. 32 concurrent × 1 s over ~165 s predicts
~5,280 orders; measured 286, an 18× over-prediction. Raising the pool 32 → 128 returns 6,275 orders and
zero exhaustions: *offered-load parity*, not the 4× a raised ceiling would give. A non-linear jump
straight to parity is the signature of a released deadlock.

**Admission control, described correctly this time.** `evaluateAdmissionReason` has five arms, not one,
and the first draft's "`idle <= 0 && queued > 0`" appears nowhere in it. The three arms that matter —
`HARD_SATURATION`, `GUARD_BAND_FAIRNESS`, and the final `NO_CAPACITY` — are **each gated on
`queued > config.queueDepthAllowance(max)`**. Measured `queueDepth` peaked at **71** against an
allowance of `ceil(32 × 8.0)` = 256, so that predicate was never true and **no reject arm could fire on
this run**. That is a stronger and simpler argument than the first draft's, and it is derivable from the
code plus one measurement.

Two corrections it forces. First, `GUARD_BAND_FAIRNESS` **can** fire with idle connections still in the
pool — `shouldRejectEarlyInGuardBand` requires `remainingHeadroom > 0` — so "the gate cannot fire until
every connection is pinned" was wrong; what is true is that it could not fire *at this queue depth*.
Second, the widening direction was **reasoned** closed, not measured: `queueDepthAllowanceRatio=32.0`
was cancelled on the 71-against-256 evidence rather than run. The tightening direction *was* measured —
STRICT (`0.0`) took rejections from ~zero to **23.87 %** (1,490 of 6,240) and returned **268** orders
against 286. Calling this "measured from both ends" overstated one half.

**The reuse figure, as an upper bound.** 81,523 `REUSE` against 37,626 `ACQUIRE` is 2.17 reuses per
acquired session, so a lifetime change converts **at most** those reuses into acquisitions — ~3.17× the
acquire rate. It is an upper bound, not an estimate: a transaction-scoped lifetime still shares one
connection across every touch **inside** a transaction, so only reuses that cross a transaction boundary
actually become acquisitions, and nobody has counted those. The true figure is somewhere in
`[1.0×, 3.17×]` and the distribution is unknown.

**`BYPASS_SCOPE_MISMATCH` = 0**, because the benchmark's security provider returns
`ImmutableStorageContext.GLOBAL` — deliberately, so the kernel is not credited with tenant isolation the
comparison arms do not carry. The ROADMAP entry adds that a tenant-declaring arm takes a second,
independent hold-and-wait, and carries an **"on v0.11"** qualifier that the first draft of this RFC
dropped. It matters: the v0.11 scope-key collision was fixed on this line by `1b14fb58`, so the claim is
false against the current tree and is not repeated here.

### Spike outcomes

None. No prototype was built. Every number above comes from the shipped code under benchmark load and
from reading declaration sites; where a number is an upper bound or an unmeasured direction, it now says
so at the point it is used.

## Options Considered

### Option A: A declared per-route lifetime, on the seam that already exists

`RouteRequirement` gains a lifetime facet; `HttpRoutePolicy` already answers per route and the
dispatcher already consults it on every request (constraint 4).

**Pros:**
- **The mechanism exists.** This is an additive facet on a **preview**-tier SPI type, not a new seam.
- Preserves the documented contract (`persistence.md:129`) and leaves
  `CommunityRequestScopeBypassIsolationIT` asserting what it asserts today.
- No leak-conversion risk: the dispatcher still owns release, so constraint 6's no-op `close()` stays
  harmless.
- The declaration is visible in one place and reviewable.

**Cons:**
- **The property declared is not a property of the route.** Whether a handler blocks is decided at
  runtime and changes when the handler changes, without the declaration changing. It goes stale
  silently.
- **Opt-*out* leaves the unsafe default** for every route nobody considered.
- Mitigable but not free: the staleness is detectable — a JFR event when a session outlives a
  threshold turns a stale declaration into a signal instead of a page.

**Cost:** 1–2 PRs. The facet, the dispatcher branch, a TCK case on the preview surface.

### Option B: An explicit detach seam

A handler about to block returns the connection and re-acquires afterwards.

**Pros:**
- Precise: the hold is released for exactly the window it is not needed, and reuse survives elsewhere.
- **Community-tier, not SPI** (constraint 3) — it does not bind out-of-repo `PersistenceEngine`
  implementations, which is materially cheaper than the first draft priced it.

**Cons:**
- Requires making `PersistenceSessionBox` re-entrant (constraint 2) — new state, and a new window
  between detach and re-acquire.
- **Opt-in to correctness**, with a failure invisible in development and visible only under
  concurrency.
- The variant that would fix that — the kernel detaching automatically when the handler blocks — is not
  implementable: the kernel cannot see "about to block".

**Cost:** 2 PRs plus a contract test for the detach/re-acquire cycle.

### Option C: Transaction-scoped by default, request session opt-in

The connection is held for the unit of work; an application wanting request-wide reuse asks for it.

**Pros:**
- **The safe behaviour is what you get by not thinking about it.** Opting in to a named hazard differs
  categorically from inheriting an unnamed one.
- Generalises the ruling the kernel already made for streams, where `dispatchStream` deliberately omits
  `REQUEST_SESSION` and says why.
- Costs no RLS correctness (constraint 1).
- Matches what all three JDBC comparison runtimes do by default (§Prior art).

**Cons:**
- **Retracts a documented contract and inverts a shipped test's central assertion** (constraint 5).
  That is a stability question, not an engineering one.
- **Needs a design answer for release ownership** (constraint 6): unless the transaction or the
  dispatcher owns release, every missed `close()` — harmless today — becomes a pool leak, and a bare
  `openConnection()` outside a transaction has no owner at all.
- The reuse cost is bounded by ~3.17× and otherwise unknown.
- Changes the default for every existing handler; read-your-writes across two untransacted calls stops
  holding.

**Cost:** 3+ PRs, plus the release-ownership design, plus TCK coverage.

### Option D (do nothing)

Keep request lifetime, document the hazard, let operators size the pool around it.

**Pros:** zero engineering cost, and pool sizing demonstrably works — 32 → 128 returned parity.

**Cons:** the remedy is "know about this and size for it", which none of the comparison runtimes
requires. Pool 128 for ~38 blocked requests is one connection per concurrent blocked request, which is
the property that does not scale. It also leaves the kernel holding two positions on one hazard — the
respond-once path binds the session, `dispatchStream` refuses to.

**Cost:** 0 to build; the cost lands on every adopter, once each.

## Recommendation

**Build Option A's seam now; treat Option C as the same seam with its default flipped, and gate the flip
on the measurement.** These are not rival designs — C is A with a different default, and both need one
mechanism: a per-route lifetime declaration on `RouteRequirement`.

That framing is what the corrected pricing produces, and it dissolves the choice the first draft forced.
Once constraint 4 is on the table, A stops being a mechanism to invent and becomes a facet on a
preview-tier type the dispatcher already reads. Once constraints 5 and 6 are on the table, C stops being
a default change and becomes a contract retraction plus a release-ownership design. Shipping the seam
first gets the hazard addressable in v0.12 without spending either.

**What decides the default is a measurement that does not exist**: where in `[1.0×, 3.17×]` the real
acquire-rate multiplier falls, and what the interceptor's session-key statements cost at that rate. If it
is near 1.0×, flipping the default is cheap and C is right. If it is near 3.17×, C trades an availability
cliff for a latency regression and A's opt-out — with a JFR signal for staleness — is the better 1.0
answer.

**Honest residual uncertainty, beyond that.** The attribution is weaker than the first draft claimed:
request-thread sessions are shown to span the park, but flow-thread holding is unmeasured because the
only instrument is blind to that path. The recommendation survives that gap — the request-side hold is
established on its own evidence, and A's seam is worth building whatever flow turns out to contribute —
but a reader should not take "the request session is the whole story" from this document.

### Why not the alternatives?

- **Option B** — cheaper than first priced, but still opt-in to correctness with a failure mode that
  appears only under concurrency, and it needs box re-entrancy that neither A nor C does.
- **Option C alone** — right shape, wrong order: adopting it before the measurement spends a documented
  contract, a shipped IT and a release-ownership design on a default that may not be affordable.
- **Option D** — ships a documented availability cliff in two 1.0-core subsystems and requires one
  pooled connection per concurrent blocked request.

### Risks of the recommendation

- **A's staleness is real and the mitigation is unproven.** A JFR signal on long-lived sessions turns a
  stale declaration into a detectable condition, but nobody has run it.
- **Shipping a seam whose default may flip later** means one migration now and possibly another after
  the measurement. Cheaper than the reverse order, not free.
- **The unmeasured flow-side hold** could mean the seam addresses part of the problem. It would still be
  the right seam.
- **Scope creep toward admission control.** No reject arm could fire at the measured queue depth
  (§Data gathered); re-opening admission inside this change would confound the fix against measurements
  that already exist.

## Decision Record

| Field                | Value                                                                              |
|:---------------------|:-----------------------------------------------------------------------------------|
| **Outcome**          | **ACCEPTED** — the recommendation is taken as written: build the seam, leave the default |
| **Date**             | 2026-08-27                                                                         |
| **Resulting ADR(s)** | [ADR-077](../adr/ADR-077-route-declared-connection-lifetime.md)                    |
| **Notes**            | See below.                                                                         |

**What the ADR settled that this document left open.**

- **Where the lifetime facet belongs.** On `RouteRequirement`, and named for the route's *execution
  shape* (`PROMPT` / `LONG_RUNNING`) rather than for a connection lifetime. `spi.http` must not name a
  persistence concept, so the Community dispatcher draws the consequence instead of the SPI stating
  it. This document framed the question as "where on `RouteRequirement`"; the answer turned out to be
  "and under what vocabulary".
- **Release ownership under a shorter lifetime.** It never moves to the handle. On a `LONG_RUNNING`
  route there is no box, so `openConnection` returns an owning handle — which is not a new ownership
  model but the one the kernel already runs on every path outside a request box, flow threads
  included. Constraint 6's warning stands and is accepted under that existing rule.
- **What gates the default flip.** Named as three artefacts rather than "a measurement": the
  acquire-rate multiplier inside `[1.0×, 3.17×]` measured as reuses crossing a transaction boundary,
  the interceptor's session-key cost at that rate, and a benchmark re-run with `ConnectionHold`
  enabled so the request-side and flow-side holds are apportioned rather than assumed.

**One correction to this document's constraint 6.** `NonOwningPersistenceConnection` is a private
static nested class inside `PersistenceSessionBox`, not a top-level type. Nothing outside the box can
reference it — which is precisely why release ownership cannot migrate to the handle without making a
private type public, and strengthens rather than weakens the constraint.

## Open questions / follow-ups

- **Measure the real acquire-rate multiplier.** The upper bound is 3.17×; the true figure needs a count
  of reuses that cross a transaction boundary, plus the cost of the interceptor's session-key statements
  at that rate. **This is what decides the default**, and the ADR should be gated on it.
- **~~Instrument the flow-thread connection path.~~ Done — the instrument exists; the measurement does
  not.** `eu.exeris.kernel.persistence.ConnectionHold` is now emitted at the pool return rather than
  from one caller, carrying `holdDurationNs` plus `withinRequestScope` and `acquiredOnVirtualThread`
  sampled at acquire. That makes the apportionment *expressible*. It does not make it *known*: nobody
  has re-run the benchmark with the event enabled, so this RFC's withdrawn attribution stays withdrawn
  until someone does.
- **Where does the lifetime facet belong on `RouteRequirement`?** It currently carries authorization
  kind and scopes; a lifetime facet is a second concern on one carrier. Owner: the ADR.
- **Release ownership under a shorter lifetime.** Constraint 6's question — who returns the connection
  when it is not the request boundary, and what happens to a bare `openConnection()` outside a
  transaction — has to be answered before any default flip, not during it.
- **`dispatchStream` still binds no session at all.** If a per-unit-of-work lifetime becomes
  expressible, the "short-lived session per emit" design its comment calls for stops being a description
  and becomes buildable.
