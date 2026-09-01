# Kernel Subsystem: Flow / Sagas (L4 Orchestration)

- SPI: `eu.exeris.kernel.spi.flow.*`
- Core runtime: `eu.exeris.kernel.core.flow.*`
- Community binding: thin provider and in-memory snapshot store in `eu.exeris.kernel.community.flow.*`

**Layer:** L4 (Orchestration)
**Status:** Baseline runtime is implemented in Core in the current repository state.

## Current Repo Reality

- Core owns flow orchestration: definition compilation, scheduling, park/wake, compensation, and runtime state transitions.
- Community is a thin binding over the shared Core engine.
- Community snapshot persistence (since v0.8 Sprint 0b, ADR-022): `CommunityFlowSubsystem.initialize()` selects `JdbcFlowSnapshotStore` when a Community `PersistenceEngine` is bootstrapped alongside (durable, cross-restart) and falls back to the heap `CommunityFlowSnapshotStore` when no engine is available. `flow.persistenceEnabled=false` leaves the `FLOW_SNAPSHOT_STORE` ScopedValue unbound. The choice is locked in `CommunityFlowSubsystemSnapshotStoreWiringTest`.

## Runtime Behavior

- Flow execution is step-based and stateful: `CREATED`, `RUNNING`, `PARKED`, `COMPENSATING`, `COMPLETED`, `FAILED_ROLLEDBACK`.
- Park/wake is supported through the Flow scheduler API.
- Compensation is supported when enabled in `FlowEngineConfig`.
- Snapshot persistence is optional and only used when a `FlowSnapshotStore` is bound and persistence is enabled.

> **Note:** `FlowOutcome.COMPLETE` provides a direct short-circuit path: a step can return `COMPLETE` to transition the flow immediately to `FlowState.COMPLETED` without executing any remaining steps.
> **Note:** `FlowBootstrapSelectedEvent` is emitted by `FlowBootstrap.loadWithProvider()` before `start()`. `FlowEngine.close()` emits `FlowEngineShutdownEvent` after runtime close and its bounded shutdown join, so the JFR payload reflects the stable shutdown counter view captured when `close()` completes. Promptly interrupted workers may still finalize snapshots afterward without changing that counter snapshot.
> **Note (shutdown semantics):** `close()` is **not** an unbounded graceful drain — it interrupts in-flight flows and joins each worker within a bounded per-thread deadline (`CoreFlowRuntime.interruptAndJoinRunningThreads`, currently 5s/thread). PARKED checkpoints persisted before `close()` survive for restart recovery (see `AbstractSagaRecoveryTck.RestartUnderLoad`); in-flight RUNNING progress past the last checkpoint may be lost. A worker abandoned past that join belongs to a now-closed runtime — a lifecycle write-fence in `persistSnapshot` (`!isActiveLifecycle && !isTerminal`) prevents such a straggler from re-persisting a `PARKED` (non-terminal) checkpoint after a rebuilt runtime has already reclaimed (deleted) the row on `complete()`, so the unbounded-mode "deleted on `complete()`" guarantee holds across restart (regression: `CoreFlowRuntimeStaleWriteFenceTest`). **Known constraint:** the per-thread join is iterated sequentially, so the aggregate worst case is N×5s under wedged-interrupt, not 5s. A future `closeTimeoutNanos` config (aggregate-bounded join) is a candidate follow-up — not yet implemented. A durability/shutdown-cost JMH benchmark is deferred to `exeris-benchmarks` (backlog).

## Boundaries

- SPI remains implementation-blind.
- Core owns orchestration and runtime behavior.
- Community stays thin: provider wiring plus snapshot-store resolution — JDBC-backed (`JdbcFlowSnapshotStore` through `PersistenceEngine`) when persistence is available, in-memory (`CommunityFlowSnapshotStore`) otherwise.

