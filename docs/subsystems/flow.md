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
> **Note:** `FlowBootstrapSelectedEvent` is emitted by `FlowBootstrap.loadWithProvider()` before `start()`. `FlowEngine.close()` emits `FlowEngineShutdownEvent` after its bounded shutdown join and snapshot finalization, so the JFR payload reflects the stable counter view captured when `close()` completes. Promptly interrupted workers may still contribute before that snapshot is finalized.

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
| `FlowEngineShutdownEvent` | `eu.exeris.kernel.flow.Shutdown` | `CoreFlowEngine.close()` after runtime close and shutdown snapshot finalization | `engineName`, `activeFlows`, `parkedFlows`, `completedFlows`, `failedFlows`, `persistenceEnabled`, `compensationEnabled` |

---

## Known Constraints

### Terminal-State Catalog Retention

`CoreFlowRuntime` maintains a `terminalStateCatalog` map that records every flow that reaches a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). This map serves as an in-process idempotency fence — it prevents re-scheduling or re-waking already-terminal flows within a single runtime lifetime.

**Sprint 1 contract lock:** entries still accumulate from `start()` until `close()`, and the map is still cleared on `close()`. Any future bounded retention, cap, or eviction policy MUST preserve this fence and MUST NOT allow re-execution or re-wake of a flow that has already reached a terminal state.

**Operational stance:** the current runtime-lifetime scope remains acceptable for the present operational model. If a bounded policy is introduced later, it must be correctness-first and remain implementation-blind at the SPI boundary.

### Cross-Restart Choreography Wake

For choreography-driven wake, the in-memory parked map remains the O(1) fast path during a live runtime.

When persistence is enabled, a restart-aware implementation may consult `FlowSnapshotStore` only after an in-memory miss to recover a `PARKED` `FlowContext` for wake. That fallback is a bounded miss-path rather than an unbounded repeated store probe: repeated unknown-flow misses should be negatively suppressed in Core so persistence cost does not amplify under choreography polling. This fallback does not change The Wall: Core continues to orchestrate through SPI contracts only, and persistence details remain hidden behind `FlowSnapshotStore`.

If persistence is disabled, cross-restart choreography wake remains unsupported by contract.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. The `rawArgs` binary layout is defined per constant Javadoc and must not diverge from this table.

| Code           | Meaning                  | Glass-Box Payload (`rawArgs`)                                                                                                                           |
|:---------------|:-------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `EX-FLOW-7001` | Provider Engine Failure  | `[0] String providerName, [1] String reason`                                                                                                            |
| `EX-FLOW-7002` | Engine Lifecycle Failure | `[0] String engineName, [1] String phase, [2] String reasonCode, [3] int contextVal` — `phase` values include `SCHEMA_MISMATCH`, `WAKE_FAILED`, `SUBMIT_REJECTED` |
| `EX-FLOW-7003` | Step Execution Failure   | `[0] String definitionName, [1] long instanceIdMost, [2] long instanceIdLeast, [3] int stepIndex, [4] String staticReasonCode ("STEP_FAILED" \| "COMPENSATION_FAILED"), [5] String causeType` |
| `EX-FLOW-7004` | Registry Conflict        | `[0] int stepId, [1] String reason`                                                                                                                     |

> **Note — `EX-EVENT-6004` / `EX-FLOW-7001` Identical Schema:** These share the same `rawArgs` layout (`providerName`, `reason`) intentionally — they model the same class of failure in two distinct subsystem domains (Event Bus vs. Flow Engine). The duplication is deliberate; see `telemetry.md` for details.

---

## TCK Coverage

| TCK Suite | Module | Description |
|:---------|:-------|:------------|
| `AbstractFlowEngineTck` | `exeris-kernel-tck` | Full flow lifecycle: submit, run, park, wake, complete, compensate |
| `AbstractFlowSchedulerTck` | `exeris-kernel-tck` | Scheduler contract: schedule, cancel, peek parked, drain |
| `AbstractFlowChoreographyTck` | `exeris-kernel-tck` | Choreography mapper registration and event-driven wake |
| `AbstractSagaRecoveryTck` | `exeris-kernel-tck` | Crash-recovery replay semantics from snapshot store |
| `AbstractIdempotencyGuardTck` | `exeris-kernel-tck` | Step-level deduplication contract for `IdempotencyGuard` |
| `FlowZeroAllocTck` | `exeris-kernel-tck` | Zero-allocation assertion on hot flow scheduling path |
| `FlowCarrierPinningTck` | `exeris-kernel-tck` | Flow orchestration does not pin Virtual Thread carrier |

Community bindings: `CommunityFlowEngineTckTest`, `CommunityFlowSchedulerTckTest`, `CommunityFlowChoreographyTckTest`, `CommunitySagaRecoveryTckTest`, `CommunityFlowCarrierPinningTckTest` in `exeris-kernel-community`.

> **Gap:** `AbstractIdempotencyGuardTck` and `FlowZeroAllocTck` have no Community-tier concrete binding in `exeris-kernel-community/src/test/`. The `IdempotencyGuard` contract is covered only by unit-level tests; no community provider binding extends `AbstractIdempotencyGuardTck`. Tracking: see `docs/ROADMAP.md`.
