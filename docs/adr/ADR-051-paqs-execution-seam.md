# ADR-051: PAQS Execution-Seam (`StreamExecutionBackend`) — an agnostic stream-execution injection point

| Attribute       | Value                                                                                                                                                                                       |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ADR #**       | **051** (reserved 2026-07-04 in `exeris-docs/adr-index.md`).                                                                                                                              |
| **Status**      | **Accepted** — shipped in **v0.11.0**. This row previously read *Proposed* with its own rule attached: "flips to **Accepted** on merge". It merged and the flip was never executed, so the header disagreed with the tree for a full release. Verified before flipping, rather than flipped on the rule alone: all three things this ADR said would land together are present — `StreamExecutionBackend` and `PaqsScheduler`'s use of it in `exeris-kernel-core/src/main`, and the `AbstractPaqsSchedulerTck` seam-contract in that same module's tests, which is the right home given this ADR's own compliance note that the seam is Core-internal and **not** SPI. |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                       |
| **Date**        | 2026-07-04                                                                                                                                                                                 |
| **Scope**       | kernel/transport                                                                                                                                                                          |
| **Owning Repo** | `exeris-kernel`                                                                                                                                                                            |
| **Driven By**   | v0.11 ROADMAP §"Transport: PAQS Execution-Seam Port (M1)"; the port of the refactor-neutral M1 seam from `research/loom-continuation-locality` (v0.6); the DST moat (ROADMAP "Road to 1.0" §"Deterministic Simulation Testing"); prior public analysis of Loom scheduler geometry and continuation locality (the cost of splitting I/O completion reaping from continuation execution). |
| **Compliance**  | The Wall (ADR-006 — the seam is Core-internal, **not** SPI); No Waste Compute (refactor-neutral default, zero hot-path delta); `ScopedValue` (no `ThreadLocal`) for stream context; no new `StructuredTaskScope` import on the default path. |

## Context and Problem Statement

`PaqsScheduler` spawns each admitted stream's root Virtual Thread **inline** —
`Thread.ofVirtual().name(threadName).start(...)` — the single deliberate exception to the
`StructuredTaskScope` mandate (this unstructured VT is the Root of the Request Tree; all work
below it uses structured scope).

The parked `research/loom-continuation-locality` branch (v0.6) extracted this spawn behind a
one-method seam, **`StreamExecutionBackend`** — its **M1** milestone — and proved the extraction
**refactor-neutral** (**M2**: the default backend reproduces the prior inline spawn byte-for-byte).
But the seam lived **only on that branch**; the default line still spawned inline, so the
extraction was one `git branch -D` away from loss.

Two forward consumers depend on exactly this injection point:

- an **Enterprise / custom-scheduler locality re-test** — a backend that pins Virtual Thread
  continuations to a supplied carrier scheduler;
- the post-1.0 **DST `SimulationScheduler`** — a backend that single-steps stream execution
  deterministically (ROADMAP "Road to 1.0" §DST).

**The locality hypothesis closed `NO_GO` on that branch — but the verdict is config-scoped, not a
refutation.** The measurement ran Community-only, on the **stock** Virtual-Thread scheduler: the
affine backend's carrier pinning rode a *reflective* VT custom-scheduler override
(`LocalityAwareExecutionBackend`) that is **absent unless running a Loom custom-scheduler JDK**, so
the "locality-aware" arm **could not actually pin carriers** — it degraded to the default. It ran
without `pullerMode=3`, and the Enterprise **io_uring** track (M4/M5: native reaper + affine +
multishot + bounded drain) **never ran**. Native io_uring ingress is precisely the regime where
continuation-locality effects should be strongest — where the split between kernel I/O completion
reaping and user-space continuation execution is most pronounced. The
research's own conclusion demanded "a materially different hypothesis" for future locality work;
that hypothesis — **io_uring + a Loom custom scheduler + `pullerMode=3`** — is the live re-test,
untested in the regime that matters.

**This ADR answers: where does the extracted seam live, what does it commit to, and how far do we
take it in v0.11 — without landing a questionable-shape abstraction or breaking The Wall?**

## 🏁 The Decision

**Adopt `StreamExecutionBackend` as a Core-internal (`eu.exeris.kernel.core.transport.scheduler`),
`@FunctionalInterface` stream-execution injection seam in `PaqsScheduler`. The default backend
spawns one Virtual Thread per stream — behaviourally identical to the prior inline spawn, changing
no admission, load-shedding, or JFR behaviour. The interface (`void start(String threadName,
Runnable task)`) is blind to strategy: `{default VT-per-stream / locality-affine /
deterministic-simulation}` are backend choices, not interface concerns.**

The seam is **agnostic by design** because both non-default consumers rest on the *same* substrate
— a custom Virtual Thread carrier scheduler (the capability `LocalityAwareExecutionBackend` reaches
for at construction). One seam therefore serves both locality and DST; which is installed is the
implementation's business, invisible to `PaqsScheduler`.

**Concrete obligations (this slice):**

- `StreamExecutionBackend` is Core-internal — **not** an SPI type. No driver, native, or
  scheduling-library detail crosses The Wall; the seam carries only `(String, Runnable)`.
- `PaqsScheduler` gains an **additive** 6-arg constructor taking the backend; the existing 5-arg
  constructor delegates to it with the default backend (source-compatible; every existing caller is
  unchanged).
- The default backend is the **exact prior inline spawn** (`Thread.ofVirtual().name().start(task)`)
  — the extraction is refactor-neutral by construction.