| Scenario                                           | Kernel Behaviour                                                                                         |
|:---------------------------------------------------|:---------------------------------------------------------------------------------------------------------|
| **New deployment adds a step** to a Saga           | Existing in-flight Sagas (persisted in `exeris_saga_state`) continue on the **old definition**. New Sagas use the new definition. The `FlowRegistry` stores the definition snapshot at submission time. |
| **New deployment removes a step** (or otherwise shrinks the plan) | **Enforced fail-closed since v0.10** (`reason="STEP_OUT_OF_RANGE"`). On wake, `CoreFlowRuntime` validates the persisted resume step (`stepIdx`) against the redeployed plan's step count; if the step no longer exists (index out of range), it throws `EX-FLOW-7002` with `phase="SCHEMA_MISMATCH"` (`reason="STEP_OUT_OF_RANGE"`, `rawArgs[3]`=persisted step) **before any step replays** — never a silent stale-index re-execution. Manual intervention / a version-aware migration is required. This is a **bounds/arity** guard. |
| **New deployment reorders steps** (same step count) | **Enforced fail-closed since v0.11 (ADR-062).** The snapshot records the *identity* of the step it parked at (`FlowSnapshot.currentStepName`, sourced from `FlowStepDescriptor.name`, which `FlowDefinition` now requires to be distinct). On wake, `CoreFlowRuntime` compares it against the step the persisted index addresses in the redeployed plan; a mismatch throws `EX-FLOW-7002` with `phase="SCHEMA_MISMATCH"`, `reason="STEP_IDENTITY_MISMATCH"` **before any step replays**. This is the case the bounds guard structurally cannot see — a same-arity reorder leaves the index valid, which is precisely why replaying it bound the saga to the wrong step. Renaming a step is therefore a compatibility change for in-flight Sagas. |
| **Saga parked before upgrading to 0.11** | **Rejected fail-closed** (`reason="STEP_IDENTITY_ABSENT"`). Such a snapshot carries no step identity, so the reorder check cannot run — and resuming it would mean trusting the index again, the behaviour ADR-062 removes. Admitting it would leave a permanent route back to positional resume. **Drain in-flight Sagas before upgrading**, the same blue/green procedure this table already prescribes for reordering deploys. |
| **New deployment changes a definition and bumps its version** | **Coexistence since v0.11 (ADR-064).** `FlowDefinition` carries an `int version` and the plan catalog is keyed by `(name, version)`, so registering v2 no longer evicts v1. A parked saga resumes on the version it recorded in `FlowSnapshot.definitionVersion`, not on whichever version is newest — the rebinding the rows above merely *detect* is what this removes. New instances start on the newest registered version. Retaining versions costs catalog slots: `FlowEngineConfig.maxExecutionPlans` bounds versions as well as distinct definitions, and retiring a version is an operator action with no automatic reclamation. Declaring no version is still valid — a definition built without one is version 1 and an application that never bumps behaves exactly as before. **Declaring one through the fluent API works only since 0.12** (ADR-064 amendment): `newDefinition(name)….version(n).build()`. For the whole of v0.11 `FlowDefinitionBuilder` had no `version`, so every definition assembled the supported way was version 1 and the coexistence this row describes was unreachable from application code. |
| **Parked saga names a version this engine does not host** | **Rejected fail-closed** (`reason="DEFINITION_VERSION_UNRESOLVED"`). Distinguished from a definition this engine hosts *no* version of, which is not an error at all but the cross-restart / cross-engine fallback (ADR-013 §8) — collapsing the two would break choreography on any node hosting only part of the flow catalogue. **The parked row is left untouched**, so deploying the missing version recovers the saga; the kernel deliberately introduces no quarantine state, because marking it terminal would be irreversible and would run compensation for a definition the runtime cannot bind. |
| **Saga parked before the version column existed** | **Rejected fail-closed** (`reason="DEFINITION_VERSION_ABSENT"`). The durable column is `NOT NULL DEFAULT 0`, and the 0 is load-bearing — it is `FlowSnapshot.VERSION_ABSENT`, and since versions start at 1 it can never name a real one. A pre-0.11 row backfills to "no version recorded" and is refused, rather than to 1, which would assert that every parked saga belongs to the first version — the guess this epic exists to stop making. The absence lives in the migration rather than in a nullable column because the row cursor has no NULL representation for an integer read: a nullable column would express the intent and then raise on load instead of refusing on resume. Same remedy as the identity row above — drain in-flight Sagas before upgrading. |
| **Retiring the version a saga is parked under** | **Migratable since v0.11 (ADR-064).** An application registers an adjacent transform with `FlowExecutionPlanFactory.registerMigration(name, fromVersion, migration)`; on wake the runtime walks `v → v+1` until it reaches a version the engine hosts, and resumes there. It stops at the **first** hosted version — a transform registered past what is still hosted does not carry the saga beyond what can run it. A gap in the chain is the `DEFINITION_VERSION_UNRESOLVED` refusal above, with the row left intact so registering the missing transform and waking again is the remedy. A row that needs a walk but records **no step identity** refuses as `STEP_IDENTITY_ABSENT` instead, and deliberately so: it cannot be walked (the transform's input is built from the parked step's name) and no deployment fixes it, so reporting it as a version miss would point the operator at a remedy that is often already satisfied. Registering a second transform for the same `(name, fromVersion)` is refused rather than silently replacing it. The transform receives and returns a `FlowMigrationState` (parked step index and name, compensation stack **and its step identities**, stack pointer, opaque state); every component it can set is validated rather than trusted — naming a step the target plan lacks fails `STEP_IDENTITY_MISMATCH`, a compensation-stack entry the target plan cannot index fails `COMPENSATION_STACK_OUT_OF_RANGE`, and an entry whose declared identity the target plan contradicts fails `COMPENSATION_STACK_IDENTITY_MISMATCH` (see the rows below). Since v0.11 (ADR-064 A5) that closes the gap: application code on this path is not a new trust boundary for where the saga lands **or for what a rollback would undo**. A row that needs a walk but carries a live stack with no identities refuses as `COMPENSATION_STACK_IDENTITY_ABSENT` before the transform runs, for the same reason the missing-cursor-identity case does — there is no input to hand it. A transform that throws propagates and the wake fails closed, leaving the row recoverable. **Resume-restore only:** `schedule()` fixes the target version at the caller's plan, which makes the chain's terminating condition path-dependent, so the resubmit path keeps refusing instead. Migration runs on `wake()` and on `lookupParked()`'s durable-store fallback — that fallback is a restore rather than a read (it already built an instance, registered it parked and emitted `WakeOnLoadFallbackEvent`), and it is how `FlowChoreographyBridge` wakes a cross-engine saga (`lookupParked(...).ifPresent(wake)`), so excluding it would make a choreographed saga on a retired version refuse instead of migrate. A bare introspection `lookupParked` therefore can run a transform and write — once, since the persisted result leaves nothing to migrate on the next call. A successful migration is persisted before the resumed step runs, so the chain does not re-run on every wake and transform purity is not an unstated obligation. |
| **Parked saga's compensation stack no longer indexes the plan** | **Rejected fail-closed since v0.11** (`reason="COMPENSATION_STACK_OUT_OF_RANGE"`, ADR-064 amendment A4). The two cursor guards above validate where a saga *resumes*; a rollback walks the other direction, through the compensation stack, which is stored as bare step indices. A saga can pass both cursor guards — right index, right name — and still hold a stack that means nothing in the plan it just bound to (a shrinking redeploy, or a migration transform that rewrote it). Checked on resume rather than left to fail during compensation, because `runCompensationStep` resolves each entry with `plan.stepAt(entry)` **outside its own catch**: a stale entry there aborts the remaining unwind *and* skips the terminal write, leaving the saga mid-compensation with its idempotency guard still held — and only after some other failure has already put it on the rollback path. Only the live prefix below `stackPointer` is checked; entries above it are dead, and refusing on those would fail closed on a sound saga. This is the **bounds** half; the identity half is the row below. |
| **Parked saga's compensation stack indexes the plan but addresses different steps** | **Rejected fail-closed since v0.11** (`reason="COMPENSATION_STACK_IDENTITY_MISMATCH"`, ADR-064 amendment A5). `FlowSnapshot.compensationStepNames` records, per live entry, the identity of the step that entry addressed when it was pushed; resume compares each against the plan it is binding to. This is the half bounds structurally cannot reach — a same-arity reorder leaves every entry in range — and it is the **more dangerous** of the two, which is the opposite of how the pair reads at first. An out-of-range entry throws at `plan.stepAt` inside failure handling: loud, and the parked row survives. An in-range entry addressing a different step throws nothing, and the unwind either skips a compensation that was owed or runs a *different* step's compensation — no exception, no JFR event, no counter. Neither outcome is undoable the way a refused resume is, because a compensation is a side effect that has already happened by the time it can be observed. A live stack carrying **no** identities refuses as `COMPENSATION_STACK_IDENTITY_ABSENT` rather than being trusted by position (ADR-062 obligation 6, one component over); an **empty** stack is not an absent one, since with nothing live there is nothing to validate. Only the live prefix below `stackPointer` is checked, as for bounds. |
| **Safe migration pattern (current)**               | Bump the definition version rather than changing a definition in place — coexistence keeps in-flight sagas on the version they parked under, and a registered transform moves them forward when the old version is retired. Blue/green with saga drain remains the pattern for the cases versioning does not cover: an in-place change under a saga parked before v0.11 (no recorded identity or version) is still a fail-closed refusal, not a migration. |

