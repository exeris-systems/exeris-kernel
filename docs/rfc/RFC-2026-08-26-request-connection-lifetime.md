# RFC-2026-08-26: What connection lifetime does the kernel promise a request handler?

| Field             | Value                                                                                          |
|:------------------|:-----------------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                                      |
| **Author(s)**     | Arkadiusz Przychocki                                                                           |
| **Date Opened**   | 2026-08-26                                                                                     |
| **Date Closed**   | —                                                                                              |
| **Target ADR(s)** | TBD — the next free slot is ADR-074 as of this date (073 is reserved for the schema-history ledger). The number is reserved **when this RFC is accepted**, not now. |
| **Affected Repos**| `exeris-kernel`                                                                                |
| **Reviewers**     | —                                                                                              |

## Question

`CommunityHttpRequestDispatcher` binds a `PersistenceSessionBox` around every non-streaming request, so
a pooled connection acquired by the first persistence call is held until the handler returns. Should
the kernel keep promising **request** lifetime, promise **transaction** lifetime and make the request
session opt-in, or keep request lifetime and give the application a way out of it?

## Context

The binding is unconditional. `handleWithinRequestSession` constructs the box, binds it as
`REQUEST_SESSION`, and releases it in the handler's `finally`. There is no configuration key anywhere
in SPI or Community that disables it, and no ADR governs it — it is described in
`docs/subsystems/persistence.md` §"Request Session and the Scope Key" and nowhere else. The connection
handed to application code is a `NonOwningPersistenceConnection` whose `close()` is a no-op, so the
pool gets the connection back only from `box.release()`.

For a handler that returns promptly this is a good design: one connection per request, the RLS session
keys published once, and reuse across every persistence touch in that request. For a handler that
**blocks**, it is hold-and-wait on a single pool, because the work the handler waits on draws from that
same pool. Flow steps run on bare `Thread.ofVirtual()` (`CoreFlowRuntime.launch`), inherit no
`ScopedValue`, and acquire independently — including the park checkpoint write, which every parked
saga performs.

The cost of leaving this unanswered is no longer hypothetical. A cross-runtime saga benchmark run in
August 2026 has the kernel returning 286 orders where four comparison runtimes return 6,273–6,275 on
identical load. That is an availability collapse reachable from an ordinary application shape with
nothing unusual configured, and it is the first cross-runtime result where this kernel loses
categorically rather than by a margin — which makes it a product claim as well as an engineering one.

**This RFC is deliberately narrow.** It settles connection lifetime. It does **not** settle why the
benchmark's handler was blocking in the first place: `FlowScheduler` exposes no completion surface, so
an application wanting request/response over a saga resolves it inline in the handler. That gap is
tracked separately (v0.12 T2-6, the flow-await RFC). It is the route in, not the defect — this binding
punishes *any* handler that blocks long enough, saga or not, and an application returning `202` and
polling would never hold the connection. The two are fixed independently and neither substitutes for
the other.

## Investigation

### Constraints

Four facts from the tree bound the option space. Each was read at its declaration site rather than
inferred, because three of the four contradict what the shape of the problem suggests.

1. **A shorter lifetime does not cost RLS correctness.**
   `RlsConnectionInterceptor.onConnectionAcquired` republishes the tenant and shared-scope session keys
   on **every** acquire, and resets `search_path` on the arms that do not set their own. The isolation
   property is re-established per connection acquisition, not per request. So the objection that a
   shorter lifetime would weaken tenant isolation does not hold; what a shorter lifetime costs is
   round-trips, not correctness.

2. **`PersistenceSessionBox.release()` is terminal.** After it runs, `getOrAcquireInternal` emits
   `RequestSessionLifecycleEvent("REJECTED_RELEASED")` and throws
   `IllegalStateException("Request persistence session already released")`. A handler cannot release
   and resume. This is what makes Option B larger than it looks: a detach seam is not a method
   addition, it is making the box re-entrant.

