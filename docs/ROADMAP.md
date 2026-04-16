# Exeris Kernel Open-Core Roadmap

> This roadmap applies only to the **open-core / foundation / community** scope of Exeris Kernel.
> Benchmarking strategy, perf-box planning, benchmark semantics, and release benchmark gates are tracked separately.

---

## Scope of This Roadmap

### In scope
- **Foundation:** `memory`
- **Core runtime:** `bootstrap`, `config`, `transport`, `http`, `security`, `persistence`, `telemetry`
- **Product-critical subsystem:** `flow`
- **Product-important subsystems:** `events`, `graph`
- **Cross-cutting work:** refactoring, static analysis, CI hardening, documentation truthfulness, open-core / enterprise separation

### Explicitly out of scope
- Proprietary / Enterprise runtime work
- `io_uring`, QUIC/HTTP3, NUMA-aware slab management, native DB driver, QPACK
- strict Enterprise-only zero-allocation / zero-copy guarantees
- benchmark implementation repository itself

### Community Contract Stance
Community tier follows a **best-effort performance contract**:

- explicit ownership and bounded resource use by design,
- zero-copy / zero-allocation where practical and measurable,
- correctness, portability and maintainability take precedence over strict purity,
- NIO-backed implementations are acceptable in Community where they preserve clarity and cross-platform behavior.

### Milestone Gate Policy
- **Hard gate:** required to close a milestone.
- **Best-effort:** planned optimization/hardening; does not block a correctness release if explicitly deferred.
- Any SPI-observable behavior extension requires Abstract*Tck coverage and Community binding before an SPI minor release.
- Enterprise bindings are out-of-repo, but remain contractual obligations for every new abstract TCK suite.

---

## Known Gaps / Future Work planned for v0.6

### Flow: Terminal-State Catalog Bounded Retention

`CoreFlowRuntime` maintains an in-process `terminalStateCatalog` (`ConcurrentHashMap<FlowKey, FlowState>`) as an idempotency fence that prevents re-scheduling or re-waking flows that have already reached a terminal state (`COMPLETED`, `FAILED_ROLLEDBACK`). Entries accumulate for the entire runtime lifetime and are only cleared on `close()`.

**Current contract:** runtime-lifetime scoped — acceptable for the current operational model of bounded-duration runtime instances.

**Gap:** For long-running runtimes processing high flow throughput, unbounded accumulation may become a memory concern before a planned `close()`. A proper bounded-retention policy is not yet designed.

**Owner:** Core / Flow subsystem.

**Resolution:** Add a design decision in `docs/subsystems/flow.md` and introduce a `FlowEngineConfig` limit for terminal catalog retention. Preserve correctness guarantees — retention policy must not re-enable execution of already terminal flows.

**Merge Gate:** Architecture sign-off on retention semantics in subsystem docs; if SPI/config surface changes, extend Flow TCK before release.

See also: [Flow Subsystem](./subsystems/flow.md) — Known Constraints.

---

### Flow: Cross-Restart Choreography via `lookupParked` Snapshot Consultation

`FlowScheduler.lookupParked(long, long)` currently resolves a parked flow context exclusively from the in-memory `parkedInstances` map. After a runtime restart, parked flows survive only as snapshots in `FlowSnapshotStore`; `lookupParked` returns `Optional.empty()` for them, so event-driven choreography cannot wake flows that were parked before restart.

**Gap:** `lookupParked` does not consult `snapshotStore` as a fallback. Cross-restart choreography wake is therefore not supported.

**Owner:** Core / Flow subsystem.

**Resolution:** Extend `lookupParked` to load a snapshot when the in-memory map has no entry, filter for `FlowState.PARKED`, and return its `FlowContext`. Keep the O(1) in-memory fast path; `snapshotStore` load is fallback-only on miss. Document/configure the persistence enablement flag in config docs.

**Merge Gate:** Add restart-aware choreography coverage in Flow TCK (via `AbstractFlowChoreographyTck` and/or `AbstractSagaRecoveryTck`) and require Community binding pass in CI.

See also: [Flow Subsystem](./subsystems/flow.md) — Known Constraints.

---

### Flow: Missing TCK Bindings — `IdempotencyGuard` and `FlowZeroAllocTck`

`AbstractIdempotencyGuardTck` and `FlowZeroAllocTck` exist in `exeris-kernel-tck` but have no Community-tier concrete binding in `exeris-kernel-community/src/test/`. The `IdempotencyGuard` contract (`eu.exeris.kernel.spi.flow.IdempotencyGuard`) is covered in Core but not bound in Community.

