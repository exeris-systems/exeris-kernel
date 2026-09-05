# Scheduling Subsystem

Deferred and repeating work, scoped to the tenant that scheduled it. Decided in
[ADR-057](../adr/ADR-057-job-scheduler-spi.md).

SPI: `eu.exeris.kernel.spi.scheduling` — `JobSchedulerProvider` discovers, `JobScheduler` operates.
Community driver: `eu.exeris.kernel.community.scheduling`, in-process dispatch on virtual threads.

## What the contract covers

*When* work runs, and *whose* identity it runs under. Nothing else. Durable job stores, leader
election, and cross-node coordination are excluded by contract, not merely unimplemented — they need
the coordination seam, which is a separate decision, and naming them here would invite a driver to
invent a parallel mechanism.

## Triggers

Three kinds, and the set is sealed so a driver cannot add a fourth.

| Trigger | Fires |
|---|---|
| `JobTrigger.Cron` | On a five-field cron schedule |
| `JobTrigger.FixedInterval` | After an initial delay, then at a fixed gap between runs |
| `JobTrigger.OneShot` | Once, after a delay |

The ROADMAP listed a fourth, event-driven. It is excluded: an event-triggered job is a subscription,
and the kernel already has one of those. Routing it through a scheduler would create a second way to
consume events whose ordering and delivery guarantees would have to be re-specified.

`FixedInterval` is fixed *delay*, not fixed *rate* — a run that overruns its interval delays the next
one instead of causing a burst of catch-up runs. Catch-up is what turns a temporary slowdown into an
outage, so the contract does not offer it. The driver enforces this structurally: a job is removed
from the queue for the whole run and requeued only when it finishes, so it is either queued or
running and never both.

### Cron subset

Five numeric fields — `minute hour day-of-month month day-of-week` — over `0-59`, `0-23`, `1-31`,
`1-12`, `0-7` (both `0` and `7` mean Sunday). Each field is a comma-separated list of terms, where a
term is `*`, a value, a `lo-hi` range, or either followed by `/step`.

**Steps are strides and have no upper bound.** `/step` must be at least `1`, and beyond that only the
two-digit parse limits it — a step of `100` is refused for its length, not for its size. A step wider
than the field it strides through is a schedule, not a typo: it passes every value but the first, so
it fires exactly once per cycle. That is what `0 */24 * * *` means, and it is the common spelling of
"daily". Note the consequence, which reads backwards at first: `*/24` on hours fires **once**, while
`*/23` fires **twice** (at `0` and `23`). Any bound placed at the field's upper value would therefore
reject the first and admit the second.

**Deliberately absent:** a seconds field, `@reboot` and friends, `?`, `L`, `W`, `#`, and name aliases
such as `MON` or `JAN`. Names are excluded despite being common because implementations disagree on
whether day-of-week `0` is Sunday or Monday, and the alias spelling hides that disagreement; numbers
make the ambiguity visible and the `0`/`7` rule resolves it.

