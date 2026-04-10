---

## Known Gaps / Future Work planned for v0.6

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
---

### Flow: Missing TCK Bindings — `IdempotencyGuard` and `FlowZeroAllocTck`

`AbstractIdempotencyGuardTck` and `FlowZeroAllocTck` exist in `exeris-kernel-tck` but have no Community-tier concrete binding in `exeris-kernel-community/src/test/`. The `IdempotencyGuard` contract (`eu.exeris.kernel.spi.flow.IdempotencyGuard`) is covered only by unit-level tests.

**Gap:** No `CommunityFlowIdempotencyGuardTckTest` extends `AbstractIdempotencyGuardTck`. No `CommunityFlowZeroAllocTckTest` extends `FlowZeroAllocTck`.

**Owner:** Core / Flow subsystem.

**Resolution:** Add `CommunityFlowIdempotencyGuardTckTest` in `exeris-kernel-community` passing `CoreIdempotencyGuard` (the default fallback) as the SUT. Add `CommunityFlowZeroAllocTckTest` asserting zero allocation on the scheduler hot path. Both tests must be CI-gated before the next Flow SPI minor bump.

See also: [Flow Subsystem](./subsystems/flow.md) — TCK Coverage.

---

### Flow: `FlowEngineShutdownEvent` Not Implemented

`FlowEngine.close()` does not emit a JFR event. The Javadoc references a `FlowEngineShutdownEvent` class that does not yet exist as an implementation. Only `FlowBootstrapSelectedEvent` exists (emitted by `FlowBootstrap.loadWithProvider()`).

**Gap:** No `FlowEngineShutdownEvent` JFR class; `close()` path has no JFR lifecycle telemetry.

**Owner:** Core / Flow subsystem.

**Resolution:** Create `FlowEngineShutdownEvent` (`eu.exeris.kernel.core.flow.jfr`), emit it at the start of `close()` with fields: `engineName`, `terminatedFlowCount`, `pendingFlowCount`. TCK: extend `AbstractFlowEngineTck` to assert the event is emitted on `close()` using `RecordingStream`.

See also: [Flow Subsystem](./subsystems/flow.md) — JFR Events.

---

### Security: `CitadelGuard` Lacks Abstract TCK Suite

`CitadelGuard` (Core: `eu.exeris.kernel.core.security.CitadelGuard`) implements a sentinel-pool RBAC enforcement gate with `preAllocate(String role)` / `seal()` / `requireRole(String role)`. There is no abstract `AbstractCitadelGuardTck` in `exeris-kernel-tck`.

**Gap:** Any alternative RBAC gate implementation cannot prove contract compliance through a shared TCK harness. Coverage relies entirely on Core unit tests.

**Owner:** Core / Security subsystem.

**Resolution:** Create `AbstractCitadelGuardTck` in `exeris-kernel-tck` covering: `preAllocate` before `seal`, rejection of unknown roles after `seal`, `requireRole` throws `SecurityException` on insufficient privilege, `EX-SEC-2003` emission. Bind in Community with `CommunityCitadelGuardTckTest`.

See also: [Security Subsystem](./subsystems/security.md) — Responsibilities.

---

### Security: `StorageContextBridge` Lacks Abstract TCK Suite

`StorageContextBridge.derive(PrincipalContext)` (Core: `eu.exeris.kernel.core.security.StorageContextBridge`) derives a SHARED-isolation `StorageContext` from a verified `PrincipalContext`. The derivation contract is tested only transitively via `AbstractSecurityInterceptorTck`.

**Gap:** No standalone `AbstractStorageContextBridgeTck` to verify SHARED derivation produces correct `tenantId`; SEPARATED_SCHEMA/DEDICATED strategies are rejected (delegated to `SecurityProvider`); null principal throws `EX-SEC-2001`.

**Owner:** Core / Security subsystem.

**Resolution:** Create `AbstractStorageContextBridgeTck` in `exeris-kernel-tck`. Bind in Community with `CommunityStorageContextBridgeTckTest`.