> **Not covered by versioning:** retiring a version is an operator action — the kernel reclaims no
> catalog slot automatically, and nothing sweeps sagas onto newer versions ahead of a wake. Migration
> transforms are registered in code against `(definitionName, fromVersion)`; the kernel has no
> annotation-driven or configuration-driven declaration of them.

---

## Compensation Failure Handling

**What the stack holds.** `FlowSnapshot` records the compensation stack as two parallel arrays plus a
depth: `compensationStack` carries plan **positions**, `compensationStepNames` carries the **identity**
of the step each live position addressed when it was pushed, and `stackPointer` says how many entries
of each are live. Entries at or above `stackPointer` are dead high-water marks, not state — guards that
scan the whole array instead of the live prefix refuse sound sagas. The pairing exists because a
position alone means a different step after a reorder, which is ADR-062's argument for the cursor
applied one component over (ADR-064 amendment A5); identities were **added beside** positions rather
than replacing them, because execution still addresses steps by index.

The runtime derives the identities at snapshot time from the plan the instance is bound to, rather than
tracking them alongside the in-memory stack. In memory the two cannot disagree — every push comes from
a descriptor the bound plan resolved — so a parallel in-heap array would be redundant state whose only
distinctive behaviour is desynchronising when `pushCompensation` grows the stack by doubling.

**The stack is append-only for the instance's life.** There is no pop: the unwind walks the live prefix
downward without mutating it, so an entry stays live after its compensation has run. A rollback is
therefore a reverse read, not a drain.

**A per-step compensation failure does not stop the unwind.** `runCompensationStep` catches `Exception`
around the compensation action and emits `FlowStepFailedEvent` with
`staticReasonCode="COMPENSATION_FAILED"`, then continues to the next entry — cleanup must reach every
step that needs it, not only the ones before the first failure. `Error` still propagates.

**What is not caught, and why that is where the guards live.** The entry read and the `plan.stepAt`
lookup sit *outside* that catch, so an entry that does not index the plan throws out of the unwind
entirely and skips `finalizeFailedInstance` — the sole writer of `FAILED_ROLLEDBACK`, the idempotency
guard release, the terminal-catalog record and the terminal checkpoint. The saga is left mid-rollback
with its guard held, and only after some other failure has already put it there. This is why both stack
guards run on the **resume** path instead: refusing a resume leaves the row intact and recoverable,
which a truncated unwind does not. See the two compensation-stack rows in the redeployment matrix
above for the bounds and identity halves, and why the identity half is the quieter and more dangerous
one.

## Idempotency

