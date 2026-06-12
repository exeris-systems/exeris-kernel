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

> **Closeout (v0.7.0 release readiness — Sprint 8f, 2026-05-10):** all entries below are annotated with their delivery status. Items not closed in v0.7 are explicitly re-triaged into "Known Gaps / Future Work planned for v0.8". See `docs/release/v0.7.0-release-notes.md` and `CHANGELOG.md` for the per-EPIC narrative.

### Bootstrap / HTTP: Embedded Health Endpoint for Community Runtime

**Gap:** Community runtime still lacks a clearly defined, first-class embedded health endpoint model for production-style readiness/liveness integration.

**Owner:** Core / Bootstrap / HTTP.

**Resolution:** Provide an embedded health endpoint for Community runtime with explicit readiness/liveness semantics and correct interaction with bootstrap and degraded runtime state.

**Merge Gate:** Health endpoint behavior covered by HTTP/bootstrap tests and documented operational contract.

**Status (v0.7):** **DELIVERED** in Sprint 7b — `HealthEndpointHandler` + `HealthProbe` SPI integrated with the Community embedded HTTP runtime.

---

### Security: `@RequiresRole` APT Processor Not Yet Implemented

`@RequiresRole` annotation and the compile-time `RoleCheckRegistry` APT processor are described in `security.md` as the target mechanism for zero-reflection RBAC. Neither the annotation type nor the APT processor exists in `exeris-kernel-build-config` or `exeris-kernel-spi`.

**Gap:** The planned bitmask-based O(1) role check cannot yet be used. `CitadelGuard.requireRole()` remains the primary runtime enforcement path.

**Owner:** Build / Security subsystem.

**Resolution:** Implement `@RequiresRole` in SPI, add an APT processor in build config that generates `RoleCheckRegistry`, and integrate the generated registry into runtime RBAC enforcement.

**Merge Gate:** ADR update is required before implementation begins; TCK coverage path for generated role checks defined and CI-gated.

See also: [Security Subsystem](./subsystems/security.md) — `@RequiresRole` Processing.

**Status (v0.7):** **DELIVERED** under ADR-014 in Sprints 8a / 8b-i / 8b-ii. SPI annotation surface (Sprint 8a), APT processor generating `RoleCheckRegistry` in `exeris-kernel-build-config` (Sprint 8b-i), runtime decision via `RoleCheckEnforcer.isAllowed` with zero-allocation TCK coverage (Sprint 8b-ii). Operator wiring (`LazyConstant<RoleCheckRegistry>` bootstrap loader, `ImmutablePrincipal.roleMask()` integration) deferred to v0.8.

---

### Telemetry: Core-Internal Async Dispatch Not Yet Implemented

`telemetry.md` describes a planned Core-internal dispatcher that fans out telemetry events to registered `TelemetrySink` instances off the caller's critical path while preserving low overhead. The current reference implementation (`JfrTelemetrySink`) emits synchronously on the caller thread.

**Gap:** Under high event throughput, synchronous JFR emission adds latency to the hot path. No bounded-overhead async dispatch path exists yet in open-core runtime.

**Owner:** Core / Telemetry subsystem.

**Resolution:** Design and implement a Core-internal dispatcher using a pre-allocated queue/ring structure and dedicated consumer with explicit capacity/drop policy. Community target is bounded overhead and operational predictability.

**Merge Gate:** Validate async path with both `AbstractTelemetryRingBufferTck` (dispatch/ring contract) and `TelemetryZeroAllocTck` (caller-path allocation discipline). Keep synchronous path as supported baseline until async gate passes.

See also: [Telemetry Subsystem](./subsystems/telemetry.md) — Design Principles.

**Status (v0.7):** **DELIVERED** in Sprint 7c — `AsyncTelemetrySink` dispatcher with bounded ring and explicit drop policy; `AbstractTelemetryRingBufferTck` and `TelemetryZeroAllocTck` Community bindings green.

---

### Cross-Cutting Quality: Sprint 5 Deferred Items Carried Into v0.7

**Gap:** Sprint 5 was closed with P1 gates green; the remaining P2/P3 quality items were intentionally deferred and must stay visible in v0.7 release planning.

**Owner:** Cross-cutting quality backlog.

**Resolution:** Carry the Sprint 5 deferred quality backlog into v0.7 until each item is either completed or explicitly re-triaged in release planning.

**Merge Gate:** v0.7 planning retains these deferred Sprint 5 quality items until disposition is recorded.

- QA-008 Assess `CommunityGraphCypherHelper` decomposition for GodClass removal.
- QA-009 Continue PMD suppression reduction to `<= 100` main-source target.
- SQ-005 Replace `catch(Error)` / `catch(Throwable)` with `catch(Exception)` in HTTP and flow runtime.
- SQ-006 Reduce Cognitive Complexity in `CoreFlowRuntime` dispatch method.
- SQ-007 Reduce constructor arity in `RuntimeFlowInstance`.
- SQ-008 Replace `Math.min` / `Math.max` chaining with `Math.clamp`.
- SQ-009 Clean up `tools/jfr-reporter` SonarCloud issues.
- SQ-010 Replace `Thread.sleep()` with deterministic synchronization in tests.
- SQ-011 Document empty method stubs in tests.

**Status (v0.7):** **DELIVERED (mostly)**. SQ-005..011 closed in Sprint 8c (catch narrowing, `Math.clamp` adoption with `S2184` overflow guard, `tools/jfr-reporter` SonarCloud cleanup, `Thread.sleep` → `LockSupport.parkNanos` + bounded settle-window, S5778 single-throwing-call discipline, S6218 record-array equality suppressions). QA-008 closed in Sprint 8d (`CommunityGraphCypherHelper` decomposed into `CypherExecutor` + Reader + Writer + Identifier helpers). QA-009 **partial** — the aspirational ≤ 100 target re-triaged to v0.8 as QA-010..018 (nine remaining `GodClass` decompositions, one PR each).

---

### Cross-Cutting: v0.6.0 PR Review Follow-ups Carried Into v0.7

**Gap:** The v0.6.0 release-cut PR review (architect / TCK / performance / docs-adr passes) surfaced one P1 and several P2 items that were intentionally not blocking the merge but must stay visible in v0.7 planning. None of these block the v0.6.0 cut.

**Owner:** Cross-cutting (Core / Community / TCK / Docs).

**Resolution:** Carry the items below into v0.7 until each is closed, fixed in a v0.6.x patch, or re-triaged in release planning.

**Merge Gate:** v0.7 planning retains these items until disposition is recorded.

**P1 — Contract integrity:**

- TCK-061 `BootstrapProviderSelector` does not enforce `TransportProvider.isAvailable()` filter. SPI Javadoc (`exeris-kernel-spi/.../TransportProvider.java`) mandates "descending priority + first-available" but only `CommunityTransportSubsystem` applies the filter manually; other subsystems (`PersistenceBootstrap`, `FlowBootstrap`, `EventBootstrap`, `GraphBootstrap`) routed through the shared selector would silently bypass `isAvailable()` if those SPIs ever add the method. Either move the filter into `BootstrapProviderSelector` or document the filter as caller responsibility and add a binding test for each subsystem.

**P2 — Hot-path performance:**

- PERF-061 Per-frame `LoanedBuffer` allocation in `CommunityHttp2SessionProcessor.writeHttp2Frame` and helpers (`sendHttp2PingAck`, settings/handshake helpers); replace with a per-stream/session reusable outbound carrier sized to peer `MAX_FRAME_SIZE` and slice in place — removes 2N allocations + copies per response.
- PERF-062 Double allocate+copy in TLS ingress path (`NativeTcpStream.decryptIngress` / `readTlsIngressFromFd`) under `synchronized(tlsLock)` with per-record retain/close; pre-pin a per-stream plaintext slab and only retain when the queue takes ownership transfer.
- PERF-063 Unbounded `ConcurrentLinkedQueue` in reactor control path (`NativeTcpCarrier.pendingRequests`) allocates per-event (record + CLQ node); evaluate bounded `MpscArrayQueue` (JCTools) sized to max channels per reactor (subject to Hot-Path Collections Review constraints below). Also document that `CLQ.offer` is always-true to silence the CodeQL "ignored return" flag.
- PERF-064 `LongAdder.reset()` race during engine shutdown (`CoreFlowRuntime.resetLifecycleTotals` / `close`); JDK docs state `reset()` is only effective with no concurrent updates, but `runInstance` virtual threads may still be decrementing via `decrementLifecycleCounterOnExit`. Replace with snapshot+replace via `lifecycleGeneration` or skip the reset and rely on generation gating.

**P2 — TCK contract coverage:**

- TCK-062 `FlowEngineShutdownEvent` JFR emission is documented in SPI Javadoc as a contract obligation, but verification lives only in `CoreFlowRuntimeTest` (Core impl). An Enterprise `FlowEngine` could omit the event without TCK failure. Either lift the assertion into `AbstractFlowEngineTck` as a binding-agnostic JFR-stream check, or soften the SPI Javadoc to "implementations may emit".
- TCK-063 Restart-aware semantics in `CoreFlowRuntime` (counter reset on restart, parked-reschedule no-op, `lookupParked` snapshot-probe suppression) are tested only at Core impl level (`CoreFlowRuntimeTest.java:91-174`). If these are intended as cross-binding contract guarantees, lift to `AbstractFlowEngineTck` / `AbstractSagaRecoveryTck`; if Core-internal, keep current placement and clarify in the SPI Javadoc.
- TCK-064 `NativeTcpTransportStressTest.stressTest10ClientsWith4Reactors` deadlocks under constrained CI (2 vCPU GitHub Actions runner) past the 2-minute Future timeout, while the same test passes deterministically locally in <500 ms across 5/5 runs. Tagged `@Tag("stress")` and excluded from default Surefire runs in v0.6.0 to unblock the merge; a dedicated `transport-stress-gate` CI job (or nightly schedule) using `-DincludedGroups=stress -DexcludedGroups=` should be added once root cause is understood. Hypotheses to investigate: (a) thread-pressure deadlock with 10 client engines × 1 reactor + 4 server reactors + 10 ForkJoinPool workers on a 2-vCPU host, (b) `pendingRequests` CLQ ordering anomaly under reactor wakeup contention, (c) short-read assumption in client `stream.read(buf, MESSAGE_SIZE)` failing under load and the resulting AssertionError appearing as a Future timeout via test wrapper. Reproduction recipe: add CPU stress (`stress-ng --cpu N`) on host while running `mvn -DincludedGroups=stress test`. Outcome of investigation should produce either a structural test fix (single client engine + N streams, short-read loop) or a real transport fix.

**P2 — Documentation alignment:**

- DOC-061 `docs/subsystems/persistence.md:197` documents `StorageContext.attributes()` as `Map<String, Object>`; SPI declares `Map<String, String>` (Wall-protective). Correct the doc example.
- DOC-062 `docs/subsystems/transport.md` (Responsibilities section) lacks a paragraph describing `TransportProvider.isAvailable()` and the descending-priority + first-available selection contract. Mirror the snippet from the SPI Javadoc.
- DOC-063 `docs/subsystems/security.md` should explicitly link the `StorageContextBridge` null-principal path to `PrincipalContextMissingException` / `EX-SEC-2001`; currently only TCK note covers fail-closed behavior.
- DOC-064 (optional) `docs/subsystems/crypto.md` operator note for `SocketChannelFdReflectionResolver`: document the `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` and `--add-opens java.base/java.io=ALL-UNNAMED` requirement on closed JDKs, with `bindFileDescriptor(int)` as the explicit fallback.

**Heuristic — class-size decomposition:**

- HEUR-061 Two Community transport files exceed 1k LOC after the v0.6.0 rewrite: `NativeTcpCarrier.java` (1376) and `NativeTcpStream.java` (1229). CLAUDE.md `>5 collaborators` heuristic flags both. Evaluate decomposition along reactor / FD-owner / PAQS-dispatch responsibility lines, analogous to SQ-006 for `CoreFlowRuntime`.

**Status (v0.7):** **DELIVERED**. P1 TCK-061 closed in Sprint 1 (`BootstrapProviderSelector` enforces `TransportProvider.isAvailable()`). P2 PERF-061..064 closed in Sprint 2 (HTTP/2 reusable buffer; TLS plaintext slab; NIC reactor `MpscUnboundedArrayQueue`; `LongAdder.reset` race fix via baseline snapshot per generation). TCK-062 (`FlowEngineShutdownEvent`) and TCK-063 (restart-aware semantics) lifted in Sprint 4. TCK-064 transport stress harness rework + `transport-stress-gate` CI job added in Sprint 2. DOC-061..064 (persistence/transport/security/crypto) corrected in Sprint 1. HEUR-061 transport class decomposition reviewed in Sprint 2; remaining transport `GodClass` items folded into v0.8 QA-010..018 batch.

---

### Runtime: Hot-Path Collections Review

**Gap:** Some Community runtime hot paths may justify a targeted collections review, but replacement structures must preserve current contracts and lookup semantics.

**Owner:** Core / Flow / Transport subsystem.

**Resolution:** Evaluate specialized runtime collections only where profiling shows real contention. Identity-based maps such as JCTools `NonBlockingIdentityHashMap` are not a fit for Flow registries/catalogs because those paths use value-semantic keys for restart, wake, and idempotency lookups; queue-oriented JCTools structures may still be assessed for bounded internal handoff paths as an implementation detail only.

**Merge Gate:** Any adoption must remain Community-internal, keep SPI/Core contracts unchanged, and show measurable benefit under representative profiling.

**Status (v0.7 Sprint 8e):** discharged via design note `docs/release/hot-path-collections-review.md`. No replacement justified for v0.7 beyond the previously merged PERF-063 NIC reactor MPSC queue; idempotency-guard packed-bitmap and `pendingWriteInterest` identity-keyset recorded as v0.8 watch-items.

---

### Telemetry: Operational Metrics Export Baseline

**Gap:** JFR-first telemetry is present, but Community runtime still lacks a clearly defined operational metrics export baseline suitable for platform integration.

**Owner:** Telemetry subsystem.

**Resolution:** Add a machine-readable metrics export path suitable for open-core runtime operations (Prometheus and/or OTLP depending on final implementation choice). Keep the Community contract best-effort and explicitly separate from proprietary Enterprise telemetry paths.

**Merge Gate:** Export contract documented and covered by integration tests.

**Status (v0.7):** **DELIVERED** in Sprint 7d — Prometheus export baseline; OTLP path remains open as a v0.8/v0.9 optional addition.

---

### Flow: Durable Community Snapshot Store

**Gap:** Flow is product-critical but Community snapshot durability still needs hardening beyond heap-only/in-memory behavior.

**Owner:** Flow / Persistence subsystem.

**Resolution:** Provide a JDBC-backed Community `FlowSnapshotStore`, validate restart behavior, and verify snapshot-backed wake/recovery semantics in tests.

**Merge Gate:** Restart/recovery tests pass for durable Community snapshot storage.

**Status (v0.7):** **DELIVERED** under EPIC-1 in Sprints 3 and 4 — `JdbcFlowSnapshotStore` + `exeris_saga_state` DDL + auto-DDL bootstrap (Sprint 3); `lookupParked` snapshot fallback, bounded terminal-state catalog, saga TTL enforcement, `FlowEngineShutdownEvent` JFR (Sprint 4). `AbstractDistributedFlowSnapshotStoreTck` + `AbstractSagaRecoveryTck` Community Testcontainers PostgreSQL bindings green. ADR-013 signed off.

---

### Events: Core Scenario Hardening

**Gap:** Events support important product scenarios (outbox, projection, Flow integration), but practical runtime guarantees need to be hardened further.

**Owner:** Events subsystem.

**Resolution:** Prioritize outbox correctness, projection stability, event/Flow interaction, and visibility of queue/backpressure/retry behavior. Avoid broadening Events scope into full-platform work before practical runtime scenarios are stable.

**Merge Gate:** Runtime scenario tests for outbox/projection/Flow integration pass in CI.

**Status (v0.7):** **DELIVERED** in Sprints 5–6. Sprint 5a closed EVENT-202 SPI audit, EVENT-203 `EventStreamReader` / `EventStreamAppender` skeletons, and EVENT-205 registry/backpressure TCK. Sprint 5b shipped EVENT-204 (Community Kafka driver in new `exeris-kernel-community-kafka` module) and EVENT-206 (`AbstractKafkaEventEngineTck` + Testcontainers Kafka 3.x). Sprint 6a–6c closed DIST-302 cross-engine choreography handoff. Distributed-saga JFR events emitted per ADR-013 §8 (Sprint 6 telemetry). Outbox runtime hardening continues into v0.8 backpressure/projection follow-ups.

---

### Exceptions: Environment-Aware Disclosure Mode Not Ported

`exceptions.md` documents environment-aware disclosure as a goal: PROD returns opaque error codes, DEV exposes full stack traces. This is not yet implemented in active runtime code.

**Gap:** Transport-facing error disclosure is still insufficiently environment-aware.

**Owner:** Core / Exceptions subsystem.

**Resolution:** Implement environment-aware disclosure configuration and extend exception mapping tests to assert PROD/DEV behavior. `ExerisKernelExceptionGlassBoxTckTest` already covers rawArgs contract; add a dedicated abstract TCK for disclosure mode behavior.

**Merge Gate:** Dedicated disclosure-mode abstract TCK and Community binding must pass before milestone close.

See also: [Exceptions Subsystem](./subsystems/exceptions.md) — Overview.

**Status (v0.7):** **DELIVERED** in Sprint 7a — `EnvironmentDisclosurePolicy` + `AbstractDisclosureModeTck` and Community binding green; PROD opaque codes vs. DEV stack-trace surface separated.

---

### Events: Kafka/Redpanda Driver — Core-Shared Implementation Model

The Events subsystem documents Kafka and Redpanda as supported backends with zero-copy native flow via the Kafka wire protocol. No Kafka/Redpanda driver exists in Community or Core today. The architectural placement decision is: broker protocol orchestration logic belongs in **Core**, shared between Community and Enterprise bindings — following the same model as PAQS (Core-owned scheduler consumed by Community NIO and Enterprise native paths) and TLS (Core-owned FD-owner lifecycle consumed by both tiers).

**Placement decision:** Core owns the Kafka wire protocol session orchestration (`eu.exeris.kernel.core.events.kafka`): producer send lifecycle, consumer poll/commit cycle, at-least-once delivery guarantees, and `OutboxBrokerPort` integration. Community binds over Core using the Kafka Java client (NIO-backed, acceptable under the Community best-effort performance contract). Enterprise binds over the same Core orchestration using Panama FFM socket operations for native zero-copy page-cache delivery. SPI blindness is preserved — Core orchestrates through `OutboxBrokerPort` and `EventLoop` SPI contracts; broker transport details do not leak into Core or SPI.

**Gap:** No Kafka session orchestration exists in Core. Community `OutboxBrokerPort` is wired to the in-memory `EventBus` only (`CommunityEventBusOutboxBrokerPort`). `EventStreamReader` and `EventStreamAppender` SPI interfaces are described in `events.md` as target state but not yet defined in `exeris-kernel-spi`. There is no Kafka-backed `EventEngine` provider in Community or Enterprise for distributed architecture use.

**Owner:** Core / Events subsystem.

