# ADR-057: Adopt a `JobScheduler` SPI dispatching on virtual threads, without a scheduled executor

| Attribute       | Value                                                                                      |
|:----------------|:-------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                               |
| **Deciders**    | Arkadiusz Przychocki                                                                       |
| **Date**        | 2026-07-30                                                                                 |
| **Scope**       | `kernel/scheduling`                                                                        |
| **Owning Repo** | `exeris-kernel`                                                                            |
| **Driven By**   | `docs/ROADMAP.md` §"Runtime: `JobScheduler` SPI" and §"Platform Baseline for 1.0 GA"         |
| **Compliance**  | [No Waste Compute](../whitepaper.md); ADR-012 (identity carried across the job boundary)    |

## Context and Problem Statement

Background work — "run this every five minutes", "retry this in an hour", "emit this digest nightly" —
has no kernel seam. An application either hand-rolls a timer or pulls in Quartz, and in both cases the
job runs *outside* everything the kernel establishes per request: no `PrincipalContext`, no
`StorageContext`, no JFR event, no structured shutdown. The failure this produces is not a missing
feature but a silent authority gap. A scheduled job that touches tenant data with no identity bound
either reads nothing (if the persistence layer denies, per ADR-012) or reads everything (if it does not).
Neither is a scheduling problem, and both are discovered in production.

Job dispatch also has genuinely alternative implementations — an in-process Loom scheduler, a DB-backed
durable queue, an external orchestrator hook — so it clears the ≥2-driver limb of the Wall test on its
merits rather than by construction.

The ROADMAP entry sketches a Community driver built on `StructuredTaskScope` plus
`ScheduledExecutorService`. Both halves of that sketch turn out to conflict with commitments the kernel
has already made elsewhere, and the conflicts are not stylistic — one is a 1.0 GA blocker, the other
makes the entry's own merge gate unreachable.

**This ADR answers: on what concurrency and timing primitives does a kernel job scheduler dispatch, and
what does it carry across the job boundary?**

## 🏁 The Decision

**Adopt a `JobScheduler` SPI in `eu.exeris.kernel.spi.scheduling` whose Community driver dispatches on
virtual threads with an explicit `ScopedValue` rebind, owns its timing loop against an injectable time
source, and uses no `ScheduledExecutorService` — so the subsystem adds neither a preview dependency nor a
scoped-ban exception.**

**Concrete obligations:**

1. **Package and type shape.** `eu.exeris.kernel.spi.scheduling` holds `JobSchedulerProvider` (the
   ServiceLoader discovery handle: `providerId()` / `providerName()` / `priority()` / `createScheduler(…)`),
   `JobScheduler` (the operational type), `JobDescriptor`, `JobHandle`, and `JobTrigger`. This follows the
   convention every sibling package already uses; unlike ADR-056, the ROADMAP's operational name survives
   unchanged, because `JobScheduler` was never the discovery handle. Bootstrap exposes
   `KernelProviders.JOB_SCHEDULER_PROVIDER` and `KernelProviders.JOB_SCHEDULER`.

2. **Dispatch on virtual threads with an explicit `ScopedValue` rebind — not `StructuredTaskScope`.**
   This deviates from the ROADMAP entry. `StructuredTaskScope` is the kernel's only remaining preview
   dependency, and the Platform Baseline commits the distributable default artifact to being
   preview-clean for 1.0 GA. Four import sites exist on the default line today —
   `CommunityEventLoop`, `SubsystemOrchestrator`, `OutboxOrchestrator`, `InMemoryEventBus`. Building a
   *new* subsystem on it would make a fifth, in a milestone whose stated direction is to remove them.
   The same reasoning the ROADMAP already applies to the anticipated `KafkaEventLoop` — "build it on the
   GA structured-concurrency layer from the start" — applies here with more force, because nothing has
   been written yet. **An ArchUnit rule forbids importing `StructuredTaskScope` from the new package.**

3. **No `ScheduledExecutorService`, and therefore no scoped-ban exception.** This also deviates from the
   ROADMAP entry, which asks for one *and* for an injectable time source. Those two requirements are
   mutually exclusive, and that is the whole argument: `ScheduledThreadPoolExecutor` computes its
   deadlines from `System.nanoTime()` internally, with no seam to displace it. A driver timing through
   STPE cannot be driven by an injected clock, so the entry's own merge gate — a deterministic trigger
   TCK with no `Thread.sleep` — is unreachable through it. The driver therefore owns its timing loop: a
   delay-ordered queue and one dispatcher virtual thread that waits until the next due deadline. Removing
   the exception is strictly better than documenting it; the kernel's scoped bans are worth more when the
   list of carve-outs stays empty.