Step-level deduplication during crash-recovery replay and choreography re-wakes is governed by `IdempotencyGuard` (SPI: `eu.exeris.kernel.spi.flow.IdempotencyGuard`).

- **ScopedValue binding:** `KernelProviders.IDEMPOTENCY_GUARD` (optional)
- **Fallback:** `CoreIdempotencyGuard` (heap-based CAS per `(instanceId, stepIndex)` tuple)
- **Lifecycle:** Per-instance guard entries are cleared on `releaseInstance()` at terminal state

Custom `IdempotencyGuard` implementations can be bound via the ScopedValue slot before calling `FlowEngine.submit()`.

## Events Integration

### Flow → Events (Progress Publication)

- When `KernelProviders.EVENT_ENGINE` is bound, Core may publish flow progress events to the Events SPI.
- This publication is best-effort and optional.
- If no event engine is bound, event registration is unavailable, or publishing fails, flow execution continues unchanged.
- The progress payload is intentionally small and currently includes the definition name, step index, and flow state.
- Only terminal state transitions (`COMPLETED`, `FAILED_ROLLEDBACK`) emit a progress event; intermediate states are skipped to avoid allocation on hot paths.

### Events → Flow (Choreography)

Flow can be driven by external events through the choreography bridge:

- **`FlowChoreographyMapper`** — `@FunctionalInterface` that maps an incoming event to a `ChoreographyDecision`
- **`ChoreographyDecision`** — sealed interface: `Ignore` (no action), `Wake` (wake a parked flow), `Start` (start a new flow instance)
- **`FlowEngine.registerChoreographyMapper(String eventType, FlowChoreographyMapper mapper)`** — registers the mapper
- **`choreographySupport`** capability flag in `FlowEngineCapabilities` must be `true`
- **`FlowChoreographyBridge`** (Core internal) connects the EventBus handler to `FlowScheduler`

---

## JFR Events

| Event | JFR Name | Emitted By | Key Fields |
|---|---|---|---|
| `FlowBootstrapSelectedEvent` | `eu.exeris.kernel.flow.BootstrapSelected` | `FlowBootstrap.loadWithProvider()` | `providerClass`, `priority`, `providerId`, `engineName` |
| `FlowStepFailedEvent` | `eu.exeris.kernel.flow.StepFailed` | `CoreFlowRuntime` on step exception | `definitionName`, `stepIndex`, `instanceIdMost`, `instanceIdLeast`, `failureReason` |
| `FlowEngineShutdownEvent` | `eu.exeris.kernel.flow.Shutdown` | `CoreFlowEngine.close()` after runtime close and bounded shutdown join; captures the stable shutdown counter view even if late workers finalize snapshots afterward | `engineName`, `activeFlows`, `parkedFlows`, `completedFlows`, `failedFlows`, `persistenceEnabled`, `compensationEnabled`, `nonDurableParkedFlows` (since 0.12), `shutdownDurationNs` (since 0.7). `nonDurableParkedFlows` counts parked instances whose PARK checkpoint the store refused, sampled before `close()` clears the parked index because that is the last moment the number exists; it is the one thing `parkedFlows` cannot tell an operator before a restart. Note the two are read at different points on purpose: `parkedFlows` is the stabilised post-close counter view and reads 0. |
| `FlowTimeoutEvent` (since 0.7) | `eu.exeris.kernel.flow.Timeout` | `CoreFlowRuntime.runStep()` when a flow's absolute deadline has passed; the engine then drives compensation and routes the instance to `FAILED_ROLLEDBACK` | `engineName`, `definitionName`, `instanceIdMost`, `instanceIdLeast`, `currentStep`, `overrunNanos` |
| `WakeOnLoadFallbackEvent` (since 0.7) | `eu.exeris.kernel.flow.WakeOnLoadFallback` | `CoreFlowRuntime` snapshot-store fallback path — emitted on every miss in the in-memory `parkedInstances`/`liveInstances` indices; the `restored` flag distinguishes a successful cross-engine restore from a stale wake event for an unknown instance (ADR-013 §8). Since 0.12 the emit sits at the store read itself rather than at one caller, so every path that consults the store reports it - `lookupParked`, the key-addressed `wake(long, long)`, and `wake(FlowContext)`, which resolves through the same `resolveParkedInstance` and previously emitted nothing. A refused wake emits `restored=false` before it throws | `engineName`, `instanceIdMost`, `instanceIdLeast`, `restored`, `loadDurationNanos` |
| `OptimisticLockConflictEvent` (since 0.7) | `eu.exeris.kernel.flow.OptimisticLockConflict` | `JdbcFlowSnapshotStore.save()` race-loser branches — `phase=UPDATE_STALE` for stale-`schemaVersion` UPDATE losers, `phase=INSERT_TOCTOU` for first-writer INSERT race losers remapped from integrity-constraint violations (ADR-013 §5/§8) | `engineName`, `phase`, `loadedSchemaVersion` |
| `FlowSchemaMismatchEvent` (since 0.10) | `eu.exeris.kernel.flow.SchemaMismatch` | Every fail-closed resume refusal, emitted single-phase immediately before the `EX-FLOW-7002 / phase=SCHEMA_MISMATCH` throw. No longer only the step-bounds check: since 0.11 the cursor-identity, definition-version, compensation-stack bounds and compensation-stack identity guards all emit it, discriminated by `reason` | `engineName`, `definitionName`, `instanceIdMost`, `instanceIdLeast`, `persistedStep`, `planStepCount`, `reason`, `persistedStepName`, `planStepName` |
| `FlowSnapshotSaveFailedEvent` (since v0.8 Sprint 5, JFR-091) | `eu.exeris.kernel.flow.FlowSnapshotSaveFailed` | `JdbcFlowSnapshotStore.save()` non-OCC `PersistenceProviderException` rollback path — the **non-OCC** sibling of `OptimisticLockConflictEvent`. OCC race losers continue to emit `OptimisticLockConflictEvent` and never overlap with this event. Public visibility — application code may install `RecordingStream` consumers (matches `OptimisticLockConflictEvent`). | `engineName`, `sqlState` (`SQLSTATE_UNKNOWN` sentinel when no `SQLException` in cause chain), `exceptionClass`, `exceptionMessage` |
| `FlowSnapshotPersistFailedEvent` (since 0.12) | `eu.exeris.kernel.flow.SnapshotPersistFailed` | `FlowSnapshotWriter.save()`, i.e. `CoreFlowRuntime.persistSnapshot`'s call site — fires for **any** store failure, whichever binding is installed. Complements `FlowSnapshotSaveFailedEvent` rather than duplicating it: that one is emitted from inside `JdbcFlowSnapshotStore.save`'s try-with-resources **body**, so a failure raised by the resource expression itself (`engine.openConnection()` — pool exhaustion, acquire timeout) escapes every catch there and emitted nothing. That is also the failure that leaves the instance running on a transition the store never accepted, since `applyParkOutcome` sets `PARKED` and registers the instance *before* persisting, and the exception then escapes `runInstance` uncaught. | `definitionName`, `state`, `stepIndex`, `instanceIdMost`, `instanceIdLeast`, `failureReason` |