**Resolution:**
1. Define `EventStreamReader` and `EventStreamAppender` SPI skeletons in `exeris-kernel-spi` (implementation-blind; no Kafka/broker types in SPI).
2. Implement Core Kafka session orchestration in `eu.exeris.kernel.core.events.kafka` — producer lifecycle, consumer poll/commit, delivery acknowledgement, and `OutboxBrokerPort` contract integration. Core carries no dependency on the Kafka Java client; it defines the orchestration contract that both Community and Enterprise bindings wire into.
3. Add Community `KafkaEventBrokerPort` implementing `OutboxBrokerPort` backed by the Kafka Java client, wired to the Core orchestrator. Register as `EventProvider` via `ServiceLoader`.
4. Community `KafkaEventLoop` wraps the consumer group poll cycle with `StructuredTaskScope` — virtual thread per poll iteration, `EventQueue` backpressure on event arrival.
5. Enterprise binds the same Core orchestrator with a Panama FFM Kafka wire protocol transport (zero-copy, no Kafka Java client heap allocation). Out-of-repo obligation.
6. Kafka and Redpanda share the same driver path (Kafka protocol v2 compatible); no separate Redpanda-specific code.

**Merge Gate:** Architecture sign-off on Core placement and SPI extension surface before any implementation begins. `AbstractKafkaEventEngineTck` added to `exeris-kernel-tck`; Community binding passes with Testcontainers Kafka 3.x. Enterprise binding obligation declared in TCK. `events.md` updated to reflect Core placement and Community/Enterprise split.

See also: [Events Subsystem](./subsystems/events.md) — Multi-Provider Strategy, Zero-Copy Native Flow.

**Status (v0.7):** **DELIVERED** under EPIC-2 in Sprints 5a–5b. Community-tier Kafka driver in `exeris-kernel-community-kafka`; `AbstractKafkaEventEngineTck` Testcontainers binding green. Core-shared session-orchestration model recorded in EVENT-201 decision (2026-04-27). Enterprise FFM-backed path remains an out-of-repo obligation per the open-core model.

---

### Flow + Events: Distributed Saga State and Orchestration — Implementation Backlog

> **Status (v0.7):** **DELIVERED.** EPIC-1 (FLOW-101..108) closed across Sprints 1, 3, 4. EPIC-2 (EVENT-201..206) closed in Sprints 5a–5b. EPIC-3 (DIST-301..303) closed across Sprints 4 and 6 with ADR-013 sign-off. EPIC-4 (TCK-401, TCK-402) closed in Sprint 1. The detailed line items below remain as historical record of the v0.7 backlog scope.

> **Doc Impact:** `ADR_REVIEW_POSSIBLE` — this backlog formalises a change from the current architectural assumption ("saga state is in-process / heap-only") to "saga state may be distributed across restarts and services". A new ADR is required (see DIST-301) before implementation begins on any cross-service saga state work.
>
> **Code-verified gaps (as of v0.6):**
> - `CommunityFlowSnapshotStore` is a `ConcurrentHashMap` — no persistence, no cross-restart recovery.
> - `CommunityEventEngine` has no Kafka/Redpanda driver — only in-memory `EventBus` + JDBC Outbox.
> - `FlowSnapshot.opaqueState` is capped at 916 bytes — may be insufficient for rich distributed saga payloads (FLOW-101 must assess).
> - `FlowEngineConfig.persistenceEnabled` flag already exists — JDBC store is a drop-in replacement without SPI changes.
> - `CommunityEventBusOutboxBrokerPort` confirms the `OutboxBrokerPort` extension point is already architecturally reserved for broker delivery.

#### EPIC-1: Distributed Flow Snapshot Store (JDBC Community Tier)

**FLOW-101 — [ARCH][DECIDED] SPI audit: is `FlowSnapshotStore` sufficient for distributed state?**

- **Priority:** Critical / Blocker
- **Owner:** Exeris Architect
- **Description:** Analyse whether the current `FlowSnapshotStore` SPI (`save`, `load`, `delete`, `exists`) covers the needs of distributed saga state for v1.0. Specifically:
  - Does `list(FlowState)` / `listParked()` need to be added for post-restart recovery?
  - Does `loadByDefinition(String definitionName)` need to be added for admin-triggered drain?
  - Is `FlowSnapshot.opaqueState` (max 916 bytes) sufficient for user-level saga payload in distributed scenarios?
  - Is optimistic locking / a version field needed in `FlowSnapshot` to prevent lost-update under concurrent snapshot writes from multiple service instances?
- **Acceptance:** Architectural decision recorded in `flow.md`; SPI extension PR opened if API gaps confirmed.
- **Merge Gate:** Architecture sign-off required before any FLOW-102+ implementation begins.

> **[DECISION — 2026-04-27]**
>
> **`listParked()` → ADD to SPI.** Required for post-restart bulk recovery: Core must seed the in-memory parked map at startup; reactive per-instance miss fallback alone cannot enumerate all PARKED instances after a clean restart. Add `List<FlowSnapshot> listParked()` to `FlowSnapshotStore` with a `default` implementation returning `List.of()` so existing in-memory implementations do not break.
>
> **`loadByDefinition()` → DEFER.** Admin-triggered saga drain is an operator procedure today; the programmatic `loadByDefinition` API is only meaningful once Saga definition versioning exists (planned but not implemented). Adding it now would encode an API that anticipates a schema that does not exist. Defer to the definition-versioning epic.
>
> **`opaqueState` 916-byte cap → KEEP as hard limit.** The cap maps 1:1 to the Enterprise off-heap context slab stride (`FLOW_CONTEXT_STRIDE`). Increasing it in Community would break the Enterprise binary ABI. Documented guidance: store large business payloads in the domain aggregate table and reference them by a surrogate key serialised into `opaqueState`. This is idiomatic CQRS and does not require a cap change.
>
> **Optimistic locking → ADD `long schemaVersion` to `FlowSnapshot`.** Under Option A (multiple service instances sharing a JDBC snapshot store — see DIST-301), two nodes may race to write the same saga. DB PK prevents duplicate rows but not stale overwrites. `schemaVersion` enables `UPDATE … WHERE schema_version = ?` CAS at the JDBC layer. On conflict: throw `EX-FLOW-7002 / phase=OPTIMISTIC_LOCK_CONFLICT`. The `schema_version` DDL column is already in the FLOW-103 spec (upgrade its type to `BIGINT` — see FLOW-103 edit). In-memory `CommunityFlowSnapshotStore` ignores this field (single-process; unconditional overwrite is correct behaviour).
>
> **SPI changes required:** `FlowSnapshotStore.listParked()` (with default) + `FlowSnapshot.schemaVersion` field added to the record. Both unblock FLOW-102 through FLOW-106.

---

**FLOW-102 — [ARCH][DECIDED] Placement decision: JDBC `FlowSnapshotStore` in Community vs. new module**

- **Priority:** Critical / Blocker
- **Owner:** Exeris Architect
- **Description:** Community already has a JDBC Outbox (`CommunityJdbcOutboxEventStoreAdapter`). Decide whether `JdbcFlowSnapshotStore` belongs in `exeris-kernel-community` (consistent with the outbox pattern) or requires a new module. Evaluate:
  - Dependency footprint: can Community import JDBC without new transitive dependencies?
  - Is the `exeris_saga_state` DDL (referenced in `flow.md`) already defined anywhere?
  - Should `PersistenceEngine` SPI be used instead of raw JDBC to stay Wall-compliant?
- **Acceptance:** Placement decision documented in an ADR or as an authoritative note in `flow.md`.

> **[DECISION — 2026-04-27]**
>
> **`JdbcFlowSnapshotStore` → `exeris-kernel-community` (same module as JDBC Outbox).** Community already carries a JDBC dependency for `CommunityJdbcOutboxEventStoreAdapter`; adding `JdbcFlowSnapshotStore` in the same module adds no new transitive dependencies. Using raw JDBC (not `PersistenceEngine` SPI) is consistent with the existing outbox precedent and keeps the implementation simple. `PersistenceEngine` would be the correct path only if the store needed to participate in a managed transaction context — the snapshot store is an independent write path and does not require transaction composition with business logic.
>
> **`exeris_saga_state` DDL** is not yet defined anywhere in the repository; it is introduced by FLOW-103.
>
> **`PersistenceEngine` SPI → NOT required here.** Raw JDBC via injected `DataSource` is Wall-compliant: the snapshot store is a Community-internal detail and does not cross a SPI boundary. The `FlowSnapshotStore` SPI remains implementation-blind.
>
> **Unblocks:** FLOW-103, FLOW-104.

> **[REVISITED — 2026-05-11 / v0.8 Sprint 0b — ADR-022]** The "raw JDBC, no `PersistenceEngine`" call from 2026-04-27 stood until v0.8 Sprint 0b uncovered a wiring gap: `CommunityFlowSubsystem.initialize()` always bound the in-memory store regardless of JDBC availability because `JdbcFlowSnapshotStore` constructor required a raw `DataSource` and the `PersistenceEngine`-owned pool could not be exposed without breaking the swappable-engine contract. The decision was **reversed**: `JdbcFlowSnapshotStore` now consumes `PersistenceEngine.openConnection()`. Two additive SPI methods (`PersistenceStatement.bindInstant`, `RowCursor.getInstant`) were added under ADR-022 to support the timestamp columns that the previous raw-JDBC path used. The original rationale ("no managed transaction composition required") was correct in isolation; what the original decision missed was that routing through the SPI is the only way to wire the JDBC store from the bootstrap layer without leaking the pool. The placement decision (Community module, not a new module) **remains valid**.

---

**FLOW-103 — [IMPL] `exeris_saga_state` DDL schema and auto-DDL bootstrap**

- **Priority:** High
- **Depends on:** FLOW-101, FLOW-102
- **Owner:** Exeris Implementer
- **Description:** Define DDL for the `exeris_saga_state` table. Required fields: `instance_id_most` (BIGINT), `instance_id_least` (BIGINT), `definition_name` (TEXT), `current_step` (INT), `state` (TEXT), `last_update` (TIMESTAMPTZ), `timeout_at` (TIMESTAMPTZ nullable), `compensation_stack` (INT[] or packed BYTEA, decision in FLOW-101), `stack_pointer` (INT), `opaque_state` (BYTEA nullable), `schema_version` (BIGINT, default 1), PRIMARY KEY `(instance_id_most, instance_id_least)`. Add a partial index on `state = 'PARKED'` (most common recovery query pattern). Integrate with the existing auto-DDL bootstrap pattern used by `exeris_outbox_dlq`.
- **Acceptance:** DDL creates cleanly on a fresh DB; missing table does not throw on kernel start.

---

**FLOW-104 — [IMPL] `JdbcFlowSnapshotStore` implementation**

- **Priority:** High
- **Depends on:** FLOW-103
- **Owner:** Exeris Implementer
- **Description:** Implement `FlowSnapshotStore` backed by JDBC. Requirements:
  - `save()`: UPSERT (`INSERT ... ON CONFLICT DO UPDATE`)
  - `load()`: SELECT by primary key
  - `delete()`: DELETE by primary key
  - `exists()`: `SELECT 1` (not `SELECT *`)
  - `listParked()` if SPI extended in FLOW-101: `SELECT WHERE state = 'PARKED'`
  - Serialise `int[]` compensationStack per decision from FLOW-101
  - Serialise `Instant` → `TIMESTAMPTZ` via `java.sql.Timestamp.from()`
  - No `ThreadLocal`, no `ByteBuffer.allocate()` on any path
  - `DataSource` injected via constructor; no hard-wired connection management
- **Acceptance:** `AbstractSagaRecoveryTck` passes with `JdbcFlowSnapshotStore` as SUT (requires FLOW-106 TCK binding).

---

**FLOW-105 — [IMPL] `lookupParked` snapshot fallback for cross-restart choreography wake**

- **Priority:** High
- **Depends on:** FLOW-104
- **Owner:** Exeris Implementer
- **Description:** Implements the ROADMAP-tracked gap: `FlowScheduler.lookupParked()` must consult `snapshotStore` as a fallback when the in-memory parked map has no entry (post-restart recovery path). Requirements:
  - In-memory O(1) fast path unchanged
  - On miss: `snapshotStore.load(instanceIdMost, instanceIdLeast)` → filter `FlowState.PARKED` → reconstruct `FlowContext`
  - Negative cache for repeated misses to prevent store hammering under choreography polling
  - No allocation on in-memory hit path
  - Only active when `FlowEngineConfig.persistenceEnabled` is true; behaviour unchanged if persistence is disabled
- **Acceptance:** New test in `AbstractFlowChoreographyTck` or `AbstractSagaRecoveryTck` covering restart + choreography wake; Community binding passes in CI.

---

**FLOW-106 — [TCK] `AbstractDistributedFlowSnapshotStoreTck` + Community JDBC binding**

- **Priority:** High
- **Depends on:** FLOW-101, FLOW-104
- **Owner:** Exeris TCK/Test
- **Description:** New abstract TCK suite for persistence-backed `FlowSnapshotStore`:
  - `save → load` round-trip
  - `delete` removes the entry
  - `listParked` returns only PARKED instances (if API added)
  - Cross-restart simulation: save snapshot, new store instance, load → verify state intact
  - Concurrent saves under Virtual Thread concurrency: no data races
  - `CommunityJdbcFlowSnapshotStoreTckTest` in `exeris-kernel-community` extends this suite; uses embedded PostgreSQL (Testcontainers) or H2-compatible DDL fallback
- **Acceptance:** TCK passes in CI. Enterprise binding obligation recorded.

---

**FLOW-107 — [IMPL] Terminal-State Catalog bounded retention**

- **Priority:** Medium
- **Depends on:** FLOW-101 architecture sign-off
- **Owner:** Exeris Implementer
- **Description:** Implements the ROADMAP-tracked gap for `CoreFlowRuntime.terminalStateCatalog`. Add `terminalCatalogMaxSize` to `FlowEngineConfig` (default: unbounded for backward compatibility). When the limit is reached, apply LRU eviction. Correctness requirement: an evicted entry must be recoverable from `snapshotStore` (state = `COMPLETED` or `FAILED_ROLLEDBACK`) before the engine accepts a re-submit for that instance — the idempotency fence must not be weakened.
- **Acceptance:** `AbstractFlowEngineTck` test for bounded catalog + eviction; idempotency guarantees do not regress.

---

**FLOW-108 — [IMPL] `FlowEngineShutdownEvent` JFR implementation**

- **Priority:** Low
- **Owner:** Exeris Implementer
- **Description:** Implements the ROADMAP-tracked gap. Create `FlowEngineShutdownEvent` in `eu.exeris.kernel.core.flow.jfr`. Emit at `close()` completion. Fields (per `flow.md` JFR Events table): `engineName`, `activeFlows`, `parkedFlows`, `completedFlows`, `failedFlows`, `persistenceEnabled`, `compensationEnabled`. Additionally capture `shutdownDurationNs`.
- **Acceptance:** `AbstractFlowEngineTck` `RecordingStream` assertion for shutdown event emission; Community binding passes.

---

#### EPIC-2: Distributed Events — Kafka/Redpanda Community Driver

**EVENT-201 — [ARCH][DECIDED] Kafka driver placement: Community tier vs. Enterprise-only**

- **Priority:** Critical / Blocker
- **Owner:** Exeris Architect
- **Description:** `events.md` describes Kafka/Redpanda as a supported backend using "the Kafka wire protocol directly via Panama FFM socket operations". This capability does not exist in Community today. Decide:
  - Is a Panama FFM Kafka wire protocol driver realistic in the open-core Community tier?
  - Does Community receive a simplified Kafka driver (Kafka Java client, NIO-backed, no zero-copy) while Enterprise holds the native Panama FFM path?
  - Do ADR-007 or ADR-008 impose the Community/Enterprise split?
  - Is `CommunityEventBusOutboxBrokerPort` the correct extension point for a Kafka broker port binding?
- **Acceptance:** Decision recorded in ADR or authoritative note in `events.md`; placement is unambiguous.
- **Note:** The Core-shared placement decision (Core owns session orchestration; Community and Enterprise bind differently) has been established as the intended model — see "Events: Kafka/Redpanda Driver — Core-Shared Implementation Model" above. This item confirms and documents that decision with ADR backing.

> **[DECISION — 2026-04-27]**
>
> **Core placement → CONFIRMED.** `eu.exeris.kernel.core.events.kafka` owns the Kafka session orchestration (producer lifecycle, consumer poll/commit state machine, delivery acknowledgement, `OutboxBrokerPort` integration). Core carries zero dependency on `org.apache.kafka.*` — it orchestrates through existing `OutboxBrokerPort` and `EventLoop` SPI contracts. This is structurally identical to how Core drives TLS phase transitions without knowing which OpenSSL symbols Community or Enterprise use. Wall compliance: PASS.
>
> **Community implementation → Kafka Java client (NIO-backed).** ADR-008 explicitly reserves the "JDBC Tax" / "Syscall Tax" tolerance for Community. The Kafka Java client is the same allocation category as the existing JDBC outbox. Acceptable under the Community best-effort performance contract. The client must not appear in Core or SPI. Wall compliance: PASS.
>
> **`OutboxBrokerPort` as extension point → CONFIRMED.** `CommunityEventBusOutboxBrokerPort` already demonstrates the pattern. A `KafkaOutboxBrokerPort` implements the same contract and forwards batched entries to a Kafka producer instead of the in-memory bus. No new SPI interface is needed for the Outbox delivery path.
>
> **Module placement → NEW MODULE `exeris-kernel-community-kafka`.** The Kafka Java client transitive dependency tree (`kafka-clients` → `zstd-jni`, `lz4-java`, `snappy-java`, `jackson-databind`) must not pollute `exeris-kernel-community` for operators running single-node kernels. A separate Maven module `exeris-kernel-community-kafka` (depends on `exeris-kernel-community` + `kafka-clients`) is the correct isolation boundary, consistent with ADR-009's rationale for module separation. Registers `KafkaEventProvider` via `ServiceLoader`; automatic when JAR is on classpath. `exeris-kernel-bom` and root reactor `pom.xml` must declare the new module.
>
> **Kafka and Redpanda → single driver path.** Both brokers communicate over Kafka protocol v2; no separate Redpanda-specific code.
>
> **Unblocks:** EVENT-202, EVENT-203, EVENT-204.

---

**EVENT-202 — [ARCH] `EventEngine` SPI extension audit for multi-backend**

- **Priority:** High
- **Depends on:** EVENT-201
- **Owner:** Exeris Architect
- **Description:** Does the current `EventEngine` SPI (`EventBus`, `EventQueue`, `EventLoop`, `EventRegistry`) suffice for a Kafka-backed implementation? Specifically:
  - Is `EventBus.publish()` + off-heap `EventPayload` lifecycle compatible with Kafka producer send (may require serialise → `ProducerRecord` step)?
  - Does the `EventLoop` contract cover Kafka consumer group poll/commit cycles?
  - Are `EventStreamReader` / `EventStreamAppender` (currently "Target State — not yet implemented") required before a Kafka driver can be built?
  - Is `OutboxBrokerPort` the correct and sufficient extension point for Kafka delivery?
- **Acceptance:** SPI gap analysis recorded; list of required new SPI types produced, or confirmation that existing contracts suffice.

---

**EVENT-203 — [IMPL] `EventStreamReader` / `EventStreamAppender` SPI skeletons**

- **Priority:** High
- **Depends on:** EVENT-202
- **Owner:** Exeris Implementer
- **Description:** Define the SPI interfaces referenced in `events.md` as "Target State". `EventStreamReader`: `replayFrom(StreamId, Instant)`, `replayFromVersion(StreamId, long)`, `replayByType(String, Instant)` — all return `EventStream`. `EventStreamAppender`: `append(StreamId, EventDescriptor, EventPayload)`. Supporting types: `StreamId` as a primitive `record` (Valhalla-ready, no identity operations); `EventStream` as `Iterable<EventPayload>` with auto-close per payload. All interfaces are SPI-blind — no Kafka, JDBC, or broker-specific types in the SPI package.
- **Acceptance:** SPI compiles; no implementation yet (skeleton for TCK and downstream binding).