3. **The kernel already ruled the other way — for streams.** `dispatchStream` binds the allocator and
   the decoder registry and **deliberately omits** `REQUEST_SESSION`, with the reasoning written into
   the code: the box "lazily takes a pooled JDBC connection and holds it for the scope's duration, so
   one read inside a live feed would pin a connection for as long as the client stays connected. A
   streaming handler that needs the database wants a short-lived session per emit — a design, not a
   binding copied across." A handler that blocks across a park is the same hazard two orders of
   magnitude down and gets the binding anyway. **The kernel currently holds two positions on one
   hazard**, and the only thing separating them is whether the hold time is unbounded and therefore
   obvious.

4. **Admission control is closed as a lever, measured from both ends** (see §Data gathered). Whatever
   this RFC concludes, ADR-035 tunability is not the remedy, and the ADR that follows must not change
   admission defaults in the same commit — doing so would confound the fix against measurements that
   already exist.

### Data gathered

Cross-runtime saga benchmark. Park 1000 ms, pool 32, ~38 sagas in flight, ~165 s, identical load for
all arms:

| arm | exit | orders | conn-exhaustion |
|---|---|---|---|
| quarkus-lra-jdbc | 0 | 6273 | 0 |
| spring-axon-jdbc | 0 | 6275 | 0 |
| spring-axon-embedded | 0 | 6273 | 0 |
| restate | 0 | 6275 | 0 |
| **exeris-community** | **5** | **286** | **386** |

**Attribution is the request thread, not the flow engine.** `RequestSessionLifecycleEvent` split by
thread kind on a healthy run (park 250 ms, 6,273 orders): 37,626 `ACQUIRE`, 37,626 `RELEASE`, 81,523
`REUSE` — **all** on HTTP request-carrier threads and **not one** on a flow virtual thread. p50 3 ms,
p90 273 ms, and **6,271 sessions ≥200 ms against 6,273 orders**: of the ~6.0 request-sessions each
order costs, exactly one spans the park. One pinned connection per saga in flight, counted rather than
modelled. The park itself pins nothing.

**It is a deadlock, not a ceiling.** 32 concurrent × 1 s over ~165 s predicts ~5,280 orders; measured
286, an 18× over-prediction. Raising the pool 32 → 128 returns 6,275 orders and zero exhaustions —
*offered-load parity*, not the 4× a raised ceiling would give. A non-linear jump straight to parity is
the signature of a released deadlock. The collapsed run's `RELEASE_NO_SESSION` = 15,434 (zero on the
healthy run) is the same fact from the other side: requests whose first acquire timed out.

**Admission control fails in both directions.** `AdmissionDecision.queueDepth` peaked at **71** against
an allowance of `ceil(32 × 8.0)` = 256, so widening cannot bite. Tightening to `0.0` (STRICT) did reach
the controller — rejections went from ~zero to **23.87 %** (1,490 of 6,240) — and returned **268**
orders against 286. The gate fires on `idle <= 0 && queued > 0`, i.e. *after* every connection is
already pinned, so it cannot prevent the deadlock forming; and a shed request is a lost order rather
than a deferred one, so the shed rate is a straight throughput tax.

**The reuse the binding buys, quantified.** 81,523 `REUSE` against 37,626 `ACQUIRE` is **2.17 reuses
per acquired session**. Under a transaction-scoped default those reuses become acquisitions — roughly
**3.17× the acquire rate** — each paying the interceptor's session-key statements. That number is the
price of Option C and it is the one figure in this RFC that has *not* been measured end to end.

**`BYPASS_SCOPE_MISMATCH` = 0**, and only because the benchmark's security provider returns
`ImmutableStorageContext.GLOBAL` — deliberately, so the kernel is not credited with tenant isolation
the comparison arms do not carry. An arm declaring a tenant takes a second, independent hold-and-wait
on top of this one.

### Spike outcomes

None. No prototype was built; every number above comes from the shipped code under benchmark load and
from reading declaration sites. Constraint 1 (RLS republishes per acquire) is the one that most wants a
spike before the ADR locks, because Option C's cost rides entirely on it.