4. **Time is injected, and so is waiting.** A time source alone is not sufficient for determinism — a
   test that advances a clock while the dispatcher sleeps on a real monitor still waits in wall-clock
   time. The driver takes both a monotonic time source and the wait primitive it parks on, so a test
   binding can advance virtual time and release the dispatcher immediately. This is a **subsystem-local
   seam, deliberately scoped**: the kernel has no unified clock abstraction (time is read ad hoc across
   the codebase, and consolidating it is a separate, later decision). This SPI is written so that
   migrating onto that seam is a substitution, not a redesign — and so the new subsystem adds no fresh
   ad-hoc-time debt in the meantime.

5. **Identity crosses the job boundary explicitly, by capture at submission.** `PrincipalContext` and
   `StorageContext` are captured when the job is *submitted* and rebound with
   `ScopedValue.where(...)` on the dispatching thread, using the `ScopedValue.Carrier` chaining the
   codebase already uses for multi-value rebinds. Two consequences are normative, not incidental:
   - A job dispatched with **no** captured context MUST NOT run under an ambient or default identity. It
     fails closed, and the failure is a JFR event, not a log line — the same reasoning as ADR-012 §4a's
     rule that a resolution the kernel cannot honour is a deny rather than a downgrade.
   - The captured context is a **snapshot**. A job that fires an hour after submission carries the
     identity that scheduled it, which may since have lost the rights it holds. Re-validating a token at
     dispatch time is out of scope here, and named as such below, because it is an identity decision
     rather than a scheduling one.

6. **`JobHandle.cancel` plus shutdown drain constitute the structured lifecycle boundary.** CLAUDE.md
   forbids unstructured concurrency in orchestration paths. Dispatching on bare virtual threads satisfies
   that rule through *this* boundary — every dispatched job is reachable from a handle, and subsystem
   shutdown drains or cancels outstanding jobs deterministically rather than abandoning them — and not by
   deferring to any orchestration helper. The scheduler must not acquire a dependency on one.

7. **Cron support is the standard five-field syntax and nothing more.** No seconds field, no `@reboot`,
   no vendor extensions. A cron parser is a surface where scope creep is invisible and compatibility
   claims are expensive; the subset is stated in the contract so a driver cannot quietly widen it.
   `JobTrigger` covers exactly three kinds — cron, fixed-interval, and one-shot. The ROADMAP entry lists
   a fourth, event-driven; it is deliberately excluded, for the reason given below.

8. **JFR is the observability surface.** `JobDispatchEvent`, `JobCompletionEvent`, and `JobFailureEvent`
   are single-phase commits. A job runs on a virtual thread, and straddling blocking work between an
   event's `begin()` and `commit()` there is a hazard this codebase has already met — see the standing
   comment at `CommunityOidcIdentityProvider.authenticate`, "Single-phase JFR commit AFTER (possibly
   blocking) validation — never straddle a VT". A scheduler whose entire purpose is dispatching blocking
   work onto virtual threads is the most exposed subsystem yet, so the rule is contractual here, not
   advisory.

9. **New error codes register in `KernelErrorCodes`,** under an `EX-JOB-*` family; exact numbers land
   with the SPI PR. House rules unchanged: constants only, no string literals at throw sites, `rawArgs`
   layout comments on failure paths.

## Consequences

### ✅ Positive Outcomes

- **[+] The new subsystem is GA-clean on arrival.** It adds nothing to the preview-taint set the
  milestone is trying to shrink, so the Platform Baseline work never has to come back for it.
- **[+] The trigger TCK is deterministic and fast.** Obligation 4 makes "cron fires on schedule" a test
  that advances virtual time rather than one that sleeps, which is the difference between a suite that is
  trusted and one that is retried.
- **[+] Scheduled work inherits the kernel's identity discipline.** A job carries the principal and
  storage context that scheduled it, and a context-less job refuses to run rather than running as nobody.
- **[+] One fewer scoped-ban exception.** The `ScheduledExecutorService` carve-out the ROADMAP
  anticipated is not needed at all, so the ban list stays absolute and therefore enforceable.

### ⚠️ Trade-offs

- **[-] The driver owns timing code it could have inherited.** A delay-ordered queue plus a dispatcher
  loop is code the JDK already ships, correctly, in `ScheduledThreadPoolExecutor`. Writing it is a real
  cost and a real defect surface — drift accumulation on periodic triggers, clock adjustment, and
  cancellation races are exactly the corners STPE gets right. The judgement is that testability against
  an injected clock is worth more than an inherited implementation, but the cost is not zero and the TCK
  must carry it.