**Gap:** No `CommunityFlowIdempotencyGuardTckTest` extends `AbstractIdempotencyGuardTck`. No `CommunityFlowZeroAllocTckTest` extends `FlowZeroAllocTck`.

**Owner:** Core / Flow subsystem.

**Resolution:** Add `CommunityFlowIdempotencyGuardTckTest` in `exeris-kernel-community` passing `CoreIdempotencyGuard` as the SUT. Add `CommunityFlowZeroAllocTckTest` asserting low-allocation / zero-allocation behavior on the scheduler hot path for Community tier.

**Merge Gate:** Both Community bindings must pass in CI before the next Flow SPI minor bump. Enterprise binding obligation applies for out-of-repo implementations.

See also: [Flow Subsystem](./subsystems/flow.md) — TCK Coverage.

---

### Flow: `FlowEngineShutdownEvent` Not Implemented

`FlowEngine.close()` does not emit a JFR event. The SPI Javadoc references a `FlowEngineShutdownEvent` class that does not yet exist as an implementation.

**Gap:** No `FlowEngineShutdownEvent` JFR class; `close()` path has no Flow lifecycle shutdown telemetry.

**Owner:** Core / Flow subsystem.

**Resolution:** Create `FlowEngineShutdownEvent` in `eu.exeris.kernel.core.flow.jfr`, emit it at the start/end of `close()`, and include operationally useful fields (`engineName`, `parkedFlowCount`, `interruptedFlowCount`, `shutdownDurationNs`).

**Merge Gate:** Extend `AbstractFlowEngineTck` with `RecordingStream` assertion for shutdown event emission; require Community binding pass.

See also: [Flow Subsystem](./subsystems/flow.md) — JFR Events.

---

### Security: `CitadelGuard` Abstract TCK Suite Added

`CitadelGuard` (Core: `eu.exeris.kernel.core.security.CitadelGuard`) now has a shared `AbstractCitadelGuardTck` in `exeris-kernel-tck` plus Community and Core bindings.

**Status:** Contract closure is in place for v0.6; alternative RBAC gate implementations can now prove compliance through the shared harness.

**Owner:** Core / Security subsystem.

**Resolution:** Create `AbstractCitadelGuardTck` in `exeris-kernel-tck` covering: `preAllocate` before `seal`, rejection of unknown roles after `seal`, `requireRole` throws `SecurityException` on insufficient privilege, `EX-SEC-2003` emission, and steady-state allocation discipline on `requireRole`.

**Merge Gate:** Add `CommunityCitadelGuardTckTest` binding and enforce pass in CI. Enterprise binding obligation applies for out-of-repo implementations.

See also: [Security Subsystem](./subsystems/security.md) — Responsibilities.

---

### Security: `StorageContextBridge` Abstract TCK Suite Added

`StorageContextBridge.derive(PrincipalContext)` (Core: `eu.exeris.kernel.core.security.StorageContextBridge`) now has a standalone `AbstractStorageContextBridgeTck` in `exeris-kernel-tck`, with Community and Core bindings.

**Status:** SHARED derivation and fail-closed null-principal handling are now covered directly for v0.6.

**Owner:** Core / Security subsystem.

**Resolution:** Create `AbstractStorageContextBridgeTck` in `exeris-kernel-tck`, with derivation correctness and low-waste checks on the hot path. Bind in Community with `CommunityStorageContextBridgeTckTest`.

**Merge Gate:** Community binding must pass in CI. Enterprise binding obligation applies for out-of-repo implementations.

See also: [Security Subsystem](./subsystems/security.md) — Responsibilities.

---

### Transport: `MpscArrayQueue` (Agrona) — Migration to Active Community Carrier

**Gap:** The Community transport carrier (`NativeTcpCarrier`) does not yet use a lock-free MPSC queue for cross-VT event handoff in the active ingress path. The absence of an efficient queue on carrier ingress handoff limits throughput and increases pinning risk under high concurrent stream arrival.

**Owner:** Core / Transport subsystem.

**Resolution:** Integrate `MpscArrayQueue` (or equivalent lock-free MPSC queue) into `NativeTcpCarrier` for cross-VT ingress handoff. If Agrona is adopted, keep it as a Community-internal detail and document dependency rationale.

**Merge Gate:** `TransportCarrierPinningTck` must pass with no pinning regressions; add community-scale sustained ingress load validation in CI/perf pipeline.

See also: [Transport Subsystem](./subsystems/transport.md) — Testing Strategy / Load Tests.

---

### Transport: Community Socket Path Migration to Core FFM Syscalls

