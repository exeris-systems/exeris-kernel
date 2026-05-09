# Kernel Subsystem: Flow / Sagas (L4 Orchestration)

- SPI: `eu.exeris.kernel.spi.flow.*`
- Core runtime: `eu.exeris.kernel.core.flow.*`
- Community binding: thin provider and in-memory snapshot store in `eu.exeris.kernel.community.flow.*`

**Layer:** L4 (Orchestration)
**Status:** Baseline runtime is implemented in Core in the current repository state.

## Current Repo Reality

- Core owns flow orchestration: definition compilation, scheduling, park/wake, compensation, and runtime state transitions.
- Community is a thin binding over the shared Core engine.
- Community snapshot persistence is currently heap-backed and in-memory via `CommunityFlowSnapshotStore`.

## Runtime Behavior

- Flow execution is step-based and stateful: `CREATED`, `RUNNING`, `PARKED`, `COMPENSATING`, `COMPLETED`, `FAILED_ROLLEDBACK`.
- Park/wake is supported through the Flow scheduler API.
- Compensation is supported when enabled in `FlowEngineConfig`.
- Snapshot persistence is optional and only used when a `FlowSnapshotStore` is bound and persistence is enabled.

> **Note:** `FlowOutcome.COMPLETE` provides a direct short-circuit path: a step can return `COMPLETE` to transition the flow immediately to `FlowState.COMPLETED` without executing any remaining steps.
> **Note:** `FlowBootstrapSelectedEvent` is emitted by `FlowBootstrap.loadWithProvider()` before `start()`. `FlowEngine.close()` emits `FlowEngineShutdownEvent` after runtime close and its bounded shutdown join, so the JFR payload reflects the stable shutdown counter view captured when `close()` completes. Promptly interrupted workers may still finalize snapshots afterward without changing that counter snapshot.

## Boundaries

- SPI remains implementation-blind.
- Core owns orchestration and runtime behavior.
- Community stays thin: provider wiring plus the current in-memory snapshot store.

| Scenario                                           | Kernel Behaviour                                                                                         |
|:---------------------------------------------------|:---------------------------------------------------------------------------------------------------------|
| **New deployment adds a step** to a Saga           | Existing in-flight Sagas (persisted in `exeris_saga_state`) continue on the **old definition**. New Sagas use the new definition. The `FlowRegistry` stores the definition snapshot at submission time. |
| **New deployment removes a step**                  | If an in-flight Saga was parked on the removed step: on wake, the engine detects the missing step via the persisted `stepIdx`. `EX-FLOW-7002` with `phase="SCHEMA_MISMATCH"` is thrown and manual intervention is required. |
| **New deployment reorders steps**                  | Treated as removal + addition — highest risk scenario. Avoid during active Saga execution. Use blue/green deployment with Saga drain before switching. |
| **Safe migration pattern (current)**               | Perform blue/green deployment with Saga drain before switching traffic. Avoid changing step order while Sagas are in-flight. The engine currently maintains a single active definition per Saga type; fine-grained, version-aware routing is **planned** but not yet available in the public Flow SPI/Core. |

> **Planned feature:** Future Flow engine iterations may introduce explicit Saga definition versioning
> (for example, via annotations and version-aware routing in the Saga registry/engine) to allow multiple
> definition versions to coexist until all old instances complete. This capability is *not implemented*
> in the current codebase and MUST NOT be relied upon until the corresponding SPI/Core APIs exist.

---

## Compensation Failure Handling

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
| `FlowEngineShutdownEvent` | `eu.exeris.kernel.flow.Shutdown` | `CoreFlowEngine.close()` after runtime close and bounded shutdown join; captures the stable shutdown counter view even if late workers finalize snapshots afterward | `engineName`, `activeFlows`, `parkedFlows`, `completedFlows`, `failedFlows`, `persistenceEnabled`, `compensationEnabled`, `shutdownDurationNs` (since 0.7) |
| `FlowTimeoutEvent` (since 0.7) | `eu.exeris.kernel.flow.Timeout` | `CoreFlowRuntime.runStep()` when a flow's absolute deadline has passed; the engine then drives compensation and routes the instance to `FAILED_ROLLEDBACK` | `engineName`, `definitionName`, `instanceIdMost`, `instanceIdLeast`, `currentStep`, `overrunNanos` |
| `WakeOnLoadFallbackEvent` (since 0.7) | `eu.exeris.kernel.flow.WakeOnLoadFallback` | `CoreFlowRuntime` `lookupParked` snapshot-store fallback path — emitted on every miss in the in-memory `parkedInstances`/`liveInstances` indices; the `restored` flag distinguishes a successful cross-engine restore from a stale wake event for an unknown instance (ADR-013 §8) | `engineName`, `instanceIdMost`, `instanceIdLeast`, `restored`, `loadDurationNanos` |
| `OptimisticLockConflictEvent` (since 0.7) | `eu.exeris.kernel.flow.OptimisticLockConflict` | `JdbcFlowSnapshotStore.save()` race-loser branches — `phase=UPDATE_STALE` for stale-`schemaVersion` UPDATE losers, `phase=INSERT_TOCTOU` for first-writer INSERT race losers remapped from integrity-constraint violations (ADR-013 §5/§8) | `engineName`, `phase`, `loadedSchemaVersion` |

---

## Known Constraints

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

### Cross-Restart Choreography Wake

For choreography-driven wake, the in-memory parked map remains the O(1) fast path during a live runtime.

