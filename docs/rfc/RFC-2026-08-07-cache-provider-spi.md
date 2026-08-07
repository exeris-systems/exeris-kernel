# RFC-2026-08-07: Should the kernel own a `CacheProvider` SPI, and what coherence does it promise?

| Field             | Value                                                                    |
|:------------------|:-------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                             |
| **Author(s)**     | Arkadiusz Przychocki                                                     |
| **Date Opened**   | 2026-08-07                                                               |
| **Date Closed**   | 2026-08-07                                                               |
| **Target ADR(s)** | none — this RFC recommends no SPI, so it authors no ADR (see §Recommendation) |
| **Affected Repos**| `exeris-kernel`, `exeris-sdk`, `exeris-tooling`                          |
| **Reviewers**     | —                                                                        |

## Question

Two questions, answered in order, because the second only matters if the first is yes. **(1) Is the
kernel's stated precondition for a `CacheProvider` SPI — a concrete second-backend pull — met today?
(2) When it is met, what coherence does the contract promise, given that local and distributed cache
backends have different semantics and a business rule written against the contract must not become
wrong when the backend is swapped?**

## Context

`docs/ROADMAP.md` §"Runtime: `CacheProvider` SPI — RFC Track Only" has carried this as a decision-only
item since v0.8. Its gate is explicit: *"SPI lands only when a concrete second-backend pull materializes
(downstream consumer requesting Redis, or benchmark demonstrating off-heap slab win)"*, on the reasoning
that committing to a contract without a second backend risks designing the wrong shape — a
Caffeine-only deployment would carry contract weight it never exercises.

The SDK has run ahead of the kernel. `@ExerisDomain` has carried `cacheable` / `cacheTtl` /
`cacheRegion` since v0.6, and four sibling annotations carry their own cache attributes. Nothing
consumes any of them. That asymmetry is what keeps the question open rather than closed: annotations
that promise a behaviour the runtime does not have are a standing claim the project has not made good.

An audit note added to the ROADMAP on 2026-06-22 speculated that the gate's precondition *"may already
be met"*, naming downstream dogfooding as the Redis pull. That speculation is the reason this RFC opens
now, and checking it is most of §Investigation. It does not survive.

The cost of leaving the question unanswered is not urgent but it is real: five annotations continue to
advertise caching, and every milestone that passes without either wiring them or saying plainly that
they are inert makes the SDK's surface less honest.

## Investigation

### Prior art

Three shapes dominate, and each answers the coherence question differently.

**JSR-107 / JCache** specifies a `Cache<K,V>` with an explicit consistency section, and is candid that
it does not mandate coherence across nodes — implementations declare it. **Spring Cache** abstracts over
providers with `@Cacheable` / `@CacheEvict` and deliberately says nothing about coherence, pushing it to
the provider; the practical result is that swapping Caffeine for Redis under a Spring application
changes its correctness envelope silently. That is exactly the failure this RFC exists to avoid.
**Quarkus** splits the surface in two — a local cache extension and a separate Redis client — rather
than unifying them behind one contract, which is a deliberate refusal of the abstraction.

The relevant lesson is negative: the widely-adopted abstraction (Spring Cache) is the one whose
coherence silence is a known source of production surprises, and the framework closest to this
project's constraints (Quarkus) declined to build the abstraction at all.

### Constraints

These bound the answer regardless of what shape is preferred.

- **ADR-001 already rules on Redis and has not been superseded.** *"The Exeris Kernel has zero runtime
  dependency on Redis… Redis is a recommended application-tier cache, an operational recommendation,
  not a kernel dependency… Will Redis ever become a Kernel dependency? Only if a future ADR explicitly
  introduces a `DistributedStateProvider` SPI with a Redis-backed implementation. No such ADR exists at
  this time."* A `CacheProvider` SPI with a Redis binding is precisely that future ADR, so this RFC is
  the thing ADR-001 anticipated — it does not contradict it, but it must be explicit that it opens what
  ADR-001 closed.
- **The Wall.** `spi.*` may depend only on `java.*`/`jdk.*`, so the contract cannot name a backend, a
  serialization format, or a wire type. Community ships an in-process binding; anything distributed is a
  separate binding.
- **`spi.flow`-style stability discipline applies from day one.** ADR-065's japicmp gate now fails the
  build on a binary-incompatible change to a surface declared `stable`. A cache seam would enter as
  `preview`, but the gate makes "ship it and reshape later" measurably more expensive than it used to be.