**Gap:** The active Community transport carrier still uses Java NIO socket primitives (`ServerSocketChannel`, `SocketChannel`, `Selector`) for bind / accept / connect / readiness management. Core already ships cross-platform POSIX / Winsock symbol loading in `CoreSyscallLoader`, but Community does not yet consume that socket path.

**Owner:** Core / Transport subsystem.

**Resolution:** Migrate the Community carrier incrementally from primary NIO socket lifecycle management to the Core-provided FFM syscall path backed by `CoreSyscallLoader`. Preserve SPI blindness, PAQS semantics, and current TLS FD-owner binding while moving socket bootstrap and readiness operations onto the shared POSIX / Winsock layer. Java NIO remains the explicit portability / compatibility fallback when FFM socket bootstrap is unavailable, unsupported, or temporarily disabled.

**Merge Gate:** Add Community integration and TCK coverage proving boot, bind, connect, ingress, and load-shed behavior through the FFM-backed socket path. Linux validation is mandatory; Windows-capable CI coverage is required for Winsock compatibility before milestone close.

See also: [Transport Subsystem](./subsystems/transport.md) — Responsibilities / Zero-Copy Ingress.

---

### Quality: PMD Suppression Reduction and Structural Refactoring

**Gap:** A number of `@SuppressWarnings` / `//NOPMD` usages are justified by boundary-heavy or performance-sensitive code, but some still hide maintainability debt that could be removed through structural refactoring.

**Owner:** Cross-cutting / Core / Community.

**Resolution:** Audit all suppressions and classify them as:
- architectural justification,
- temporary debt,
- removable by refactor.

Reduce suppressions where possible without violating the Community best-effort performance contract. Any suppression retained for 1.0 should carry explicit rationale.

**Merge Gate:** Suppression audit completed; retained suppressions carry rationale.

---

### Quality: SonarQube Baseline and Quality Gate

**Gap:** The repository does not yet have a full maintainability / duplication / new-code quality gate beyond current static analysis and tests.

**Owner:** Build / Cross-cutting.

**Resolution:** Introduce SonarQube for open-core modules, establish a baseline debt profile, and gate new critical/blocker findings and new-code regressions before 1.0.

**Merge Gate:** SonarQube baseline established and enforced on new code.

---

### Product Boundary: Open-Core / Enterprise Separation Audit

**Gap:** Some documentation and historical backlog language still refers to proprietary Enterprise target-state in ways that blur the open-core/community roadmap.

**Owner:** Architecture / Docs.

**Resolution:** Audit docs and roadmap entries and classify each feature as:
- open-core supported,
- open-core planned,
- enterprise-only,
- out-of-repo.

Remove enterprise milestones from the open-core release path and keep out-of-repo obligations explicitly tagged.

**Merge Gate:** Docs audit complete; roadmap terminology consistently scoped to open-core.

---

## Known Gaps / Future Work planned for v0.7

### Bootstrap / HTTP: Embedded Health Endpoint for Community Runtime

**Gap:** Community runtime still lacks a clearly defined, first-class embedded health endpoint model for production-style readiness/liveness integration.

**Owner:** Core / Bootstrap / HTTP.

**Resolution:** Provide an embedded health endpoint for Community runtime with explicit readiness/liveness semantics and correct interaction with bootstrap and degraded runtime state.

**Merge Gate:** Health endpoint behavior covered by HTTP/bootstrap tests and documented operational contract.

---

### Security: `@RequiresRole` APT Processor Not Yet Implemented

`@RequiresRole` annotation and the compile-time `RoleCheckRegistry` APT processor are described in `security.md` as the target mechanism for zero-reflection RBAC. Neither the annotation type nor the APT processor exists in `exeris-kernel-build-config` or `exeris-kernel-spi`.

**Gap:** The planned bitmask-based O(1) role check cannot yet be used. `CitadelGuard.requireRole()` remains the primary runtime enforcement path.

**Owner:** Build / Security subsystem.

**Resolution:** Implement `@RequiresRole` in SPI, add an APT processor in build config that generates `RoleCheckRegistry`, and integrate the generated registry into runtime RBAC enforcement.

**Merge Gate:** ADR update is required before implementation begins; TCK coverage path for generated role checks defined and CI-gated.

See also: [Security Subsystem](./subsystems/security.md) — `@RequiresRole` Processing.

---

### Telemetry: Core-Internal Async Dispatch Not Yet Implemented

`telemetry.md` describes a planned Core-internal dispatcher that fans out telemetry events to registered `TelemetrySink` instances off the caller's critical path while preserving low overhead. The current reference implementation (`JfrTelemetrySink`) emits synchronously on the caller thread.

**Gap:** Under high event throughput, synchronous JFR emission adds latency to the hot path. No bounded-overhead async dispatch path exists yet in open-core runtime.

