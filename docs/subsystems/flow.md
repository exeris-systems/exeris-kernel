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
- Enterprise-specific storage and advanced off-heap targets remain staged, not implemented in this repository state.

## Runtime Behavior

- Flow execution is step-based and stateful: `CREATED`, `RUNNING`, `PARKED`, `COMPENSATING`, `COMPLETED`, `FAILED_ROLLEDBACK`.
- Park/wake is supported through the Flow scheduler API.
- Compensation is supported when enabled in `FlowEngineConfig`.
- Snapshot persistence is optional and only used when a `FlowSnapshotStore` is bound and persistence is enabled.

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

## Optional Events Integration

- When `KernelProviders.EVENT_ENGINE` is bound, Core may publish flow progress events to the Events SPI.
- This publication is best-effort and optional.
- If no event engine is bound, event registration is unavailable, or publishing fails, flow execution continues unchanged.
- The progress payload is intentionally small and currently includes the definition name, step index, and flow state.
- Only terminal state transitions (`COMPLETED`, `FAILED_ROLLEDBACK`) emit a progress event; intermediate states are skipped to avoid allocation on hot paths.

---

## Known Constraints

### Terminal-State Catalog Retention

`CoreFlowRuntime` maintains a `terminalStateCatalog` map that records every flow that reaches a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). This map serves as an in-process idempotency fence — it prevents re-scheduling or re-waking already-terminal flows within a single runtime lifetime.

**Current behavior:** entries accumulate from `start()` until `close()`. The map is fully cleared on `close()`. There is no per-entry TTL, cap, or eviction policy.

**Implication:** For long-running runtimes processing very high flow throughput, `FlowKey` entries may accumulate in heap between `start()` and `close()`. This is a known gap. Implementing eviction requires a correctness-aware design — an overly aggressive policy could allow re-scheduling a completed flow.

**Future work:** a configurable `terminalCatalogMaxSize` (or TTL) property in `FlowEngineConfig`, governed by a policy decision documented here and in `docs/ROADMAP.md`. No ADR exists for this yet; one should be created before implementation.