- **Blocking on virtual threads is acceptable, but pinning is a reportable fault.** `BaseRepository`
  states the Loom-first contract — *"All methods are blocking… no reactive wrapper, no `Mono<T>`, no
  `CompletableFuture<T>`"* — so a synchronous `get` is idiomatic. But `KernelErrorCodes.EX_RUN_3002`
  exists for *"Virtual Thread pinned the carrier"* and two TCKs assert carrier-pinning behaviour on the
  event bus. A socket-blocking Redis binding must clear that bar; an in-process binding trivially does.
- **A cache region key is a pair, not a value.** `StorageContext` carries `isolationKey()` (where rows
  physically live) and, since 0.11, `sharedScopeKey()` — documented as *"An orthogonal dimension, not a
  fourth strategy… Presence is the mode"*, where a present shared scope means reads widen to the shared
  partition while writes stay pinned to the owner. A region keyed on `isolationKey` alone mis-scopes
  shared-scope reads; keyed on `sharedScopeKey` alone it leaks across write owners. Any region model
  must key on both, and this constraint did not exist when the ROADMAP entry was written.

### Data gathered

Three ROADMAP claims were checked against source. One holds, two do not.

**The second-backend pull is NOT met, and the audit note that says otherwise is refuted on its
checkable half.** BudgetHQ is the active consumer — twelve backend poms declare
`exeris-kernel-community` / `exeris-spring-runtime-web` — and it has zero cache dependency in any of them,
zero Spring Cache annotation, and zero Redis client code. It runs a `redis:8-alpine` container that
nothing connects to; the only `depends_on: redis` sits inside a commented-out future-services block.
Every place it touches caching it has recorded a decision *against* a distributed cache, each with an
explicit drop trigger: the widget catalog is a `ConcurrentHashMap` with *"Drop trigger: p95 > 100ms"*,
the Tink token cache says *"swap to a distributed Redis-backed cache when sync-service scales beyond
single-replica"*, the gateway rate limiter says *"in-memory is correct and does not require Redis"*, and
a migration comment lists *"a per-service cache (Redis/Caffeine + TTL + invalidation)"* among options
explicitly not taken. **A drop trigger is the opposite of a pull** — it is a written statement that the
condition has not arrived, authored by the consumer the ROADMAP cites as the puller.

The alternative gate is also unmet: `exeris-benchmarks` contains no cache or slab-cache benchmark at
all, and its compose README advertises a redis *"Cache fixture (optional)"* that no compose file
defines.

A second downstream consumer is closed-source and not checkable from this repository, so no claim is
made about it here. What can be said is that the kernel's own transcription of that dogfooding
produced six attributed gaps — the Events
`topic` seam, streaming/SSE, shared scope, client addressing, multi-node coordination, the Events
multi-node contract — and not one is about caching.

**The SDK flags are dead, and deader than documented.** Confirmed: no emitter in any codegen module
reads a cache attribute, and the kernel sink is absent. But the ROADMAP's "they round-trip" half holds
for `@ExerisDomain` only. `@Graph`'s cache attributes are read into a local map and then discarded —
`extractGraphMetadata` returns `new GraphMetadata(label, null, List.of(), List.of())` and the AST record
has no cache component to receive them. `@Projection` is never extracted by the processor at all. The
real surface is five annotations with three different boolean names (`cacheable`, `cacheQueries`,
`cacheReadModel`, `cached`, `cacheAggregates`) and two incompatible TTL encodings (ISO-8601 string,
and `int` seconds on `@EventSourced`). Any design assuming a coherent pre-existing metadata surface
would be designing against something that does not exist.

**Invalidation over the Events bus does not discharge the coherence question.** This is the finding that
changes the answer, because it removes the escape hatch the ROADMAP relies on.

- `InMemoryEventBus.publish` starts one virtual thread per handler and **returns before any handler
  runs**. A write followed by `publish(invalidate)` does not establish that any cache saw the
  invalidation before the next read.
- The bus contract promises **no ordering at all**. `AbstractEventBusTck` states it as design:
  *"No-Ordering by Design (ADR-049)… publish() fans out concurrently… deliberately asserts no delivery
  order; callers needing ordering use the durable log."*
- The in-heap bus **cannot cross a node boundary** — `events.md` is explicit that *"A second kernel
  instance running the same application does not observe the first instance's publications."*
- The Kafka *bus* binding runs its poll loop with `enable.auto.commit=true`, which is at-most-once on
  consume: a dropped invalidation leaves a permanently stale entry with no error anywhere.