**Owner:** Core / Telemetry subsystem.

**Resolution:** Design and implement a Core-internal dispatcher using a pre-allocated queue/ring structure and dedicated consumer with explicit capacity/drop policy. Community target is bounded overhead and operational predictability.

**Merge Gate:** Validate async path with both `AbstractTelemetryRingBufferTck` (dispatch/ring contract) and `TelemetryZeroAllocTck` (caller-path allocation discipline). Keep synchronous path as supported baseline until async gate passes.

See also: [Telemetry Subsystem](./subsystems/telemetry.md) — Design Principles.

---

### Runtime: Hot-Path Collections Review

**Gap:** Some Community runtime hot paths may justify a targeted collections review, but replacement structures must preserve current contracts and lookup semantics.

**Owner:** Core / Flow / Transport subsystem.

**Resolution:** Evaluate specialized runtime collections only where profiling shows real contention. Identity-based maps such as JCTools `NonBlockingIdentityHashMap` are not a fit for Flow registries/catalogs because those paths use value-semantic keys for restart, wake, and idempotency lookups; queue-oriented JCTools structures may still be assessed for bounded internal handoff paths as an implementation detail only.

**Merge Gate:** Any adoption must remain Community-internal, keep SPI/Core contracts unchanged, and show measurable benefit under representative profiling.

---

### Telemetry: Operational Metrics Export Baseline

**Gap:** JFR-first telemetry is present, but Community runtime still lacks a clearly defined operational metrics export baseline suitable for platform integration.

**Owner:** Telemetry subsystem.

**Resolution:** Add a machine-readable metrics export path suitable for open-core runtime operations (Prometheus and/or OTLP depending on final implementation choice). Keep the Community contract best-effort and explicitly separate from proprietary Enterprise telemetry paths.

**Merge Gate:** Export contract documented and covered by integration tests.

---

### Flow: Durable Community Snapshot Store

**Gap:** Flow is product-critical but Community snapshot durability still needs hardening beyond heap-only/in-memory behavior.

**Owner:** Flow / Persistence subsystem.

**Resolution:** Provide a JDBC-backed Community `FlowSnapshotStore`, validate restart behavior, and verify snapshot-backed wake/recovery semantics in tests.

**Merge Gate:** Restart/recovery tests pass for durable Community snapshot storage.

---

### Events: Core Scenario Hardening

**Gap:** Events support important product scenarios (outbox, projection, Flow integration), but practical runtime guarantees need to be hardened further.

**Owner:** Events subsystem.

**Resolution:** Prioritize outbox correctness, projection stability, event/Flow interaction, and visibility of queue/backpressure/retry behavior. Avoid broadening Events scope into full-platform work before practical runtime scenarios are stable.

**Merge Gate:** Runtime scenario tests for outbox/projection/Flow integration pass in CI.

---

### Exceptions: Environment-Aware Disclosure Mode Not Ported

`exceptions.md` documents environment-aware disclosure as a goal: PROD returns opaque error codes, DEV exposes full stack traces. This is not yet implemented in active runtime code.

**Gap:** Transport-facing error disclosure is still insufficiently environment-aware.

**Owner:** Core / Exceptions subsystem.

**Resolution:** Implement environment-aware disclosure configuration and extend exception mapping tests to assert PROD/DEV behavior. `ExerisKernelExceptionBlackBoxTckTest` already covers rawArgs contract; add a dedicated abstract TCK for disclosure mode behavior.

**Merge Gate:** Dedicated disclosure-mode abstract TCK and Community binding must pass before milestone close.

See also: [Exceptions Subsystem](./subsystems/exceptions.md) — Overview.

---

## Known Gaps / Future Work planned for v0.8

### HTTP/2: Community Lifecycle Hardening

**Gap:** Community HTTP/2 support is already exercised in real scenarios, but stream lifecycle, shutdown/drain behavior, and ownership boundaries between transport and HTTP still need deeper hardening for production-candidate use.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Harden stream table ownership, stream lifecycle transitions, GOAWAY/drain behavior, and concurrency controls on the Community transport path.

**Merge Gate:** Lifecycle and shutdown/drain behavior validated by regression/integration tests.

---

### Flow: Production Correctness Hardening

**Gap:** Flow has proven product importance, but durability/recovery and E2E correctness confidence still need to move from exploratory evidence to production-candidate confidence.

**Owner:** Flow subsystem.

**Resolution:** Add restart-under-load tests, drain-semantics tests, durability benchmarks, and stronger validation of unresolved vs failed vs compensated outcomes.