See also: [Security Subsystem](./subsystems/security.md) — Responsibilities.

---

### Security: `@RequiresRole` APT Processor Not Yet Implemented

`@RequiresRole` annotation and the compile-time `RoleCheckRegistry` APT processor are described in `security.md` as the target mechanism for zero-reflection RBAC. Neither the annotation type nor the APT processor exists in `exeris-kernel-build-config` or `exeris-kernel-spi`.

**Gap:** The planned bitmask-based O(1) role check cannot be used. `CitadelGuard.requireRole()` is the current runtime enforcement path.

**Owner:** Build / Security subsystem.

**Resolution:** Requires design + implementation of: `@RequiresRole` annotation in SPI, APT processor generating `RoleCheckRegistry` in `exeris-kernel-build-config`, runtime hook in `CitadelGuard` consuming the registry. ADR update may be required.

See also: [Security Subsystem](./subsystems/security.md) — `@RequiresRole` Processing.

---

### Telemetry: Core-Internal Async Dispatch Not Yet Implemented

`telemetry.md` describes a planned Core-internal dispatcher that fans out telemetry events to registered `TelemetrySink` instances off the caller's critical path while preserving the zero-allocation contract. The current reference implementation (`JfrTelemetrySink`) emits synchronously on the caller thread.

**Gap:** Under high event throughput, synchronous JFR emission adds latency to the hot path. The planned off-thread dispatch via pre-allocated routing structures does not exist in Core.

**Owner:** Core / Telemetry subsystem.

**Resolution:** Design a `TelemetryDispatcher` Core-internal class using a pre-allocated fixed-size ring buffer and a dedicated virtual-thread consumer. Must not allocate per-emission. TCK: extend `TelemetryZeroAllocTck` to assert synchronous and async dispatch paths are both allocation-free from the caller's perspective.

See also: [Telemetry Subsystem](./subsystems/telemetry.md) — Design Principles.

---

### Exceptions: Environment-Aware Disclosure (`BlackBoxSecurityMode`) Not Ported

`exceptions.md` documents environment-aware disclosure as a goal: PROD returns opaque error codes, DEV exposes full stack traces. Environment-aware disclosure (`GlassBoxSecurityMode`) has not been implemented in the active kernel modules (`exeris-kernel-core`).

**Gap:** The current implementation exposes the same level of detail regardless of environment. CWE-532 risk: stack traces or internal state may be surfaced to clients in production.

**Owner:** Core / Exceptions subsystem.

**Resolution:** Implement `GlassBoxSecurityMode` in `exeris-kernel-core`; wire it through `ErrorMapperRegistry.mapToTransportCode()` so that PROD mode strips stack trace from the transport-facing response. Config key: `exeris.kernel.error-disclosure` (`FULL` for DEV, `OPAQUE` for PROD, default: `OPAQUE`). TCK: `ExerisKernelExceptionGlassBoxTckTest` should be extended to assert PROD/DEV behaviour.

See also: [Exceptions Subsystem](./subsystems/exceptions.md) — Overview.

---

### Transport: `MpscArrayQueue` (Agrona) — Migration to Active Community Carrier

**Gap:** The Community transport carrier (`NativeTcpCarrier`) does not use a lock-free MPSC queue for cross-VT event handoff. The `MpscArrayQueue` from Agrona was used in earlier prototypes but is not wired into the active Community carrier. The absence of a lock-free queue on the carrier ingress path limits throughput under high concurrent stream arrival.

**Owner:** Core / Transport subsystem.

**Resolution:** Integrate `MpscArrayQueue` (or an equivalent lock-free MPSC queue) into `NativeTcpCarrier` for cross-VT event handoff on the ingress path. Validate with `TransportCarrierPinningTck` and load tests asserting zero `CarrierPinnedEvent` emissions under millions of concurrent stream events per second.

See also: [Transport Subsystem](./subsystems/transport.md) — Testing Strategy / Load Tests.