## Options Considered

### Option A: Per-route opt-out

The application declares which routes may block, and the dispatcher skips the session binding for
them.

**Pros:**
- No new runtime seam; the decision is static and visible in one place.
- Existing routes keep today's behaviour and today's reuse.

**Cons:**
- **The property being declared is not a property of the route.** Whether a handler blocks is a
  property of what it does at runtime, and it changes when the handler changes without the declaration
  changing. The annotation goes stale silently, and the failure it then permits is an availability
  collapse.
- **Opt-*out* leaves the unsafe default in place** for every route nobody thought about — which is
  every route, until someone has already been paged.
- Asks the application to know something it usually does not: whether a call it makes transitively
  blocks on the pool it is holding from.

**Cost:** ~1 PR. Cheap to build and the cheapest to get wrong.

### Option B: An explicit detach seam

A handler about to block calls something that returns the connection to the pool and re-acquires
afterwards.

**Pros:**
- Precise: the hold is released for exactly the window it is not needed.
- Keeps the reuse for the ordinary case, which is the case the binding exists for.

**Cons:**
- **Larger than a method addition.** `release()` is terminal today (constraint 2), so this requires
  making `PersistenceSessionBox` re-entrant — new state, and a new set of things that can go wrong
  between detach and re-acquire.
- **A new SPI obligation, and opt-in to correctness.** Miss the call and you get the collapse; the
  failure is invisible in development and appears only under concurrency.
- The variant that would fix this — the kernel detaching *automatically* when the handler blocks —
  is not implementable: the kernel cannot see "about to block", and the one blocking call it could
  recognise (a flow await) does not exist yet (T2-6).

**Cost:** 2 PRs plus a TCK contract for the detach/re-acquire cycle, and it binds every implementation
outside this repository.

### Option C: Transaction-scoped by default, request session opt-in

The connection is held for the unit of work, not the request. An application that wants request-wide
reuse asks for it.

**Pros:**
- **The safe behaviour is what you get by not thinking about it.** Opting *in* to a hazard you named
  is categorically different from inheriting one you did not.
- **It makes the kernel hold one position instead of two.** `dispatchStream` already chose exactly
  this shape and wrote down why (constraint 3); this generalises the ruling the kernel already made.
- Correctness is preserved without extra work: the RLS interceptor republishes per acquire
  (constraint 1).
- Nothing in the application has to predict its own blocking behaviour.

**Cons:**
- **Costs the reuse, and the figure is 2.17 reuses per acquired session** — roughly 3.17× the acquire
  rate, each paying the interceptor's session-key round-trips. Unmeasured end to end.
- Changes the default behaviour of every existing handler, so a consumer relying on read-your-writes
  across two persistence calls in one request without an explicit transaction would see a different
  result. That is arguably a bug being surfaced, but it surfaces on someone else's schedule.
- The opt-in surface is new API, and it is the API a blocking handler must *not* use — a footgun with
  a warning label rather than no footgun.

**Cost:** 2 PRs plus TCK coverage for the lifetime contract and a Community binding test.

### Option D (do nothing)

Keep request lifetime, document the hazard, and let operators size the pool around it.

**Pros:**
- Zero engineering cost, and pool sizing does demonstrably work — 32 → 128 returned parity.

**Cons:**
- The remedy is "know about this and size for it", which is exactly what the four comparison runtimes
  do not require. Pool 128 for ~38 sagas in flight is not a tuning; it is one connection per concurrent
  blocked request, which is the definition of the property that does not scale.
- The kernel would be shipping a documented availability cliff in two of its 1.0-core subsystems, and
  the doc that describes the binding is the one place the hazard is not mentioned.
- It leaves the two-positions-on-one-hazard inconsistency (constraint 3) in the tree permanently.

**Cost:** 0 to build; the cost lands on every adopter, once each.

## Recommendation

**Option C — transaction-scoped by default, with the request session available as an explicit opt-in.**

