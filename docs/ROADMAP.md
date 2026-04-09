---

## Known Gaps / Future Work

### Flow: Terminal-State Catalog Bounded Retention

`CoreFlowRuntime` maintains an in-process `terminalStateCatalog` (`ConcurrentHashMap<FlowKey, FlowState>`) as an idempotency fence that prevents re-scheduling or re-waking flows that have already reached a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). Entries accumulate for the entire runtime lifetime and are only cleared on `close()`.

**Current contract:** runtime-lifetime scoped — acceptable for the current operational model of bounded-duration runtime instances.

**Gap:** For long-running runtimes processing high flow throughput, unbounded accumulation may become a memory concern before a planned `close()`. A proper eviction policy (size-capped LRU, TTL window, or configurable max-size via `FlowEngineConfig`) has not yet been designed or specified.

**Owner:** Core / Flow subsystem.

**Resolution:** Requires a design decision in `docs/subsystems/flow.md` and likely a `FlowEngineConfig` config property before implementation. No correctness impact in current operational model.

See also: [Flow Subsystem](./subsystems/flow.md) — Known Constraints.