---

## Known Constraints

### A flow is a linear chain, and the checkpoint is why

`FlowDefinition` carries an **ordered `List<FlowStepDescriptor>`** with distinct names, and
`FlowStepDescriptor` is `(stepId, name, action, compensation)`. There is no grouping, no fan-out, no
join. **Steps cannot run in parallel, and the constraint is deeper than the model that expresses
them:**

- **Resume is a single integer cursor.** `FlowSnapshot.currentStep` is one position, and
  `RuntimeFlowInstance` advances it by one. A parallel group has no single position — resuming one
  needs the *set* of steps already done, which is a different checkpoint, not a wider field.
- **Compensation is a stack of positions.** `compensationStack` is plan positions unwound in reverse
  order (with `compensationStepNames` beside it since ADR-064 A5). Compensating a fan-out is not a
  reverse-order pop: the branches have no order relative to each other, and a partial failure inside
  the group has no defined unwind.

So parallelism is not a missing field on `FlowDefinition`. It is a different durability contract,
and both halves of ADR-062/ADR-064's fail-closed resume are built on the linear one.

**This is what a downstream generator is up against.** `@SagaStep` in the SDK declares `parallel`,
`waitForAll` and `failFast`, with semantics the kernel has no model for — *"steps with the same
`order` can execute in parallel"*, *"cancel other parallel steps immediately"*. A generator compiling
those to anything other than a linear chain would be inventing a contract the runtime does not offer,
so **emitting the linear chain is the correct compilation**, and the unread attributes record a
kernel gap rather than a generator omission. Tracked in `docs/ROADMAP.md`.

### Terminal-State Catalog Retention

`CoreFlowRuntime` maintains a `terminalStateCatalog` map that records every flow that reaches a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). This map serves as an in-process idempotency fence — it prevents re-scheduling or re-waking already-terminal flows within a single runtime lifetime.

**Two operating modes (since 0.7.0)** selected by `FlowEngineConfig.terminalCatalogMaxSize`:

- **`maxSize == 0` — unbounded (default).** Backward-compatible with v0.6: entries accumulate from `start()` until `close()` and the map is cleared on `close()`. The durable snapshot is deleted on `complete()`.
- **`maxSize  > 0` — bounded LRU.** Backed by an access-order `LinkedHashMap`; the least-recently-accessed entry is evicted when the cap is reached. To preserve the idempotency fence under eviction, the engine **does not** delete the durable `FlowSnapshotStore` row on `complete()` in bounded mode — the persisted COMPLETED row is the cross-eviction proof of completion. Lifetime of those rows is governed by saga TTL retention (DIST-303); operators choosing bounded mode without persistence accept best-effort idempotency once an entry is evicted.

On a resubmit miss against the in-memory catalog the engine restores the snapshot through `FlowSnapshotStore.load()`, observes the terminal state, re-caches the entry into the catalog, and short-circuits the schedule. Subsequent resubmits then hit the in-memory fast path again.

**Correctness invariant (preserved in both modes):** an evicted entry MUST remain recoverable from `FlowSnapshotStore` so the engine can reject a resubmit. Bounded mode without persistence weakens this invariant by design — operators MUST pair it with `persistenceEnabled = true` for production runtimes.

### Saga Timeout Enforcement (since 0.7.0)

`FlowEngineConfig` exposes two informational defaults for callers to choose from when constructing a `FlowContext`:

- `DEFAULT_SAGA_TIMEOUT_SHORT_NANOS` — 30 s, transactional sagas.
- `DEFAULT_SAGA_TIMEOUT_LONG_NANOS` — 30 days, business-process sagas.