---

**EVENT-204 — [IMPL] Community Kafka/Redpanda `EventEngine` provider (NIO-backed)**

- **Priority:** High
- **Depends on:** EVENT-201, EVENT-202, EVENT-203
- **Owner:** Exeris Implementer
- **Description:** Implement `KafkaEventEngine` Community provider in `exeris-kernel-community` (or `exeris-kernel-community-kafka` submodule per FLOW-102 placement decision analogue). Requirements:
  - Kafka Java client (NIO-backed; acceptable under the Community best-effort performance contract)
  - `EventBus.publish()` → Kafka producer send (async; `StructuredTaskScope` for completion handling)
  - `EventLoop` → Kafka consumer group poll loop (one virtual thread per poll iteration; `EventQueue` backpressure on arrival)
  - `OutboxBrokerPort` → Kafka delivery adapter reusing the existing `OutboxOrchestrator`
  - `EventStreamReader` → Kafka consumer seek + poll
  - `EventStreamAppender` → Kafka producer with explicit partition/offset targeting
  - No `ThreadLocal`; consumer rebalance must not block carrier threads
  - Configuration via `EventEngineConfig` extensions: `bootstrap.servers`, topic prefix, `group.id`
  - Registered via `ServiceLoader` as `EventProvider`
- **Acceptance:** Integration test with embedded Kafka (`Testcontainers KafkaContainer`); `AbstractEventEngineTck` passes.

---

**EVENT-205 — [TCK] TCK gaps from `events.md` — registry conflict and backpressure**

- **Priority:** Medium
- **Owner:** Exeris TCK/Test
- **Description:** Implement TCK gaps explicitly flagged in `events.md`:
  - `EventRegistry` ordinal conflict: `EX-EVENT-6003` thrown on duplicate type registration
  - Backpressure: `EX-EVENT-6002` thrown with correct `rawArgs` (`eventType`, `queueDepth`, `queueCapacity`) when queue is at capacity
  - Both as abstract TCK suites with Community bindings
- **Acceptance:** CI passes; `rawArgs` layout matches `KernelErrorCodes.java` source of truth.

---

**EVENT-206 — [TCK] `AbstractKafkaEventEngineTck` + Community Kafka binding**

- **Priority:** High
- **Depends on:** EVENT-204
- **Owner:** Exeris TCK/Test
- **Description:** Abstract TCK suite for Kafka-backed `EventEngine`:
  - Publish + consume round-trip (at-least-once semantics)
  - Outbox guarantee: broker disconnect mid-flight → event not lost in DB outbox
  - Replay from offset and from timestamp
  - Dead Letter Queue transition on `max-retries` exhaustion
  - Consumer group rebalance does not pin carrier threads (`FlowCarrierPinningTck` pattern)
  - `CommunityKafkaEventEngineTckTest` in `exeris-kernel-community` using Testcontainers Kafka 3.x
- **Acceptance:** CI passes with Testcontainers Kafka 3.x. Enterprise binding obligation recorded.

---

#### EPIC-3: Flow + Events Integration for Distributed Architecture

**DIST-301 — [ARCH][DECIDED] Distributed Saga state distribution model → ADR-013**

- **Priority:** Critical
- **Owner:** Exeris Architect
- **Description:** Define the architectural model for passing saga state between distributed services and across restarts. Three options to evaluate:
  - **Option A:** `FlowSnapshotStore` JDBC-backed + choreography via Kafka Events (one topic per saga type). Each service runs its own `FlowEngine` instance; shared DB holds canonical saga state. Single-writer semantics enforced by DB PK constraints.
  - **Option B:** Centralised `FlowEngine` (single process / single writer per saga type) with distributed event bus (Kafka) for step triggers. Simpler consistency model; harder to scale.
  - **Option C:** Saga state in Kafka compacted topic (Kafka as snapshot store). Eliminates JDBC dependency; requires Kafka-backed `FlowSnapshotStore` implementation.
  - For each option: assess blast radius on The Wall (SPI blindness), impact on `IdempotencyGuard`, and compatibility with the existing `FlowChoreographyBridge`.
- **Acceptance:** New ADR-013 (or equivalent) produced with chosen option and rationale. ADR must be signed off before DIST-302 or DIST-303 implementation begins.

> **[DECISION — 2026-04-27]**
>
> **SELECTED: Option A** — JDBC-backed `FlowSnapshotStore` (shared DB) + Kafka Events for choreography wake.
>
> **Rationale:** Option A requires the smallest delta from the existing implementation. `FlowSnapshotStore` SPI already exists; `JdbcFlowSnapshotStore` (FLOW-104) is the only new persistence component. Kafka is added for choreography wake events only — `FlowChoreographyBridge` (Core-internal) already accepts any `EventBus`; wiring `KafkaEventEngine.bus()` into `registerChoreographyMapper` requires **zero Core changes**. Option B is a single point of failure incompatible with the per-service `FlowEngine` model. Option C is incompatible with the `schemaVersion` optimistic locking decision from FLOW-101-4: a Kafka compacted topic cannot enforce the CAS semantics needed to prevent lost-update races between service nodes. Option C is deferred post-v1.0.
>
> **Wall compliance:** PASS. Core orchestrates through `FlowSnapshotStore` and `OutboxBrokerPort` / `EventLoop` SPI contracts only. No JDBC or Kafka types in Core or SPI.
>
> **`IdempotencyGuard` impact:** Minor. Each service instance runs its own `CoreIdempotencyGuard` (heap-based CAS). Cross-instance idempotency is enforced by the `schemaVersion` optimistic lock at the DB layer — a second write for the same step from a second node conflicts on `schema_version` and throws `EX-FLOW-7002/OPTIMISTIC_LOCK_CONFLICT`. The `IdempotencyGuard` SPI contract is unchanged.
>
> **Core changes required:** None. `FlowChoreographyBridge` wires to Kafka `EventBus` without modification.
>
> **ADR-013 required:** YES. This decision changes the architectural assumption about saga state scope from "in-process / heap-only" to "distributed / DB-canonical" — a boundary change that the ROADMAP explicitly flags as requiring ADR treatment. ADR-013 must record: Option A selected; rationale against B and C; `schemaVersion` optimistic lock contract; Kafka choreography wake contract; v1.0 re-evaluation trigger for Option C.
>
> **Unblocks:** DIST-302, DIST-303 (after FLOW-104 and EVENT-204 complete).

---

**DIST-302 — [IMPL] Flow → Events: distributed choreography handoff**

- **Priority:** High
- **Depends on:** DIST-301, EVENT-204, FLOW-104
- **Owner:** Exeris Implementer
- **Description:** Implement the pattern where `FlowChoreographyMapper` consumes events from a Kafka `EventEngine` and wakes or starts saga instances. Requirements:
  - `FlowChoreographyBridge` (already in Core) must be wireable to a Kafka `EventLoop` consumer
  - Event payload → `ChoreographyDecision` deserialisation with minimal heap allocation on hot path
  - Cross-service saga instance routing: event payload must carry `instanceIdMost` / `instanceIdLeast` for wake routing
  - At-least-once delivery with `IdempotencyGuard` fence (already in SPI) preventing duplicate step execution
- **Acceptance:** Integration test: Service A starts saga and parks; Service B receives Kafka event, wakes saga; state persisted in `JdbcFlowSnapshotStore` and consistent across both service processes.

---

**DIST-303 — [IMPL] Long-running vs. short-lived saga TTL management**

- **Priority:** Medium
- **Depends on:** FLOW-101, FLOW-104
- **Owner:** Exeris Implementer
- **Description:** `FlowSnapshot.timeout` field exists but is not enforced at runtime. Implement:
  - `FlowEngineConfig.defaultSagaTimeoutShort` (e.g. 30 s for transactional sagas)
  - `FlowEngineConfig.defaultSagaTimeoutLong` (e.g. 30 days for long-running business process sagas)
  - Timeout enforcement in the scheduler: on wake, check `snapshot.timeout().isBefore(Instant.now())` → trigger compensation path
  - TTL-based eviction from `terminalStateCatalog` and `exeris_saga_state` table (configurable retention period)
  - JFR event: `FlowTimeoutEvent` with fields `definitionName`, `instanceIdMost`, `instanceIdLeast`, `timeoutAt`
- **Acceptance:** `AbstractFlowEngineTck` test for timeout-triggered compensation; TCK passes.

---

#### EPIC-4: Missing TCK Bindings (v1.0 quality gate prerequisites)

**TCK-401 — `CommunityFlowIdempotencyGuardTckTest`**

- **Priority:** High
- **Owner:** Exeris TCK/Test
- **Description:** Implements ROADMAP-tracked gap. Add `CommunityFlowIdempotencyGuardTckTest` in `exeris-kernel-community/src/test/` extending `AbstractIdempotencyGuardTck`. SUT: `CoreIdempotencyGuard`. No new SPI surface required.
- **Acceptance:** CI passes.

---

**TCK-402 — `CommunityFlowZeroAllocTckTest`**

- **Priority:** High
- **Owner:** Exeris TCK/Test
- **Description:** Implements ROADMAP-tracked gap. Add `CommunityFlowZeroAllocTckTest` extending `FlowZeroAllocTck`. Assert low-allocation / zero-allocation behaviour on the hot scheduler path for the Community tier. Allocation budget must be defined explicitly in the test.
- **Acceptance:** CI passes; allocation budget documented in test.

---

#### Validation Gates for EPIC-1 through EPIC-4

| Gate | Trigger |
|:-----|:--------|
| Architecture sign-off: FLOW-101, FLOW-102, EVENT-201, DIST-301 | **Blocker** — no implementation begins until all four decisions are recorded |
| `AbstractSagaRecoveryTck` + `AbstractDistributedFlowSnapshotStoreTck` CI pass | **Blocker** before FLOW-104 merges |
| `AbstractKafkaEventEngineTck` CI pass (Testcontainers) | **Blocker** before EVENT-204 merges |
| Performance/Memory audit of FLOW-104, FLOW-105, EVENT-204 | **Required** — distributed paths must not allocate on the in-memory fast path |
| ADR-013 review and sign-off (DIST-301) | **Required** before any cross-service saga state work starts; required before v1.0 |
| `flow.md` and `events.md` updated | **Required** after each EPIC merge |

---

## Known Gaps / Future Work planned for v0.8

### Quality: PMD Suppression Reduction Continuation (QA-010..018)

**Gap:** The aspirational v0.7 target of ≤ 100 PMD suppressions in main code was not reached. QA-008 closed one `GodClass` (`CommunityGraphCypherHelper`) in Sprint 8d using the `Executor` + Reader + Writer + Identifier helpers pattern; nine remaining `GodClass`-flagged classes still carry suppressions.

**Owner:** Cross-cutting quality backlog.

**Resolution:** Apply the QA-008 decomposition pattern to each remaining `GodClass`, one PR per class, preserving transactional/lifecycle cohesion via shared service interfaces:

- QA-010 `CommunityPersistenceEngine`
- QA-011 `CommunityHttpRequestProcessor`
- QA-012 `Slf4jTelemetrySink`
- QA-013 `NativeTcpCarrier`
- QA-014 `OutboxOrchestrator`
- QA-015 `CommunityHttpClientEngine`
- QA-016 `NativeTcpStream`
- QA-017 `JdbcFlowSnapshotStore`
- QA-018 `CommunityHttp2SessionProcessor` and `SubsystemOrchestrator`

**Merge Gate:** Each PR closes at minimum one `GodClass` suppression; cumulative effect must move the main-source suppression count toward the ≤ 100 target. Re-evaluate the target at v0.8 close.

**Status (v0.8):** **DELIVERED** in Sprint 1 / Sprint 3 — all nine decompositions landed (QA-010 `CommunityPersistenceEngine`, QA-011 `CommunityHttpRequestProcessor`, QA-012 `Slf4jTelemetrySink`, QA-013 `NativeTcpCarrier` → `NativeTcpSocketBackend`/`NativeTcpProbe` + `NativeTcpReactor`, QA-014 `OutboxOrchestrator`, QA-015 `CommunityHttpClientEngine`, QA-016 `NativeTcpStream`, QA-017 `JdbcFlowSnapshotStore` codec extract, QA-018 `CommunityHttp2SessionProcessor` four seams + `SubsystemOrchestrator` with ADR-026 amendment record). Refactor-only; per-PR detail in git log.

---

### Runtime: Hot-Path Collections Watch-Items (Sprint 8e carry-over)

**Gap:** The Sprint 8e Hot-Path Collections Review (`docs/release/hot-path-collections-review.md`) discharged the v0.7 gate with no replacements but recorded three watch-items for v0.8 if a future profile shows real contention.

**Owner:** Core / Flow / Transport subsystem.

**Resolution:** Each watch-item is gated on a measured profile signal — do not implement speculatively:

- **Idempotency-guard inner-map → packed `volatile long[]` bitmap** once plan-compile-time step count is reachable from `CoreIdempotencyGuard`. Removes inner `ConcurrentHashMap` and `Integer` autobox; gated on a measured allocation hit on the dispatch path.
- **`pendingWriteInterest` identity keyset** (NIC reactor) only if a stress profile shows the CHM keyset as a hot CAS site.
- **Parked-lookup miss-cache as ring buffer** only if the lock-guarded `ArrayDeque` shows up under contended bridge-miss workloads.

**Merge Gate:** Any adoption must remain Community-internal, keep SPI/Core contracts unchanged, and show measurable benefit under representative profiling — same gate as the parent "Runtime: Hot-Path Collections Review" entry.

**Status (v0.8):** **CARRIED** — each watch-item is gated on a measured profile signal that did not materialise in v0.8 (do-not-implement-speculatively). Re-evaluated at v0.9. (PERF-072 ingress-read elision below is the one allocation win that *was* signalled and landed.)

---

### Transport: Zero-Allocation Ingress Read — Elide Per-Read Segment Slice (Sprint 6 PERF-072)

**Gap:** The plain-socket ingress read path allocates a fresh heap `NativeMemorySegmentImpl` wrapper on **every** read. `NativeTcpCarrier.readIngress` calls `NativeTcpStream.readPlainIngress(slab.segment(), (int) slab.capacity())`, where `slab.capacity() == slab.segment().byteSize()` (`AbstractLoanedBuffer.capacity()`). Both seam and NIO read paths in `NativeTcpStreamPlainSocketIo` then evaluate `target.asSlice(0, maxBytes)` — but since `maxBytes == target.byteSize()`, the slice is base- and length-identical to `target`: a pure wrapper allocation with no semantic effect. A constrained `entity-read-by-id` JFR profile (`-XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xmx192m`, ~3.1k rps) attributed ~2,400 `ObjectAllocationSample` hits to `asSliceNoCheck`/`dup` under `trySocketSeamRead → readPlainIngress → readIngress`. STW pause is negligible (37 ms over 46 s) but the run was CPU-throttled 465/547 cgroup periods, so allocation→GC CPU (470 ms) competes directly with request servicing — reducing it tightens the throttled tail rather than the pause budget.

**Owner:** Transport subsystem (`exeris-kernel-community`).

**Resolution:** Guard the slice so it materialises only for a genuine sub-range: `MemorySegment dst = maxBytes == target.byteSize() ? target : target.asSlice(0, maxBytes)`, passing `dst` to the `recv` downcall (length is already a separate argument, so the full segment is safe) and to the NIO-fallback `asByteBuffer()`. The guard is also semantically required on the NIO path — a `ByteBuffer` over the full segment would have `capacity == byteSize`, letting `channel.read()` overrun `maxBytes`; eliding only on equality keeps behaviour identical. Egress `send`/write paths pass a real `(offset, length)` sub-range and are intentionally left unchanged.

**Merge Gate:** Community-internal only — no SPI/Core contract change, ownership and loaned-buffer lifecycle unchanged. Existing transport read/write coverage stays green (`NativeTcpClientServerE2eIntegrationTest`, stream wakeup tests). Validation: a re-run of the constrained `entity-read-by-id` JFR shows `NativeMemorySegmentImpl`/`asSliceNoCheck`/`dup` samples under the ingress read path drop to ~0; a JMH `-prof gc` harness over `readIngress` asserts `gc.alloc.rate.norm ≈ 0 B/op` on the seam-read path.

**Status (v0.8):** **DELIVERED** in Sprint 6 (PERF-072) — the full-slab `asSlice` wrapper is elided on the ingress read path; egress sub-range slices unchanged. Community-internal, no SPI/Core change.

---

### Security: `@RequiresRole` Operator Wiring

**Gap:** ADR-014 compile-time RBAC machinery is in place (Sprints 8a / 8b-i / 8b-ii) but the operator-side wiring deferred from Sprint 8b-ii has not landed: the `LazyConstant<RoleCheckRegistry>` bootstrap loader, automatic discovery and wiring of the APT-generated registry into runtime, and `ImmutablePrincipal.roleMask()` integration with the CitadelGuard fast path.

**Owner:** Core / Bootstrap / Security subsystem.

**Resolution:** Wire the APT-generated `RoleCheckRegistry` into the runtime via a `LazyConstant` bootstrap loader so consumers do not need to construct the registry manually. Integrate `ImmutablePrincipal.roleMask()` so `@RequiresRole`-annotated entry points resolve through the bitmask path without falling back to `CitadelGuard.requireRole`.

**Merge Gate:** Bootstrap regression tests pass with autoload; zero-allocation TCK continues to pass on the wired path.

**Status (v0.8):** **DELIVERED** in Sprint 4 (SEC-080, ADR-014 §3) — `GeneratedRoleRegistryLoader` resolves the APT-generated `RoleCheckRegistry` reflectively (FQN, no compile edge), binds its accessors to `MethodHandle`s once at bootstrap as a `RoleRegistry`, fail-closed empty singleton when no `@RequiresRole` is compiled. `SecurityInterceptor` populates `roleMask` via a Core-internal `MaskedPrincipal`; `RoleRegistryLoaded` JFR added. **Descoped:** kernel-edge URL→`methodId` enforcement is an `exeris-tooling` codegen concern (dispatcher is path/scope-based), not `CommunityHttpRequestDispatcher`. New TCKs: `AbstractGeneratedRoleRegistryLoaderTck` + `AbstractRoleMaskPopulationTck`.

---

### HTTP/2: Community Lifecycle Hardening

**Gap:** Community HTTP/2 support is already exercised in real scenarios, but stream lifecycle, shutdown/drain behavior, and ownership boundaries between transport and HTTP still need deeper hardening for production-candidate use.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Harden stream table ownership, stream lifecycle transitions, GOAWAY/drain behavior, and concurrency controls on the Community transport path.

**Merge Gate:** Lifecycle and shutdown/drain behavior validated by regression/integration tests.

**Status (v0.8):** **PARTIAL** — RFC 7540 §5.1.1/§5.1.2 stream-identifier admission landed in Sprint 5 (HTTP-112, `Http2SessionContextAdmissionTest`) and the h2c upgrade path was fixed (see next entry). Deeper GOAWAY/drain ownership and concurrency-control hardening remain ongoing.

---

### HTTP/2: h2c Upgrade Path RFC 7540 §3.2 Compliance (Sprint 6 HTTP-137)

**Gap:** `CommunityHttp2SessionProcessor.handleUpgrade` (`exeris-kernel-community/src/main/java/eu/exeris/kernel/community/http/CommunityHttp2SessionProcessor.java:103-114`) violates RFC 7540 §3.2 by entering the HTTP/2 frame loop immediately after the `101 Switching Protocols` response. Three required transitions are missing:

1. **Connection preface not consumed.** After `101`, the client MUST send the 24-byte preface `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n` before any frame. `handleUpgrade` skips straight to `processBufferedHttp2Frames`, which parses the first 9 bytes (`PRI * HTT`) as a frame header in `Http2FrameParser.parseAndValidate` — the parse throws, the loop returns `HTTP2_FRAME_LOOP_INVALID`, and the processor sends `GOAWAY PROTOCOL_ERROR` with `last_stream_id=0`. Symptom: `curl --http2 http://localhost/...` exits with `GOAWAY error=1 last_stream_id=0` on the first request.
2. **`HTTP2-Settings` header not applied.** `CommunityHttpH2cUpgradeDetector.isH2cUpgradeIntent` matches on `Upgrade: h2c` + `Connection: Upgrade` only — the base64url-encoded `HTTP2-Settings: ...` payload from the original HTTP/1.1 request is discarded instead of being decoded and applied as the client's initial SETTINGS frame via `Http2SessionContext.processSettingsPayload`.
3. **Original request not synthesized as stream 1.** RFC 7540 §3.2.1 requires the upgraded HTTP/1.1 request to be assigned stream identifier 1 with the client side implicitly half-closed; the response is then written on stream 1 as HEADERS + DATA + END_STREAM. The current implementation neither dispatches the originating request nor reserves the stream identifier, leaving the upgraded request silently dropped if the preface bug above is fixed in isolation.

Prior-knowledge HTTP/2 (`handlePriorKnowledge` lines 88-101) is unaffected — it is invoked only after `CommunityHttpH2cUpgradeDetector.isHttp2PriorKnowledgePreface` has matched the 24-byte preface, so the preface bytes are naturally outside the frame-loop window when control transfers. ALPN-negotiated HTTPS HTTP/2 reaches the same prior-knowledge path and is also unaffected. Scope therefore narrows to cleartext h2c Upgrade only — `curl --http2`, k6, wrk2 against cleartext endpoints, and dev/loopback flows.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Three coordinated changes landing as a single PR:

1. **Detector — extract `HTTP2-Settings`.** Extend `CommunityHttpH2cUpgradeDetector.isH2cUpgradeIntent` (or a sibling extractor method) to capture the `HTTP2-Settings` header value when the upgrade intent is detected; thread the base64url-decoded payload and the parsed originating `HttpRequest` through `handleHttp1UpgradeToH2c` into `CommunityHttp2SessionProcessor.handleUpgrade`.
2. **Processor — consume preface + apply peer SETTINGS + synthesize stream 1.** Rewrite `handleUpgrade` so that, after writing `101 Switching Protocols`, it: (a) validates and consumes the 24-byte preface from the aggregate (or buffers additional reads until the preface is fully received), with mismatch → `GOAWAY PROTOCOL_ERROR last_stream_id=0`; (b) applies the decoded `HTTP2-Settings` payload via the existing `Http2SessionContext.processSettingsPayload(MemorySegment, int)` before entering the frame loop; (c) dispatches the original `HttpRequest` directly through `CommunityHttpRequestDispatcher` (no codec round-trip), writes the response back as `HEADERS` + `DATA` with `stream_id=1` and `END_STREAM`, and seeds `Http2SessionContext.lastProcessedStreamId = 1` so subsequent client streams must use odd identifiers `> 1` per RFC 7540 §5.1.1.
3. **TCK — adversarial h2c upgrade scenarios.** Extend `AbstractHttpServerEngineTck` (or `AbstractHttpProviderTck` if more appropriate) with a parameterized h2c upgrade scenario that performs the full `curl --http2`-equivalent flow over an in-process socket: HTTP/1.1 `POST` with `Upgrade: h2c`, `Connection: Upgrade`, `HTTP2-Settings: <base64url>`; expect `101 Switching Protocols`; send preface + (no further frames, the request body already crossed in HTTP/1.1 form); expect response on stream 1 carrying `END_STREAM`. Additional adversarial cases: corrupted preface (`PRI * HTTP/9.9...`), missing preface (TCP close before 24 bytes received), malformed `HTTP2-Settings` payload (odd byte length, unknown setting IDs handled per the existing `Http2SessionContext` rules).

**Merge Gate:** `curl --http2 http://localhost:<port>/...` against a `CommunityHttpServerEngine` returns the expected response (no `GOAWAY error=1`); h2c upgrade TCK scenarios green (positive + adversarial); HTTP/2 prior-knowledge tests remain green (no regression on the unaffected path); stream identifier accounting per RFC 7540 §5.1.1 verified via TCK assertion that stream 3 sent by the client immediately after upgrade is accepted while stream 2 is rejected with `PROTOCOL_ERROR`.

**Status (v0.8):** **DELIVERED** in Sprint 6 (HTTP-137) — `CommunityHttpH2cUpgradeDetector` extracts `HTTP2-Settings`; the processor consumes the connection preface, applies peer SETTINGS, and synthesises the original request as stream 1 per RFC 7540 §3.2. Cleartext `curl --http2` no longer fails with `GOAWAY error=1`; prior-knowledge / ALPN paths unaffected.

---

### HTTP/2: Rapid Reset (CVE-2023-44487) Flood Defense

**Gap:** `CommunityHttp2SessionProcessor` handles each inbound `RST_STREAM` per-frame (`CommunityHttp2SessionProcessor.java:243`), and `Http2SessionContext.resetRequestStream` frees the stream's concurrency slot. There is **no per-window `RST_STREAM` budget** — a peer can open-then-immediately-reset streams without bound (the freed slot means `SETTINGS_MAX_CONCURRENT_STREAMS` is never reached), forcing unbounded request setup/teardown work. This is CVE-2023-44487 (the 2023 HTTP/2 Rapid Reset DoS). Surfaced by the v0.9 Sprint 4c Phase 2 adversarial sweep.