- **[-] Bare virtual threads are weaker than a structured scope, by construction.** `StructuredTaskScope`
  would give lifetime containment for free; obligation 6 buys the equivalent by hand, through the handle
  and the drain. It is a rule the implementation must uphold rather than a property the compiler
  enforces — and a future dispatch site added carelessly is precisely how it would erode.
- **[-] The captured-context snapshot can go stale.** Obligation 5 is honest about this rather than
  papering over it: a long-delayed job may run under authority that has since been revoked. The
  alternative — re-validating at dispatch — needs a token or a provider reachable at fire time, which is
  a larger design and a different subsystem's decision.
- **[-] The five-field cron subset will not satisfy every migration.** An application arriving from
  Quartz with second-level triggers or `@reboot` semantics has to change its schedules. That is a
  deliberate cost of a small, well-defined surface.

### 📋 What is NOT in scope

- **Leader election and durable, at-least-once queueing.** These are the properties a multi-node
  deployment needs, and they are **not** re-invented here. They are named as the first consumer of the
  cross-node coordination seam being decided separately in this milestone — a scheduler that grew its own
  election protocol would be a parallel mechanism competing with that seam, which is the outcome to
  avoid. The in-process driver is explicitly single-node.
- **Re-validating identity at dispatch time** (see obligation 5's second consequence).
- **Job persistence across restart.** The in-process driver's schedule lives in memory; surviving a
  restart is a durable-backend property, and travels with the durable-queue work above.
- **Event-driven triggers**, which the ROADMAP entry lists as a fourth `JobTrigger` kind. Excluded on
  coupling direction. "Run this job when event X occurs" is already expressible: an event handler calls
  `submit`, and the job gains context capture, a handle, cancellation, and JFR events by that route. A
  trigger kind would instead make `spi.scheduling` depend on `spi.events` to name the subscription —
  putting an events dependency inside the scheduling contract to express something composition already
  covers. Events submitting jobs is the direction that keeps both contracts narrow; the reverse is a
  seam the kernel would have to maintain forever for no capability gained. Debounce and rate-limit
  semantics, which are the genuinely scheduler-shaped part of the request, are dispatch policy rather
  than a trigger kind, and are not in v0.11 either.
- **Distributed fan-out, work stealing, and job dependency graphs.** A scheduler that also expresses
  DAGs is a workflow engine, and this repository already has one — the Flow subsystem. Together with the
  entry above, both exclusions hold the same line: the scheduler composes with its neighbours rather
  than absorbing them.
- **1.0 GA scope.** `docs/ROADMAP.md` marks the v0.11/v0.12 SPI row — including `JobScheduler` —
  explicitly post-1.0. This SPI ships in v0.11 and 1.0 is not gated on it.

## Cross-references

- ADR-012 (Security Trust Model) — supplies `PrincipalContext` / `StorageContext` and the fail-closed
  reasoning behind obligation 5.
- ADR-056 (`BlobStorageProvider` SPI) — the sibling v0.11 SPI; obligation 1 follows the same
  provider/engine convention, and records why the naming deviation needed there does not arise here.
- `docs/ROADMAP.md` §"Runtime: `JobScheduler` SPI" — the entry this ADR realises and twice deviates from;
  §"Platform Baseline for 1.0 GA" — the preview-clean mandate behind obligation 2.
- `docs/subsystems/flow.md` — the neighbouring orchestration subsystem, and the boundary named above.
- `CLAUDE.md` §"Scoped Bans" — the ban obligation 3 declines to carve an exception from.

## Engineering Protocol

The codebase is not yet compliant — this ADR precedes the SPI. Enforcement lands with the implementation
slices:

1. **`AbstractJobSchedulerTck`** covers cron firing, interval delay, one-shot, cancellation through
   `JobHandle`, the submit → dispatch context round-trip (mandatory, per obligation 5), the context-less
   fail-closed case, and shutdown drain. Driven by virtual time throughout — a `Thread.sleep` in this
   suite is a defect, not a shortcut.
2. **`ExerisArchitectureTest`** gains a rule forbidding `java.util.concurrent.StructuredTaskScope` inside
   `eu.exeris.kernel..scheduling..` (obligation 2). The existing `noThreadLocal` rule already covers
   obligation 5's `ScopedValue`-only requirement.
3. **Guard-rationale correction.** The existing `noExecutorsAnywhere` rule states its reason as "All
   concurrency must use `StructuredTaskScope` (JEP 525)". That rationale predates the Platform Baseline
   and now contradicts it — the rule itself stays, only its stated reason is stale. Correcting the text
   rides the SPI PR; the rule is not weakened.
4. **`docs/subsystems/scheduling.md`** lands with the SPI slice, per the one-contract-doc-per-subsystem
   convention.
5. **Registry check:** `EX-JOB-*` codes present in `KernelErrorCodes` before any throw site references
   them.