Validation lives in the SPI (`JobTrigger.Cron`'s constructor), so the subset is a property of the
carrier rather than a rule each driver is trusted to enforce. What a valid expression *means* — the
next fire time — stays driver work.

Two semantics worth stating because they surprise people:

- **UTC, not local time.** Local time would make every schedule depend on the host's zone database
  and would expose the two classic DST defects: a daily job running twice on the autumn overlap, and
  one silently skipping the spring gap. Neither exists in UTC.
- **Day-of-month and day-of-week are OR'd** when both are restricted. `0 0 1 * 1` fires on the first
  of the month *and* every Monday — not on Mondays that fall on the first.

## Identity

`PrincipalContext` and `StorageContext` are captured at `submit` and rebound with `ScopedValue` on
the dispatching thread. Two consequences are normative:

- **A job with no captured context fails closed, and does so once.** It does not run under an
  ambient or default identity — the same reasoning as ADR-012 §4a, that a resolution the kernel
  cannot honour is a deny rather than a downgrade. The refusal is a JFR event carrying
  `EX-JOB-9001`, and it **retires the job**, repeating trigger or not. Because the capture is a
  snapshot (below), a refusal is a permanent property of that job rather than a bad run: rescheduling
  it would re-refuse on every interval forever, turning one deny into an unbounded failure stream.
- **A settled job releases what it was holding.** The handle stays addressable by id — that is what
  post-mortem lookup needs — but the job body and the captured identity are dropped once the job can
  no longer run. Keeping them would pin one closure and one identity per job the scheduler has ever
  run, for the scheduler's whole life. A job cancelled mid-flight releases when the body returns, not
  when the cancel lands.
- **The handle itself is retained for the scheduler's life, and that is currently unbounded.** ADR-057
  §6 keeps a `JobHandle` addressable after the job ends, so the Community registry never removes a
  settled job from its id map. What accumulates is the handle shell and its id — the body and the
  captured identity are already gone, per the bullet above — but a service submitting one-shot jobs
  per unit of work grows that map forever, with no cap and no TTL. **Do not treat this as a settled
  design.** Bounding it means choosing how long "addressable" lasts, which is a revision of §6 and
  needs its own decision record rather than a quietly-added eviction policy.
- **The capture is a snapshot.** A job firing an hour after submission carries the identity that
  scheduled it, which may since have lost the rights it holds. Re-validating a token at dispatch time
  is an identity decision, not a scheduling one, and is out of scope here.

## Lifecycle boundary

Jobs dispatch on bare virtual threads, not inside a `StructuredTaskScope`. The repository forbids
unstructured concurrency in orchestration paths (`.agents/policies/the-wall.md`); the equivalent containment comes from
`JobHandle.cancel()` plus the drain in `JobScheduler.close()`. Every dispatched job is reachable from
a handle, and shutdown awaits runs already in flight rather than abandoning them.

This is a rule the implementation upholds rather than a property the compiler enforces — a dispatch
site added carelessly is exactly how it would erode, which is why the guards below name it.

`cancel()` promises no *further* dispatches. It does not interrupt a run already in flight:
interrupting arbitrary application code at an arbitrary point is not something a scheduler can do
safely.

## Timing

The driver owns its timing loop against an injected time source — one dispatcher virtual thread and a
delay-ordered queue — rather than delegating to a `ScheduledExecutorService`.

This is the ADR's sharpest deviation from the ROADMAP, which asked for a scoped-ban exception for
`ScheduledExecutorService` *and* an injectable time source. Those are mutually exclusive:
`ScheduledThreadPoolExecutor` computes deadlines from `System.nanoTime()` internally with no seam to
displace, so a driver timing through it cannot be driven by an injected clock, and the ROADMAP's own
merge gate — a deterministic trigger TCK with no `Thread.sleep` — is unreachable through it. Removing
the exception is better than documenting it: the kernel's scoped bans are worth more when the list of
carve-outs stays empty.

The seam injects **both** the clock and the wait primitive. A time source alone is not sufficient: a
test that advances a clock while the dispatcher sleeps on a real monitor still waits in wall-clock
time. It is deliberately subsystem-local — the kernel has no unified clock abstraction yet, and this
is shaped so migrating onto one is a substitution, not a redesign.

## Telemetry

| Event | When | Fields |
|---|---|---|
| `eu.exeris.kernel.scheduling.JobDispatch` | A job is dispatched onto a virtual thread | `schedulerName`, `jobName`, `jobId` |
| `eu.exeris.kernel.scheduling.JobCompletion` | A body returns normally | `schedulerName`, `jobName`, `jobId`, `durationNanos` |
| `eu.exeris.kernel.scheduling.JobFailure` | A body throws, or a dispatch is refused for want of context | `schedulerName`, `jobName`, `jobId`, `errorCode`, `reason` |
| `eu.exeris.kernel.scheduling.SchedulingBootstrapSelected` | Bootstrap picks a provider | `providerClass`, `providerId`, `priority`, `schedulerName` |

All single-phase commits. A scheduler's whole purpose is dispatching blocking work onto virtual
threads, so it is the subsystem most exposed to the `begin() → blocking-op → commit()` straddle that
binds a carrier-local `EventWriter` across a park; here the rule is contractual, not advisory.

Job names are developer-chosen and safe to record. Nothing a body touched, and no principal or tenant
identifier, is recorded.

`JobFailure` is where a *refusal* becomes distinguishable from a *crash*. At the SPI level both look
the same — the body did not run, the state is `FAILED` — and only `errorCode` tells an operator the
kernel decided this.

## Guards

| Guard | What it holds |
|---|---|
| `AbstractJobSchedulerTck` | The contract above, bound per driver. Every timing case advances virtual time; `advanceTime` is abstract rather than defaulted-to-sleep, so a binding cannot pass the suite while sitting on real time. |
| `CronSyntaxTest` | The accepted subset and its boundary, in the SPI — including that a six-field Quartz expression is a field-count error rather than a schedule shifted by a factor of sixty. |
| `CommunityCronScheduleTest` | What a valid expression means: rollover, steps, lists, the `0`/`7` Sunday aliases, the day-of-month/day-of-week union, leap day, and UTC interpretation. |
| `CommunityJobJfrTest` | All three events commit, and a refusal carries `EX-JOB-9001` rather than looking like a crash. |
| `CommunitySchedulingArchitectureTest` | No `StructuredTaskScope`, no `ThreadLocal`, no `ScheduledExecutorService` in the driver. |
| `CommunitySchedulingSubsystemTest` | Bootstrap wiring: both slots bound to a scheduler that actually runs a job under the submitter's tenant, `stop()` drains rather than abandons, and the selection is recorded. |
| `ExerisArchitectureTest.noStructuredTaskScopeInSchedulingSpi` | The same preview ban on the SPI half. |

The architecture guard is split across two modules on purpose. `ExerisArchitectureTest` runs in
`exeris-kernel-tck`, whose only production dependency is the SPI — so no Core or Community class is
ever on its analysis classpath, and a rule written there naming a Community package can never fire.
`CommunitySchedulingArchitectureTest` lives in the module that can see the driver, and asserts a
non-empty analysis set before asserting anything else.

## Bootstrap

`CommunitySchedulingSubsystem` runs in the `SERVICES` phase and **declares no dependencies**.

That is not an oversight. The in-process driver allocates no off-heap memory, opens no connection and
reads no store — its construction is a config lookup and a virtual thread. The tempting declaration
is `security`, on the reasoning that a job carries the identity that submitted it; that is wrong
twice over. There is no `security` subsystem in the registry — identity arrives through `ScopedValue`
slots, not a boot-ordered component — and identity crosses this seam at *submit* time from the
caller's own scope, which no boot order can influence.

| Key | Default | Meaning |
|---|---|---|
| `scheduling.schedulerName` | `default` | Name in JFR events and diagnostics |

Provider selection goes through the shared `BootstrapProviderSelector` (highest `priority()` wins)
and commits `eu.exeris.kernel.scheduling.SchedulingBootstrapSelected`. Which provider won a
ServiceLoader race is invisible after the fact and is the first thing anyone asks when a scheduler
behaves unexpectedly, so it is recorded at selection rather than reconstructed from behaviour.

No provider on the classpath is a terminal `EX-JOB-9004` rather than a degraded boot: a kernel that
started with scheduling silently absent would let an application submit jobs that never run.

Subsystem `stop()` calls `JobScheduler.close()`, so the drain of ADR-057 §6 is what shutdown
actually performs — not a property the scheduler merely offers.

Both slots are bound: `KernelProviders.JOB_SCHEDULER_PROVIDER` and `KernelProviders.JOB_SCHEDULER`.

## Error codes

| Code | Meaning |
|---|---|
| `EX-JOB-9001` | Dispatch refused — the submission captured no identity context |
| `EX-JOB-9002` | The scheduler is closed |
| `EX-JOB-9003` | A job body threw; recorded on the JFR failure event rather than raised, since a dispatched body has no caller to throw to |
| `EX-JOB-9004` | No `JobSchedulerProvider` on the classpath at bootstrap |

## Not in this subsystem

Durable job stores, leader election, distributed coordination, retry and back-off policy, job
priorities, and dispatch-time re-validation of a captured identity.