The actual deadline carried by the engine is the absolute `timeoutNanos` on the `FlowContext` (or, if zero, `plan.timeoutDurationNanos()` evaluated at `RuntimeFlowInstance.fromContext`). On every step iteration `CoreFlowRuntime.runStep()` checks the deadline; if it has passed, the engine emits `FlowTimeoutEvent` and drives the instance through the compensation path to `FAILED_ROLLEDBACK`. `Instant.MAX` (encoded as `Long.MAX_VALUE`) means "no timeout" and skips the check.

The check is binding-agnostic — both Community (heap) and Enterprise (off-heap) implementations are obliged via `AbstractFlowEngineTck.SagaTimeoutContract`.

### FlowEngine Restart Semantics (since 0.7.0)

`AbstractFlowEngineTck.RestartAwareSemantics` lifts three cross-restart obligations into the binding-agnostic TCK:

- **Counter reset.** `FlowEngineStats` returned by `engine.stats()` reflects the current lifecycle generation; `close()` followed by `start()` resets `activeFlows`, `completedFlows`, `failedFlows`, `parkedFlows`, `compensationsRun`, and `stepExecutions` to zero (PERF-064 baseline gating, not `LongAdder.reset()`).
- **Parked-reschedule no-op.** Scheduling a context whose `state()` is already `PARKED` MUST register the instance in the parked map without spawning step execution (`PARKED_SCHEDULE_NOOP` path).
- **Unknown-instance lookup.** `FlowScheduler.lookupParked(...)` for an instance that was never scheduled MUST return `Optional.empty()` without throwing, regardless of whether persistence is bound.

### Deferred Wake (since 0.11)

**A wake that cannot be scheduled yet MUST still be honoured.** `FlowScheduler.wake()` promises to re-submit the flow for execution and expressly permits an implementation to tolerate the immediate schedule → park → wake window rather than throwing. Tolerating that window is not the same as discarding the request: until 0.11 `CoreFlowRuntime` did neither, so a wake landing while a run still owned the instance was dropped with no exception, no event, and no resumption. `AbstractFlowSchedulerTck$BasicScheduling#wakeDuringRunIsNotLost` pins the obligation — it holds a step on a latch so the wake provably lands mid-run, then asserts the flow completes.

The consequence was invisible, which is what made it dangerous. A choreography wake is one event per business trigger, not a poll, so a dropped request strands the saga until some unrelated event happens to arrive. And because the instance stays discoverable through `lookupParked` (it remains in the live index), the miss-path `WakeOnLoadFallbackEvent` never fires — nothing marked the loss.

Core records the refused wake on the instance under the same monitor that guards its scheduled flag, and the draining run re-submits it after releasing its own bookkeeping. Two refusals stay deliberately silent because neither loses work: a **terminal** instance has already finished, and a **superseded lifecycle generation** means the engine restarted underneath the run — re-launching there would resurrect a reclaimed snapshot row rather than resume anything.

**Enterprise obligation.** The ring-buffer scheduler has the same window on its CAS-enqueue path (a wake enqueued while a consumer still owns the slot) and inherits this contract through the same TCK case.

### Cross-Restart Choreography Wake

For choreography-driven wake, the in-memory parked map remains the O(1) fast path during a live runtime.

When persistence is enabled, a restart-aware implementation may consult `FlowSnapshotStore` only after an in-memory miss to recover a `PARKED` `FlowContext` for wake. That fallback is a bounded miss-path rather than an unbounded repeated store probe: repeated unknown-flow misses are negatively suppressed in `CoreFlowRuntime` via a `parkedLookupMisses` set capped at 256 entries with FIFO eviction (`MAX_PARKED_LOOKUP_MISSES`). The cache is cleared on every successful lookup, on park/wake/complete transitions, on plan recompilation, and on engine restart. This bounds persistence cost under choreography polling without ever masking a genuine PARKED instance.

**A refused PARK checkpoint keeps the saga, not the claim (since 0.12).** `save()` throwing at park used to escape `runInstance` uncaught, killing the flow virtual thread while the instance had already been flipped to `PARKED` and registered - so it advertised a durability it did not have. Flipping the state only after a successful write is the obvious repair and is worse: the instance is wakeable in this JVM, so refusing to park it turns a transient store outage into a saga lost even without a restart. The engine therefore parks it, retries the write once (unspaced, because this runs under the instance monitor and the failure it most often meets is an exhausted connection pool, where waiting longer holds a thread against the contention that caused it), and past that marks the instance non-durable. Every attempt emits `FlowSnapshotPersistFailedEvent`, and the count surfaces as `nonDurableParkedFlows` on `FlowEngineShutdownEvent`. The mark clears on the next accepted write, whichever transition carries it. `Error` still propagates: an exhausted heap is not a checkpoint that can be retried.

**The choreographed wake is key-addressed (since 0.12).** `FlowScheduler.wake(long, long)` resolves the instance inside the engine, and `FlowChoreographyBridge` uses it instead of the `lookupParked(...).ifPresent(wake)` pair it used to. That pair was check-then-act and its two failure modes were not symmetric: an instance still inside the step about to `PARK` is reported absent by `lookupParked` (deliberately, since handing out a running instance as parked would let a second event schedule the same flow again), so the wake was dropped for good, while a genuinely unknown key paid a second durable-store probe on the way to the same refusal. Resolving by key removes both. `NOT_PARKED` is still absorbed by the bridge and now means only what it always should have: the instance is already running. The SPI method is a `default` that keeps the old two-call behaviour, so an implementation that does not override it is unchanged, including in its exposure to the race; `AbstractFlowSchedulerTck.keyAddressedWakeBeforeParkIsNotLost` is what an implementation must satisfy to claim otherwise.

