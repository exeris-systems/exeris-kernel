# ADR-082: Time the kernel *decides* on goes through a seam; time it *measures* does not

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-09-01                                                                                |
| **Scope**       | `kernel/spi`, `kernel/core`, `kernel/community`                                            |
| **Driven By**   | v0.12 Stream T2-1; ROADMAP §"Cross-Cutting: Unified Injectable Clock Seam"                 |
| **Compliance**  | [docs/subsystems/flow.md](../subsystems/flow.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

Time is read ad hoc. Measured on `development/0.12.0`, main sources only: **122** `System.nanoTime`,
**20** `Instant.now`, **3** `System.currentTimeMillis`, **3** `Clock.system` — 148 sites, including
inside the SPI itself (`KernelEvent`, `ExerisKernelException`, the diagnostics snapshots). The
ROADMAP's audit recorded 95 `nanoTime`; the count has grown since, which is the argument for a seam
rather than another sweep.

Two injectable seams already exist and they use **different types** — `FairnessTracker` takes a
`LongSupplier`, `CommunityRotatingKeySet` a `java.time.Clock`. Two idioms and no policy is the
consistency half of the problem.

The other half is that **you cannot virtualise time you read ad hoc**, and deterministic simulation
testing is the ROADMAP's largest declared moat. A saga TTL test today either sleeps or does not test
expiry at all.

## What the tree already decided

**The shape is not invented here.** `CommunitySchedulerClock` (v0.11) is an interface carrying
exactly:

```java
long    nanoTime();   // monotonic, for deadlines
Instant wallTime();   // calendar time, for cron fields
```

plus scheduler-specific waiting primitives (`lock()`, `awaitUntil`, `awaitSignal`), and it ships with
a `VirtualSchedulerClock` that **already drives scheduling deterministically**. A kernel-wide seam
that invented a third vocabulary would be the very defect this ADR exists to remove.

**Both reads are required, and the saga path proves it rather than asserting it.**
`RuntimeFlowInstance` converts a *persisted wall-clock* deadline into a *monotonic* one:

```java
long remainingNanos = Duration.between(Instant.now(), snapshot.timeout()).toNanos();
timeoutNanos        = System.nanoTime() + Math.max(0L, remainingNanos);
```

and inverts it on the way out. Virtualising one clock and not the other makes that conversion drift,
so a one-method seam could not have driven this path at all.

## 🏁 The Decision

**1. `TimeSource` in `eu.exeris.kernel.spi.time`, carrying `nanoTime()` and `wallTime()`.** The names
match `CommunitySchedulerClock` deliberately: that interface becomes `TimeSource` **plus waiting**,
so the scheduler keeps its wait primitives without owning a second definition of what time is.
`java.time` only, so The Wall holds.

**2. Threaded by `ScopedValue`, never `ThreadLocal`.** `KernelProviders.TIME_SOURCE`, alongside every
other kernel-wide binding, and the `ThreadLocal` ban is already executable in
`KernelTierBanArchitectureTest`.

**3. Unbound means the system clock, through one accessor.** `KernelProviders.timeSource()` returns
the bound source or `TimeSource.SYSTEM`. The alternative — every call site writing `orElse` — is how
one site forgets and becomes untestable while looking migrated.

**4. Migrate the reads that DECIDE, not the reads that MEASURE.** This is the load-bearing rule and
the one that keeps the work finite:

| | migrates | why |
|---|---|---|
| a read compared against a deadline | **yes** | it chooses an outcome, so it must be drivable |
| a read persisted as a saga's timeout | **yes** | it decides expiry on some later process's clock |
| a read bracketing an operation to report elapsed time | **no** | it reports; virtualising it makes JFR durations lie |
| `nanoTime` inside a spin or backpressure loop | **no** | a seam adds an indirection on a hot path and buys no determinism |

Of the 148 sites, **five** are `nanoTime` in a comparison and roughly twenty are `Instant.now` — the
rest are instrumentation. Reading the difference is what turns this from a 148-site sweep into a
bounded change.

**5. Diagnostic timestamps in the SPI stay as they are.** `KernelEvent` and `ExerisKernelException`
stamp `Instant.now()` at construction. They are records on a failure path, the stamp is never
compared against anything, and routing them through a `ScopedValue` lookup would put a slot read on
the exception path to make a field that nothing decides on virtualisable.

## Consequences

- A saga TTL becomes drivable: bind a virtual `TimeSource`, advance it, assert the timeout — no
  sleeping, no flake.
- DST gains its prerequisite. It does not gain DST: this is the seam, not the harness.
- **Two clocks now exist in the tree and one wraps the other.** `CommunitySchedulerClock` extends
  `TimeSource`; a reader must know the scheduler's is the wider one. The alternative — collapsing the
  wait primitives into the kernel-wide seam — would put `ReentrantLock` in the SPI.
- **The decide/measure line is a judgement, and it will be argued.** It is written into this ADR with
  a table rather than left to each migration, because the version of this rule that lives only in
  reviewers' heads produces a seam that is half-applied and therefore not a seam.
- An unbound read costs one `ScopedValue.orElse`. On the paths that migrate — expiry checks, not
  spin loops — that is not measurable, which is exactly why the hot paths are excluded.

## Dissent recorded

**The strongest case against is that this buys nothing until DST exists.** True of the seam alone,
and the reason the disposition is 1.0-*recommended* rather than blocking. It is answered by the saga
TTL test in the same change: that test could not be written deterministically before, so the seam
pays for itself once, immediately, independently of DST.

**A second objection is that five comparison sites do not justify SPI surface.** The count is not the
argument — the *inability to drive any of them* is. And the surface is one interface with two methods
whose names the tree already uses.

## Cross-references

- ADR-057 (`JobScheduler` SPI) — refused `ScheduledExecutorService` partly because no seam existed to
  displace `System.nanoTime()`. This is that seam.
- ADR-064 (flow definition versioning) — the saga timeout that migrates here is persisted state whose
  resume path that ADR governs.
- ROADMAP §"Differentiator: Deterministic Simulation Testing (DST) Harness" — the consumer.