**Merge Gate:** Restart/recovery and outcome correctness suites become mandatory CI gates.

---

### Events: Backpressure and Projection Confidence

**Gap:** Events need stronger runtime confidence in their actual supported role for outbox/projection/Flow integration.

**Owner:** Events subsystem.

**Resolution:** Harden queue behavior, projection consistency, retry/dead-letter visibility, and event/Flow coordination under load.

**Merge Gate:** Backpressure and projection consistency tests pass at community target load.

---

### Graph: Baseline Production Hardening

**Gap:** Graph already works and participates in real scenarios, but CI/TCK and operational confidence for baseline PGQ/Bolt paths still need hardening.

**Owner:** Graph subsystem.

**Resolution:** Prioritize baseline driver stability, correctness tests for exercised PGQ/Bolt paths, resource-usage visibility, and CI coverage for real product scenarios. Avoid broadening Graph scope beyond practical paths already in use.

**Merge Gate:** Baseline Graph correctness/stability tests pass in CI.

---

### Documentation Truthfulness Audit

**Gap:** By production-candidate phase, documentation must distinguish clearly between what is implemented, supported, preview, and deferred. Historical planned wording becomes misleading if left in place.

**Owner:** Docs / Architecture.

**Resolution:** Complete a full docs audit and align wording to actual open-core/community reality.

**Merge Gate:** Docs audit checklist closed; architecture and subsystem docs aligned with code reality.

---

### CI Quality Gate Expansion

**Gap:** CI is not yet the full 1.0-grade gate for maintainability, leak-detection expectations, contract coverage, and release-candidate quality.

**Owner:** Build / Cross-cutting.

**Resolution:** Expand CI to include SonarQube gating, stronger leak-detection expectations, required contract suites, and release-candidate quality checks.

**Merge Gate:** Expanded gates enforced for open-core merge path.

---

## Known Gaps / Future Work planned for v0.9

### SPI Stability Declaration

**Gap:** No formal 1.0 support/stability declaration exists yet for open-core APIs and subsystem contracts.

**Owner:** Architecture / Build.

**Resolution:** Declare which SPI/API surfaces are stable, which are preview, and which remain experimental.

**Merge Gate:** Stability matrix published and referenced from module docs.

---

### Support Matrix Finalization

**Gap:** Product scope must be frozen explicitly before 1.0.

**Owner:** Architecture / Docs.

**Resolution:** Publish the final support matrix covering:
- foundation,
- core runtime,
- flow,
- events,
- graph,
- community-specific limits,
- enterprise-only out-of-scope items.

**Merge Gate:** Support matrix reviewed and approved with release criteria.

---

### Upgrade / Restart / Recovery Validation

**Gap:** 1.0 requires confidence in operational continuity, not just fresh boot behavior.

**Owner:** Cross-cutting / Runtime.

**Resolution:** Validate upgrade behavior, restart behavior, recovery semantics, degraded-mode transitions, and readiness/liveness correctness across lifecycle changes.

**Merge Gate:** Operational continuity suite is green on release-candidate branch.

---

### Reference Deployment Preparation

**Gap:** 1.0 cannot ship as a purely theoretical runtime.

**Owner:** Runtime / Docs / Operations interface.

**Resolution:** Prepare a reference deployment path with documented topology, runtime profile, resource envelope, observability setup, and known operational limits.

**Merge Gate:** Reference deployment documentation and validation checklist completed.

---

## Open-Core / Community 1.0 Release Requirements

Community 1.0 requires:

- clear open-core/community product scope,
- stable and documented Community runtime boundaries,
- explicit memory/resource discipline,
- production-grade request path across transport / HTTP / security / persistence / telemetry,
- product-grade Flow support,
- practical Events support for outbox/projection/integration scenarios,
- product-important Graph support for baseline exercised paths,
- documented Community best-effort performance contract,
- reduced suppression / stronger maintainability baseline,
- SonarQube-backed quality gate,
- explicit separation from proprietary Enterprise work,
- reference deployment documentation,
- no unresolved ambiguity in docs about what is supported vs planned vs enterprise-only,
- TCK-complete coverage for all SPI-observable behavior introduced by roadmap items (with Community bindings and explicit out-of-repo Enterprise obligations).

---

## Summary by Importance

### Foundation
- `memory`

### Core runtime
- `bootstrap`
- `config`
- `transport`
- `http`
- `security`
- `persistence`
- `telemetry`

### Product-critical
- `flow`

### Product-important
- `events`
- `graph`

### Cross-cutting 1.0-critical
- refactoring / PMD suppression reduction
- SonarQube
- docs truthfulness
- CI quality hardening
- open-core / enterprise separation