One correction in the other direction, and it matters: the Kafka **durable log** path is not
at-most-once. `KafkaEventLog` sets `enable.auto.commit=false`, and ADR-049 gives the log per-stream
total ordering. So an ordered, manually-committed channel does exist — it is simply not the one the
ROADMAP names. "Invalidation rides the Events bus" and "invalidation rides the durable log" are
different proposals with different guarantees, and the ROADMAP conflates them.

### Spike outcomes

None. No prototype was built and none is proposed — a spike would answer a shape question that has no
consumer to validate against, which is the same error the gate exists to prevent.

## Options Considered

### Option A: Ship the SPI now, with coherence delivered by invalidation over the Events bus

The ROADMAP's stated shape. A `CacheProvider` SPI with a Community in-process binding; a region flush or
key invalidate is published as an event, so the seam reuses the multi-node substrate rather than
introducing a second coordination mechanism.

**Pros:**
- No new data plane in the kernel; one coherence mechanism serving every backend.
- Single-node deployments get coherent invalidation with no Kafka dependency.

**Cons:**
- **It does not work.** The substrate cannot carry the guarantee: publish is asynchronous and returns
  before handlers run, the contract promises no ordering, the in-heap bus cannot reach another node, and
  the cross-node bus binding is at-most-once. A dropped invalidation is a permanently stale entry.
- Would ship a contract whose central promise is unenforceable, on a surface the compatibility gate now
  watches.

**Cost:** SPI + one binding + TCK, and a coherence claim that the first multi-node deployment falsifies.

### Option B: Ship the SPI now, with coherence as an explicit, declared contract

The contract states its own staleness envelope — what a caller may rely on regardless of backend — and
each binding must satisfy it or refuse to load. Invalidation, if it needs a channel, rides the durable
event log (ordered, manually committed), not the bus.

**Pros:**
- Resolves the `[CONTRACT]` landmine the right way: a backend swap cannot silently change correctness
  because the guarantee is written down and TCK-asserted rather than inherited from the backend.
- Uses the substrate that can actually carry it.

**Cons:**
- Designs a coherence envelope with no second backend to validate it against — precisely the
  wrong-shape risk the gate was created to avoid.
- The region key must carry the `isolationKey` + `sharedScopeKey` pair, which is new surface area
  invented for a consumer that does not exist.

**Cost:** SPI + binding + TCK + a staleness contract nobody is yet in a position to falsify.

### Option C: In-process-only cache utility, no SPI, no distribution

Give the kernel a concrete in-process cache and wire the SDK flags to it. No provider seam, no
swappability, therefore no coherence divergence possible.

**Pros:**
- Makes the five annotations honest at the lowest possible cost.
- Zero contract risk: with one implementation there is nothing to swap.

**Cons:**
- Not an SPI, so it does not answer this RFC's question — it answers a different, smaller one.
- Adds a kernel primitive whose only consumer would be codegen that does not exist yet, in a project
  whose stated rule is that a new abstraction must justify measurable value.

**Cost:** small, and mostly in `exeris-tooling` rather than the kernel.

### Option D (do nothing): stay RFC-only; the gate stays closed

Record the shape the SPI would take when the pull arrives, correct the ROADMAP entry to match measured
reality, and commit no kernel surface.

**Pros:**
- Honest about the state: no consumer wants a second backend, and the one cited as wanting it has
  written down that it does not.
- Costs nothing that has to be maintained, and the gate remains a real gate rather than one waived by an
  unchecked audit note.

**Cons:**
- The SDK annotations stay inert for another milestone, and the honesty problem in §Context is deferred
  rather than solved.

**Cost:** documentation only.

## Recommendation

**Take Option D now — the gate stays closed — and pre-commit to Option B's coherence model as the shape
the SPI takes when the gate opens, because Option A is not available.**

The precondition is not met, and this is not a judgement call about readiness: the consumer the ROADMAP
names as the puller has recorded three separate decisions against a distributed cache, each with a drop
trigger naming the condition under which it would change its mind. Those triggers are the gate,
expressed better than the ROADMAP expressed it, and by the party with standing to trip them. Building
the SPI before one trips would be designing a coherence envelope against a hypothetical.

But this RFC cannot simply defer, because it found that the ROADMAP's own answer to the coherence
question is unavailable. The entry says binding invalidation to the Events bus *"discharges [the
coherence landmine] automatically"*; the substrate cannot carry that. So the merge gate's requirement —
that a backend swap cannot silently change correctness — is resolved here in favour of an **explicit
declared staleness contract**, with the durable log rather than the bus as the channel if one is needed.
Recording that now is the durable output: it stops the next person from re-deriving a discharge that was
never there, and it means the gate, when it opens, opens onto a shape that has already survived a check.