**Layer note — distinct from `TransportStream.reset(long)` (downstream issue #23):** this is an **inbound HTTP/2 codec** flood defense (peer floods *us* with `RST_STREAM` *frames* over one TCP stream); the transport-stream abort SPI is an **outbound transport-layer** capability (QUIC stream reset / TCP abortive close). Same word, different layer and direction — neither blocks the other. See the "Transport-Agnostic Stream Abort" entry.

**Owner:** HTTP subsystem (Community codec).

**Resolution:** Add a per-connection sliding-window `RST_STREAM` counter to `Http2SessionContext`; once inbound resets exceed a configurable budget within the window, emit `GOAWAY(ENHANCE_YOUR_CALM)` and stop admitting new streams on the connection. Surface a config knob (default tuned to a generous-but-finite per-connection reset rate) and a secret-safe JFR flood event (counts + connection id only). The executable spec already exists: `Http2RapidResetSpecTest` (`@Disabled`) encodes the open+reset flood pattern and the expected throttle — enable it when the seam lands.

**Merge Gate:** `Http2RapidResetSpecTest` enabled and green; config knob documented; JFR flood event emitted under attack and silent under normal load; no regression on legitimate `RST_STREAM` (client cancel) within budget.

**Status (v0.9):** **PLANNED** — Phase 2 committed the `@Disabled` executable spec + this hardening entry; implementation is a dedicated follow-up (codec-internal, no SPI change).

---

### Persistence: Latency-Keyed Admission Fairness Gate (ADR-035 follow-up)

**Gap:** ADR-035 recalibrated Community admission so a full pool sheds only once `pendingAcquires > ceil(maxPool × queueDepthAllowanceRatio)` (default ratio `8.0`). Because *all* shed branches in `CommunityPersistenceAdmissionController` — including `REJECT_GUARD_BAND_FAIRNESS` and the early-guard-band check — are gated behind that single queue-depth allowance, the fairness/guard-band machinery (`FairnessTracker.indicatesAdmissionStress`, `shouldRejectEarlyInGuardBand`) is effectively dormant under the default ratio: it only re-arms when an operator lowers `queueDepthAllowanceRatio`, which *also* sheds the small-pool burst the recalibration exists to admit. The two shed signals (depth-based backpressure and fairness) cannot be tuned apart through one scalar knob. See ADR-035 §Consequences ("Fairness/guard-band machinery is intentionally dormant under the default ratio").

**Owner:** Core / Persistence subsystem.

**Resolution:** Gate the fairness/guard-band shed path on an observed wait-time signal (`queueWaitP95`, already computed by `FairnessTracker.computeSnapshot()` and emitted on `AdmissionDecisionEvent`) instead of on `queueDepthAllowance`. This lets sustained fairness inversion shed independently of `queueDepthAllowanceRatio`, so depth-based small-pool availability and latency-based fairness become separately tunable. Keep the decision non-blocking and zero-allocation on the hot path; expose any new threshold as a `persistence.admission.*` key consistent with the ADR-035 tunable surface.

**Merge Gate:** Community admission tests prove fairness sheds under sustained `queueWaitP95` stress while the default `queueDepthAllowanceRatio=8.0` still admits a transient small-pool burst (the constrained-benchmark regression guard stays green). No SPI field added — The Wall unchanged. Enterprise binding obligation per ADR-035 still applies.

**Status (v0.8):** **CARRIED TO v0.9** — Sprint 0b forward-ported the ADR-035 admission **tunability** (`persistence.admission.*`, depth-allowance shedding) from v0.7.1, but the latency-keyed (`queueWaitP95`) fairness/guard-band shed gate described here is not yet implemented; it remains a v0.9 follow-up.

---

### Persistence: Latency-Keyed Admission Fairness Gate (ADR-035 follow-up)

**Gap:** ADR-035 recalibrated Community admission so a full pool sheds only once `pendingAcquires > ceil(maxPool × queueDepthAllowanceRatio)` (default ratio `8.0`). Because *all* shed branches in `CommunityPersistenceAdmissionController` — including `REJECT_GUARD_BAND_FAIRNESS` and the early-guard-band check — are gated behind that single queue-depth allowance, the fairness/guard-band machinery (`FairnessTracker.indicatesAdmissionStress`, `shouldRejectEarlyInGuardBand`) is effectively dormant under the default ratio: it only re-arms when an operator lowers `queueDepthAllowanceRatio`, which *also* sheds the small-pool burst the recalibration exists to admit. The two shed signals (depth-based backpressure and fairness) cannot be tuned apart through one scalar knob. See ADR-035 §Consequences ("Fairness/guard-band machinery is intentionally dormant under the default ratio").

**Owner:** Core / Persistence subsystem.

**Resolution:** Gate the fairness/guard-band shed path on an observed wait-time signal (`queueWaitP95`, already computed by `FairnessTracker.computeSnapshot()` and emitted on `AdmissionDecisionEvent`) instead of on `queueDepthAllowance`. This lets sustained fairness inversion shed independently of `queueDepthAllowanceRatio`, so depth-based small-pool availability and latency-based fairness become separately tunable. Keep the decision non-blocking and zero-allocation on the hot path; expose any new threshold as a `persistence.admission.*` key consistent with the ADR-035 tunable surface.

**Merge Gate:** Community admission tests prove fairness sheds under sustained `queueWaitP95` stress while the default `queueDepthAllowanceRatio=8.0` still admits a transient small-pool burst (the constrained-benchmark regression guard stays green). No SPI field added — The Wall unchanged. Enterprise binding obligation per ADR-035 still applies.

---

### Flow: Production Correctness Hardening

**Gap:** Flow has proven product importance, but durability/recovery and E2E correctness confidence still need to move from exploratory evidence to production-candidate confidence.

**Owner:** Flow subsystem.

**Resolution:** Add restart-under-load tests, drain-semantics tests, durability benchmarks, and stronger validation of unresolved vs failed vs compensated outcomes.

**Merge Gate:** Restart/recovery and outcome correctness suites become mandatory CI gates.

**Status (v0.8):** **DELIVERED** in Sprint 7 (FLOW-110) — restart-under-load and outcome-correctness (unresolved vs failed vs compensated) Flow TCK suites added and promoted to mandatory CI gates. No SPI change. Follow-up: the `closeTimeoutNanos` aggregate-bound drain-semantics refinement carries to v0.9.

---

### Events: Backpressure and Projection Confidence

**Gap:** Events need stronger runtime confidence in their actual supported role for outbox/projection/Flow integration.

**Owner:** Events subsystem.

**Resolution:** Harden queue behavior, projection consistency, retry/dead-letter visibility, and event/Flow coordination under load.

**Merge Gate:** Backpressure and projection consistency tests pass at community target load.

**Status (v0.8):** **PARTIAL** — Sprint 5 added bounded-queue overflow visibility (EVENT-111 `CommunityEventQueueOverflowEvent` JFR) and Sprint 6 made the Kafka integration tests a CI gate (C-P0-02). The broader projection-consistency / retry / dead-letter-visibility-under-load hardening remains open and carries forward.

---

### Graph: Baseline Production Hardening

**Gap:** Graph already works and participates in real scenarios, but CI/TCK and operational confidence for baseline PGQ/Bolt paths still need hardening.

**Owner:** Graph subsystem.

**Resolution:** Prioritize baseline driver stability, correctness tests for exercised PGQ/Bolt paths, resource-usage visibility, and CI coverage for real product scenarios. Avoid broadening Graph scope beyond practical paths already in use.

**Merge Gate:** Baseline Graph correctness/stability tests pass in CI.

**Status (v0.8):** **DELIVERED** in Sprint 7 (GRAPH-111) — `ExecutionGraphZeroAllocTck` and `GraphChurnRatioTck` bound with Community bindings (`CommunityExecutionGraphZeroAllocTckTest`, `CommunityGraphChurnRatioTckIT`); fixed a latent JDK-26 `ObjectAllocationSample` defect in the abstract churn TCK. No SPI change.

---

### HTTP Server: Request Body Decode SPI — Completing the Codec Matrix (Sprint 7 HTTP-138 + ADR-036)

**Gap:** The HTTP body-codec surface is a 2×2 matrix — {request, response} × {encode, decode}. Three quadrants have SPI seams: `HttpRequestBodyEncoder` (0.8.0, ADR-034 — client sends), `HttpResponseBodyEncoder` (0.5.0, ADR-009 — server sends), `HttpResponseBodyDecoder` (0.8.0, ADR-034 — client reads). The fourth — server-side request-body **decode** — has none. `exeris-tooling/KernelHandlerGenerator.buildParseBody` inlines a static Jackson `MAPPER` + `JacksonException` catch into every generated controller (`LoanedBuffer → byte[] → String → readValue`): a build-time Wall breach (a concrete codec symbol baked into application source) plus a double-allocation on the server ingress hot path. The client side resolves cleanly from a registry (`KernelWebClient.decodeSuccessBody`); the server has no symmetric seam, so the asymmetry is real and has a design consequence.

**Owner:** kernel/transport. **Decision:** ADR-036 (server-side request body decoder SPI), resolution site (B) — the generated handler resolves; the kernel router stays type-blind.

**Resolution:**
- **HTTP-138 SPI triplet + driver (kernel).** Add `eu.exeris.kernel.spi.http.HttpRequestBodyDecoder` (`supports(Class<?>, String)`, `decode(LoanedBuffer, Class<?>, HttpRequestDecodingContext)`, `priority()`), `HttpRequestBodyDecoderRegistry` (`@FunctionalInterface resolve` + `empty()` + `of(List)` descending-priority stable sort), and the `HttpRequestDecodingContext` record (`method, path, headers, allocator`) — mirroring the response-decoder triplet verbatim, generics-free with a single confined cast at the resolution site. Wiring mirrors ADR-034: `HttpProvider.requestBodyDecoderRegistry()` default + `HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY` ScopedValue slot + `Optional` accessor; bootstrap via `CommunityHttpProvider`. Community driver `CommunityJsonRequestBodyDecoder` keeps Jackson in the driver (SPI never sees `ObjectMapper` — The Wall, ADR-006). Observable status mapping is preserved by the generated handler (decode failure → `400`, unresolved decoder → `5xx`; no new `415` negotiation — deferred v0.9). `AbstractHttpRequestBodyDecoderTck` + Community binding, CI-bound (no orphan).
- **TOOL-139 `KernelHandlerGenerator` rewrite (cross-repo `exeris-tooling`).** Replace the inline `MAPPER.readValue` path in `buildParseBody` with `requestBodyDecoderRegistry.resolve(type, contentType).decode(body, type, ctx)`, dropping the static Jackson field + `JacksonException` constant from emitted code and letting the decoder consume the off-heap segment directly (eliminates the `byte[] + String` double-allocation on server ingress). Lockstep — a generated-output contract change; lands as a separate `exeris-tooling` PR sequenced after kernel-side SPI publication. Plus e2e fixture update + `docs/adr/ADR-036.link.md` stub. `exeris-kernel-enterprise` gets a one-line `ADR-036.link.md` stub.

**Merge Gate:** SPI triplet + `CommunityJsonRequestBodyDecoder` + `AbstractHttpRequestBodyDecoderTck` Community binding green and CI-bound; ADR-036 reserved in `~/exeris-systems/exeris-docs/adr-index.md` before content (done 2026-06-03); `exeris-tooling` generator PR opened and reviewed (does not block the kernel merge — the generator update lands in a subsequent kernel-snapshot consumption cycle, per the Sprint 6 HTTP-130..136 / TOOL-136 precedent).

**Status (v0.8):** **DELIVERED** in Sprint 7 (HTTP-138, ADR-036) — `HttpRequestBodyDecoder` + `HttpRequestBodyDecoderRegistry` + `HttpRequestDecodingContext` landed in `eu.exeris.kernel.spi.http`, completing the fourth (and final) body-codec quadrant. Community driver `CommunityJsonRequestBodyDecoder` keeps Jackson behind the SPI; `JsonBodyCodecs` extracted for dedup. `AbstractHttpRequestBodyDecoderTck` + Community binding CI-bound. The Wall held. **Lockstep CARRIED TO v0.9 (snapshot-gated):** the `exeris-tooling` `KernelHandlerGenerator` rewrite (TOOL-139, #67) and the `exeris-kernel-enterprise` ADR-036 stub (#30) consume the published SPI in a later snapshot cycle.

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

### HTTP: WebSocket and Server-Sent Events as Stream Handlers

**Gap:** Long-lived streaming endpoints (WebSocket per RFC 6455, Server-Sent Events per HTML Living Standard) are documented at TRL-5 in the comprehensive technical document but no `StreamHandler` variant exists in the Community HTTP runtime. The current request lifecycle assumes one request → one response; there is no contract for half-duplex (SSE) or full-duplex (WebSocket) frame routing, no upgrade negotiation path through `Http1RequestParser`, and no carrier-pinning policy for the long-lived virtual thread holding the upgraded stream.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Define a `StreamHandler` SPI variant in `exeris-kernel-spi` that takes ownership of a stream after the handshake (WebSocket frame parser/serializer; SSE event emitter); implement Community bindings backed by the existing `LoanedBuffer` ingress path; specify and enforce carrier-pinning constraints so long-lived handlers do not starve the carrier loop; emit JFR lifecycle events (`StreamHandlerAttachedEvent`, `StreamHandlerDetachedEvent`).

**Merge Gate:** Abstract TCK for upgrade-negotiation contract and frame ordering passes; Community binding green; carrier-pinning regression test demonstrates no PAQS starvation under N concurrent long-lived streams.

---

### HTTP: gRPC Streaming over HTTP/2

**Gap:** The comprehensive technical document targets gRPC streaming at TRL-5 as an HTTP/2 stream variant. The current `CommunityHttp2SessionProcessor` handles HTTP/2 framing but has no gRPC-aware path: no `application/grpc` content-type routing, no length-prefixed message framing (5-byte LP), no trailers handshake (`grpc-status` / `grpc-message`), no server-streaming / client-streaming / bidirectional-streaming variants.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Add a gRPC-aware request adapter on top of `CommunityHttp2SessionProcessor` that recognises `application/grpc` content type, exposes a message-framed `StreamHandler` view (5-byte LP decoder/encoder), routes status via HTTP/2 trailers, and emits structured error codes (`EX-HTTP-43xx-GRPC-*`). Keep gRPC routing isolated in a new `eu.exeris.kernel.community.http.grpc` package; no gRPC types leak into Core or SPI.

**Merge Gate:** Interop tests against a reference gRPC client (e.g. `grpcurl`) cover unary, server-streaming, client-streaming, and bidirectional-streaming cases; HTTP/2 conformance tests remain green; abstract TCK for gRPC message framing passes.

---

### HTTP: Chunked Upload Aggregate Buffer Support

**Gap:** The comprehensive technical document targets at TRL-6 complete HTTP chunked-upload handling — the streaming-upload code path that aggregates chunked transfer-encoded request bodies into a bounded request-scoped aggregate buffer without single-allocation bloat or unbounded memory growth. Today the Community HTTP path handles fixed `Content-Length` bodies but lacks a production-grade `Transfer-Encoding: chunked` ingress aggregator with admission-controlled growth.

**Owner:** HTTP / Transport / Memory subsystem.

**Resolution:** Implement a chunked-body aggregator backed by `LoanedBuffer` chains with bounded growth checks tied to `WatermarkManager`; reject oversized aggregate bodies with `413 Payload Too Large` and a structured error code; surface the request-scoped aggregate via the existing handler API without forcing a single contiguous segment; correctly handle trailers per RFC 9112.

**Merge Gate:** Abstract HTTP TCK for chunked upload covers correct aggregation, trailer parsing, oversized-body rejection, and admission-control interaction; Community binding green; zero-leak assertion on the `LoanedBuffer` chain under PARANOID mode.

---

### HTTP Client: Generator-Support SPI Primitives (Sprint 6 HTTP-130..136 + ADR-032)

**Gap:** ADR-026 (`CommunityWebClient`, amended 2026-05-17) landed the typed HTTP-verb façade on top of `HttpClientEngine` SPI, but `exeris-tooling/KernelClientGenerator` emits per-entity client code that bypasses safety primitives the kernel does not yet provide: path-segment substitution is done as raw `BASE_PATH + "/" + id` (no RFC 3986 §2.3 percent-encoding — `id="../admin"` traverses the server-side route matcher), query strings are built as `"?page=" + page + "&size=" + size` (no escaping of filter values containing `&` or `=`, no nullable-filter skip), and outbound multi-tenant propagation is absent (no header enricher reads `KernelProviders.PRINCIPAL_CONTEXT` for `X-Tenant-Id` / `X-Principal-Id`). The single status-mapping predicate `WebClientException.isNotFound()` covers the one case the generator emits today but leaves no affordance for callers writing client code by hand. The `HttpClientEngine` factory/DI hook is verified existing (`HttpProvider.createClientEngine`, `HttpKernelProviders.HTTP_CLIENT_ENGINE` ScopedValue + `httpClientEngine()` accessor, `CommunityHttpSubsystem` ServiceLoader binding) — only documentation is missing. Two further gaps — symmetric body codec (Gson/Moshi/native alternatives to Jackson 3) and retry/backoff policy — are deferred to v0.9 (see entries below).

**Owner:** HTTP / Transport subsystem + cross-repo coordination with `exeris-tooling`.

**Resolution:** Six in-repo work items plus one cross-repo follow-up, all landing in Sprint 6 alongside the audit-absorbed quick-wins:

- **HTTP-130 `UriTemplate` SPI.** `eu.exeris.kernel.spi.http.UriTemplate` — immutable record exposing `of(String)`, `resolve(Map<String, ?>)`, `resolve(String, Object)`, and `escapePathSegment(String)`. RFC 3986 §2.3 unreserved-character passthrough; `/` in a substituted value is escaped to `%2F` to prevent path-segment traversal; missing variable throws `IllegalArgumentException` with the variable name. `AbstractUriTemplateTck` covers reserved-character round-trip, multi-variable substitution, missing/extra/null variable, and adversarial inputs (`id="../admin"`, `id="foo%2Fbar"`).
- **HTTP-131 `QueryParams` SPI.** `eu.exeris.kernel.spi.http.QueryParams` — fluent builder exposing `empty()`, `add(String, Object)` (null value → skip, idiomatic for nullable filters), `addAll(String, List<?>)` (multi-value `?tag=a&tag=b`), `render()` (returns `""` when empty, otherwise `"?k1=v1&k2=v2"` with RFC 3986 query-component percent-encoding), and `isEmpty()`. Insertion-order preservation for deterministic cache keys + test stability. `AbstractQueryParamsTck` covers round-trip against the existing server-side query parser, multi-value, null-value skip semantics, and adversarial inputs (`value="evil&injected=1"`).
- **HTTP-132 `WebClientException` predicate expansion (Community).** Add `isClientError()` (4xx), `isServerError()` (5xx), `isUnauthorized()` (401), `isForbidden()` (403), `isConflict()` (409), `isValidationError()` (422) to the existing `CommunityWebClient.WebClientException` inner class. No SPI surface change; no new exception types. Existing `isNotFound()` retained. Generator continues to use only `isNotFound()` for now; the additional predicates exist as ergonomic affordance for hand-written client code.
- **HTTP-133 `HttpClientRequestEnricher` SPI + ADR-032.** `eu.exeris.kernel.spi.http.HttpClientRequestEnricher` — functional interface `HttpRequest enrich(HttpRequest)` with `noop()` and `chain(List<...>)` factories. Contract enforces immutable rebuild (returns a new `HttpRequest` record), zero body interaction (`LoanedBuffer` ownership untouched), and CR / LF / NUL rejection in header values (CWE-93 outbound symmetry with Security S-P0-04). `AbstractHttpClientRequestEnricherTck` covers chain composition, header-injection rejection, `ScopedValue` read-when-bound vs noop-when-unbound semantics, and null-input rejection. ADR-032 documents the contract and the rejected alternatives.
- **HTTP-134 `CommunityKernelContextEnricher` (Community, opt-in).** Default-bundled enricher in `eu.exeris.kernel.community.http.client` reading `KernelProviders.PRINCIPAL_CONTEXT` to add `X-Tenant-Id` (from `tenantId().orElse-skip`) and `X-Principal-Id` (from `principalId()`). Unbound ScopedValue → silently skip; never throw. Bearer-token forwarding intentionally out-of-scope — the kernel does not hold the raw token (`PrincipalContext` is the parsed identity, not the JWT); applications shipping outbound Bearer must compose a custom enricher reading their own token store. W3C `traceparent` header deferred until the consolidated 1.0 GA roadmap Sprint 0.12 lands the `TraceContext` ScopedValue slot.
- **HTTP-135 `CommunityWebClient` new constructor + DI Javadoc.** Add `CommunityWebClient(HttpClientEngine, MemoryAllocator, ObjectMapper, HttpClientRequestEnricher)` as the four-arg constructor; the existing three-arg constructor delegates with `HttpClientRequestEnricher.noop()` to preserve ADR-026's "no implicit behaviour" surface. Extend `CommunityWebClientIntegrationTest` to assert tenant-scoped requests arrive at the in-process handler with `X-Tenant-Id` headers. Add `community/http/client/package-info.java` documenting the canonical engine acquisition pattern: `HttpKernelProviders.httpClientEngine().orElseThrow(...)` (engine is bound by `CommunityHttpSubsystem` when `HttpConfig.mode()` is `CLIENT` or `DUAL`) and a four-arg construction example showing `chain(...)` composition with a hypothetical `BearerForwardingEnricher`.
- **TOOL-136 `KernelClientGenerator` emission update (cross-repo `exeris-tooling`).** Update `exeris-tooling/exeris-codegen-java/src/main/java/eu/exeris/tooling/codegen/java/kernel/KernelClientGenerator.java` to emit `UriTemplate.of("/api/v1/widgets/{id}").resolve("id", id)` in place of `BASE_PATH + "/" + id` (lines 107, 173, 185 in current source) and `QueryParams.empty().add("page", page).add("size", size).render()` in place of `BASE_PATH + "?page=" + page + "&size=" + size` (line 131). Generator's `WEB_CLIENT` `ClassName` constant already migrated to `CommunityWebClient` via ADR-026 amendment; this change pins the emitted code to the new SPI primitives without touching that import. Lands as a separate PR in `exeris-tooling`, sequenced after the kernel-side SPI publication.

**Merge Gate:** All three new SPI primitives have abstract TCKs + Community bindings green; `CommunityWebClientIntegrationTest` covers four-arg-constructor with enricher; ADR-032 registered in `~/exeris-systems/exeris-docs/adr-index.md` before content lands; `exeris-tooling/KernelClientGenerator` PR opened and reviewed (does not block kernel merge — generator update lands in a subsequent kernel-snapshot consumption cycle).

**Status (v0.8):** **PARTIAL** — Delivered in Sprint 6: HTTP-133 `HttpClientRequestEnricher` SPI + `AbstractHttpClientRequestEnricherTck` + **ADR-032**; HTTP-132 `WebClientException` status predicates (`isNotFound`/`isClientError`/`isServerError`/`isConflict`/`isValidationError`/…). The verb facade itself was re-placed by **ADR-034** as the tier-neutral `KernelWebClient` in **Core** (`eu.exeris.kernel.core.http.client`), superseding the ADR-026 `CommunityWebClient` placement, and ships alongside the client-side body-codec SPI (`HttpRequestBodyEncoder` / `HttpResponseBodyDecoder` + registries + contexts). **CARRIED TO v0.9:** HTTP-130 `UriTemplate` SPI and HTTP-131 `QueryParams` SPI were **not** implemented (no such types in the source tree), and the HTTP-134 `CommunityKernelContextEnricher` default-bundled enricher did not land (only the enricher SPI + TCK). The TOOL-136 generator emission update follows the same snapshot-gated lockstep.

---

### Telemetry: OTLP Metrics Export and Distributed Tracing

**Gap:** The Prometheus metrics path was delivered in v0.7 Sprint 7d. The comprehensive technical document additionally targets two TRL-5 OTLP capabilities that v0.7 explicitly deferred: (a) `PrometheusOtlpTelemetrySink` — emitting `exeris_kernel_*` metrics in OTLP wire format with zero allocation on the emission path; (b) distributed tracing — propagating `traceId` carried in JFR `rawArgs[]` into OTLP spans so a kernel request can be correlated with upstream/downstream services in a tracing backend (Jaeger / Tempo / equivalent).

**Owner:** Telemetry subsystem.

**Resolution:** Implement an OTLP sink targeting the OpenTelemetry Protocol HTTP/protobuf endpoint; reuse the async dispatcher delivered in v0.7 Sprint 7c so emission stays off the caller's critical path; build protobuf frames from `LoanedBuffer` slices to maintain the zero-allocation invariant on emission. For tracing: define a `TraceContext` carrier inside the JFR `rawArgs[]` layout, propagate it through `ScopedValue`, and emit a `TraceSpanEvent` JFR record per kernel-bounded span that the OTLP sink translates into an OTLP span proto.

**Merge Gate:** OTLP metrics integration test against an OpenTelemetry Collector container shows metrics arriving with correct labels; tracing integration test produces a parent-child span across kernel + downstream HTTP client; both paths verified zero-allocation on emission via TCK budget assertions.

---

### Telemetry: Glass-Box Binary Serializer and `exeris-decoder` CLI

**Gap:** `architecture.md` and the comprehensive technical document describe a deterministic Glass-Box binary crash buffer written to `/tmp/exeris-crash/` on failure before JFR is fully initialised, an off-heap ring buffer with VarHandle release-fence semantics (TRL-4 GlassBoxSerializer), and a companion `exeris-decoder` CLI (TRL-5) that renders binary `rawArgs[]` carriers without invoking `toString()` on production objects. None of the three is implemented in the open-core tree today.

**Owner:** Telemetry subsystem (kernel) + Operations tooling (`tools/exeris-decoder` planned).

**Resolution:** Define a stable, versioned binary record layout for `rawArgs[]` and ring-buffer entries; implement `GlassBoxSerializer` as an off-heap writer with VarHandle release fences; provide a fail-safe crash-buffer writer that runs before JFR is fully up; ship `exeris-decoder` as a separate CLI artefact reading the binary layout and rendering structured output (text + JSON) without classpath dependency on the kernel runtime.

**Merge Gate:** Crash-buffer writer survives a JFR-uninitialised failure path (test injects pre-JFR failure); `exeris-decoder` CLI round-trips binary samples produced by the kernel into stable structured output; binary layout documented and version-tagged so the CLI and the kernel can evolve independently.

---

### Telemetry: Slf4jTelemetrySink Structured-JSON for Log Aggregation

**Gap:** The comprehensive technical document targets `Slf4jTelemetrySink` emitting structured JSON suitable for Loki / Fluent Bit / generic log-aggregation pipelines at TRL-5. The current SLF4J sink emits human-readable text; field extraction by log-shippers requires regex.

**Owner:** Telemetry subsystem.

**Resolution:** Add a structured-JSON output mode to `Slf4jTelemetrySink` that serialises typed `rawArgs[]` fields as JSON object properties (event name, nanosecond timestamp, severity, error code, contextual fields) without `String.format` on the emission path. Toggle via `TelemetrySinkConfig`. Document the field layout so log-pipeline operators can parse it without per-event mappings.

**Merge Gate:** Sample-based test verifies stable JSON layout across typical event types; secret-safety assertion (CWE-532) confirms no token, key, or credential ever lands in the structured output.

---

### Config: `@Dynamic` Hot-Reload via `WatchService`

**Gap:** The comprehensive technical document targets at TRL-5 `@Dynamic` config annotation enforcement via `java.nio.file.WatchService` — keys flagged dynamic must be re-readable from file sources without a kernel restart. Currently, config is loaded once during bootstrap; there is no file-change watcher and no propagation channel from a re-read key to active subsystems.

**Owner:** Config subsystem.

**Resolution:** Implement a file-source `WatchService` driver that re-reads keys flagged `@Dynamic` on modification events; expose a `ConfigChangeListener` SPI for subsystems that opt into hot-reload (initial subsystems: log levels, telemetry sink targets, watermark thresholds); REQUIRED-but-not-dynamic keys remain bootstrap-only and reject re-read attempts with a structured error. Hot-reload remains best-effort under the Community contract; subsystems whose contracts forbid runtime mutation (crypto, security) explicitly opt out.

**Merge Gate:** Abstract TCK for hot-reload contract (dynamic-key change → listener notified; non-dynamic-key change → reject) passes; Community binding green; no leaked file handles under sustained churn.

---

### TCK: ABI Symbol Resolution Suite

**Gap:** The comprehensive technical document targets at TRL-5 an ABI-symbol TCK that verifies every required OpenSSL / FFM symbol is resolvable at cold start before the kernel claims `READY`. `CoreOpenSslLoader.verifyOpenSslVersion` covers a small subset; there is no exhaustive contract suite asserting that the full symbol set the kernel expects to call is present on the target platform's library variant. Coupled with the OpenSSL 4 migration entry under v0.9 §"Cryptography", this becomes a multi-variant matrix check.

**Owner:** TCK / Crypto / Transport subsystem.

**Resolution:** Add `AbstractAbiSymbolResolutionTck` enumerating every native symbol the kernel calls (OpenSSL TLS context, cipher, syscall path); each binding declares its required-symbol manifest as data; the TCK asserts that `SymbolLookup` returns a non-null address for each entry under the runtime's selected library. Run as part of the bootstrap acceptance test on every supported platform variant (Linux x86-64 / ARM64), and across OpenSSL 3.5 LTS and OpenSSL 4.0 once the cryptographic compliance workstream lands.

**Merge Gate:** Suite enumerates a complete and reviewed symbol manifest per binding; CI bootstrap acceptance is gated on the suite passing across the platform matrix; failure mode produces a structured `EX-CRY-ABI-MISSING` error identifying the absent symbol.

---

### Deployment: Helm Charts and Kubernetes Production Manifests

**Gap:** The embedded health endpoint shipped in v0.7 enables K8s readiness/liveness probes, but the comprehensive technical document additionally targets at TRL-5 a complete production-ready manifest set: Helm chart, `PodDisruptionBudget`, `HorizontalPodAutoscaler`, baseline `NetworkPolicy`, and `Resource` requests/limits tuned to the documented runtime profile.

**Owner:** Deployment / Operations interface.

**Resolution:** Provide a versioned Helm chart under `deploy/helm/` referencing the official kernel container image; include PDB (minAvailable ≥ N-1), HPA driven by Prometheus-exported `exeris_kernel_throughput_rps` with CPU fallback, default `resources.requests` / `resources.limits` matching the documented constrained 128 MB / 0.5 vCPU and full-path footprints; document the chart and a reference deployment in `docs/operations/` alongside the planned FIPS-mode operator guide.

**Merge Gate:** Chart installs cleanly on a fresh KIND or minikube cluster; HPA scales under synthetic load; PDB blocks a forced drain when below `minAvailable`; chart values reviewed against documented runtime profile.

---

### Test Coverage: JaCoCo Enforced Gate in Default CI (v0.8 Fast-Win #1)

**Gap:** The `jacoco-maven-plugin` is wired only in the opt-in `coverage` profile (root `pom.xml` lines ~75-115) and emits XML/HTML reports without any `<rule>` / `<limit>` / `<minimum>`. The default `Build & TCK Verification` job in `.github/workflows/maven.yml` does not pass `-Pcoverage`, so neither a coverage percentage nor a regression threshold is computed in CI today. Every line-coverage regression ships invisibly. Sourced from `docs/release/test-coverage-by-category-audit-v0.8.md` §1.3 / §5.3 (2026-05-16 fast-win #3).

**Owner:** Build / Cross-cutting.

**Resolution:** Add `<execution>` `jacoco:check` with a per-module `<rule>` block. Initial v0.8 thresholds (raised toward ~85% in the 1.0 GA ramp, see `1_0-gA-roadmap-consolidated.md` row #82): SPI line ≥ 60%, Core ≥ 60%, Community ≥ 55%, TCK module ≥ 70%, Build-config ≥ 50%. Enable `-Pcoverage` in the default `build-and-verify` step so the gate fires on every push and PR. Introduce a separate `exeris-kernel-coverage-aggregate` module hosting `jacoco:report-aggregate` so a single rollup `coverage.xml` is generated for downstream tooling. Keep current opt-in HTML rendering for local inspection.

**Merge Gate:** `mvn verify` in the default CI job fails when any module drops below its declared threshold; per-module XML report + aggregate XML are uploaded as CI artifacts; thresholds documented in `docs/quality/coverage-gates.md`.

**Status (v0.8):** **DELIVERED** in Sprint 6 (Coverage C-P0-01) — `-Pcoverage` activates the JaCoCo agent + per-module `<check>` rule enforcement in the default `build-and-verify` job (`.github/workflows/maven.yml`); per-module LINE floors live in each module's `<jacoco.line.minimum>`.

---

### Test Coverage: Kafka Integration CI Gate (v0.8 Fast-Win #2)

**Gap:** Module `exeris-kernel-community-kafka` ships 3 `@Tag("integration")` Testcontainers tests (`CommunityCrossEngineChoreographyIT` — 304 LoC, cross-engine saga; `CommunityKafkaFlowChoreographyTckIT` — 92 LoC; `CommunityKafkaEventEngineTckIT` — 53 LoC) but the string `kafka` does not appear in `.github/workflows/maven.yml`. None of these tests runs in any CI job; the cross-engine saga that pins v0.7's flagship `AbstractFlowChoreographyTck` to a real Kafka broker is verified only on-demand by the author. Any regression in flow choreography between two `EventEngine` providers ships invisibly. Sourced from `docs/release/test-coverage-by-category-audit-v0.8.md` §2.2 / §2.4 (2026-05-16 fast-win #1).

**Owner:** Build / Events subsystem.

**Resolution:** Add a `kafka-integration-gate` GitHub Actions job mirroring the existing `persistence-rls-gate` shape: pre-install `exeris-kernel-community-kafka` with `-am -DskipTests` then run `mvn -pl exeris-kernel-community-kafka -DincludedGroups=integration -DexcludedGroups= test`. Run on push to `main` / `development/**` and on PRs targeting the same branches. Upload JFR recordings as artifacts on `push` events to `main` (mirroring `persistence-rls-gate`'s retention policy: 14 days).

**Merge Gate:** Job is required on PR merge for `exeris-kernel-community-kafka` paths; first green run includes all 3 Kafka ITs visible in CI logs; broken-choreography canary regression (simulated by reverting a `CrossEngineFlow` snapshot store change) fails the gate as expected.

**Status (v0.8):** **DELIVERED** in Sprint 6 (Coverage C-P0-02) — `kafka-integration-gate` job added to `.github/workflows/maven.yml`, mirroring `persistence-rls-gate`; runs all three `exeris-kernel-community-kafka` `@Tag("integration")` Testcontainers tests and uploads JFR recordings.

---

### Test Coverage: Core Integration CI Gate / OpenSSL TLS Loopback IT in Default CI (v0.8 Fast-Win #3)

**Gap:** `OffHeapTlsEngineLoopbackIT` (`exeris-kernel-core`, 567 LoC) performs a real OpenSSL TLS 1.3 handshake over a loopback TCP socketpair — the only end-to-end OpenSSL TLS regression test in the repo. It is tagged `@Tag("integration")`, the default `build-and-verify` Surefire selector excludes `integration`, and no core-level integration gate job exists. The test therefore runs nowhere in default CI. OpenSSL handshake regressions (cipher / ALPN / hostname-verify wiring) ship invisibly. Sourced from `docs/release/test-coverage-by-category-audit-v0.8.md` §2.2 / §2.4 (2026-05-16 fast-win #2).

**Owner:** Build / Crypto subsystem.

**Resolution:** Add a `core-integration-gate` GitHub Actions job that mirrors `persistence-rls-gate` for `exeris-kernel-core` module — `mvn -pl exeris-kernel-core -DincludedGroups=integration -DexcludedGroups= test`. Linux-only via the existing `@EnabledOnOs(LINUX)` + `@EnabledIfSystemProperty(named="exeris.tls.testOpenssl", matches="true")` (or equivalent) gating already present on the test class. Upload JFR recordings to artifact retention same as the other gates. Alternative implementation (no new job): drop the `@Tag("integration")` tag from `OffHeapTlsEngineLoopbackIT` and rely on the existing environment-gate annotations to skip it where OpenSSL is unavailable — this lets the existing `build-and-verify` job pick it up.

**Merge Gate:** OpenSSL TLS 1.3 loopback test runs and passes on every PR; a deliberate `SSL_VERIFY_PEER` regression (canary revert) fails the gate; JFR `TlsHandshakeEvent` and `TlsBindingEvent` records are present in the uploaded artifact.

**Status (v0.8):** **PARTIAL** — the *no-new-job* alternative (Resolution option B) was taken: `@Tag("integration")` was dropped from `OffHeapTlsEngineLoopbackIT` (Sprint 6 C-P0-02), so the default `build-and-verify` job now picks the IT up, gated by its `@EnabledOnOs(LINUX)` + OpenSSL env-property annotations (it executes where the runner has OpenSSL configured, skips otherwise). The dedicated `core-integration-gate` job (option A) was **not** added — so there is still no unconditional OpenSSL gate. Treat the always-on dedicated gate as carried to v0.9.

---

## Known Gaps / Future Work planned for v0.9

### Diagnostics: `KernelDiagnostics` SPI + Community Provider + CLI Artefact (ADR-033)

**Gap:** External consumers — primarily `exeris-ai-bridge` 0.4.0 (ADR-025) and any future operator CLI — currently have no contract-stable, cold-path surface to read kernel state out-of-process. The orchestrator's `SubsystemOrchestrator.subsystems()` / `.subsystem(name)` plus `MemoryAllocator.stats()` and `Provider.name()` accessors are sufficient ingredients but live in `exeris-kernel-core`, not in SPI; treating them as a public contract breaks the SPI/Core boundary and silently drifts on every orchestrator refactor. `exeris-ai-bridge`'s `kernel:*` MCP tool family (`kernel:list_providers`, `kernel:list_capabilities`, `kernel:get_bootstrap_dag`, `kernel:describe_subsystem`) returns `isError: true` placeholders until this gap is closed.

**Owner:** Diagnostics / Telemetry subsystem (kernel-architect lead).

**Resolution:** Implement [ADR-033](adr/ADR-033-kernel-diagnostics-spi.md) per RFC-2026-05-18:

1. **New SPI module / package** `eu.exeris.kernel.spi.diagnostics` in `exeris-kernel-spi`. Public types: `KernelDiagnostics` interface (4 methods — `listProviders`, `listCapabilities`, `getBootstrapDag`, `describeSubsystem`), `KernelDiagnosticsProvider` (ServiceLoader-discovered, `priority()` open-core hook), plus immutable snapshot records (`ProvidersSnapshot`, `CompositionSnapshot`, `BootstrapDagSnapshot`, `SubsystemSnapshot`, nested descriptors). Every top-level snapshot carries `schemaVersion: String` (initial `"1.0"`, append-only growth policy) and `capturedAt: Instant`. No imports from `exeris-kernel-core`, no host-runtime types — Wall preserved.
2. **Community provider** `CommunityKernelDiagnosticsProvider` (`priority() = 0`) in `exeris-kernel-community`, reading from `KernelBootstrap` / `SubsystemOrchestrator` state. `META-INF/services/eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider` registration.
3. **CLI artefact** — new Maven module `exeris-kernel-diagnostics-cli` in the `exeris-kernel-community` reactor. Shaded executable JAR, `main` = `eu.exeris.kernel.community.diagnostics.cli.DiagnosticsCli`. Reads framed JSON requests on stdin, writes JSON responses on stdout; auth-free local-spawn mode (the spawning process is trusted). No separate Enterprise CLI — the same binary picks up `EnterpriseKernelDiagnosticsProvider` (priority 100) when the Enterprise jar is on the classpath, per the ADR-008 open-core extension model.
4. **TCK** — `AbstractKernelDiagnosticsTck` in `exeris-kernel-tck` plus a JSON schema fixture file asserted on every CI run; Community binding green; Enterprise binding obligation documented for `exeris-kernel-enterprise` 0.6 (Obligation 9 of ADR-033).
5. **JFR emission for diagnostic calls themselves** — codes `EX-DIAG-1001`..`EX-DIAG-1004` (one per method) emitted at INFO level so operators can audit out-of-process introspection. Zero-allocation contract holds because the call frequency is "per minute, not per request" (ADR-033 Obligation 2).

**Merge Gate:** SPI module compiles green with no `exeris-kernel-core` imports (ArchUnit check); Community provider passes `AbstractKernelDiagnosticsTck`; CLI artefact shades cleanly and runs as a child process under the `exeris-ai-bridge` integration test against a fixture kernel; JSON schema fixture asserted in CI; documentation drift swept against ADR-033's ten obligations.

**Cross-repo dependents:** `exeris-ai-bridge` 0.4.0 implements `src/transport/kernel-adapter.ts` against the CLI's stdio JSON protocol (ADR-025 §Engineering Protocol item 2 binding closure). `exeris-kernel-enterprise` 0.6 ships `EnterpriseKernelDiagnosticsProvider` (priority 100) returning the same record types with Enterprise-only fields populated where the ADR-033 §Obligation 6 use-case test applies; the Enterprise overlay is mirrored as a link stub at `exeris-kernel-enterprise/docs/adr/ADR-033.link.md`. Out-of-scope here: authenticated / remote diagnostic transport (lands in `exeris-ai-bridge` 0.6+ transport-auth work), any mutation surface, `bridge:health` synthetic derived checks (aggregator concern, not kernel SPI concern).

---

### Diagnostics: JVM Runtime Ergonomics Snapshot (Introspective) + Recommended-Flags Baseline Doc

> **Sequencing:** depends on the base `KernelDiagnostics` SPI (previous entry; interface
> shipped in Sprint 1, PR #163) — the snapshot is a fifth method on that interface, so
> this entry must land after the base SPI in the same release window.

**Gap:** The kernel surfaces no read-only view of the JVM and container environment it
actually runs in: GC in use and heap geometry vs the cgroup `memory.max`,
`ActiveProcessorCount` vs `cpu.max` quota, large-pages state, CDS/AOT archive presence,
`cpuset` vs available cores. Operators diagnosing throughput problems have no
kernel-surfaced signal that the deployment is CPU-throttled or memory-squeezed by its own
container limits — the constrained `entity-read-by-id` JFR profile (Sprint 6 PERF-072
context) showed 465/547 cgroup periods throttled, competing directly with request
servicing, and nothing in the diagnostics surface would have said so. The Enterprise
preflight track (`exeris-kernel-enterprise` EPIC-E8, item E8.23) needs the same snapshot
shape; per ADR-008 the *introspective* surface belongs open-core while the *actionable*
tuning advisor (recommendation ladder, thresholds) stays Enterprise-private.

**Owner:** Diagnostics / Telemetry subsystem (kernel-architect lead).

**Resolution:**

1. **SPI surface** — a fifth method on `KernelDiagnostics`:
   `getJvmErgonomics() → RuntimeErgonomicsSnapshot`. Per ADR-033 §5 (Java interface =
   binary contract) adding a method is a binary-breaking change, so it ships with the full
   compatibility story: a `default` implementation returning a snapshot whose optional
   fields are all `Optional.empty()` (third-party providers stay binary-compatible), all
   known providers (Community, Enterprise) updated in lockstep, and the matching
   `AbstractKernelDiagnosticsTck` extension. Audit event code **`EX-DIAG-1005`** assigned
   to the new method and registered in the telemetry error-code registry
   (`docs/subsystems/telemetry.md` — the `CommunityKernelDiagnosticsEvent` row currently
   reads `EX-DIAG-1001..1004` and must extend to `..1005`).
2. **Snapshot type** — `RuntimeErgonomicsSnapshot` joins the ADR-033 snapshot family
   (`schemaVersion` + `capturedAt`, append-only growth, same policy as the existing
   records): GC name, heap max/committed, container limits from the cgroup-v2 unified
   hierarchy (membership resolved via `/proc/self/cgroup`, limit values read from
   `/sys/fs/cgroup/<path>/cpu.max`, `.../memory.max`, `.../cpuset.cpus.effective`),
   `availableProcessors`, large-pages/THP state, CDS/AOT presence. Strictly
   observational — no recommendation fields (those are the Enterprise advisor's surface,
   ADR-008).
3. **Community provider** — populate from `java.lang.management` + procfs/cgroupfs reads in
   `CommunityKernelDiagnosticsProvider`; per ADR-033 Obligation 5, legitimately absent
   data (non-Linux host, cgroup-v1-only hierarchy, no container limits) is modelled as
   `Optional.empty()` on the affected fields — no new sentinel type.
4. **Baseline doc** — `docs/operations/jvm-flags-baseline.md` (note: `docs/operations/` is
   a new docs sub-directory introduced by this entry): generic recommended flag baseline
   for Community deployments (GC choice guidance, container-awareness flags, large-pages
   prerequisites). Generic guidance only — no Enterprise-private thresholds.
5. **TCK** — extend `AbstractKernelDiagnosticsTck` with the new snapshot's schema fixture
   (append-only assertion), the `Optional.empty()`-degradation case, and a
   default-method-compat case (a provider that does not override `getJvmErgonomics()`
   still satisfies the contract).

**Merge Gate:** schema fixture asserts append-only growth; Community binding green
including the degradation and default-method cases; no `exeris-kernel-core` imports added
to SPI (existing ArchUnit check covers the module) and the ADR-033 §v0.9 guardrail rule
(`eu.exeris.kernel.spi.diagnostics.*` MUST NOT import `jdk.jfr.Event` or
`exeris-telemetry-spec` types) confirmed to cover the new snapshot type;
`docs/subsystems/telemetry.md` registry row extended to `EX-DIAG-1005`; ADR-033 amended in
the same PR: §Obligation 9 method count updated (four-method → five-method surface) and
Engineering Protocol step 8 error-code range extended (`EX-DIAG-1001..1004` →
`EX-DIAG-1001..1005`); Enterprise
companion PR reviewed for ADR-033 Obligation 6 compliance — same
`RuntimeErgonomicsSnapshot` record type, no fork, Enterprise-only threshold fields absent
from the record itself; baseline doc reviewed against ADR-008 (no Enterprise-private
tuning content).

**Cross-repo dependents:** `exeris-kernel-enterprise` EPIC-E8 E8.23 (`JvmErgonomicsProbe`)
consumes the same snapshot shape and layers the actionable `jvm.flags` advisory on top
(Enterprise-private thresholds) — lockstep provider update per Resolution item 1;
E8.22/E8.24 (cgroup probe, resource-driven runtime geometry) read the container-limit
fields as geometry inputs.

---

### SPI Stability Declaration

**Gap:** No formal 1.0 support/stability declaration exists yet for open-core APIs and subsystem contracts.

**Owner:** Architecture / Build.

**Resolution:** Declare which SPI/API surfaces are stable, which are preview, and which remain experimental.

**Merge Gate:** Stability matrix published and referenced from module docs.

**Status (v0.9):** **DELIVERED** in Sprint 2 — `docs/stability-matrix.md` published as a tracked, consumer-facing artifact: per-package maturity (`stable` / `preview` / `experimental`), since-version, anchor ADR, TCK coverage, and Enterprise-overlay status, plus the canonical semver policy and pre-1.0 / TRL-3 framing. `diagnostics` is the first explicitly-`stable` surface (ADR-033); `events` / `graph` / `security` / `crypto` held at `preview` (scheduled v0.9 contract work); `http` is `mixed` (engines + enricher `stable`, ADR-034 body-codec quadrant `preview`). All 13 `docs/subsystems/*.md` and 5 `docs/modules/*.md` carry a `## Stability` cross-reference; `CHANGELOG.md` links the matrix as the semver-policy source. Drift sweep clean (whitepaper / architecture / performance-contract enumerate no per-surface levels to sync).

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

### Security: JWKS Key Rotation with Overlap Window

**Gap:** The comprehensive technical document targets JWKS key rotation with an explicit overlap window and cutover deadline so a tenant's signing keys can be rotated without dropping in-flight validations, combined with the ADR-012 invariant that JWKS endpoint outage equals deterministic deny. The current `CommunityJwksValidator` resolves a `kid` against a static, immutable `kid -> RSA public key` map fixed at construction — there is no overlap window, staleness budget, or refresh path.

**Owner:** Security subsystem.

**Resolution:** Add a `JwksRotationPolicy` SPI carrying the overlap window length and cutover deadline; cache the previous key-set for the overlap duration so signatures produced under the old `kid` continue to verify until the deadline; emit a `JwksKeyRotationEvent` JFR record at fetch / rotation / cutover; deterministically deny on JWKS endpoint outage past the configured stale-window, consistent with the failure-mode plan for cached JWKS keys.

**Merge Gate:** Abstract TCK covers overlap-window behaviour, cutover deny, and stale-fetch deny; Community binding green; security-audit checklist signed for rotation correctness.

**Status (v0.9):** **DELIVERED** in Sprint 4 — Community-only per the Sprint 3 IdentityProvider RFC (no SPI surface this milestone; the rotation timing policy promotes to `eu.exeris.kernel.spi.security.identity` in v0.10 alongside `IdentityProvider`/ADR-040). Shipped: a format-blind `KeyRotationPolicy` (overlap window + stale-fetch budget) and `CommunityRotatingKeySet` (current + retiring key generation, `Clock`-driven overlap/cutover, deterministic deny on stale refresh past budget — ADR-012 fail-closed, never fail-open), composed into `CommunityJwksValidator` behind a `JwksKeyResolver` seam so the static-map path is byte-for-byte unchanged. Deny reasons `kid-rotated-out` / `jwks-stale` map to `EX-SEC-2002` (secret-safe). `CommunityJwksKeyRotationEvent` JFR emits `ROTATION` (one event per actual generation rotation) / `CUTOVER_DENY` / `STALE_DENY` (per-request denies; opaque kid labels + counts only — no key material). `AbstractSecurityProviderTck` gains the overlap-fresh / cutover-deny / stale-fetch-deny merge-gate triplet (reason-string asserted), Community binding green, plus a JFR-emission test. Real OIDC/JWKS HTTP fetch remains the v0.10 `CommunityOidcIdentityProvider` deliverable that consumes this rotation seam.

---

### Security: `IdentityProvider` SPI Direction — RFC Track

**Gap:** Application-side authentication ("edge token validation") has no kernel SPI seam in v0.8. `PrincipalContext` is the canonical parsed-identity carrier (UUIDv7 `principalId` + `Optional<UUID> tenantId` + roles + scopes), but the path from "incoming HTTP request with `Authorization: Bearer ...`" to a populated `PrincipalContext` is entirely host-application territory. The choice of token validator (OIDC-aware JWT, PASETO, custom HMAC) and identity store (Keycloak, Auth0, federated, in-process) crosses driver and policy boundaries; committing to an SPI shape without enumerating the option space risks locking in the wrong contract. Auth/IDP is also the top blocker for the B2B IDP target shape downstream (BudgetHQ et al.) — without it, no application flow reaches `PrincipalContext`-aware code through a kernel-supported path.

**Owner:** Security / Bootstrap subsystem.

**Resolution:** Open an RFC in `exeris-docs/` (`RFC-YYYY-MM-DD Identity Provider SPI Shape.md`, per `exeris-docs/templates/RFC-TEMPLATE.md` — RFC not ADR because this is a multi-option strategic question with no committed decision yet). Scope enumerates: option space (OIDC-first vs PASETO-first vs federation-first vs custom-claim-first), interaction with the existing v0.9 JWKS rotation item, claims-to-`PrincipalContext` mapping contract, multi-IDP composition (per-tenant IDP selection driven by `StorageContext.isolationKey`), failure-mode classification (signature invalid / claims malformed / token expired / IDP unreachable), and TCK strategy. RFC accepted → reserve next free ADR number in `~/exeris-systems/exeris-docs/adr-index.md` → ADR drafted → SPI implementation lands in v0.10.

**Outbound / WebClient touchpoint (MUST be in RFC scope — added 2026-06-02).** The IDP SPI is not inbound-only. `PrincipalContext` is the convergence point for *both* directions: IDP **writes** it inbound (token → `PrincipalContext` via `ScopedValue`), and the outbound `HttpClientRequestEnricher` SPI (ADR-032, consumed by `KernelWebClient`) **reads** it to propagate identity downstream. The two never couple directly — they meet only at `PrincipalContext` (no `KernelWebClient`→IDP compile edge; The Wall preserved). The RFC MUST resolve the service-to-service auth question, because today the enricher seam carries only parsed identity (`X-Tenant-Id` / `X-Principal-Id`), **not** the raw `Bearer` — the kernel holds the parsed `PrincipalContext`, not the raw token. Enumerate the propagation options: (a) parsed-identity headers only, downstream re-validates via its own IDP; (b) pass-through bearer — requires the IDP SPI to retain/expose the raw credential, which the enricher forwards; (c) token exchange / on-behalf-of / client-credentials — IDP exposes an *outbound credential / token-source seam* the enricher consumes. The decision determines whether `HttpClientRequestEnricher` needs a companion `OutboundCredentialProvider` seam (or an IDP method `outboundCredential(PrincipalContext) → Optional<Credential>`). Decide one contract in the same RFC → the reserved ADR covers both inbound validation and outbound propagation. See the deferred WebClient entries (Symmetric Body Codec, Retry/Backoff) for the adjacent client-side SPI surface that lands alongside.

**Merge Gate:** RFC accepted with one preferred option called out and dissenting positions documented; no kernel code changes in this gate (research / decision track only). Implementation gate deferred to v0.10.

**Status (v0.9):** **DELIVERED** in Sprint 3 — `docs/rfc/RFC-2026-06-08-identity-provider-spi-shape.md` (ACCEPTED). Three axes decided: **structural** = dedicated `IdentityProvider` SPI (`eu.exeris.kernel.spi.security.identity`) with `SecurityProvider` as a thin dispatcher (mirrors the ADR-034/036 registry/driver shape); **first driver** = OIDC+JWKS (`CommunityOidcIdentityProvider`, mostly a refactor of today's `CommunityJwksValidator`); **outbound** = parsed-identity headers (ADR-032 status quo) for v0.10, outbound-credential seam reserved. Dissenting positions (PASETO-first / federation-first / custom-claim-first; structural extend-in-place; outbound pass-through / token-exchange) recorded. Claims→`PrincipalContext` mapping, multi-IDP issuer-dispatch + `StorageContext.isolationKey` routing, failure-mode → `EX-SEC-2002` (fail-closed, terminal-deny, no fall-through), and TCK strategy (`AbstractIdentityProviderTck` + extended `AbstractSecurityProviderTck`) all scoped. **ADR-040 reserved** in `exeris-docs/adr-index.md` (PROPOSED — content + SPI implementation in v0.10). v0.9 Sprint 4 JWKS rotation is a load-bearing dependency. Zero kernel code this sprint.

---

### Config: `@Immutable` Annotation Enforcement for Config Keys

**Gap:** The comprehensive technical document targets `@Immutable` annotation enforcement for config keys that must never be hot-reloaded under any circumstance (security trust anchors, isolation boundaries, native library paths). The annotation type does not exist in the SPI; there is no compile-time or runtime enforcement that prevents a security-critical key from being accidentally tagged `@Dynamic` (see v0.8 §"Config: `@Dynamic` Hot-Reload via `WatchService`" for the inverse capability).

**Owner:** Config / Security subsystem.

**Resolution:** Add the `@Immutable` annotation to `exeris-kernel-spi`; extend the `WatchService` driver from v0.8 to refuse re-read of `@Immutable`-flagged keys with a structured error; add a compile-time check (APT processor in `exeris-kernel-build-config`) that rejects keys carrying both `@Immutable` and `@Dynamic`; publish the security-relevant key catalog that must carry `@Immutable` at GA.

**Merge Gate:** APT processor catches the mutually-exclusive annotation combo; runtime watcher refuses `@Immutable` re-read with a structured event; documented `@Immutable` key catalog reviewed and signed off by Security.

---

### Transport: `TransportStream.reset(long errorCode)` SPI — Transport-Agnostic Stream Abort

**Gap:** `eu.exeris.kernel.spi.transport.TransportStream` exposes `read`/`write`/`queueWrite`/`close` but has **no transport-agnostic stream-abort method**. A downstream consumer that needs to forcibly abort a single stream (e.g. an HTTP/3 handler issuing `RESET_STREAM` on an uncaught-exception cleanup path) is therefore forced into an `instanceof` reach-through to a concrete transport type to reach its reset/abort primitive. This works while exactly one transport implements a given protocol, but it bypasses the SPI and makes adding a second carrier for the same protocol (e.g. a non-`io_uring` fallback or a memory-loop test transport) risky — the reach-through silently fails to dispatch to the new carrier. Tracked from the H3 failure-isolation work (downstream issue #23).

**Owner:** Transport subsystem.

**Resolution:** Add `void reset(long errorCode)` to `TransportStream` — *"forcibly abort the stream with the given protocol error code, then close it; implementations map the code to the underlying transport's stream-reset mechanism (RFC 9000 §20.1 QUIC `RESET_STREAM` where each protocol stream IS a transport stream; abortive close for raw TCP); idempotent."* Bind it in the Community transport stream implementations (raw TCP stream → abortive close, error code advisory). **Layer note — do not conflate with HTTP/2 `RST_STREAM`:** an HTTP/2 `RST_STREAM` is an *application-codec frame* multiplexed over a single TCP `TransportStream` (see `CommunityHttp2ControlFrames.sendRstStream*`), **not** a transport-stream reset; it is emitted by the HTTP/2 codec and never routes through `TransportStream.reset()`. This SPI addresses the *transport-stream* abort axis (QUIC stream reset / TCP abortive close), where the H3 `instanceof` reach-through lives — it is orthogonal to the HTTP/2 codec's existing per-stream `RST_STREAM` machinery and to inbound rapid-reset (CVE-2023-44487) flood defense. Add an `Abstract*Tck` reset contract (idempotency, post-reset `close()` is a no-op, post-reset I/O rejected). The error code is a caller-supplied `long` — The Wall (ADR-008) holds: the contract exposes no `io_uring`/QUIC/native detail. **Scope is the abort capability only**; promoting other transport-specific reach-throughs (`concludeSendSide`, `releaseDispatch`, etc.) is explicitly out of scope — each needs its own SPI surface review and should be split if pursued. Landing this unblocks a downstream Enterprise cleanup that removes the temporary `instanceof` reach-through introduced for H3 failure isolation.

**Merge Gate:** `reset(long)` on the SPI with Javadoc'd protocol-mapping contract; Community binding(s) green; `Abstract*Tck` covers idempotency + post-reset semantics; ArchUnit/boundary check confirms no native/QUIC vocabulary entered the SPI.

**Status (v0.9):** **DELIVERED.** `TransportStream.reset(long errorCode)` added as a Wall-safe **`default` method** (best-effort graceful `close()`) so the ~5 in-repo test-double implementers keep compiling and a true abort is opt-in by override. Community `NativeTcpStream.reset` overrides it with an abortive close — SO_LINGER 0 so the terminal channel close emits a RST (not a FIN), abandons queued writes (`hasPendingData()` clears immediately, no drain wait), unparks blocked reader/writer, and rejects post-reset I/O via the existing `closeRequested`/`closed` guards. Idempotent and composes with `close()`. `AbstractTransportStreamTck.ResetContract` (4 cases: abandons-pending, idempotent+compose, post-reset write/queueWrite rejected) is green against the real loopback `NativeTcpStream` pair (Community binding). The reset machinery reuses the proven single-consumer/`finishCloseIfDrained` discipline (the MPSC outbound queue is only ever drained by the carrier reactor). **Bundled robustness fixes** (the recurring 2-vCPU CI flake's root cause): **(1)** an unrecoverable outbound-write `IOException` now abandons the queued writes (`abortOnUnrecoverableWriteFailure`, gate-safe) before propagating — previously the pending entry stayed forever, hanging `close()`/teardown; **(2)** `JfrAllocationMonitor` now scopes allocation accounting to the workload thread id, so a leaked transport reactor thread from a prior test can no longer contaminate a zero-allocation contract (the `CitadelGuard AllocationContract` false-failure). Open follow-up: a secret-safe single-phase JFR event at reset (deferred with the broader security/transport validation-stage JFR pass, see Phase 4 N1).

---

### HTTP Client: Symmetric Body Codec SPI (Multi-Binding) — deferred from v0.8

**Gap:** `CommunityWebClient` hardcodes Jackson 3 as the JSON binder per ADR-026's deliberate scoping decision. There is no SPI seam for alternative binders — Jackson 2 (legacy applications stuck on the older line), Gson / Moshi (different ecosystems), or binary protocols (CBOR / Protobuf for service-to-service paths that want zero-allocation encoding). The server-side codec pair `HttpResponseBodyEncoder` + `HttpResponseBodyEncoderRegistry` (since 0.5.0) exists for inbound traffic but has no client-side counterpart. v0.8 Sprint 6 (HTTP-130..136) deliberately deferred this gap on YAGNI grounds — designing the codec contract without a second binding to validate against would lock in the wrong shape.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Introduce `HttpRequestBodyEncoder<T>` (T → `LoanedBuffer` + content-type headers) and `HttpResponseBodyDecoder<T>` (`LoanedBuffer` + `Class<T>` → T) as a symmetric pair to the existing server-side `HttpResponseBodyEncoder` in a new `eu.exeris.kernel.spi.http.codec` subpackage. Both interfaces must remain implementation-blind — no Jackson types leak across the SPI boundary. `CommunityWebClient` gains a codec-injection seam (additional constructor variant accepting the encoder + decoder pair, or a small `CodecRegistry` wrapper); the existing constructors retain the Jackson 3 default for ergonomics. Provide an `AbstractHttpBodyCodecTck` covering round-trip, error-on-mismatch, and content-type negotiation across the two TCK-bound bindings.

**Merge Gate:** SPI contract validated against at least two bindings (Jackson 3 as the canonical default + one alternative — Gson, Moshi, or native Panama binary — chosen at sprint scoping based on the first external consumer's request) so the abstract TCK reflects real contract pressure, not single-implementation guesswork. Sprint scheduling gated on either (a) a concrete external consumer requesting non-Jackson binding, or (b) a benchmark demonstrating measurable zero-allocation win for a native binary codec on the request hot path.

---

### HTTP Client: Generic-Element Decode (`TypeReference`-style facade overload) — deferred from v0.8

**Gap:** The body-codec decode SPIs take a raw `Class<?> targetType` (`HttpResponseBodyDecoder.decode(LoanedBuffer, Class<?>, …)` client-read; `HttpRequestBodyDecoder` server-read). A raw `Class` cannot carry a parameterized element type: decoding a `findAll` → `List<Widget>` response by passing `List.class` loses `Widget` to type erasure, so the Jackson driver yields `List<LinkedHashMap>` rather than `List<Widget>`. Any `KernelWebClient` call — and any generated client operation — returning a generic container (`List<T>`, `Map<K, V>`, nested generics) cannot recover the element type through the `Class<?>` seam. The single-object path (`findById → Widget.class`) is unaffected; this is a real limitation only on collection/parameterized returns.

**Owner:** HTTP / Transport subsystem (kernel facade — `KernelWebClient`).

**Resolution:** Add a generics-carrying **overload on the kernel facade** decode path — a kernel-neutral type token (analogous to Jackson `TypeReference<T>`, but implementation-blind so no Jackson type crosses the SPI boundary) that preserves the full parameterized type to the resolved decoder. This is a **facade-side overload on `KernelWebClient`, NOT a codec change in `exeris-tooling`**: the generator keeps emitting the decode call and only switches collection-returning operations to the token overload; the kernel owns the type-carrying seam and the driver's Jackson binding consumes the resolved generic type. The existing `Class<?>` path stays as the default for non-generic returns. Decide whether the token threads through the decode SPI (a parallel `decode(LoanedBuffer, <type-token>, …)`) or is resolved to a parameterized `java.lang.reflect.Type` at the facade and handed to the driver — keep it implementation-blind either way.

**Merge Gate:** A `List<Widget>` round-trip (plus one `Map` / nested-generic case) decodes to the correct element type via the facade token overload; the `Class<?>` path is unchanged for non-generic types; no Jackson `TypeReference` (or any driver type) appears in an SPI signature (The Wall). Naturally rides alongside the Symmetric Body Codec multi-binding work above — same decode surface, same facade seam.

---

### HTTP Client: Retry / Backoff Policy SPI — deferred from v0.8

**Gap:** `CommunityWebClient` performs no implicit retry per ADR-026's explicit scoping decision ("retry policy is the caller's concern; failures map to `WebClientException` exactly once with full diagnostic context"). Application code wanting "5xx retry with exponential backoff + jitter + max attempts" must hand-write the loop on every call site. The TypeScript side of the ecosystem ships a structured `RetryConfig` (`retryableStatuses`, `backoffMultiplier`, `maxAttempts`) that the kernel deliberately did not mirror at 0.8.0 because the semantic decisions are non-trivial.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Define an `HttpClientRetryPolicy` SPI in `eu.exeris.kernel.spi.http` with a single decision method `RetryDecision decide(HttpRequest, HttpResponse | Throwable, int attemptIndex)` returning either `RETRY(long delayMs)` or `GIVE_UP`. Composed by `CommunityWebClient` as an opt-in constructor parameter (paralleling the `HttpClientRequestEnricher` opt-in pattern from HTTP-133). Requires a companion ADR resolving:

- Which 5xx codes are retryable by default (502 / 503 / 504 vs 500 / 501).
- Idempotency-key generation and propagation (no implicit retry on `POST` without idempotency-key; `GET` / `PUT` / `DELETE` safely retryable).
- Request body re-buffering — when retry is decided, the original `LoanedBuffer` body has already been consumed by `engine.send`; the policy needs a body-snapshot abstraction or must require the caller to provide a `Supplier<LoanedBuffer>`.
- Circuit-breaker integration — does the policy block the calling virtual thread on backoff (acceptable on Loom), or yield to a scheduler? Interaction with the existing `WatermarkManager` SHED_LOAD decisions.
- Jitter strategy and failure-mode classification (transient vs permanent — DNS resolution failure, connection reset, TLS handshake failure, application-level 5xx).

**Merge Gate:** Companion ADR accepted with the above decisions documented; abstract retry TCK exercises an in-process server that fails N-1 times then succeeds, asserting the policy reaches success within budget and respects `GIVE_UP` boundaries; Community binding green; zero-leak assertion on `LoanedBuffer` lifecycle across retried requests.

---

### Cryptography: OpenSSL 4 Migration and Provider-Aware Bindings

**Gap:** Current Community/Core off-heap TLS bindings (`CoreOpenSslLoader`, `CoreSslHandles`) target OpenSSL 3.x only and use the legacy `SSL_CTX_new(method)` constructor. OpenSSL 4.0 final shipped 2026-04-14; the provider-aware `SSL_CTX_new_ex(libctx, propq, method)` is the canonical constructor going forward and is a prerequisite for any future provider-controlled crypto path (FIPS, post-quantum providers, custom property-query chains). Distribution baselines will transition over the 1.0 lifetime; pinning to `libssl.so.3` only is not durable.

**Owner:** Runtime / Crypto.

**Resolution:** Extend library candidate lists to include `libssl.so.4` / `libcrypto.so.4` (and Windows equivalents) with OpenSSL 3.5 LTS retained as supported fallback; migrate the SSL context constructor to `SSL_CTX_new_ex`; introduce a JFR `OpenSslLoadEvent` carrying detected major/minor/patch, library path, and loaded provider list; refresh ADR-008 to declare OpenSSL 4 as the new baseline with `.so.3` marked transitional; add a CI smoke matrix that builds and runs the integration tier against both OpenSSL 3.5 LTS and OpenSSL 4.0.

**Merge Gate:** Build green on both OpenSSL 3.5 LTS and OpenSSL 4.0 in CI; ADR-008 reflects the new baseline; no behavioral regression in existing TLS TCK suites; `OpenSslLoadEvent` observable in JFR recordings of a normal startup.

**Status (v0.9):** **DELIVERED** in Sprint 4b — `CoreOpenSslLoader` migrated the CTX constructor from `SSL_CTX_new` to the provider-aware `SSL_CTX_new_ex` (FIPS-ready `libctx` / `propq` seam, NULL for now); library candidate lists gained `.so.4` SONAMEs resolved newest-major-first (explicit env override still wins); the version gate was hardened to `OPENSSL_version_major` / `OPENSSL_version_minor` with an accepted band of `3 <= major <= 4` plus a per-library `ssl` / `crypto` major-match guard. The supported floor is **retained at 3.0.0** to keep the FIPS-validated 3.1.2 build loadable. `OpenSslLoadEvent` (version + resolved paths) lands in JFR; the CI smoke matrix builds OpenSSL 3.5.6 and 4.0.0 from source, both required. ADR-008 §1 was descriptively refreshed within the same boundary (no new ADR). The FIPS provider, a dedicated 3.1.2 CI job, and the libctx-injection overload are **deferred to Workstream F** (which will carry its own reserved ADR). Rationale for not adding a 3.1.2 binary CI job in 4b: gate acceptance of 3.1.2 is already pinned by the pure version-band unit test (`CoreOpenSslVersionGateTest#acceptsSupportedBand`), and in non-FIPS mode a 3.1.2 binary is ABI-redundant with the 3.5.6 job (same major 3, same `.so.3` SONAME, same `SSL_CTX_new_ex` handshake path). The 3.1.2 job earns distinct signal only in **FIPS mode** (`libctx` + validated fips provider + `propq="fips=yes"`), which is exactly Workstream F's machinery — so 3.1.2 lands there as a FIPS-mode job, testing the property that is actually unique to it.

---

### Cryptography: Off-Heap Key Material Zeroization

**Gap:** Sensitive cryptographic material (TLS handshake secrets, derived session keys, JWT signing material, password buffers) lives in off-heap `Arena`-managed memory. JVM GC and a plain `Arena.close()` do not zero buffer contents — bytes remain readable in process memory until reused. Without explicit zeroization, post-mortem inspection, heap dumps, and memory snapshots can leak key material. This is a correctness baseline independent of regulated-environment deployment.

**Owner:** Runtime / Crypto / Memory.

**Resolution:** Bind `OPENSSL_cleanse` from `libcrypto` and expose it via the OpenSSL runtime facade; audit existing call-sites that hold key material (`OffHeapTlsEngine` handshake state, native cipher contexts, JWT signing material) and route their disposal through cleanse before arena release. Promote to a `SensitiveBuffer` SPI marker if additional call-sites accumulate, with `close()` contractually guaranteeing cleanse before underlying arena release.

**Merge Gate:** `OPENSSL_cleanse` binding present and invoked from every identified secret-holding path on disposal; TCK assertion via a probe segment proves zeroed buffer contents after disposal for at least one representative path.

---

### Cryptography: FIPS Readiness (Optional Workstream)

**Gap:** Some downstream deployments (regulated industry, government, payments) require operation under FIPS 140-3 mode. OpenSSL 3+ delivers FIPS as a separate provider module (`fips.so` / `fips.dll`) loaded via `OSSL_PROVIDER_load(libctx, "fips")`; running under FIPS additionally requires (a) refusing the default provider, (b) rejecting non-allowlisted cipher suites at handshake, (c) statically disabling any JSSE/Java-side fallback (the cryptographic boundary is the compiled OpenSSL module — the JVM sits outside it), and (d) verifying behavior on an OS-level FIPS-enabled host. The kernel currently has no FIPS-aware path; the Community TLS provider retains a JSSE fallback incompatible with FIPS-enforce.

**Owner:** Crypto / Security. Workstream is opt-in — its activation is a product-scope decision taken before v0.9 closes.

**Resolution:** Three layers executed only if FIPS scope is confirmed; each layer composes with the OpenSSL 4 migration above:

1. **Provider initialization.** Introduce `FipsModePolicy { OFF, ALLOW, ENFORCE }` on `CryptoProviderConfig`; bind `OSSL_PROVIDER_load`, `OSSL_PROVIDER_unload`, `OSSL_PROVIDER_available`; under ENFORCE, load the `fips` provider, refuse the default provider, fail-fast on absence; emit a structured bootstrap JFR event with reason code; new ADR for kernel FIPS mode policy.
2. **Boundary preservation.** Under ENFORCE, `CommunityKernelCryptoProvider` refuses any JSSE-fallback engine selection; an algorithm allowlist rejects non-FIPS cipher suites at handshake with a structured `TlsException`; ArchTest rule forbids `javax.net.ssl.*` imports in FIPS-scoped packages outside test fixtures.
3. **CI lane.** Dockerfile based on AlmaLinux 9 / UBI 9 with FIPS enabled at boot; new workflow runs the TCK and integration tier inside the FIPS container; `@FipsExempt` annotation auto-skips known non-FIPS test fixtures; operator-facing `docs/operations/fips-mode.md` describes activation, supported algorithms, and failure modes.

This workstream pairs with the §"Off-Heap Key Material Zeroization" item above: the `OPENSSL_cleanse` binding is the baseline that lands regardless; the full `SensitiveBuffer` SPI plus read-back TCK lands together with FIPS scope.

**Merge Gate:** Only enforced if FIPS scope is confirmed for 1.0 — otherwise this section documents the pre-rolled plan for post-1.0 activation when a concrete regulated engagement materializes. Confirmed scope is estimated at approximately 17-20 additional working days within the v0.9 cycle.

---

### Quality: Test Coverage & TCK Hardening

**Gap:** A 2026-06-09 re-measure put aggregate coverage at **68.4% line / 55.3% branch**, with the JaCoCo gate enforcing **LINE only** (branch entirely unguarded), and **8 `Abstract*Tck` contract bases unbound** (defined with real `@Test` methods but no concrete binding → running nowhere). Adversarial cases (`alg=none`, HS/RS confusion, request smuggling, Rapid Reset) exist as one-off unit tests but are not codified as portable, provider-binding contracts. For a runtime of this maturity the edge-case levers are branch coverage and bound contracts, not raw line %.

**Owner:** Quality / TCK. Sequenced TCK-breadth-first (founder decision 2026-06-09): correctness contracts before raw %.

**Resolution (phased):**

1. **Phase 1 — bind the bindable unbound contracts.** Add concrete binding subclasses for the `Abstract*Tck` bases whose production implementation already exists, so their dormant `@Test` methods run in CI against shipping code.
2. **Phase 2 — codify adversarial cases as portable contracts.** Promote one-off security/HTTP negative tests into `Abstract*Tck` requirements: `alg=none` / HS-RS confusion / embedded `jwk`/`jku`/`x5u` rejection in `AbstractSecurityProviderTck`; request smuggling / Rapid Reset (CVE-2023-44487) / pseudo-header duplication in the HTTP server TCKs; TLS-floor / `SSL_VERIFY_PEER` in the crypto TCK.
3. **Phase 3 — coverage mechanics.** Add a BRANCH minimum to the JaCoCo gate at the current measured level then ratchet; merge IT-gated `jacoco.exec` (persistence-rls / kafka-integration gates) into the aggregate report (accounting fix — lifts artificially-depressed flow/kafka numbers without new tests); add cheap SPI carrier/exception unit tests.
4. **Phase 4 (separate) — `IsolationStrategyContract` fail-OPEN → fail-closed** (requires TCK + impl + ADR-012 amendment).

**Pending-feature (deliberately unbound, NOT a test gap):** `AbstractEventStreamReaderTck` / `AbstractEventStreamAppenderTck` (blocked on a durability driver) and `AbstractHttpClientRequestEnricherTck` (blocked on a concrete ADR-032 enricher impl — the SPI + `noop()` exist, no driver landed). These bind when their feature ships.

**Merge Gate:** Each bound TCK passes against the shipping implementation (a red bound TCK is a contract-violation finding, not a reason to weaken the test); branch gate added and ratcheted; aggregate trends toward 85% line / branch.

**Status (v0.9):** Phase 1 **DELIVERED** — bound 3 of 5 bindable contracts (**28 dormant `@Test` now running in CI, all green, zero contract violations surfaced**): `AbstractBootstrapOrchestratorTck` (against the real `SubsystemTopologicalSorter`), `AbstractProviderBindingLifecycleTck`, and `AbstractRowCursorTck` (against `CommunityPersistenceEngine` over H2 — note the contract's native-segment-release clause is vacuous for the JDBC binding, which allocates no `MemoryAllocator` segments; that clause is exercised only by a future native/Enterprise `RowCursor` binding). `AbstractGracefulShutdownTck` and the generic `AbstractCarrierPinningTck` are **deferred to a follow-up**: both require a fully-started multi-engine kernel over the native TCP transport (100k-VT drain / 10k-VT pinning spike) — heavy end-to-end fixtures that would be flaky or vacuous if forced; per-subsystem carrier-pinning is already covered transitively by the bound `AbstractSubsystemCarrierPinningTck` hierarchy.

Phase 2 **DELIVERED (partial — security portable, HTTP codec-local)**: the JWT algorithm vectors became a true portable contract — `AbstractSecurityProviderTck.AlgorithmConfusionContract` asserts `alg=none` (signature-stripping downgrade) and RS256→HS256 confusion (HMAC keyed on the published RSA public key) are denied with `EX-SEC-2002`, with `TestJwt.algNone()` / `hmacConfusion()` minting the adversarial tokens; the Community binding overrides both factories (3 new `@Test`, green — `CommunityJwksValidator`'s kid→alg→signature order already pins RS256, so these lock the posture against a future `IdentityProvider` SPI regression). The HTTP/2 vectors are **codec-local, not portable TCK** — HTTP/2 frame decode has no SPI seam (The Wall holds; an HTTP/2 logical stream is multiplexed over one TCP `TransportStream`, the codec internals are Community runtime), so they land as Community tests: **pseudo-header duplication is now fixed** in `PendingRequestHeaders` (RFC 7540 §8.1.2.3 — a repeated `:path`/`:method`/`:authority` fails closed instead of last-wins overwrite, closing a request-smuggling vector) with `PendingRequestHeadersTest` covering duplicate/ordering/unknown cases. **Deferred within Phase 2:** embedded `jwk`/`jku`/`x5u` header rejection (already structurally safe — the validator resolves keys only via its own kid keyset, never honoring token-supplied keys — a confirming contract is a cheap follow-up); HTTP/1.1 CL-vs-`Transfer-Encoding` smuggling (Community ingress is HTTP/2-first; no HTTP/1.1 body-framing surface to attack); TLS-floor / `SSL_VERIFY_PEER` (needs a live-OpenSSL crypto IT, Workstream-F-adjacent). Rapid Reset (CVE-2023-44487) is a real missing mitigation, carved out as its own hardening entry (below) with an `@Disabled` executable-spec (`Http2RapidResetSpecTest`) committing the contract ahead of the fix.

Phase 3 **DELIVERED (branch gate + SPI tests; IT-exec accounting deferred)**: the JaCoCo gate now enforces a **BRANCH** floor alongside LINE — a second `COVEREDRATIO` limit on the `BRANCH` counter in the root coverage profile, with per-module `${jacoco.branch.minimum}` overrides ratcheted ~6-8 pp below the 2026-06-10 unit-path baseline (spi 0.22, core 0.55, community 0.50, community-kafka 0.10, build-config 0.70; diagnostics-cli ungated like its LINE floor — only 12 branches, too volatile). Branch was the v0.8 audit's blind spot (line could hold while branch rotted); it is now gated with `haltOnFailure=true`. Three cheap, contract-meaningful SPI test classes lifted the weakest module (spi branch **22.1% → 30.0%**, +61 covered branches): `HttpStatusTest` (code-band validation + 1xx-5xx class predicates), `HttpMethodTest` (RFC 9110 safe/idempotent/typical-body semantics), `PrincipalContextDefaultsTest` (role/scope `hasAny*` short-circuit defaults via a minimal SPI-only impl). **Deferred — IT-exec accounting:** merging the persistence-rls / kafka-integration gate `jacoco.exec` into a combined report is genuine cross-job CI plumbing (artifact upload from each gate job + a `report-aggregate` step) and is carved out as its own follow-up rather than bundled into the branch-gate change; until then the community/kafka branch floors stay at the unit-only baseline (their `IT`-covered branches are real but uncounted, noted in each module pom).

Phase 4 **DELIVERED — `IsolationStrategyContract` fail-OPEN → fail-closed (S-P0-07, ADR-012 §4a amended)**: the v0.8 security audit's P0 finding was that a declared `SEPARATED_SCHEMA`/`DEDICATED` strategy with a missing/blank/wrong-typed required sub-claim — or an unrecognized strategy value — was silently **downgraded to SHARED** (the weakest isolation tier). ADR-012 §4a literally codified this as the "deterministic-deny analog", but a downgrade is **fail-OPEN**, not deny. Inverted across all three layers: `CommunityJwksValidator.resolveStorageContext` now **terminal-denies** (`SecurityAuthenticationException` → `EX-SEC-2002`, secret-safe reason codes `isolation-incomplete` / `isolation-unknown-strategy` / `isolation-malformed`) for every declared-but-unhonourable strategy; the **only** permissive fall-through is a genuinely absent/blank claim → SHARED keyed on the subject. `AbstractSecurityProviderTck.IsolationStrategyContract` and the Community `CommunityIsolationStrategyResolutionTest` are inverted to assert deny (was: assert SHARED); a new explicit-`SHARED` case and a wrong-typed-claim case were added (`TestJwt.claimRaw` mints the non-string claim). ADR-012 §4a + §9 amended (2026-06-10) and superseded the old conflated rule; SECURITY.md §Storage Isolation already listed the downgrade as reportable (now consistent). This closes the v0.9 Sprint 4c coverage/TCK-hardening arc; the remaining backlog (IT-exec accounting, Rapid Reset impl, `TransportStream.reset` #23) is tracked in its own entries. **Follow-up (PR #176 review N1) — validation-stage deny JFR:** the new deny reason codes (like the pre-existing `signature-invalid` / `unknown-kid` / `expired` codes) throw `SecurityAuthenticationException` without a dedicated *validation-stage* JFR event — they are surfaced today by `SecurityInterceptor`'s catch-and-emit at the authn boundary, not at the `CommunityJwksValidator` stage. ADR-012 §8 envisions typed per-stage deny telemetry; emitting a secret-safe (reason-code-only, single-phase) JFR event at each validator deny site is a tracked follow-up covering all reason codes uniformly (not just the new isolation ones), to be done alongside the broader security-JFR pass rather than piecemeal.

---

## Known Gaps / Future Work planned for v0.10

### Security: `IdentityProvider` SPI + First Driver

**Gap:** The v0.9 RFC track (§"Security: `IdentityProvider` SPI Direction — RFC Track") selected the IDP SPI shape and committed an ADR number; the SPI itself plus at least one Community driver remain to land. Without this, the B2B IDP target shape (BudgetHQ and downstream applications) cannot reach `PrincipalContext`-aware code from incoming HTTP requests through a kernel-supported path. This is the single largest "ship a B2B SaaS" blocker for the ecosystem.

**Owner:** Security / Bootstrap subsystem.

**Resolution:** Implement the SPI per the accepted RFC and reserved ADR — minimally `IdentityProvider`, `TokenValidator`, `ClaimsMapper` in `eu.exeris.kernel.spi.security.identity` — and ship the first Community driver (the default selected by RFC, expected to be OIDC + JWKS). Wire validator into the HTTP request lifecycle so a request bearing a valid token populates `PrincipalContext` via `ScopedValue` before reaching dispatcher handlers. The v0.9 JWKS rotation with overlap window becomes a load-bearing dependency. Provide `AbstractIdentityProviderTck` covering: valid token → `PrincipalContext` populated, malformed token → request rejected with structured `EX-SEC-*` error code, expired token → rejected with distinct code, IDP unreachable → fail-fast vs fail-open per RFC decision, multi-IDP composition driven by `StorageContext.isolationKey`.

**Merge Gate:** ADR accepted; SPI green on TCK; first Community driver bound; integration test against an OIDC container (Keycloak or RFC-selected default) in CI; no Spring or framework DI types leak into SPI / Core packages (Wall preserved); `PrincipalContext` carrier shape unchanged (additive only — no breaking-change framing per pre-1.0 / TRL-3 stance); JFR `IdentityValidationEvent` / `IdentityRejectionEvent` carried for observability.

---

## Known Gaps / Future Work planned for v0.11

### Storage: `BlobStorageProvider` SPI

**Gap:** File and media handling has no kernel SPI seam. Application generators that need to emit upload widgets, signed-URL flows, or download streams cannot rely on a kernel-side adapter — every host application hand-wires its own S3 / MinIO / GCS / local-FS code, with no zero-copy story and no isolation-key scoping. Blob I/O is a runtime hot path (large-payload streaming, native sendfile candidate) with ≥2 plausible drivers, qualifying as kernel SPI territory under the Wall test.

**Owner:** Storage / Runtime subsystem.

**Resolution:** Define `BlobStorageProvider`, `BlobRef`, `BlobUploadHandle`, `BlobDownloadHandle` in `eu.exeris.kernel.spi.storage.blob`. Streaming I/O backed by `LoanedBuffer` / `MemorySegment` (no `byte[]` round-trip on the hot path). Community drivers: local filesystem + a minimal S3-compatible HTTP driver reusing `HttpClientEngine`. Enterprise driver track (out-of-repo): zero-copy `io_uring sendfile` for local FS, native multipart upload for S3 (parallel uploads via `StructuredTaskScope`). `AbstractBlobStorageTck` covers: upload round-trip, download streaming, content-range read, signed-URL generation contract, isolation-key scoping (`StorageContext.isolationKey` honored for tenant-segregated buckets per ADR-012).

**Merge Gate:** SPI green on TCK with at least two bindings (local FS + S3-compatible HTTP); zero-leak assertion on `LoanedBuffer` lifecycle through upload + download paths; `StorageContext` isolation honored per ADR-012; ArchTest forbids `java.io.File` / `java.nio.file.Files` imports inside the SPI package (consistent with existing scoped bans in runtime hot paths).

---

### Runtime: `JobScheduler` SPI

**Gap:** Background-job execution (cron triggers, queued retries, scheduled emissions) has no kernel SPI. Applications wanting "run this every 5 minutes" or "schedule this to fire in 24h" hand-roll a scheduler or pull in Quartz, losing `PrincipalContext` / `StorageContext` propagation and missing JFR observability. Job dispatch is a runtime concern that composes with the existing event publisher and Flow engine, and has plausible alternative drivers (in-process Loom-based scheduler vs DB-backed durable queue vs external orchestrator hook).

**Owner:** Runtime / Scheduling subsystem.

**Resolution:** Define `JobScheduler`, `JobDescriptor`, `JobHandle`, `JobTrigger` (cron / interval / one-shot / event-driven) in `eu.exeris.kernel.spi.scheduling`. Context propagation: `PrincipalContext` and `StorageContext` captured at submission time, restored via `ScopedValue` on dispatch (mirrors the Flow engine context-capture pattern). Community driver: in-process Loom scheduler using `StructuredTaskScope` plus `ScheduledExecutorService` (one of the few approved scoped-ban exceptions, justified because the scheduling primitive itself is the boundary — orchestrated job code remains on Loom). Enterprise driver track (out-of-repo): DB-backed durable queue with at-least-once semantics + leader election. `AbstractJobSchedulerTck` covers: cron trigger fires on schedule, interval trigger respects delay, context propagation across job boundary, cancel via `JobHandle`, leader-election semantics for durable backend.

**Merge Gate:** SPI green on TCK with Community in-process driver bound; context propagation TCK verifies `PrincipalContext` + `StorageContext` round-trip across submit → dispatch; ArchTest confirms no `ThreadLocal` use for context propagation (`ScopedValue` only); JFR `JobDispatchEvent` / `JobCompletionEvent` / `JobFailureEvent` carried for observability; companion ADR documents the `ScheduledExecutorService` scoped-ban exception with explicit scope.

---

## Known Gaps / Future Work planned for v0.12

### HTTP: `WebSocketProvider` SPI (or SSE-Only Commitment)

**Gap:** The `realTimeApi` flag on `DomainMetadata` (SDK) exists in v0.8 but no generator emits real-time wire code, and the kernel has no WebSocket primitive. Real-time delivery splits into two distinct wire shapes: **Server-Sent Events** (pure HTTP/1.1 chunked streaming, runs on existing `HttpServerEngine` with no new SPI), and **WebSocket** (RFC 6455 handshake + frame codec, wire-protocol territory equivalent to HTTP/2 — requires kernel SPI). Without a decision, the `realTimeApi` flag remains aspirational and downstream applications hand-roll incompatible solutions.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Open a short RFC (single option-comparison page) deciding between (a) **SSE-only for v1.0, WebSocket post-1.0** — minimum scope, zero new SPI, ship an SSE response-writer pattern in Community plus generator emission, or (b) **`WebSocketProvider` SPI lands in v0.12** — full `WebSocketSession`, `WebSocketFrame`, `WebSocketHandler` family in `eu.exeris.kernel.spi.http.websocket`, Community NIO driver, Enterprise `io_uring` driver track. If (b), TCK covers RFC 6455 handshake compliance, frame fragmentation, ping/pong, close-code semantics, masking, and adversarial cases (oversized frame, malformed payload, partial-message split, post-close traffic). Either way, generator emission for the `realTimeApi` flag lands in `exeris-tooling/` in the same window.

**Merge Gate:** RFC accepted with one shape selected and dissenting position recorded; if (b), SPI green on TCK + Community binding green + isolation-key scoping verified (per-tenant WS rooms via `StorageContext.isolationKey`); if (a), SSE pattern documented in operator-facing docs with an explicit "WebSocket = post-1.0" note in the Support Matrix (per v0.9 §"Support Matrix Finalization").

---

### Runtime: `CacheProvider` SPI — RFC Track Only

**Gap:** `DomainMetadata.cacheable` + `cacheRegion` + `cacheTtl` flags exist in v0.8 SDK but no generator wires application services to a cache layer, and the kernel has no cache primitive. Caching is a runtime hot path with plausible alternative drivers (in-process Caffeine vs Redis vs off-heap slab), qualifying as SPI candidate territory; but committing to an SPI contract without a real second-backend pull risks designing the wrong shape — Caffeine-only deployments would carry contract weight they never exercise.

**Owner:** Runtime / Performance subsystem.

**Resolution:** Open an RFC enumerating contract questions: read-through vs read-aside semantics, invalidation strategy (TTL-only vs key-level invalidate vs region-flush), `PrincipalContext` / `StorageContext` scoping (per-tenant cache regions), serialization shape for distributed backends, async vs sync `get` semantics on Loom (blocking-on-Loom is acceptable but the contract must be explicit), interaction with the existing `WatermarkManager` SHED_LOAD decisions. RFC stops at "RFC accepted with preferred shape called out"; SPI lands only when a concrete second-backend pull materializes (downstream consumer requesting Redis, or benchmark demonstrating off-heap slab win). Until then, generator emission for `cacheable` flag remains a tooling-only Caffeine wiring.

**Merge Gate:** RFC accepted; no kernel SPI commits in this gate (decision-only track). SPI implementation gate deferred to a future version, conditional on real second-backend pull.

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
- OpenSSL 4 migration + provider-aware bindings
- off-heap key material zeroization
- FIPS readiness (optional, scope-decided before v0.9 closes)