This fallback does not change The Wall: Core continues to orchestrate through SPI contracts only, and persistence details remain hidden behind `FlowSnapshotStore`.

If persistence is disabled, cross-restart choreography wake remains unsupported by contract.

### Distributed Snapshot Store Contract (since 0.7.0)

Three additions land in 0.7 to support distributed saga state per ADR-013:

- **`FlowSnapshotStore.listParked()`** — returns every snapshot whose state is `PARKED`. The default returns `List.of()` (correct for in-memory stores that do not survive restart). Durable stores (`JdbcFlowSnapshotStore`) override to enumerate every parked row so the engine can resume choreography on the cross-restart fallback path. Cold path; pagination is not required for v0.7.
- **`FlowSnapshot.schemaVersion: long`** — monotonic optimistic-locking version. New snapshots use `FlowSnapshot.SCHEMA_VERSION_INITIAL` (`1L`); on every accepted save (INSERT or UPDATE) the durable store advances the on-disk version by exactly one. The runtime engine round-trips this version through `RuntimeFlowInstance.schemaVersion()` / `markPersisted()` so subsequent saves carry the up-to-date expected version. Stale-version writes are rejected with `EX-FLOW-7002` `phase=OPTIMISTIC_LOCK_CONFLICT` (`reasonCode=STALE_VERSION`, `contextVal=incomingSchemaVersion`).
- **`JdbcFlowSnapshotStore`** (Community) — durable JDBC implementation backed by the `exeris_saga_state` table (created via `db/migration/V0.7.0__create_saga_state.sql`). Since v0.8 Sprint 0b (ADR-022) the constructor takes a `PersistenceEngine` (not a raw `DataSource`); all connection acquisition flows through `engine.openConnection()` and all I/O uses the `PersistenceStatement` / `QueryResult` SPI surface. The save path is a portable two-step UPDATE-then-INSERT: an UPDATE with a CAS guard on `schema_version` is attempted first; on affected-rows = 0 the implementation distinguishes "row absent" (→ INSERT) from "row present with stale version" (→ raise `EX-FLOW-7002`). `compensation_stack` is packed into `BYTEA` (4 bytes per int, big-endian) for cross-database portability — H2 does not support native `INT[]`. `state` is stored as TEXT (`FlowState.name()`); `last_update` and `timeout_at` as `TIMESTAMPTZ` via the additive `PersistenceStatement.bindInstant` / `RowCursor.getInstant` SPI methods (ADR-022 §3); `Instant.MAX` is encoded as NULL and decoded back to `Instant.MAX` because it falls outside the TIMESTAMPTZ range (4713 BC..294276 AD).

In-memory bindings (`CommunityFlowSnapshotStore`, test stores) continue to ignore `schemaVersion`; the `markPersisted()` increment is harmless for them. Enterprise binding inherits the same SPI contract and TCK obligations on parity (`AbstractDistributedFlowSnapshotStoreTck`).

### HikariCP statement-cache requirement (DOC-090, v0.8 Sprint 5)

`JdbcFlowSnapshotStore.save` re-prepares two statements on every saga write — `SQL_UPDATE_OCC` (the OCC-guarded UPDATE) and `SQL_INSERT` (the first-writer INSERT on UPDATE → 0 rows). Without a driver-side prepared-statement cache the JDBC driver re-parses both per save and PostgreSQL never promotes them to server-side prepared form, so each accepted save pays full parse cost. The Community Hikari binding (`CommunityHikariSupport.applyDataSourceProperties`) sets these as **opt-out defaults**:

| Property | Default | Status |
|:--|:--|:--|
| `cachePrepStmts` | `true` | **HARD requirement** — do not disable. |
| `prepStmtCacheSize` | `250` | Recommended; covers OCC + outbox + RLS paths. Override smaller only for memory-constrained deployments. |
| `prepStmtCacheSqlLimit` | `2048` | Recommended per-statement SQL length cap. Override smaller only if the operator audited their longest SQL emitted by the binding. |

The same defaults apply to the outbox-orchestrator pump and the RLS-interceptor session-set path — both also re-prepare a small fixed statement set per request. Operators can override every value via `PersistenceConfig.properties()`; the Community binding's check-then-default pattern (matching how `ssl=true` and `defaultRowFetchSize=50` are applied) ensures user-supplied properties always win. The `JdbcFlowSnapshotStore` class-level Javadoc cross-references this section as the source of truth.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. The `rawArgs` binary layout is defined per constant Javadoc and must not diverge from this table.