When persistence is enabled, a restart-aware implementation may consult `FlowSnapshotStore` only after an in-memory miss to recover a `PARKED` `FlowContext` for wake. That fallback is a bounded miss-path rather than an unbounded repeated store probe: repeated unknown-flow misses are negatively suppressed in `CoreFlowRuntime` via a `parkedLookupMisses` set capped at 256 entries with FIFO eviction (`MAX_PARKED_LOOKUP_MISSES`). The cache is cleared on every successful lookup, on park/wake/complete transitions, on plan recompilation, and on engine restart. This bounds persistence cost under choreography polling without ever masking a genuine PARKED instance.

This fallback does not change The Wall: Core continues to orchestrate through SPI contracts only, and persistence details remain hidden behind `FlowSnapshotStore`.

If persistence is disabled, cross-restart choreography wake remains unsupported by contract.

### Distributed Snapshot Store Contract (since 0.7.0)

Three additions land in 0.7 to support distributed saga state per ADR-013:

- **`FlowSnapshotStore.listParked()`** — returns every snapshot whose state is `PARKED`. The default returns `List.of()` (correct for in-memory stores that do not survive restart). Durable stores (`JdbcFlowSnapshotStore`) override to enumerate every parked row so the engine can resume choreography on the cross-restart fallback path. Cold path; pagination is not required for v0.7.
- **`FlowSnapshot.schemaVersion: long`** — monotonic optimistic-locking version. New snapshots use `FlowSnapshot.SCHEMA_VERSION_INITIAL` (`1L`); on every accepted save (INSERT or UPDATE) the durable store advances the on-disk version by exactly one. The runtime engine round-trips this version through `RuntimeFlowInstance.schemaVersion()` / `markPersisted()` so subsequent saves carry the up-to-date expected version. Stale-version writes are rejected with `EX-FLOW-7002` `phase=OPTIMISTIC_LOCK_CONFLICT` (`reasonCode=STALE_VERSION`, `contextVal=incomingSchemaVersion`).
- **`JdbcFlowSnapshotStore`** (Community) — durable JDBC implementation backed by the `exeris_saga_state` table (created via `db/migration/V0.7.0__create_saga_state.sql`). Constructor-injected `DataSource` (HikariCP in Community); raw JDBC, no `PersistenceEngine` dependency. The save path is a portable two-step UPDATE-then-INSERT: an UPDATE with a CAS guard on `schema_version` is attempted first; on affected-rows = 0 the implementation distinguishes "row absent" (→ INSERT) from "row present with stale version" (→ raise `EX-FLOW-7002`). `compensation_stack` is packed into `BYTEA` (4 bytes per int, big-endian) for cross-database portability — H2 does not support native `INT[]`. `state` is stored as TEXT (`FlowState.name()`); `last_update` and `timeout_at` as `TIMESTAMPTZ`; `Instant.MAX` is encoded as NULL and decoded back to `Instant.MAX` because it falls outside the TIMESTAMPTZ range (4713 BC..294276 AD).

In-memory bindings (`CommunityFlowSnapshotStore`, test stores) continue to ignore `schemaVersion`; the `markPersisted()` increment is harmless for them. Enterprise binding inherits the same SPI contract and TCK obligations on parity (`AbstractDistributedFlowSnapshotStoreTck`).

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
| `AbstractFlowEngineTck` | `exeris-kernel-tck` | Full flow lifecycle: submit, run, park, wake, complete, compensate; JFR shutdown event (TCK-062), restart-aware semantics (TCK-063), saga timeout enforcement (DIST-303) |
| `AbstractFlowSchedulerTck` | `exeris-kernel-tck` | Scheduler contract: schedule, cancel, peek parked, drain |
| `AbstractFlowChoreographyTck` | `exeris-kernel-tck` | Choreography mapper registration and event-driven wake |
| `AbstractSagaRecoveryTck` | `exeris-kernel-tck` | Crash-recovery replay semantics from snapshot store |
| `AbstractIdempotencyGuardTck` | `exeris-kernel-tck` | Step-level deduplication contract for `IdempotencyGuard` |
| `FlowZeroAllocTck` | `exeris-kernel-tck` | Zero-allocation assertion on hot flow scheduling path |
| `FlowCarrierPinningTck` | `exeris-kernel-tck` | Flow orchestration does not pin Virtual Thread carrier |
| `AbstractDistributedFlowSnapshotStoreTck` | `exeris-kernel-tck` | Durable snapshot store contract (since 0.7) — save/load round-trip, delete, listParked filter, cross-restart recovery, OCC stale-version conflict |

Community bindings: `CommunityFlowEngineTckTest`, `CommunityFlowSchedulerTckTest`, `CommunityFlowChoreographyTckTest`, `CommunitySagaRecoveryTckTest`, `CommunityFlowCarrierPinningTckTest`, `CommunityJdbcFlowSnapshotStoreTckIT` (Postgres via Testcontainers) in `exeris-kernel-community`.

End-to-end cross-engine recovery (DIST-302 closure, since 0.7 Sprint 6c) is covered by `CommunityCrossEngineChoreographyIT` in `exeris-kernel-community-kafka`: two `FlowEngine`s share a `JdbcFlowSnapshotStore` and a Kafka broker. Service A schedules a saga that PARKs (snapshot persisted); Service A's `EventEngine` is then closed so it cannot consume the wake event. Service B publishes the wake event over Kafka, its `FlowChoreographyBridge` finds nothing in B's in-memory parked-instance index, falls back to the shared snapshot store, restores the saga, and completes it locally — proving the snapshot fallback path runs end-to-end against a real durable store with real broker delivery.

> **Gap:** `AbstractIdempotencyGuardTck` and `FlowZeroAllocTck` have no Community-tier concrete binding in `exeris-kernel-community/src/test/`. The `IdempotencyGuard` contract is covered only by unit-level tests; no community provider binding extends `AbstractIdempotencyGuardTck`. Tracking: see `docs/ROADMAP.md`.