Residual uncertainty, stated plainly. One downstream consumer is closed-source and not inspectable
from here, so the pull's strongest possible source is unverified — the finding is "not met on everything checkable", not "not met
anywhere". And Option D leaves five SDK annotations advertising a behaviour that does not exist; that is
a real cost, and the follow-up below is how it gets paid rather than forgotten.

### Why not the alternatives?

- **Option A** — the substrate cannot deliver the guarantee the option is built on, and shipping a
  contract whose central promise is unenforceable is worse than shipping nothing.
- **Option B** — right shape, wrong time: it invents a staleness envelope and a two-part region key for
  a consumer that does not exist, which is the wrong-shape risk the gate was created to prevent.
- **Option C** — solves the honesty problem but not this RFC's question, and adds a kernel primitive
  whose only consumer is codegen that has not been written.

### Risks of the recommendation

- **The gate can be waived again by an unchecked note.** The 2026-06-22 audit note did exactly that, and
  it stood for six weeks. Mitigation: the ROADMAP entry is rewritten in the same PR to name BudgetHQ's
  drop triggers as the observable condition, so the next re-evaluation checks a fact rather than a
  recollection.
- **Deferring keeps the SDK dishonest.** Five annotations continue to advertise caching. Mitigated only
  partly by the follow-up below; if it is not taken, this risk compounds each milestone.
- **The shape may not survive contact with a real second backend.** Pre-committing to a declared
  staleness contract is a claim about a design that has never been exercised. It is recorded as a
  recommendation, not a decision, precisely so the eventual ADR can reject it with evidence.

## Decision Record

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | **ACCEPTED** — Option D (stay RFC-only; the SPI gate stays closed), with Option B's coherence model pre-committed as the shape the SPI takes when the gate opens. |
| **Date**             | 2026-08-07 |
| **Resulting ADR(s)** | **none.** Accepting this RFC commits no kernel surface, so it authors no ADR — matching the ROADMAP merge gate's "no kernel SPI commits in this gate (decision-only track)". An ADR is authored when the gate opens and the SPI is actually designed. |
| **Notes**            | Dissent recorded rather than suppressed: Option B is the *right shape* and a reasonable reviewer could argue for building it now, on the grounds that a declared staleness contract is cheap and the SDK annotations have been inert for four milestones. It is rejected on timing, not on merit — designing a coherence envelope with no second backend to falsify it is the specific failure the gate exists to prevent. If the gate opens and Option B proves wrong under a real backend, that is the outcome this RFC most wants to be corrected by. Two ROADMAP claims were refuted in the course of accepting this: the second-backend pull is not met, and invalidation over the Events **bus** cannot discharge the coherence requirement (the ordered channel is the durable **log**). Both corrections landed with the RFC. |

## Open questions / follow-ups

- **Make the SDK cache annotations honest.** Five annotations advertise caching against no runtime.
  Either mark them `@Deprecated`-pending-a-sink with a Javadoc note saying no emitter consumes them, or
  remove the three that never reach the model at all (`@Graph`, `@Projection`, and the un-extracted
  `@GraphQuery` / `@EventSourced` attributes). Owner: `exeris-sdk` / `exeris-tooling`; target v0.12.
- **Reconcile the five annotations' vocabulary before any sink is written.** Three boolean names and two
  TTL encodings across one feature. Whatever consumes them will have to normalise this; deciding it once
  in the SDK is cheaper than once per emitter. Owner: `exeris-sdk`; target v0.12.
- **ADR-001's `DistributedStateProvider` clause is the right hook and should be cited by the eventual
  ADR.** It already frames "Redis enters the kernel" as requiring an explicit ADR; the cache ADR is that
  ADR and should say so rather than opening the question fresh. Owner: whoever authors it.
- **Does the durable event log want an invalidation-shaped consumer at all?** If the eventual answer is
  that coherence rides the log, that is a second consumer of `EventStreamAppender` with different
  retention needs from the first. Worth knowing before the log's retention policy is settled. Owner:
  Events subsystem.
- **Re-evaluate on a tripped drop trigger, not on a calendar.** The observable conditions are
  BudgetHQ's own: widget-catalog p95 > 100ms or > 1000 req/min per pod, or sync-service scaling beyond
  a single replica. Owner: whoever plans the milestone after one of those is observed.