The deciding argument is not the benchmark; it is constraint 3. The kernel has already reasoned about
this exact hazard, in this exact dispatcher, and chose a short-lived session per unit of work — writing
into `dispatchStream` that copying the request binding across "would pin a connection for as long as
the client stays connected". The only thing that stopped that reasoning applying to the respond-once
path is that a stream's hold time is unbounded and therefore obvious, while a blocking handler's is
merely long. That is a difference in how easy the bug is to notice, not a difference in the bug.
Option C makes the kernel hold one position; A, B and D each preserve two.

The second argument is the direction of the default. A and B both leave a collapse reachable by
omission — by a route nobody annotated or a detach nobody called — and the omission is invisible until
concurrency makes it visible, which is the worst possible moment. C's failure mode by omission is a
handler that does more round-trips than it needed to.

Constraint 1 is what makes C affordable at all, and it is worth stating plainly because the ROADMAP
entry that opened this question assumed the opposite: a shorter lifetime was expected to cost the
interceptor consistency the request session exists to guarantee. It does not.
`onConnectionAcquired` republishes the session keys on every acquire, so isolation is re-established
per acquisition. What C actually costs is round-trips.

**Residual uncertainty, stated rather than buried.** The 2.17 reuses per session is the price, and it
has not been measured end to end. If per-acquire session-key round-trips dominate at the measured
acquire rate, C trades an availability cliff for a latency regression — a better trade, but not a free
one, and the ADR should not claim it is free. **The ADR that follows this RFC should be gated on that
measurement**, not on this argument.

### Why not the alternatives?

- **Option A** — the thing being declared (does this handler block?) is a runtime property of the
  handler, not a static property of the route, so the declaration goes stale silently.
- **Option B** — opt-in to correctness with an invisible failure mode, and `release()` being terminal
  makes it a re-entrancy change to `PersistenceSessionBox` rather than the method addition it appears
  to be.
- **Option D** — ships a documented availability cliff in two 1.0-core subsystems and requires one
  pooled connection per concurrent blocked request, which is the property that does not scale.

### Risks of the recommendation

- **The reuse cost is unmeasured** (above). Highest-consequence unknown in this document.
- **Default-behaviour change for existing handlers.** Read-your-writes across two persistence calls in
  one request, without an explicit transaction, stops holding. Defensible — that is a bug surfacing —
  but it surfaces for consumers on the kernel's schedule, not theirs.
- **The opt-in is a footgun with a label.** An application that opts in and later starts blocking
  re-acquires the whole hazard. Mitigation is naming it in the API's own contract, not in a guide.
- **Scope creep toward admission control.** Constraint 4 closes that lever by measurement; re-opening
  it inside the same change would confound the fix.

## Decision Record

_(Filled in when status reaches ACCEPTED / REJECTED / WITHDRAWN.)_

| Field                | Value       |
|:---------------------|:------------|
| **Outcome**          | —           |
| **Date**             | —           |
| **Resulting ADR(s)** | —           |
| **Notes**            | —           |

## Open questions / follow-ups

- **Measure the acquire-rate cost of Option C before the ADR locks it.** 2.17 reuses per session
  becomes ~3.17× the acquire rate; the question is what the interceptor's session-key statements cost
  at that rate. Owner: this stream, before the ADR. This is the one item that can change the
  recommendation.
- **Does the opt-in belong on the route, on the handler, or on the persistence engine?** Option C
  needs an opt-in surface and this RFC does not choose its shape. Owner: the ADR.
- **`dispatchStream` still binds no session at all.** If C lands, the streaming path and the
  respond-once path converge on the same rule, and the "short-lived session per emit" design that
  `dispatchStream`'s comment calls for may become expressible rather than merely described. Owner:
  follow-up, tracked in the v0.12 plan's Tier-3 `dispatchStream` row.
- **`FlowScheduler` has no completion surface** (T2-6). Out of scope here and named so it is not
  folded in: it explains how the benchmark reached this hazard, not why the hazard exists.