- `AbstractPaqsSchedulerTck` gains an execution-backend-seam contract (a custom backend preserves
  the stream's `ScopedValue` bindings), bound in `CorePaqsSchedulerTckTest`; `PaqsSchedulerTest`
  proves the default backend's observable behaviour (thread-name format, task runs) is unchanged.
- No new `StructuredTaskScope` import on the default path (the seam is the pre-existing
  Root-of-the-Request-Tree spawn, not a new orchestration site).

## Why Core-internal, not SPI

A driver does not *implement* stream execution — it hands `PaqsScheduler` a `StreamHandler` and a
`TransportStream`; the scheduler owns admission, load-shedding, and the root-VT spawn. The execution
strategy is an orchestration detail of that spawn, not a contract a driver fulfils. An Enterprise
locality backend is installed by **constructing `PaqsScheduler` with it** at the transport engine's
composition root (out-of-repo), which needs no SPI surface. Promoting the seam to SPI would leak a
Core orchestration mechanism into the implementation-blind contract for no consumer benefit and
would invite driver/native detail across The Wall. It stays Core-internal.

## Scope: transport-local now; kernel-wide promotion gated on the DST RFC

The two consumers differ in **scope**, and this ADR commits only to the transport-local slice:

- **Locality is transport-local.** Carrier affinity matters on the stream hot path, so this one
  PAQS seam suffices for the Enterprise io_uring re-test.
- **DST is kernel-global.** A deterministic harness needs the *same* scheduler installed at **every**
  Virtual-Thread spawn site — `OutboxOrchestrator`, `InMemoryEventBus`, `CoreFlowRuntime`,
  `AsyncTelemetrySink`, `MemoryMaintenanceTask` — **and** the Platform-Baseline `fork`/`join`/`cancel`
  orchestration seam. This PAQS seam is DST's *transport* injection point, not all of DST.

Therefore "one agnostic backend serves both" holds **at the interface level**, but a kernel-wide
`ExecutionBackend` (routing all spawn sites through one scheduler) is **DST-complete** work that
overlaps the Platform-Baseline seam and is **gated on the DST RFC**
(`RFC-2026-06-22-deterministic-simulation-testing.md` — the total-order-vs-placement question). v0.11
stays transport-local; no kernel-wide promotion here.

## What this ADR does NOT do

- **Does not ship a locality backend on the default line.** `LocalityAwareExecutionBackend`
  (reflective; a no-op on a stock JDK) belongs to the Enterprise / research track, not the shipped
  Community artifact — landing reflective dead code there would violate "new abstraction must justify
  measurable value". Enterprise supplies its own io_uring-affine backend out-of-repo against this
  seam.
- **Does not port the A/B benchmark** (`CoreContinuationLocalityBaseline`) — that is measurement
  harness and belongs in `exeris-benchmarks`.
- **Does not re-open the locality investigation** — it records the config-scoped nature of the
  `NO_GO` and names the live re-test regime, but running it is a separate research track on a
  custom-scheduler JDK.

## Consequences

- **Positive.** The refactor-neutral seam lives in-tree, no longer one branch-deletion from loss;
  the Enterprise io_uring locality re-test is enabled with **zero kernel change**; DST gains its
  transport injection point; the default path is unchanged (all pre-existing PAQS tests green).
- **Tradeoff.** The default line carries an execution seam with only the default backend wired —
  justified by the two named forward consumers and the branch-loss de-risk, and bounded by the
  refactor-neutral guarantee (the seam adds an interface + one constructor, nothing on the hot path
  beyond an already-present indirection).
- **Deferred.** The reflective custom-scheduler dependency and the io_uring re-test live out-of-repo
  (Enterprise) until a Loom custom-scheduler JDK is used at test time; kernel-wide unification waits
  on the DST RFC.

## Cross-references

- ROADMAP v0.11 §"Transport: PAQS Execution-Seam Port (M1)"; ROADMAP "Road to 1.0" §"Deterministic
  Simulation Testing (DST) Harness".
- `research/loom-continuation-locality` (v0.6) — M1 seam extraction, M2 refactor-neutrality, the
  config-scoped `NO_GO`.
- ADR-006 (The Wall) — the seam is Core-internal, not SPI.
- The Platform-Baseline `fork`/`join`/`cancel` orchestration seam — a **distinct** injection point
  (subsystem orchestration vs transport continuation-resume); do not conflate. Kernel-wide
  unification of the two is DST-RFC territory.

## Engineering Protocol

- **Refactor-neutral proof:** the default backend is the exact prior inline spawn; every
  pre-existing `PaqsSchedulerTest` / `AbstractPaqsSchedulerTck` case stays green, and the new
  seam-contract case (`ExecutionBackendSeamContract` / `CustomExecutionBackend`) proves a custom
  backend both runs the task and preserves the stream's `ScopedValue` bindings.
- **The Wall:** `StreamExecutionBackend` stays in `exeris-kernel-core`; no SPI type, no driver import.
- **No Waste Compute:** no new allocation or copy on the schedule hot path; the seam is a single
  interface call replacing a single inline spawn.
- **Verification:** `mvn clean install` (nothing skipped — PMD / Checkstyle / SpotBugs / tests) green
  on the changed modules; no new `StructuredTaskScope` import on `src/main`.
- **Lockstep:** none required this slice — the seam needs no SPI, so no `.link.md` stubs; an
  Enterprise locality backend and the DST harness are separately-tracked future consumers.
