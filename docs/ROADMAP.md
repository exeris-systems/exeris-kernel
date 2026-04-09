---

## Known Gaps / Future Work

### Flow: Terminal-State Catalog Bounded Retention

`CoreFlowRuntime` maintains an in-process `terminalStateCatalog` (`ConcurrentHashMap<FlowKey, FlowState>`) as an idempotency fence that prevents re-scheduling or re-waking flows that have already reached a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). Entries accumulate for the entire runtime lifetime and are only cleared on `close()`.

**Current contract:** runtime-lifetime scoped — acceptable for the current operational model of bounded-duration runtime instances.

**Gap:** For long-running runtimes processing high flow throughput, unbounded accumulation may become a memory concern before a planned `close()`. A proper eviction policy (size-capped LRU, TTL window, or configurable max-size via `FlowEngineConfig`) has not yet been designed or specified.

**Owner:** Core / Flow subsystem.

**Resolution:** Requires a design decision in `docs/subsystems/flow.md` and likely a `FlowEngineConfig` config property before implementation. No correctness impact in current operational model.

See also: [Flow Subsystem](./subsystems/flow.md) — Known Constraints.

---

### Flow: Cross-Restart Choreography via `lookupParked` Snapshot Consultation

`FlowScheduler.lookupParked(long, long)` currently resolves a parked flow context exclusively from the in-memory `parkedInstances` map. After a runtime restart, parked flows survive only as snapshots in `FlowSnapshotStore` — `lookupParked` will return `Optional.empty()` for them, so event-driven choreography cannot wake flows that were parked before the restart.

**Gap:** `lookupParked` does not consult `snapshotStore` as a fallback. Choreography-driven wake across restarts is therefore not supported.

**Owner:** Core / Flow subsystem.

**Resolution:** Extend `lookupParked` to load a snapshot when the in-memory map has no entry, filter for `FlowState.PARKED`, and return its `FlowContext`. Requires `persistenceEnabled` to be true to have any effect. The O(1) in-memory fast-path must be preserved; `snapshotStore` load is the fallback only when in-memory lookup misses. No SPI contract change is needed — this is a Core-internal improvement. A TCK test for cross-restart choreography wake should accompany the implementation.

See also: [Flow Subsystem](./subsystems/flow.md) — Known Constraints.