| Code           | Meaning                  | Glass-Box Payload (`rawArgs`)                                                                                                                           |
|:---------------|:-------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `EX-FLOW-7001` | Provider Engine Failure  | `[0] String providerName, [1] String reason`                                                                                                            |
| `EX-FLOW-7002` | Engine Lifecycle Failure | `[0] String engineName, [1] String phase, [2] String reasonCode, [3] int contextVal` — `phase` values include `START`, `STOP`, `COMPILE`, `SCHEDULE`, `SCHEMA_MISMATCH`, `WAKE_FAILED`, `SUBMIT_REJECTED`, `OPTIMISTIC_LOCK_CONFLICT` (since 0.7) |
| `EX-FLOW-7003` | Step Execution Failure   | `[0] String definitionName, [1] long instanceIdMost, [2] long instanceIdLeast, [3] int stepIndex, [4] String staticReasonCode ("STEP_FAILED" \| "COMPENSATION_FAILED"), [5] String causeType` |
| `EX-FLOW-7004` | Registry Conflict        | `[0] int stepId, [1] String reason`                                                                                                                     |

> **Note — `EX-EVENT-6004` / `EX-FLOW-7001` Identical Schema:** These share the same `rawArgs` layout (`providerName`, `reason`) intentionally — they model the same class of failure in two distinct subsystem domains (Event Bus vs. Flow Engine). The duplication is deliberate; see `telemetry.md` for details.

---

## TCK Coverage

| TCK Suite | Module | Description |
|:---------|:-------|:------------|
| `AbstractFlowEngineTck` | `exeris-kernel-tck` | Full flow lifecycle: submit, run, park, wake, complete, compensate; JFR shutdown event (TCK-062), restart-aware semantics (TCK-063), saga timeout enforcement (DIST-303), per-outcome transition correctness incl. thrown-exception FAIL path + `FAILED_ROLLEDBACK` terminal idempotency (FLOW-110, `OutcomeTransitions`) |
| `AbstractFlowSchedulerTck` | `exeris-kernel-tck` | Scheduler contract: schedule, cancel, peek parked, drain |
| `AbstractFlowChoreographyTck` | `exeris-kernel-tck` | Choreography mapper registration and event-driven wake |
| `AbstractSagaRecoveryTck` | `exeris-kernel-tck` | Crash-recovery replay semantics from snapshot store; restart-under-load (N=16 concurrent parked instances survive force-close, resume, no re-exec/orphans, counter reset) (FLOW-110, `RestartUnderLoad`) |
| `AbstractIdempotencyGuardTck` | `exeris-kernel-tck` | Step-level deduplication contract for `IdempotencyGuard` |
| `AbstractFlowDefinitionVersioningTck` | `exeris-kernel-tck` | Definition versioning (since 0.11, ADR-064) — version coexistence and resume on the parked version (`Coexistence`); the four fail-closed refusals incl. the unhosted-definition non-refusal (`Refusals`); in-flight migration: single hop, chained hops, stop-at-first-hosted, missing link, throwing transform, unknown emitted step, out-of-range emitted compensation stack, duplicate registration, persistence before the resumed step runs (`Migration`); compensation-stack step identity (since 0.11, ADR-064 A5) — in-range entry addressing a different step, agreeing identities resuming, live stack with no identities, empty stack not treated as absent, transform emitting contradicted identities, transform refused before it runs on an unnamed stack, and identities the engine itself recorded round-tripping end to end (`StackIdentity`); the retained 0.10.0 `FlowSnapshot` constructor still failing closed (`StabilityCompatibility`); builder-declared versions (since 0.12, ADR-064 amendment) — the version reaching definition and compiled plan, and a sub-initial version refused at the call site that named it (`VersionThroughTheBuilder`) |
| `FlowZeroAllocTck` | `exeris-kernel-tck` | Zero-allocation assertion on hot flow scheduling path |
| `FlowCarrierPinningTck` | `exeris-kernel-tck` | Flow orchestration does not pin Virtual Thread carrier |
| `AbstractDistributedFlowSnapshotStoreTck` | `exeris-kernel-tck` | Durable snapshot store contract (since 0.7) — save/load round-trip, delete, listParked filter, cross-restart recovery, OCC stale-version conflict |

Community bindings: `CommunityFlowEngineTckTest`, `CommunityFlowSchedulerTckTest`, `CommunityFlowChoreographyTckTest`, `CommunitySagaRecoveryTckTest`, `CommunityFlowDefinitionVersioningTckTest`, `CommunityFlowCarrierPinningTckTest`, `CommunityJdbcFlowSnapshotStoreTckIT` (Postgres via Testcontainers) in `exeris-kernel-community`.

End-to-end cross-engine recovery (DIST-302 closure, since 0.7 Sprint 6c) is covered by `CommunityCrossEngineChoreographyIT` in `exeris-kernel-community-kafka`: two `FlowEngine`s share a `JdbcFlowSnapshotStore` and a Kafka broker. Service A schedules a saga that PARKs (snapshot persisted); Service A's `EventEngine` is then closed so it cannot consume the wake event. Service B publishes the wake event over Kafka, its `FlowChoreographyBridge` finds nothing in B's in-memory parked-instance index, falls back to the shared snapshot store, restores the saga, and completes it locally — proving the snapshot fallback path runs end-to-end against a real durable store with real broker delivery.

> **Gap:** `AbstractIdempotencyGuardTck` and `FlowZeroAllocTck` have no Community-tier concrete binding in `exeris-kernel-community/src/test/`. The `IdempotencyGuard` contract is covered only by unit-level tests; no community provider binding extends `AbstractIdempotencyGuardTck`. Tracking: see `docs/ROADMAP.md`.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.flow.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md). See the matrix for the semver policy and TCK
coverage status.
