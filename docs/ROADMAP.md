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

**Status (v0.6): DELIVERED** — entry left stale until 2026-08-08, when the v0.11 FlowJournal RFC's inventory of flow JFR events contradicted it. `FlowEngineShutdownEvent` shipped in 0.6.0 (`5c56395d`) as `eu.exeris.kernel.core.flow.FlowEngineShutdownEvent` — package-private, `@Name("eu.exeris.kernel.flow.Shutdown")`, `@StackTrace(false)` — emitted from `CoreFlowEngine.close()` once at the end of the drain, carrying the elapsed close duration. Two deviations from the Resolution above, kept because the delivered shape is the better one: the class sits in `core.flow` rather than a `core.flow.jfr` sub-package (matching the six sibling flow events), and the counter set is `activeFlows` / `parkedFlows` / `completedFlows` / `failedFlows` plus `persistenceEnabled` / `compensationEnabled` rather than the proposed `parkedFlowCount` / `interruptedFlowCount` — a full `FlowEngineStats` snapshot instead of two hand-picked numbers. The merge gate is met on both sides: `AbstractFlowEngineTck` asserts the event via `RecordingStream` within 5 s of `close()`, and `CoreFlowRuntimeTest` carries two Core-side assertions.

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

**Status (v0.11): DISCHARGED** — by PERF-063, not by a later slice of this entry, which is why it sat open: the work landed under a different name and nobody closed the row. `NativeTcpReactor.pendingRequests` is an `MpscUnboundedArrayQueue` (`NativeTcpReactor.java:66`), so the cross-VT handoff this entry describes is lock-free today. Both merge-gate halves exist: `CommunityTransportCarrierPinningTckTest` plus its 2- and 4-reactor variants, and `NativeTcpTransportStressTest` behind the dedicated `transport-stress-gate` CI job.

Two deviations from the text above, recorded so the row is not re-opened by someone diffing wording against code: the queue is **JCTools**, not Agrona (`org.jctools:jctools-core` is already a Community dependency, so no new one was taken), and it lives in `NativeTcpReactor` rather than `NativeTcpCarrier` — the reactor is where the cross-VT handoff actually is. No separate per-connection ingress queue was added and none is indicated; the Sprint-8e Hot-Path Collections Review discharged that question for the carrier as a whole.

See also: [Transport Subsystem](./subsystems/transport.md) — Testing Strategy / Load Tests.

---

### Transport: Community Socket Path Migration to Core FFM Syscalls

**Gap:** The active Community transport carrier still uses Java NIO socket primitives (`ServerSocketChannel`, `SocketChannel`, `Selector`) for bind / accept / connect / readiness management. Core already ships cross-platform POSIX / Winsock symbol loading in `CoreSyscallLoader`, but Community does not yet consume that socket path.

**Owner:** Core / Transport subsystem.

**Resolution:** Migrate the Community carrier incrementally from primary NIO socket lifecycle management to the Core-provided FFM syscall path backed by `CoreSyscallLoader`. Preserve SPI blindness, PAQS semantics, and current TLS FD-owner binding while moving socket bootstrap and readiness operations onto the shared POSIX / Winsock layer. Java NIO remains the explicit portability / compatibility fallback when FFM socket bootstrap is unavailable, unsupported, or temporarily disabled.

**Merge Gate:** Add Community integration and TCK coverage proving boot, bind, connect, ingress, and load-shed behavior through the FFM-backed socket path. Linux validation is mandatory; Windows-capable CI coverage is required for Winsock compatibility before milestone close.

**Status (v0.11): PARTIAL — validation seam delivered, carrier migration deferred to v0.12 candidate.**

What landed: `SyscallLoopbackRoundTripIT` drives the resolved handles through a real loopback connection — socket → bind → listen → connect → accept → send → recv with a byte-exact comparison, plus a refusal case with a positive control. Until it, the seam had only non-nullness assertions, so the build could not distinguish working socket handles from merely-present ones. That is the precondition for trusting the path enough to move a carrier onto it, and it is now in the default build (Failsafe, `**/*IT.java`).

What did not land, and why it is not a slip: migrating `NativeTcpCarrier` off NIO is a live-traffic change to the one component every request crosses, and it should not be attempted while the Winsock half of the seam is unexecuted anywhere. **Named precondition, verified rather than assumed:** every job in `.github/workflows/maven.yml` is `ubuntu-latest`. There is no Windows runner, so `CoreSyscallLoader.loadWindows` and the Windows branch of the new IT have never run in CI or locally here. The migration's own merge gate demands Winsock compatibility coverage before milestone close, which no v0.11 slice could have satisfied.

**v0.12 entry condition:** a Windows CI runner exists and the new IT is green on it. Absent that, the honest v0.12 scope is Linux-only migration behind a fallback, with NIO retained as the documented portability path — which the Resolution above already anticipates.

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

**Status (v0.9):** **DELIVERED** — `Http2SessionContext` gained a per-connection **net** rapid-reset budget: each inbound `RST_STREAM` (`recordInboundRstStream()`) increments a counter, each request that reaches dispatch (`recordDispatchedRequest()`) credits it back (floor 0), so a peer doing real work — even one that cancels streams — keeps the net count low while an open-then-reset flood that performs no work drives it past the budget. Crossing `HTTP2_RAPID_RESET_BUDGET` (`200`, 2× the §6.5.2 concurrent-stream floor) makes `CommunityHttp2SessionProcessor` emit `GOAWAY(ENHANCE_YOUR_CALM)` (code `0x0B`) and stop the frame loop. **Net counter, not a time window:** decoupling the flood signal from completed work satisfies "no regression on legitimate `RST_STREAM` within budget" deterministically (no wall-clock / `Clock` dependency on the codec hot path). **Budget knob:** kept a codec-internal constant rather than an `HttpConfig` SPI field — this hardening is "no SPI change" (The Wall: an HTTP/2 logical stream is multiplexed over one TCP `TransportStream`, so there is no implementation-blind SPI seam), which also avoids an orphan config knob. Secret-safe single-phase JFR `Http2RapidResetFloodEvent` (net count + last processed stream id only) maps to `EX-HTTP-4010`. `Http2RapidResetSpecTest` un-`@Disabled` — flood-trips-at-budget + legitimate-cancels-do-not-trip, green; full HTTP suite (84 tests) green; `docs/subsystems/http.md` documents the defense.

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

1. **New SPI module / package** `eu.exeris.kernel.spi.diagnostics` in `exeris-kernel-spi`. Public types: `KernelDiagnostics` interface (4 methods — `listProviders`, `getBootstrapDag`, `describeSubsystem`, `getJvmErgonomics`; the originally-planned `listCapabilities` was removed pre-1.0 — see the ADR-033 v0.9.x amendment), `KernelDiagnosticsProvider` (ServiceLoader-discovered, `priority()` open-core hook), plus immutable snapshot records (`ProvidersSnapshot`, `BootstrapDagSnapshot`, `SubsystemSnapshot`, `RuntimeErgonomicsSnapshot`, nested descriptors). Every top-level snapshot carries `schemaVersion: String` (initial `"1.0"`, append-only growth policy) and `capturedAt: Instant`. No imports from `exeris-kernel-core`, no host-runtime types — Wall preserved.
2. **Community provider** `CommunityKernelDiagnosticsProvider` (`priority() = 0`) in `exeris-kernel-community`, reading from `KernelBootstrap` / `SubsystemOrchestrator` state. `META-INF/services/eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider` registration.
3. **CLI artefact** — new top-level Maven module `exeris-kernel-diagnostics-cli` in the root kernel reactor (sibling of `exeris-kernel-community`). Shaded executable JAR, `main` = `eu.exeris.kernel.diagnostics.cli.DiagnosticsCli`. Reads framed JSON requests on stdin, writes JSON responses on stdout; auth-free local-spawn mode (the spawning process is trusted). No separate Enterprise CLI — the same binary picks up `EnterpriseKernelDiagnosticsProvider` (priority 100) when the Enterprise jar is on the classpath, per the ADR-008 open-core extension model.
4. **TCK** — `AbstractKernelDiagnosticsTck` in `exeris-kernel-tck` plus a JSON schema fixture file asserted on every CI run; Community binding green; Enterprise binding obligation documented for `exeris-kernel-enterprise` 0.6 (Obligation 9 of ADR-033).
5. **JFR emission for diagnostic calls themselves** — codes `EX-DIAG-1001`..`EX-DIAG-1004` (one per method) emitted at INFO level so operators can audit out-of-process introspection. Zero-allocation contract holds because the call frequency is "per minute, not per request" (ADR-033 Obligation 2).

**Merge Gate:** SPI module compiles green with no `exeris-kernel-core` imports (ArchUnit check); Community provider passes `AbstractKernelDiagnosticsTck`; CLI artefact shades cleanly and runs as a child process under the `exeris-ai-bridge` integration test against a fixture kernel; JSON schema fixture asserted in CI; documentation drift swept against ADR-033's ten obligations.

**Cross-repo dependents:** `exeris-ai-bridge` 0.4.0 implements `src/transport/kernel-adapter.ts` against the CLI's stdio JSON protocol (ADR-025 §Engineering Protocol item 2 binding closure). `exeris-kernel-enterprise` 0.6 ships `EnterpriseKernelDiagnosticsProvider` (priority 100) returning the same record types with Enterprise-only fields populated where the ADR-033 §Obligation 6 use-case test applies; the Enterprise overlay is mirrored as a link stub at `exeris-kernel-enterprise/docs/adr/ADR-033.link.md`. Out-of-scope here: authenticated / remote diagnostic transport (lands in `exeris-ai-bridge` 0.6+ transport-auth work), any mutation surface, `bridge:health` synthetic derived checks (aggregator concern, not kernel SPI concern).

**Status (v0.9):** **DELIVERED** in Sprint 1 (ADR-033) — `eu.exeris.kernel.spi.diagnostics.*` is the first explicitly-**stable** new SPI surface; `CommunityKernelDiagnostics` + the shaded `exeris-kernel-diagnostics-cli` artifact + `AbstractKernelDiagnosticsTck` (+ pinned JSON schema fixture) landed. **Pre-1.0 trim:** `listCapabilities()` / `CompositionSnapshot` / `CapabilityDescriptor` were removed before the SPI froze (composition is a build-time/manifest concern); `EX-DIAG-1002` retired as a reserved gap.

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

**Status (v0.9):** **DELIVERED** — `getJvmErgonomics()` added to `KernelDiagnostics` as the fifth
method, shipped as a `default` returning `RuntimeErgonomicsSnapshot.unknown()` (binary-compatible for
third-party providers per ADR-033 Obligation 5). `RuntimeErgonomicsSnapshot` joins the snapshot family
(`schemaVersion` first, append-only — `schemaVersion` held at `1.0` since v0.9.0 publishes the whole
surface as the first schema): GC name, heap max/committed, `availableProcessors`, cgroup-v2 `cpu.max`
(quota+period), `memory.max`, `cpuset.cpus.effective`, and best-effort large-pages / THP / CDS / AOT
state — all environment-sensitive fields `Optional.empty()` when undeterminable (non-Linux / cgroup-v1 /
no limits). `CommunityKernelDiagnostics` overrides it via `CommunityRuntimeErgonomics`
(`java.lang.management` + defensive procfs/cgroupfs reads; never throws), emitting audit code
**`EX-DIAG-1005`** (telemetry registry row extended `..1004` → `..1005`). The CLI gains a
`getJvmErgonomics` NDJSON method; `AbstractKernelDiagnosticsTck` gains the well-formedness,
`Optional.empty()`-degradation, and `default`-method-compat cases plus the append-only schema-fixture
growth; the JFR test asserts `EX-DIAG-1005`. Generic operator guidance landed at
`docs/operations/jvm-flags-baseline.md` (no Enterprise-private thresholds, ADR-008). ADR-033 amended in
the same change (Obligation 9 four-method → five-method; EP step 8 `EX-DIAG-1001..1004` →
`..1005`). Enterprise `JvmErgonomicsProbe` (EPIC-E8 E8.23) consuming the same record + layering the
`jvm.flags` advisory is a cross-repo follow-up.

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

**Status (v0.9):** **DELIVERED** in Sprint 8 (#205) — `docs/support-matrix.md` published: supported runtime baseline (JDK 26, Postgres 16, Kafka 3.6/CP 7.6, OpenSSL 3.0–4.x, HTTP/1.1+h2/h2c), per-subsystem SPI status mirroring `stability-matrix.md`, Community-tier limits, Enterprise-only out-of-scope items, and deferred capabilities (incl. the streaming shift to v0.10/v0.11). Bidirectionally cross-linked with `stability-matrix.md` + `operations/reference-deployment.md`.

---

### Upgrade / Restart / Recovery Validation

**Gap:** 1.0 requires confidence in operational continuity, not just fresh boot behavior.

**Owner:** Cross-cutting / Runtime.

**Resolution:** Validate upgrade behavior, restart behavior, recovery semantics, degraded-mode transitions, and readiness/liveness correctness across lifecycle changes.

**Merge Gate:** Operational continuity suite is green on release-candidate branch.

**Status (v0.9):** **DELIVERED** in Sprint 7 (#203) — reversible **`DEGRADED`** subsystem-health state in `KernelHealthMonitor` (Core-internal; `HealthProbe` untouched, The Wall held) + Community `CommunitySubsystemHealthWatcher` driving it from live subsystem health. A required-`DEGRADED` subsystem drains readiness (`503` + `X-Exeris-Health: DEGRADED`) while liveness holds; reversible to `RUNNING`. `SubsystemHealthTransition` JFR. Degraded-mode (real Postgres stop) + restart-recovery (real engine restart → parked-saga checkpoint survives) Testcontainers ITs, gated by the new **`recovery-continuity-gate`** CI job. **Carried to v0.10:** vN→vN+1 two-version upgrade fixture + outbox exactly-once dedup across restart.

---

### Reference Deployment Preparation

**Gap:** 1.0 cannot ship as a purely theoretical runtime.

**Owner:** Runtime / Docs / Operations interface.

**Resolution:** Prepare a reference deployment path with documented topology, runtime profile, resource envelope, observability setup, and known operational limits.

**Merge Gate:** Reference deployment documentation and validation checklist completed.

**Status (v0.9):** **DELIVERED** in Sprint 8 (#205) — `docs/operations/reference-deployment.md`: single-node Community topology (kernel + Postgres + optional Kafka/JWKS), runtime profile (JDK 26 preview, `kernel.profile=PROD`, OpenSSL 3.0–4.x), reference resource envelope (explicitly *validate per workload*; defers measured throughput to the `exeris-benchmarks` harness), observability (health probes incl. the `DEGRADED` semantics, Prometheus pull, JFR), and the restart/degraded continuity procedure. Cross-linked with `support-matrix.md` + `bootstrap.md`.

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

**Resolution:** Open an RFC in this repo's `docs/rfc/` — the owning repo holds the RFC for an SPI it will own (#230); `exeris-docs/` hosts only ecosystem-wide and business-shaped RFCs (`RFC-YYYY-MM-DD Identity Provider SPI Shape.md`, per `exeris-docs/templates/RFC-TEMPLATE.md` — RFC not ADR because this is a multi-option strategic question with no committed decision yet). Scope enumerates: option space (OIDC-first vs PASETO-first vs federation-first vs custom-claim-first), interaction with the existing v0.9 JWKS rotation item, claims-to-`PrincipalContext` mapping contract, multi-IDP composition (per-tenant IDP selection driven by `StorageContext.isolationKey`), failure-mode classification (signature invalid / claims malformed / token expired / IDP unreachable), and TCK strategy. RFC accepted → ADR number reserved when the implementing change reaches its build gate, **not** at RFC acceptance (deferring the reservation keeps the global namespace free of numbers that may never be written) → ADR drafted → SPI implementation lands in v0.10.

**Outbound / WebClient touchpoint (MUST be in RFC scope — added 2026-06-02).** The IDP SPI is not inbound-only. `PrincipalContext` is the convergence point for *both* directions: IDP **writes** it inbound (token → `PrincipalContext` via `ScopedValue`), and the outbound `HttpClientRequestEnricher` SPI (ADR-032, consumed by `KernelWebClient`) **reads** it to propagate identity downstream. The two never couple directly — they meet only at `PrincipalContext` (no `KernelWebClient`→IDP compile edge; The Wall preserved). The RFC MUST resolve the service-to-service auth question, because today the enricher seam carries only parsed identity (`X-Tenant-Id` / `X-Principal-Id`), **not** the raw `Bearer` — the kernel holds the parsed `PrincipalContext`, not the raw token. Enumerate the propagation options: (a) parsed-identity headers only, downstream re-validates via its own IDP; (b) pass-through bearer — requires the IDP SPI to retain/expose the raw credential, which the enricher forwards; (c) token exchange / on-behalf-of / client-credentials — IDP exposes an *outbound credential / token-source seam* the enricher consumes. The decision determines whether `HttpClientRequestEnricher` needs a companion `OutboundCredentialProvider` seam (or an IDP method `outboundCredential(PrincipalContext) → Optional<Credential>`). Decide one contract in the same RFC → the reserved ADR covers both inbound validation and outbound propagation. See the deferred WebClient entries (Symmetric Body Codec, Retry/Backoff) for the adjacent client-side SPI surface that lands alongside.

**Merge Gate:** RFC accepted with one preferred option called out and dissenting positions documented; no kernel code changes in this gate (research / decision track only). Implementation gate deferred to v0.10.

**Status (v0.9):** **DELIVERED** in Sprint 3 — `docs/rfc/RFC-2026-06-08-identity-provider-spi-shape.md` (ACCEPTED). Three axes decided: **structural** = dedicated `IdentityProvider` SPI (`eu.exeris.kernel.spi.security.identity`) with `SecurityProvider` as a thin dispatcher (mirrors the ADR-034/036 registry/driver shape); **first driver** = OIDC+JWKS (`CommunityOidcIdentityProvider`, mostly a refactor of today's `CommunityJwksValidator`); **outbound** = parsed-identity headers (ADR-032 status quo) for v0.10, outbound-credential seam reserved. Dissenting positions (PASETO-first / federation-first / custom-claim-first; structural extend-in-place; outbound pass-through / token-exchange) recorded. Claims→`PrincipalContext` mapping, multi-IDP issuer-dispatch + `StorageContext.isolationKey` routing, failure-mode → `EX-SEC-2002` (fail-closed, terminal-deny, no fall-through), and TCK strategy (`AbstractIdentityProviderTck` + extended `AbstractSecurityProviderTck`) all scoped. **ADR-040 reserved** in `exeris-docs/adr-index.md` (PROPOSED — content + SPI implementation in v0.10). v0.9 Sprint 4 JWKS rotation is a load-bearing dependency. Zero kernel code this sprint.

---

### Config: `@Immutable` Annotation Enforcement for Config Keys

**Gap:** The comprehensive technical document targets `@Immutable` annotation enforcement for config keys that must never be hot-reloaded under any circumstance (security trust anchors, isolation boundaries, native library paths). The annotation type does not exist in the SPI; there is no compile-time or runtime enforcement that prevents a security-critical key from being accidentally tagged `@Dynamic` (see v0.8 §"Config: `@Dynamic` Hot-Reload via `WatchService`" for the inverse capability).

**Owner:** Config / Security subsystem.

**Resolution:** Add the `@Immutable` annotation to `exeris-kernel-spi`; extend the `WatchService` driver from v0.8 to refuse re-read of `@Immutable`-flagged keys with a structured error; add a compile-time check (APT processor in `exeris-kernel-build-config`) that rejects keys carrying both `@Immutable` and `@Dynamic`; publish the security-relevant key catalog that must carry `@Immutable` at GA.

**Merge Gate:** APT processor catches the mutually-exclusive annotation combo; runtime watcher refuses `@Immutable` re-read with a structured event; documented `@Immutable` key catalog reviewed and signed off by Security.

**Status (v0.9):** **DELIVERED** in Sprint 5 — `eu.exeris.kernel.spi.config.Immutable` (RUNTIME-retained, `@Target(FIELD)`, `file`/`key`/`reason` members) seals a `static final` config key against hot-reload, the deliberate inverse of `@Dynamic`. **Compile time:** `ImmutableConfigProcessor` in `exeris-kernel-build-config` (registered via `META-INF/services`, reads annotation FQNs — no SPI reactor edge, mirrors `RequiresRoleProcessor`) fails the build on the mutually-exclusive `@Immutable`+`@Dynamic` combo and on a non-`static-final` placement (`ImmutableConfigProcessorTest`, 3 cases green). **Runtime:** `KernelConfigRegistry` gained sealed-key guards (`registerImmutable` / `isImmutable` / `immutableRegistrations`, same `VarHandle`-sealed discipline) exposed through the new symmetric SPI seam `ConfigProvider.guardImmutable(file, key)` (Community no-op; `RegistryBackedConfigProvider` records the guard). `DynamicConfigFileWatcher` captures each sealed key's boot value on `start()` (before the watcher VT spawns — no seed/event race), and on a subsequent on-disk change **refuses** the reload, preserves the sealed value, and emits the secret-safe single-phase `ImmutableReloadRefusedEvent` (**EX-CFG-1004**, file + key only — never the value); a contradictory `@Dynamic` binding that ever reached runtime is defended against (sealed intent wins). `DynamicConfigFileWatcherTest.ImmutableKeyRefusal` proves a sealed key is refused while a sibling `@Dynamic` key still reloads, and asserts the JFR event. **Catalog:** `docs/subsystems/config.md` §"Security-Relevant Key Catalog" enumerates the trust-anchor classes (security/identity anchors, tenant isolation boundaries, native library paths) that must carry `@Immutable` at GA — no production key is wired to a watcher today, so each is already effectively sealed; adopting the annotation per key is GA hardening. No new ADR (additive realization of the contract already documented in `config.md`); stability **preview** until the `AbstractConfigProviderTck` binding lands.

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

**Status (v0.9):** **DEFERRED to v0.10** (covers this + the two sibling HTTP-client entries below — Generic-Element Decode and Retry/Backoff Policy). Per the 2026-05-18 "Path B" decision, Sprint 6 was **design-only**: the codec/retry surface shape is locked by the ADR-034 family, with **zero kernel SPI commits in v0.9**. Implementation lands when a concrete external consumer or a measured zero-alloc win materialises (the merge-gate trigger above).

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

**Status (v0.9):** **DEFERRED / CARRIED** — not implemented in v0.9 (no `OPENSSL_cleanse` binding landed). Carried forward; pairs naturally with the FIPS workstream below.

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

**Status (v0.10):** **DELIVERED** in Sprint 1 (ADR-040, RFC-2026-06-08 Option S-A). New SPI package `eu.exeris.kernel.spi.security.identity` — `IdentityProvider` + `IdentityProviderRegistry` (priority single-select, fail-closed, **no** fallback-on-failure = anti-token-confusion; `canAttempt` = routing-not-trust), `TokenValidator` → format-blind `VerifiedClaims`, `ClaimsMapper` (claims → `PrincipalContext`, identity only), `IdentityStorageMapping.fromClaims` (the single kernel-owned fail-closed `KernelIsolationClaims` → `StorageContext` mapping per S-P0-07), and `KeyRotationPolicy` promoted from Community. `CommunitySecurityProvider` rewritten as a thin dispatcher over the registry. First driver `CommunityOidcIdentityProvider` (OIDC+JWKS) composes `CommunityOidcTokenValidator` (RS256 pipeline byte-for-byte from the former `CommunityJwksValidator`) + `CommunityClaimsMapper` + the v0.9 `CommunityRotatingKeySet` via the `overJwksEndpoint` factory. **HTTP lifecycle wiring needed zero edge changes** — the dispatcher remains the `SecurityProvider` entry point, so `SecurityInterceptor` keeps populating `PrincipalContext` / `StorageContext` via `ScopedValue` before dispatch (the S-A architectural payoff). `AbstractIdentityProviderTck` (+ Community OIDC binding, 7/7) enforces trust-free routing, fully-populated result, and terminal `EX-SEC-2002` deny on every validation failure; JFR `IdentityValidation` / `IdentityRejection` (single-phase commit, secret-safe). A **live Keycloak Testcontainers IT** proves a real RS256 token → JWKS-over-HTTP fetch → populated `PrincipalContext` end-to-end, plus foreign-issuer fail-closed deny. The IT surfaced and fixed two latent live-path defects no unit test could reach: the JWKS fetch needed a raw-text response decoder (`CommunityTextResponseBodyDecoder` — a JSON decoder cannot coerce a JWKS object into a `String`), and an empty initial key generation in `CommunityRotatingKeySet` read as fresh and never refreshed (now stamped at the epoch so the first resolve triggers the lazy fetch). **Deferred:** config-driven construction (jwks-uri/issuer/audience properties) + ServiceLoader / `META-INF/services` discovery — the `overJwksEndpoint` programmatic seam is the supported entry point this milestone. **Follow-up (perf, non-blocking):** `CommunityOidcIdentityProvider.canAttempt` currently does a full `SignedJWT.parse` for the unverified `iss` peek, then `authenticate` parses again — two full JWT parses per request in the common single-IDP path. A lightweight routing check (base64-decode only the body segment, scan `iss` without a second Nimbus parse) would make the peek genuinely O(1) over the buffer; worth doing once the router dispatches across multiple concurrent issuers (`canAttempt` called N× per request). The Wall intact (no Spring / host-runtime types in SPI/Core); `PrincipalContext` carrier additive-only.

---

### Events: Binding-Agnostic `topic` Concept on the Event Descriptor SPI

**Gap:** The Open-Core event descriptor carries no `topic` concept, so the event routing target is not portable across `EventEngine` bindings. The Community in-memory `EventBus` ignores any topic notion entirely, while the `exeris-kernel-community-kafka` driver invents its own topic mapping at the binding layer — there is no shared SPI seam that both implementations honour, so the routing target is not swappable between the in-memory and Kafka bindings. The SDK `@DomainEvent.topic` annotation (owned by `exeris-sdk`) already captures author intent at design time and is carried through codegen, but it has no sink on the kernel side: the descriptor SPI offers nowhere to land it. The deliberate tiering decision (why the open-core descriptor omits `topic`) is also undocumented. Surfaced during downstream dogfooding (a multi-service build, 2026-06; finding K1 + the `@DomainEvent.topic` twin).

**Owner:** Events subsystem (kernel SPI seam). The `@DomainEvent.topic` annotation itself stays owned by `exeris-sdk` — this entry covers only the kernel-side descriptor field that would consume it.

**Resolution:** Either (a) **document the deliberate tiering** — record in `docs/subsystems/events.md` why the open-core event descriptor has no `topic` and that broker routing is a binding-private concern — or (b) **promote `topic` to a binding-agnostic field on the event descriptor SPI** so it becomes the shared routing target, swappable between the Community `EventBus` (which may map it to a logical channel or treat it as advisory) and the Kafka driver (which maps it to a Kafka topic). If (b): keep the field optional/additive (no breaking-change framing per pre-1.0 / TRL-3 stance), define absent/empty semantics for in-memory bindings, and wire codegen so `@DomainEvent.topic` populates it. **Encoding constraint:** `EventDescriptor` is primitive-only by contract (`events.md` §1 — composed exclusively of `long` / `int` for Valhalla scalarization and O(1) zero-allocation dispatch), so `topic` must be carried as an ordinal `int` registered in `EventRegistry` (same pattern as event-type ordinals), **not** a bare `String` field — a mixed record would lose C2 scalarizability today and block value-record migration under JEP 401. Low priority — this is primarily a documentation-or-small-SPI decision, not a runtime feature.

**Merge Gate:** Decision recorded in `docs/subsystems/events.md`; if (b), the descriptor carrier change is additive and covered by an `AbstractEventEngineTck` assertion that `topic` round-trips through both the Community in-memory binding and the Kafka binding (Testcontainers), plus an end-to-end check that the SDK→codegen→SPI population path lands the captured `@DomainEvent.topic`.

**Status (v0.10):** **kernel slice DELIVERED** — resolved as **(b)**, with the carrier corrected from "field on the descriptor" to **`EventTypeSpec.topic`** (per-event-*type* registration, ADR-050): topic is a static per-type attribute, so it rides the type spec alongside the existing `name` `String` (off the hot dispatch path) and the primitive-only Valhalla `EventDescriptor` + both Kafka wire codecs stay byte-for-byte unchanged — the ROADMAP's "ordinal `int` registered in `EventRegistry`" encoding constraint (motivated only by the rejected descriptor placement) is thereby moot. `EventTypeSpec` gains optional `String topic` + `hasTopic()`; the Kafka binding honours the override on **both** publish and subscribe (`effectiveTopic`); the in-memory bus is topic-blind (advisory). Merge-gate coverage: `AbstractEventRegistryTck` topic round-trip + identity on the Community registry and `KafkaEventRegistryTest` on the Kafka registry (both bindings), `KafkaTopicResolutionTest` (override/fallback/prefix decision), `AbstractKafkaEventEngineTck` override round-trip (Testcontainers), `AbstractEventBusTck` topic-blind note. **Remaining lockstep (separate repos):** the `exeris-tooling` `KernelEventGenerator` populate (it reads `@DomainEvent.topic` today but drops it to a Javadoc-only reference for lack of a sink) + the SDK→codegen→SPI e2e; the `exeris-sdk` `@DomainEvent.topic` "Open-Core status" Javadoc stance update.

See also: [Events Subsystem](./subsystems/events.md); `docs/adr/ADR-050-events-binding-agnostic-topic.md` (Accepted); the sibling **Event-Payload Codec SPI** entry below (both touch the publish path + the SDK→codegen→kernel population chain, and are candidates to sequence together).

---

### Events: Event-Payload Codec SPI — Pluggable Domain-Event Payload Serialization (ADR-046, EV1 runtime half)

**Gap:** The event bus carries already-serialized bytes — `EventBus.publish(EventDescriptor, EventPayload)` takes an `EventPayload` wrapping a read-only `MemorySegment` (`CommunityHeapEventPayload` wraps a `byte[]`), and the outbox persists `byte[]`. Serialization must therefore happen **before** the bus, but the kernel exposes **no seam to do it**: there is a complete `{request,response}×{encode,decode}` HTTP body-codec matrix (`HttpRequestBodyDecoder` etc., ADR-009/034/036) but nothing for **event** payloads. So the generated `*EventPublisher` ships `EventPayload.empty()` (zero bytes) and generated domain events carry no data — the runtime half of the EV1 (`@DomainEvent` payload realization) lever. The design-time/metadata half (`DomainEventMetadata.payloadFields` / `sensitiveFields`, processor extraction, `-io` parity, typed-TS payload) lands independently in `exeris-sdk` / `exeris-tooling` and does **not** need this SPI. The founder intent is explicit: payload serialization must be client-selectable (JSON / gRPC / other), JSON by default — not baked into generated code. Surfaced during downstream dogfooding (EV1 payload exercise, 2026-06).

**Owner:** Events subsystem (kernel SPI seam) + `exeris-tooling` (the generated `KernelEventGenerator` publisher rewrite, lockstep).

**Resolution:** Introduce a tier-neutral `EventPayloadCodec` + `EventPayloadCodecRegistry` in `eu.exeris.kernel.spi.events.codec`, registry-selected by `(payloadType, contentType)` by descending `priority()` with the `of(...)` contract copied from `HttpRequestBodyDecoderRegistry` (default `application/json`). Ship a Community `CommunityJsonEventPayloadCodec` (reuses `JsonBodyCodecs`; Jackson stays a driver detail behind The Wall). **Resolve in the generated publisher (ADR-036 "site B"), not in the bus** — the `*EventPublisher` builds the redacted payload object from `DomainEventMetadata` (whitelist `payloadFields`, drop `sensitiveFields`), resolves a codec via a new optional `KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY` slot (mirrors `EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER`; **not** `HttpKernelProviders`), encodes to an `EventPayload`, and calls the existing `bus.publish(...)`. This keeps `EventBus` / `EventEngine` / `EventPayload` **byte-for-byte unchanged** (strictly additive: SPI types + driver + slot + TCK). The `EventCodecContext` carrier is format-only (`contentType`, `eventTypeName`) — redaction stays in the publisher, not the codec. **Encoding constraint:** per-event content-type cannot ride `EventDescriptor` (primitive-only by contract — §1 "Valhalla-Ready Routing"); v0.10 default is kernel-wide `application/json`, overridable by config; per-event selection (if ever) must be ordinal-interned like `topic` — deferred. The decode half ships on the interface for symmetry but only encode (publish) is wired this milestone; handler-side decode (`@EventHandler` consumers) is deferred.

**Merge Gate:** `AbstractEventPayloadCodecTck` bound in Community against `CommunityJsonEventPayloadCodec` and registered in CI (no orphan `Abstract*Tck`) — round-trip per content-type, registry priority + tie-by-registration, unknown-type / unknown-content-type behaviour, RAII ownership of the returned `EventPayload`, and driver-exception opacity (`startsWith("java.")` / `doesNotStartWith("tools.jackson")`). The `exeris-tooling` e2e fixture asserts the generated publisher carries no `tools.jackson.*` symbol and resolves via `KernelProviders.eventPayloadCodecRegistry()` (build-time Wall check). Additive only (no breaking-change framing per pre-1.0 / TRL-3); The Wall preserved (SPI in `-spi`, JSON impl in `-community`, Core codec-agnostic).

**Status (v0.10):** **kernel SPI + bootstrap wiring DELIVERED** (ADR-046, Accepted). PR #222 (PR-A): `EventPayloadCodec` / `EventPayloadCodecRegistry` / `EventCodecContext` in `eu.exeris.kernel.spi.events.codec`, Community `CommunityJsonEventPayloadCodec`, `AbstractEventPayloadCodecTck` + Community binding, `KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY` slot + accessor. PR-A.1: `EventProvider.eventPayloadCodecRegistry()` (default + Community override), `CommunityEventsSubsystem` binds the slot at scope init, and `CommunityEventPayloadEncodeFailedEvent` JFR (secret-safe) + emission test. Strictly additive — `EventBus`/`EventEngine`/`EventPayload` unchanged. Sequenced with the sibling Events `topic` item. **Remaining lockstep:** the `exeris-tooling` `KernelEventGenerator` `*EventPublisher` rewrite (PR-B) + the codec-resolution-failure JFR (emitted by the publisher).

See also: [Events Subsystem](./subsystems/events.md); `docs/adr/ADR-046-event-payload-codec-spi.md` (Accepted); ADR-036 (the request-decode quadrant this mirrors, incl. site-B resolution); the sibling Events `topic` entry above.

---

### Events: Log-Ordering & Optimistic-Concurrency Boundary Not Owned by the Events SPI (sourcing/streaming fundament)

**Gap:** The "one log, four views" shape — *streaming* = log read forward as transport; *sourcing* = log as source of truth, state = fold over replay; *KV* = log compacted to last-value-per-key; *distributed* = the same log replicated across nodes — requires a single, explicit consistency boundary on the **Events** surface that all four derivations honour. Today that boundary is **not on the Events SPI**. Code audit (2026-06-22) found:
- The optimistic-concurrency CAS lives entirely on the **Persistence** side: `FlowSnapshot.schemaVersion` (`exeris-kernel-spi/.../spi/flow/model/FlowSnapshot.java:71`) enforced by `JdbcFlowSnapshotStore` `SQL_UPDATE_OCC` (`exeris-kernel-community/.../flow/JdbcFlowSnapshotStore.java:107`). `EventStreamAppender.append(...)` (`exeris-kernel-spi/.../spi/events/EventStreamAppender.java:62`) is **fire-and-forget** — no expected-version / sequence parameter — and `EventStore.append(OutboxEvent)` is likewise versionless. There is no append-with-expected-version contract anywhere on the Events surface.
- The in-memory `EventBus` provides **no ordering guarantee**: `InMemoryEventBus.publish()` (`.../core/events/InMemoryEventBus.java:136`) starts one virtual thread per handler — concurrent fan-out, fire-and-forget — so there is no per-key / per-aggregate / FIFO delivery order by construction, and `AbstractEventBusTck` asserts none.
- `EventStreamReader` / `EventStreamAppender` / `EventStream` remain **skeletons** (SPI interfaces + unbound `ScopedValue` slots in `KernelProviders`, zero main-source implementors; `KafkaEventEngine` javadoc states replay is "deferred"). There is no working replay path, so sourcing's "state = fold over replay" and KV's "rebuild from compacted replay" both have **no substrate** today.

Sourcing (per-aggregate strict ordering + optimistic-concurrency append + infinite retention) and streaming (fan-out throughput + consumer offset + time/size retention) want *different* guarantees from the *same* log. The kernel has not decided **where that guarantee lives or proven it on both bindings** — this is the load-bearing "completion" that gates KV and distributed: both must respect a contract that does not yet exist on the Events SPI.

**Owner:** Events subsystem (SPI seam) + Persistence (current CAS owner).

**Resolution:** Decide and record where per-stream ordering + optimistic-concurrency append live (Events SPI vs Persistence). Make it explicit in the contract: give `EventStreamAppender.append(...)` an expected-sequence / expected-version parameter (or a documented "no-ordering" stance for the in-memory binding), define per-`StreamId` ordering semantics, and **bind `AbstractEventStreamReaderTck` / `AbstractEventStreamAppenderTck`** (currently unbound — "blocked on durability driver") against at least the Community JDBC binding and the Kafka binding so the guarantee is proven on both. Add a per-key ordering assertion to `AbstractEventBusTck`, or document the in-memory bus as explicitly unordered. This is the SPI that KV (compacted projection) and distributed (replicated log) must respect — settle it first.

**Merge Gate:** Ordering/concurrency boundary recorded in `docs/subsystems/events.md`; `EventStreamAppender` contract states its sequencing semantics; `AbstractEventStreamReaderTck` / `AppenderTck` bound and green on ≥2 bindings; `AbstractEventBusTck` either asserts the documented ordering or documents its absence; additive only (no breaking-change framing per pre-1.0 / TRL-3 stance); The Wall preserved (no broker/JDBC types leak into the Events SPI).

**Status (v0.10):** **DECISION LANDED (ADR-049, Accepted 2026-07-01).** The ownership boundary is settled: per-`StreamId` total ordering + optimistic-concurrency **append-with-expected-version** are owned by the Events SPI durable-log surface (`EventStreamAppender`); the transient `EventBus` stays **unordered by design**; `FlowSnapshot.schemaVersion` CAS stays Persistence-owned (ADR-013) as a distinct mechanism. This decision-only slice ships ADR-049 + the `events.md` boundary record + the `EventStreamAppender` Javadoc; it also corrected a stale doc note that attributed event OCC to a non-existent `PersistenceEngine.append(streamId, expectedVersion)`. **SPI surface landed (implementation slice, v0.10):** the `EventStreamAppender` signature change (`AppendResult append(StreamId, long expectedVersion, EventDescriptor, EventPayload)` + `ANY_VERSION`), `EX-EVENT-6008` + `EventStreamAppendConflictException`, the `EventStreamReader` ordering contract, and the updated abstract TCKs (`AbstractEventStreamAppenderTck` ordering + OCC; `AbstractEventBusTck` no-ordering note). **Merge gate ≥2 durable bindings — CLOSED.** Community **Postgres event-log** binding — `JdbcEventStreamAppender` / `JdbcEventStreamReader` over `exeris_event_log` (`V0.10.0`), bound by `CommunityEventsSubsystem`. Community **Kafka event-log** binding — `KafkaEventStreamAppender` / `KafkaEventStreamReader` over a `streamId`-keyed log topic, **log-authoritative** (1-based `committedSequence` stamped in the frame; in-memory head + log-tail recovery; no compacted-head topic, no transactions). Both extend `AbstractEventStreamAppenderTck` / `AbstractEventStreamReaderTck` on Testcontainers (`CommunityJdbcEventStream{Appender,Reader}TckIT`, `CommunityKafkaEventStream{Appender,Reader}TckIT`) and prove the append→replay ordering round-trip. **ADR-049 fundament complete.** Kafka OCC is single-writer-per-stream best-effort (Kafka has no cross-instance CAS — documented Community limit; a scale/durability upgrade is deferred).

See also: [Events Subsystem](./subsystems/events.md); v0.7 EVENT-203 (skeleton delivery); ADR-013 (FlowSnapshot OCC); v0.12 §"Runtime: KV-as-Projection".

---

### Multi-Tenancy: Shared-World / `Universe` Scope Tier — RFC Track

**Gap:** The kernel models isolation through a single tenant-scoping primitive (`StorageContext.isolationKey`, per ADR-012). There is no scope tier above (or beside) the tenant for a *shared* world that multiple tenants observe and mutate together — e.g. a shared game universe, a common reference dataset, or a cross-tenant collaboration space. Today applications needing this force `isolationKey` to do double duty (one key meaning both "tenant" and "shared world"), which collapses the two concerns and either over-isolates (no sharing possible) or under-isolates (the tenant boundary leaks). Surfaced during downstream dogfooding (a multi-service build, 2026-06; finding K3, High).

**Owner:** Security / Persistence subsystem (isolation model) + SDK/Tooling (scope expression at design time).

**Status (v0.10): ✅ RFC ACCEPTED (2026-07-02).** `docs/rfc/RFC-2026-07-02-shared-scope-isolation-tier.md` (kernel repo, per the owning-repo-holds-the-SPI-RFC convention — *not* `exeris-docs/`; that repo takes only the eventual ADR-012-amendment row). The load-bearing hinge is **ruled orthogonal**: the shared tier is a row-visibility dimension that composes with the physical `IsolationStrategy`, not a fourth mutually-exclusive strategy. Kernel-neutral name **shared scope** (`sharedScopeKey` / `SHARED_WORLD`); "Universe" stays the SDK/game-facing name. Write model: **read-widen + owner-scoped write**. Fail-closed inheritance from ADR-012 §4a unchanged. Decision-only gate closed (no SPI commit); the SPI carrier + RLS mode land in a later milestone as an ADR-012 amendment.

**Resolution:** ~~Open an RFC in `exeris-docs/`~~ — RFC authored in `exeris-kernel/docs/rfc/` and ACCEPTED (see Status above; the `exeris-docs/` placement note was superseded by the owning-repo convention). Retained for scope reference — RFC not ADR because this is a multi-option model change touching the load-bearing ADR-012 isolation contract, with no committed decision. Scope enumerates: whether the shared-world tier is a new orthogonal dimension on `StorageContext` (e.g. `universeKey` independent of `isolationKey`) vs a reserved sentinel value of `isolationKey` vs a composite scope carrier; read/write semantics for shared vs tenant-private state; interaction with the `StorageContextBridge` derivation path and persistence isolation queries; how the security model authorizes cross-tenant access to shared scope (whether `CitadelGuard` / `PrincipalContext` need a shared-scope grant); blast-radius / fail-closed defaults so a misconfigured shared tier never silently widens tenant visibility; SDK/Tooling expression (how a domain author declares an entity shared-world vs tenant-scoped); and TCK strategy. **The load-bearing hinge this RFC owns** is whether the chosen carrier makes `universe` *mutually exclusive* with `tenant` (a flat three-way tier) or *orthogonal* to it (an entity could be both tenant-scoped and universe-keyed) — this is an isolation-model property, and it is the exact input the SDK's design-time surface is blocked on (see the coordination note below). RFC accepted → **either** amend ADR-012 (if scope stays bounded to the isolation model — reuses ADR-012's number, no reservation) **or** reserve a new ADR number in `~/exeris-systems/exeris-docs/adr-index.md` for a companion isolation-extension ADR (if the shared-world tier grows its own surface) → SPI/model change lands in a later milestone. Expected path: ADR-012 amendment.

**Coordination (SDK design-time half — two coordinated RFCs, not one):** the SDK owns the *scope-expression* half — `exeris-sdk/docs/rfc/RFC-2026-06-24-universe-data-scope-expression.md` (DRAFT) recommends an AST-owned `DataScope { GLOBAL, TENANT, UNIVERSE }` discriminator replacing the `tenantScoped` boolean's intent, shipped **reserved** (streaming-style honesty note, no enforcement claimed) and built only once this kernel RFC selects a carrier and the RLS mode lands. It is explicitly *downstream* of this entry (one-way dependency, the ADR-037/038 cross-repo coordinated-pair pattern — both registered in `exeris-docs/adr-index.md`, not this repo's `docs/adr/`), and its central open question (flat-enum vs orthogonal-facet) resolves on this RFC's mutually-exclusive-vs-orthogonal hinge above. Keep them separate: different owners, merge gates, and lifecycles (kernel = decision-only isolation model; SDK = design-only, build-on-trigger surface with a `tenantScoped` deprecation pipeline). Resulting ADRs are coordinated (kernel ADR-012 amendment + a thin SDK expression ADR with `.link.md` stubs), numbers reserved only after this RFC accepts.

**Merge Gate:** RFC accepted with one shape selected and dissenting positions recorded, including an explicit ruling on the mutually-exclusive-vs-orthogonal hinge; no kernel SPI commits in this gate (decision-only track). Any subsequent isolation-model change must preserve ADR-012 fail-closed guarantees, extend the relevant isolation TCK with a shared-vs-tenant access matrix, and remain additive on `StorageContext` (no breaking-change framing per pre-1.0 / TRL-3 stance).

See also: ADR-012 (isolation model); `exeris-sdk/docs/rfc/RFC-2026-06-24-universe-data-scope-expression.md` (SDK design-time half); ADR-037 / ADR-038 (cross-repo coordinated-pair precedent); [Security Subsystem](./subsystems/security.md).

---

### HTTP: Generated-App Boot-Path Reachability — Path-Parameter Routing + Request-Decoder Scope

**Gap:** Two distinct kernel-side gaps surface only when a generated Entity-First app is driven over a *real* kernel HTTP boot (not a handler unit test) — the exact thing a downstream generated-app HTTP boot test does, and which substring assertions on emitted `RuntimeLifecycle` text cannot see:
- **Path-parameter routing (finding T21, High).** `HttpRouter.resolve()` (`exeris-kernel-core/.../http/routing/HttpRouter.java`) matches by exact `path.equals` + opt-in prefix only — there is **no `{id}` placeholder capture**. The generated `RuntimeLifecycle` registers `GET/PUT/DELETE /x/{id}`, so `{id}` is matched *literally*: `/x/<uuid>` never equals `/x/{id}` and every by-id / update / delete route — plus every `@Action` route, which is also id-bearing (finding T1) — **404s on a real boot**; only collection routes serve. The generated handler is correct (`extractPathId` parses the trailing segment) — it just never gets called. Latent because the handler unit test invokes `handleGetById(exchange)` directly, bypassing the router. Confirmed still open on `0.10.0-SNAPSHOT` (the SSE work did not touch it; SSE routes are collection-level `{base}/stream` and sidestep it).
- **Request-decoder request-scope (write-over-HTTP, High).** The server-side request-body decoder quadrant shipped in v0.8 (ADR-036: `HttpRequestBodyDecoderRegistry` + the `HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY` ScopedValue slot), and Community ships `CommunityJsonRequestBodyDecoder`. But the community testkit boot fixture `KernelBootstrapHttpEngineFixture` binds only `HttpKernelProviders.HTTP_SERVER_HANDLER` into request scope — **not** the decoder registry — so a `POST` with a JSON body through the testkit boot returns **500** (the decoder is never in the handler's request scope). Notably asymmetric: the *response* encoder is effective (reads serialize to JSON over the wire) and **SSE works**, but request decoding does not — so server-side validation-over-HTTP (finding T10) and action-`POST`-create cannot be exercised through the testkit boot. Whether this is a fixture gap (it does not bind the codec registries a real app boot would) or a scope-propagation issue is the open question to pin down.

**Owner:** HTTP / transport (path-parameter routing in `HttpRouter`); Community testkit + bootstrap request-scope wiring (decoder-registry binding on the `http` boot path).

**Resolution:** (1) Add `{id}` path-parameter matching to `HttpRouter` — capture the segment and expose it via the exchange's path params — so id-bearing routes resolve and the generated handlers' `extractPathId` is reachable. (The alternative — have the generator emit `prefixRoute(...)` for id-bearing routes and derive the id from the prefix tail — pushes the burden onto `exeris-tooling`; pick one and align both sides.) (2) Bind the request-body decoder registry (and confirm the encoder / response-decoder registries) into the handler's request scope on the `http` boot path — in `KernelBootstrapHttpEngineFixture` and any real-app boot wiring that mirrors it — so write-over-HTTP resolves a decoder instead of 500-ing. Guard both with a **real-boot** routing/codec e2e in the kernel test suite (a router driven over a socket), since the gaps are invisible to text assertions on generated source.

**Merge Gate:** a real-boot e2e drives a router over a socket and asserts a by-id `GET`/`PUT`/`DELETE` *resolves* (not 404) and a `POST` with a JSON body *decodes* (not 500) — with the decoder gap's root cause pinned (fixture missing the codec-registry binding vs a scope-propagation defect) and the fix applied at the correct layer; the `HttpRouter` test gains a path-parameter case; existing exact/prefix routing and SSE stream-route resolution unregressed.

**Status (v0.10):** **DELIVERED.** (1) `HttpRouter` gained `{name}` path-template routing (resolution precedence exact → template → prefix), capturing each placeholder into the new SPI default `HttpExchange.pathParams()` via a Core-side `PathParamHttpExchange` decorator (no concrete-exchange coupling); `HttpRouterRegisteredEvent` now also counts template routes. (2) Root cause of the `POST` 500 was pinned as a **scope-propagation defect, not a fixture gap**: `CommunityHttpSubsystem.providerBindings()` does bind `HTTP_REQUEST_BODY_DECODER_REGISTRY` into the kernel carrier scope (since v0.8 #160), but the native transport (`NativeTcpReactor`) runs the per-request handler on a bare reactor thread that does not inherit the carrier `ScopedValue` scope — so the slot is unbound at handler time (the same reason the encoder/security/persistence are captured at engine-construction time and threaded as fields, and `REQUEST_SESSION` is rebound per request). Fix applied at the Community dispatch seam (`CommunityHttpRequestDispatcher.handleWithinRequestSession`): the registry is captured at processor construction (inside the carrier scope) and rebound per request alongside `REQUEST_SESSION`. Guarded by `GeneratedAppBootPathReachabilityIntegrationTest` (real boot over a socket) plus 12 new `HttpRouter` path-parameter cases and an `AbstractHttpExchangeTck` default-`pathParams()` assertion. The testkit fixture (`KernelBootstrapHttpEngineFixture`) is unchanged — confirming the gap was scope propagation, not a missing fixture binding.

**1.0 disposition:** **1.0-RECOMMENDED** — both are small, concrete kernel fixes that block the Entity-First "a generated app runs over a real boot" demonstration end-to-end (by-id CRUD + `@Action`s + writes). Path-parameter routing especially is load-bearing for the entire generated CRUD table. Targetable in v0.10 alongside the SSE boot-path work.

**Related (cross-repo / informational, not kernel gaps on their own):**
- **Generated SSE stream routes unreachable on a real boot (finding T23).** The kernel stream dispatcher (`CommunityHttpStreamDispatcher`) resolves a streaming route only when the engine's active handler **is** an `HttpRouter` (`handler instanceof HttpRouter router → router.resolveStream(...)`). The generated boot publishes a `router::handle` **lambda** (and `Application` forwards a lambda), so the `HttpRouter` type is lost and every generated stream route falls back to respond-once / 404 on a real boot — even though the handler and route are emitted. The fix is on the generator side (hand the engine the `HttpRouter` *instance*: `handlerSlot.set(router)` — the router already implements `HttpHandler`, so respond-once is unaffected). Kernel-side it is worth a deliberate note on whether stream resolution should remain coupled to a concrete-type `instanceof` (`exeris-tooling` follow-up; kernel design note).
- **`--enable-preview` at runtime to boot the kernel (K-boot).** kernel-core bootstrap classes are preview-compiled (`SubsystemOrchestrator` class-file minor `0xFFFF`) while the persistence classes are not (`TransactionOrchestrator` minor `0`); embedding the persistence stack needs only JDK 26, but *booting* the kernel needs `--enable-preview`. Resolved by the Platform-Baseline preview-clean work (see below) for the default artifact; until then, generated-app run scripts / poms must set the flag, and this is a documentation point for downstream boot consumers.

All three were surfaced during downstream dogfooding (a closed-source downstream consumer multi-service build, re-verified against `origin/development/0.10.0` on 2026-06-24).

See also: ADR-036 (request-body decoder SPI quadrant); ADR-043 (SSE streaming); "Platform Baseline for 1.0 GA" (K-boot / `--enable-preview`); [HTTP Subsystem](./subsystems/http.md).

---

## Known Gaps / Future Work planned for v0.11

### Transport: PAQS Execution-Seam Port (M1) + Locality Re-Test Disposition

**Gap:** The `StreamExecutionBackend` seam — the PAQS injection point at which an admitted stream's root task is started — existed only on the parked `research/loom-continuation-locality` branch (v0.6). `PaqsScheduler` on the default line still spawned the root VT inline, so the seam (a **refactor-neutral M1 extraction, proven neutral at M2**) lived one `git branch -D` away from loss. Two forward consumers depend on this injection point: (1) the Enterprise/custom-scheduler **locality re-test**, and (2) the post-1.0 **DST `SimulationScheduler`** (see "Road to 1.0" §DST). Leaving M1 stranded meant "the architecturally risky part is done" was true only on paper.

**Owner:** Transport subsystem.

**Resolution:** Port M1 onto `development/0.11.0` as the milestone's **first move** — `StreamExecutionBackend` (Core-internal `core.transport.scheduler`, **not** SPI; no driver/native detail crosses The Wall) + an additive 6-arg `PaqsScheduler` constructor whose default backend is the exact prior inline `Thread.ofVirtual().name().start()` (behaviourally identical: no admission, load-shed, or JFR change). The `LocalityAwareExecutionBackend` impl, the reflective VT custom-scheduler dependency, io_uring, and the A/B benchmark harness (`CoreContinuationLocalityBaseline`) do **not** come — they are the *subsequent* research track, not a v0.11 default-reactor deliverable, and the benchmark belongs in `exeris-benchmarks`. Records the locality NO_GO as **config-scoped** (Community-only, stock scheduler with the custom-scheduler override reflectively absent, no `pullerMode=3`; Enterprise io_uring M4/M5 never ran) so the seam's continued existence is justified by the open re-test, not the closed Community result.

**Merge Gate:** `AbstractPaqsSchedulerTck` gains an execution-backend-seam contract case (custom backend preserves `ScopedValue` bindings) bound in `CorePaqsSchedulerTckTest`; `PaqsSchedulerTest` proves default-backend behaviour is unchanged (thread-name format, task runs) — all pre-existing PAQS tests stay green (refactor-neutral); no new `StructuredTaskScope` import; companion ADR reserved via `exeris-adr-register` recording the seam intent + config-scoped-NO_GO + named re-test regime. Distinct from the Platform-Baseline `fork`/`join`/`cancel` orchestration seam (Stream H) — different injection point (transport continuation-resume vs subsystem orchestration); do not conflate.

---

### Storage: `BlobStorageProvider` SPI (ADR-056)

**Gap:** File and media handling has no kernel SPI seam. Application generators that need to emit upload widgets, signed-URL flows, or download streams cannot rely on a kernel-side adapter — every host application hand-wires its own S3 / MinIO / GCS / local-FS code, with no zero-copy story and no isolation-key scoping. Blob I/O is a runtime hot path (large-payload streaming, native sendfile candidate) with ≥2 plausible drivers, qualifying as kernel SPI territory under the Wall test.

**Owner:** Storage / Runtime subsystem.

**Resolution:** Define `BlobStorageProvider`, `BlobRef`, `BlobUploadHandle`, `BlobDownloadHandle` in `eu.exeris.kernel.spi.storage.blob`. Streaming I/O backed by `LoanedBuffer` / `MemorySegment` (no `byte[]` round-trip on the hot path). Community drivers: local filesystem + a minimal S3-compatible HTTP driver reusing `HttpClientEngine`. Enterprise driver track (out-of-repo): zero-copy `io_uring sendfile` for local FS, native multipart upload for S3 (parallel uploads via `StructuredTaskScope`). `AbstractBlobStorageTck` covers: upload round-trip, download streaming, content-range read, signed-URL generation contract, isolation-key scoping (`StorageContext.isolationKey` honored for tenant-segregated buckets per ADR-012).

**Merge Gate:** SPI green on TCK with at least two bindings (local FS + S3-compatible HTTP); zero-leak assertion on `LoanedBuffer` lifecycle through upload + download paths; `StorageContext` isolation honored per ADR-012; ArchTest forbids `java.io.File` / `java.nio.file.Files` imports inside the SPI package (consistent with existing scoped bans in runtime hot paths).

**Status (v0.11): DELIVERED.** SPI + `AbstractBlobStorageTck` + filesystem driver (ADR-056), then the
S3-compatible driver against MinIO — the merge gate's two-binding requirement is closed. The S3 binding's
scope is recorded as an ADR-056 §10 amendment rather than left to folklore: header signing over
`host` / `x-amz-content-sha256` / `x-amz-date` plus query-signed presigned URLs, no multipart, no chunked
payload signing; an `https://` endpoint is **rejected at construction** because the Community HTTP client
engine has no client-side TLS; and the single-object ceiling is a named knob (`s3.maxObjectBytes`, default
8 MiB) rather than an implicit one, refused loudly through `EX-BLOB-8005` before any allocation.

Two things this slice deliberately did **not** close, both tracked below: bootstrap wiring for the
subsystem (so two same-priority providers still need a configured selection rather than a discovery
order), and the client engine's fixed per-response buffer.

See also: `docs/adr/ADR-056-blob-storage-provider-spi.md` (Accepted 2026-07-30) — rules package placement, the `LoanedBuffer` ownership rule across both transfer directions, tenant-relative `BlobRef` addressing, the absent-`isolationKey` deny, and the signed-URL capability contract. One deviation from the Resolution text above is recorded there: the operational type is **`BlobStore`**, with `BlobStorageProvider` kept as the ServiceLoader discovery handle, matching the `*Provider` + `createEngine` convention every sibling package already follows.

---

### Runtime: `JobScheduler` SPI (ADR-057)

**Gap:** Background-job execution (cron triggers, queued retries, scheduled emissions) has no kernel SPI. Applications wanting "run this every 5 minutes" or "schedule this to fire in 24h" hand-roll a scheduler or pull in Quartz, losing `PrincipalContext` / `StorageContext` propagation and missing JFR observability. Job dispatch is a runtime concern that composes with the existing event publisher and Flow engine, and has plausible alternative drivers (in-process Loom-based scheduler vs DB-backed durable queue vs external orchestrator hook).

**Owner:** Runtime / Scheduling subsystem.

**Resolution:** Define `JobScheduler`, `JobDescriptor`, `JobHandle`, `JobTrigger` (cron / interval / one-shot / event-driven) in `eu.exeris.kernel.spi.scheduling`. Context propagation: `PrincipalContext` and `StorageContext` captured at submission time, restored via `ScopedValue` on dispatch (mirrors the Flow engine context-capture pattern). Community driver: in-process Loom scheduler using `StructuredTaskScope` plus `ScheduledExecutorService` (one of the few approved scoped-ban exceptions, justified because the scheduling primitive itself is the boundary — orchestrated job code remains on Loom). Enterprise driver track (out-of-repo): DB-backed durable queue with at-least-once semantics + leader election. `AbstractJobSchedulerTck` covers: cron trigger fires on schedule, interval trigger respects delay, context propagation across job boundary, cancel via `JobHandle`, leader-election semantics for durable backend.

**Merge Gate:** SPI green on TCK with Community in-process driver bound; context propagation TCK verifies `PrincipalContext` + `StorageContext` round-trip across submit → dispatch; ArchTest confirms no `ThreadLocal` use for context propagation (`ScopedValue` only); JFR `JobDispatchEvent` / `JobCompletionEvent` / `JobFailureEvent` carried for observability; companion ADR documents the `ScheduledExecutorService` scoped-ban exception with explicit scope.

See also: `docs/adr/ADR-057-job-scheduler-spi.md` (Accepted 2026-07-30). It deviates from the Resolution text above on both concurrency primitives, and the second deviation also retires the Merge-Gate clause immediately above. (a) **Dispatch is virtual threads + explicit `ScopedValue` rebind, not `StructuredTaskScope`** — a new subsystem on STS would have been a fifth preview-taint site on the default line, against the "Platform Baseline for 1.0 GA" mandate below. **The other four are gone as of v0.11 / ADR-066** — `OutboxOrchestrator` and `CommunityEventLoop` on `StructuredScope`, `InMemoryEventBus.publishAndAwait` and `SubsystemOrchestrator` phase start now in-thread — so this ruling reads today as the one that kept the count from ever reaching five, and the scheduler needs no follow-up. (b) **There is no `ScheduledExecutorService` and therefore no scoped-ban exception to document** — this entry asks for an injectable time source in the same breath, and `ScheduledThreadPoolExecutor` times off `System.nanoTime()` internally with no seam to displace, so the deterministic trigger TCK this gate requires is unreachable through it; the driver owns its timing loop instead. (c) A third, smaller departure: **`JobTrigger` carries three kinds, not four** — the event-driven kind listed in the Resolution text above is excluded on coupling direction (an event handler calling `submit` already expresses it, whereas a trigger kind would put an `spi.events` dependency inside the scheduling contract).

**Status (v0.11): DELIVERED.** SPI + Community driver + `AbstractJobSchedulerTck` (18 cases); contract doc at `docs/subsystems/scheduling.md`. **The bootstrap wiring this entry called "the remaining slice" has since landed and the line was left stale** — corrected 2026-08-08 during the pre-cut audit. `CommunitySchedulingSubsystem` carries the full lifecycle (`initialize` / `start` / `stop` / `providerBindings`), resolves `scheduling.schedulerName` through `ConfigProvider` with `JobSchedulerConfig.DEFAULT_NAME` as the fallback, binds both `KernelProviders.JOB_SCHEDULER_PROVIDER` and `KernelProviders.JOB_SCHEDULER`, and is discovered at boot through `CommunitySubsystemProvider` — the single `SubsystemProvider` service entry, where it sits alongside the other nine subsystems.

Two findings from the implementation, both recorded rather than papered over:

1. **The ArchTest clause of this Merge Gate could not be met where it was written.** `ExerisArchitectureTest` runs in `exeris-kernel-tck`, whose only production dependency is the SPI — so no Core or Community class is ever on its analysis classpath, and a rule there naming a Community package can never fire. The driver-side guards (no `StructuredTaskScope`, no `ThreadLocal`, no `ScheduledExecutorService`) therefore live in `CommunitySchedulingArchitectureTest`, in the module that can see them, and assert a non-empty analysis set before asserting anything else. **This generalises beyond scheduling:** every `eu.exeris.kernel..`-scoped rule in `ExerisArchitectureTest` — including `noThreadLocal`, `noExecutorsAnywhere`, `noCompletableFuture`, `noUnsafe` — currently covers only SPI and TCK classes, which is narrower than both the rule text and `CLAUDE.md` §"Scoped Bans" imply. Closing that gap repo-wide is its own slice: it needs ArchUnit wired into Core and Community and will surface pre-existing violations. **RESOLVED 2026-08-18 (v0.12 S9), and it surfaced nothing.** `KernelTierBanArchitectureTest` in `exeris-kernel-community` carries the four bans across SPI, Core and Community, with the same non-empty-analysis assertion its sibling uses; the `ExerisArchitectureTest` rules are renamed `*InSpi` so no rule text claims a reach its module cannot have. Core and Community turned out to carry no banned dependency — the prediction of "pre-existing violations" was wrong, and the only near-match is `CommunityHttpRetryPolicy`'s `ThreadLocalRandom`, a distinct type. **Residue, stated rather than left implicit:** `exeris-kernel-community-kafka` and `exeris-kernel-diagnostics-cli` are leaf modules nothing depends on, so no suite in the reactor sees them either. Covering them needs a suite per leaf or a shared rules holder on a classpath all of them have; not scheduled.
2. **`noExecutorsAnywhere`'s stated reason is stale — RESOLVED 2026-08-08.** It read "All concurrency must use `StructuredTaskScope` (JEP 525)", which contradicted the Platform Baseline directly above it. Both that rule and `noCompletableFuture` — which carried the same defect and was not part of the original finding — now state the actual invariant: concurrency must run inside a structured scope that owns the task's lifetime, which is `StructuredScope` on the default line and `StructuredTaskScope` on the `preview` branch. What the rules ban is the unstructured escape hatch, not either sanctioned mechanism. Rule predicates unchanged, so the guard's coverage is exactly what it was. Finding 1 above is **still open** and is the substantive half: correcting a rule's stated reason does not widen the classpath it can see.

---

### Runtime: Cross-Node Coordination (Leader Election / Distributed Lock) — RFC Track

**Gap:** The "5 instances = the same app" deployment shape is standard scale-out, and the kernel already provides most of the substrate: stateless per-request handling (`ScopedValue` context, Flow/HTTP per request — any instance serves any request), shared durable state via Postgres (ADR-013 + ADR-022), and cross-node fan-out via the Kafka Events driver. What has **no** first-class kernel seam is cross-node *coordination*: distributed lock, leader election, and singleton execution ("only one instance runs this cron / drain / migration"). Today this can be hand-rolled over Kafka consumer groups or Postgres advisory locks, but with no SPI every application reinvents it and the correctness burden (fencing tokens, lease expiry, split-brain) lands on application code. **Corrected 2026-08-07 by [`RFC-2026-08-07`](rfc/RFC-2026-08-07-cross-node-coordination-seam.md): this paragraph had the dependency backwards.** It previously read that the `JobScheduler` SPI "already anticipates a subset — its durable backend carries leader-election semantics for singleton job dispatch". There is no durable backend and no singleton dispatch: [`scheduling.md`](subsystems/scheduling.md) lists durable job stores, leader election and distributed coordination under what is excluded **by contract, not merely unimplemented**, and `JobSchedulerProvider` states that a distributed binding "would select a leader through the coordination seam rather than inventing a parallel mechanism". The scheduler is a *future consumer* of this seam, not a partial implementation of it. Surfaced during downstream dogfooding (a multi-node framing, 2026-06).

**Owner:** Runtime / Coordination subsystem.

**Resolution:** Open an RFC in this repo's `docs/rfc/` — the owning repo holds the RFC for an SPI it will own (#230); `exeris-docs/` hosts only ecosystem-wide and business-shaped RFCs (`RFC-YYYY-MM-DD Cross-Node Coordination Seam.md`, per `exeris-docs/templates/RFC-TEMPLATE.md` — RFC not ADR because the option space is open and no decision is committed). **Design constraint, load-bearing — and REFUTED on its own premise, 2026-08-07.** The constraint as written was "keep this a thin seam over substrate the kernel already owns (Kafka consumer groups, Postgres advisory locks)". The kernel owns neither in usable form. **Postgres advisory locks:** zero occurrences in tracked source or migrations, and structurally inexpressible — a session-scoped lock needs a pinned connection, but `PersistenceEngine` exposes only pooled `openConnection()` (`openPhysical()` is Community-internal), so outside a request scope `tryLock` and `release` land on different sessions; `close()` performs no `DISCARD ALL`, so a leaked lock rides the recycled connection into the next checkout — the same pooled-session hazard `RlsConnectionInterceptor` already calls a fail-open bug — and Hikari's `maxLifetime` drops it on a timer with no error path. The transaction-scoped variant cannot outlive a transaction, so it can express a critical section but never `tryLock(key, lease)` + `renew`. **Kafka consumer groups:** the group exists, but the observation surface does not — zero `ConsumerRebalanceListener`, zero `ConsumerGroupMetadata.generationId()` (Kafka's own free fencing token) anywhere in the repo — and the single required `events.kafka.group-id` is used raw, so a coordination driver reusing it would entangle leadership with event delivery. The "not a cluster subsystem" half of the constraint stands and is unaffected: no membership protocol, no gossip, no embedded consensus. What changes is the cost estimate — the seam is **new substrate** (a lease table), not a thin wrapper. Scope enumerated: the minimal contract (`tryLock(key, lease)` / `renew` / `release` with a fencing token, and a `leadership(group)` observer); the backend strategy; lease-expiry / fencing-token semantics so a paused leader cannot act after losing leadership; the relationship to `JobScheduler` leader election; failure-mode classification on an unreachable backend; and TCK strategy including an adversarial split-brain probe. **Three of those six were stated here in terms the RFC refuted, and are corrected rather than left standing.** *Backend strategy:* this line originally named two drivers — Postgres advisory-lock for the no-Kafka deployment, Kafka-consumer-group for the eventing one — which are exactly the two options the RFC rejects, for the reasons given above; the selected strategy is a single Postgres lease-table driver, usable in both deployments because Postgres is already required. *JobScheduler:* this line originally read that coordination becomes "the shared primitive the scheduler's durable backend consumes"; there is no durable backend, and the dependency runs the other way — see the corrected paragraph above. *Split-brain probe:* the RFC found it unbuildable with today's test infrastructure (no partition tooling, no way to pause a holder past lease expiry), so the implementation gate below splits it into a contract-level and a container-level half rather than asking for one test that cannot exist. *Failure-mode classification* is settled by the RFC in favour of the fail-closed doctrine's existing reading — deny **and** drain, as `CommunityDegradedModeIntegrationTest` already does for Postgres loss — not "grant the lock anyway". RFC accepted → ADR number reserved when the implementing change reaches its build gate, **not** at RFC acceptance (deferring the reservation keeps the global namespace free of numbers that may never be written) → ADR → SPI/driver in a later milestone.

**Status (v0.11): RFC DELIVERED — shape selected, implementation post-1.0.** [`RFC-2026-08-07: What substrate can a cross-node coordination seam actually stand on?`](rfc/RFC-2026-08-07-cross-node-coordination-seam.md) selects a **Postgres lease table with a fencing epoch**, using the conditional-UPDATE CAS idiom `JdbcFlowSnapshotStore` already runs, and labels it new substrate rather than a thin wrapper. Dissent recorded: no-seam remains defensible now that the cost estimate is corrected.

**Merge Gate (as satisfied):** RFC accepted with one shape selected and dissenting positions recorded; no kernel SPI commits in this gate (decision-only track). **Implementation gate, restated** — the original text asked for a Postgres *advisory-lock* driver and for "`JobScheduler` singleton dispatch refactored to consume it"; the first is inexpressible (above) and the second is vacuous, since there is no singleton dispatch to refactor. It requires instead: `AbstractCoordinationTck` (lock acquire/release, lease expiry, fencing-token monotonicity, leadership handoff, and a split-brain probe **split explicitly into a contract-level half with a controllable clock and backend and a container-level half** — today's test infrastructure can express neither an asymmetric partition nor a holder paused past lease expiry); one Community driver over the lease table; at least one resource that actually **validates** the fencing token, without which the token is decorative; a module-local ArchUnit guard, since `ExerisArchitectureTest` sees only SPI and TCK; a `docs/stability-matrix.md` row plus a `stability-surfaces.conf` entry in the same commit (ADR-065 fails the build on an unclassified SPI class), entering at `preview`; and a decision on node identity, which does not exist today — `exeris.node.id` defaults to the literal `local` on every instance.

See also: v0.11 §"Runtime: `JobScheduler` SPI" (leader-election subset); ADR-013 / ADR-022 (Postgres-durable distributed state).

---

### Events: Multi-Node Delivery Boundary — Default Driver Is Single-Node (Documentation Clarity)

**Gap:** The multi-node operational contract of the Events subsystem is undocumented, and the default Community Events driver is easy to mistake for cross-node. The default driver (Postgres Outbox + JVM-heap pub/sub) is **single-node**: the in-heap event bus does not cross the node boundary. The Outbox gives durable *emission* (an event survives crash and is retried), but **cross-node delivery requires the Kafka driver** (`exeris-kernel-community-kafka`) — a multi-node deployment that fans events out to peers must run on the Kafka driver, not the default. This is a real distinction operators need stated explicitly, not a code gap. Surfaced during downstream dogfooding (a multi-node framing, 2026-06).

**Owner:** Events subsystem (documentation) / Docs.

**Resolution:** Document the delivery boundary in `docs/subsystems/events.md` and the operator-facing docs: default driver = single-node in-heap bus + durable Outbox emission; cross-node delivery = Kafka driver; the in-heap bus never crosses the node boundary. State it alongside the multi-node substrate inventory (stateless per-request via `ScopedValue`; shared durable state via Postgres per ADR-013 / ADR-022; cross-node fan-out via Kafka) so the "what do I get out of the box for 5 instances" question has a single authoritative answer. Documentation-only; no code change.

**Merge Gate:** `docs/subsystems/events.md` states the single-node-default vs Kafka-cross-node delivery boundary; the multi-node substrate inventory is recorded in one authoritative place (events.md or the operator deployment doc). No TCK / SPI change.

**Status (v0.11): DELIVERED.** `events.md` §"Delivery Boundary: Single-Node Default vs Cross-Node (Kafka)" states the boundary and hosts the substrate inventory; `support-matrix.md` and `operations/reference-deployment.md` link there instead of restating it.

One finding from writing it, recorded because it is sharper than the gap this entry described: **the durable-emission and cross-node-delivery paths are not composable today.** The Gap text above reads as though an operator picks the Kafka driver and keeps the Outbox. They do not — `KafkaEventEngine` runs no outbox orchestrator (its `EventQueue` slot is a `NoOpQueue`, and `KafkaEventBrokerPort` is a built adapter wired into no runtime path), while `CommunityEventEngine` constructs its local broker port directly with no seam to substitute. So the choice today is durable emission on one node *or* cross-node fan-out, not both. Documented as a current limit; tracked separately as §"Events: Durable Emission And Cross-Node Delivery Cannot Be Had Together" below, because the operator-visible cost (adopting Kafka silently drops the transactional outbox) is a gap in its own right, not a documentation shortfall.

---

### HTTP Client: Service Discovery & Logical Addressing for `KernelWebClient` — RFC Track

**Gap:** `KernelWebClient` (ADR-034 — the tier-neutral Core facade in `eu.exeris.kernel.core.http.client`, superseding ADR-026's `CommunityWebClient`; on the `HttpClientEngine` SPI) targets a single, statically-configured host: the caller supplies a concrete base URL and the generated typed client emits own-app / relative-host paths only. There is no seam to resolve a *logical* service name (e.g. `billing-service`) to a concrete address at call time — no static service-map config, no DNS/SRV strategy, no registry lookup, no sidecar/mesh hook. The moment an ecosystem splits into N generated applications that must call each other, every caller hard-codes peer hostnames. This is the kernel-side half of the tooling "mesh" gap (T12, owned by `exeris-tooling`): even once the generator can import a cross-app contract, the client has nowhere to resolve the target's address. Surfaced during downstream dogfooding (a multi-service split, 2026-06; finding K4, Medium; kernel touchpoint of T12).

**Owner:** HTTP subsystem (client addressing). The cross-app contract import / generated-client side is owned by `exeris-tooling` (T12) and tracked there.

**Resolution:** Open an RFC in this repo's `docs/rfc/` — the owning repo holds the RFC for an SPI it will own (#230); `exeris-docs/` hosts only ecosystem-wide and business-shaped RFCs (`RFC-YYYY-MM-DD WebClient Service Addressing.md`, per `exeris-docs/templates/RFC-TEMPLATE.md` — RFC not ADR because the option space is wide and no decision is committed). Scope enumerates the resolution strategies and their boundary cost: (a) static logical-name → endpoint map in config (zero new runtime dependency, no liveness); (b) DNS / DNS-SRV resolution (standard, env-provided, no kernel registry); (c) a `ServiceResolver` SPI seam that `KernelWebClient` consults to turn a logical name into an endpoint (Community static/DNS driver; Enterprise/registry drivers out-of-repo); (d) delegate entirely to a service-mesh sidecar (kernel stays single-host; addressing is an ops concern). Cross-cutting questions: interaction with the existing `HttpClientRequestEnricher` (ADR-032) and the v0.9 IDP outbound-credential decision (identity must survive re-addressing); failure-mode classification (name unresolved / no healthy endpoint / resolution timeout); whether resolution is per-call or cached with TTL; and Wall integrity (no DI container, no `ThreadLocal`, resolution must not couple the client to a concrete registry type). RFC accepted → ADR number reserved when the implementing change reaches its build gate, **not** at RFC acceptance (deferring the reservation keeps the global namespace free of numbers that may never be written) → ADR → SPI/driver lands in a later milestone.

**Status (v0.11): RFC ACCEPTED — shape selected; disposition SPLIT, multi-peer addressing is 1.0 scope and the resolver seam is post-1.0.** [`RFC-2026-06-29`](rfc/RFC-2026-06-29-webclient-service-addressing.md) selects **option (c), a `ServiceResolver` SPI seam**, with (a) static map and (b) DNS-SRV as its two first-party Community drivers and (d) the mesh case reframed as a pass-through driver rather than a kernel non-feature. Its premises were re-verified at acceptance six weeks after drafting and still hold — `KernelWebClient` is still single-host and no resolver surface exists. **The disposition is split, and the split is the correction.** `HttpClientEngine`'s SPI surface never mentions a host and `HttpRequest` carries no authority, so single-host is not a contract decision — it falls out of the carrier having nowhere to put an addressee, forcing the driver to be handed one at construction (`CommunityHttpClientEngine.targetHost`). **Multi-peer addressing is therefore the shape of an existing subsystem, not a new one, and belongs in 1.0**: the narrow-core ruling holds out *new SPIs that are each a real subsystem*, and 1.0 claims to be unbreakable on `http` — a client that structurally cannot address a second peer is incomplete on `http`, and would force every generated application in the composable-unit direction to hard-code its peers. The **`ServiceResolver` seam itself stays post-1.0**, which is what the ruling actually holds out. **The split fixes *when*, not *how*.** It decides a milestone disposition — an interpretation of the narrow-core ruling — and decides nothing about the addressing shape. Whether the addressee rides on `HttpRequest`, whether the client keeps a per-host engine pool, or whether it holds a resolver plus an engine factory is the RFC's own Open Question 1, still open and still gating the pre-ADR spike; that question is **owed its own option table, costs and recorded dissent** before the spike treats it as settled, because none of the RFC's options A–E evaluates addressing-without-a-resolver. What can be said without choosing a shape: the cost is bounded, since `HttpRequest` and `HttpClientEngine` are both in the compatibility gate's `stable` bucket and the mitigations for that bucket already exist in-repo — the retained-canonical-constructor bridge `FlowSnapshot` used three times this milestone, and the refusing `default` `registerMigration` used. That is evidence the disposition is affordable, not that any shape is chosen. Dissent recorded, and narrowed by the split: option (e) do-nothing stays live for the resolver half if T12 does not materialise, since C's justification there rests on its two in-repo drivers supplying the contract pressure a single external consumer would otherwise have to. It does not apply to the 1.0 half, which has a consumer today — any application talking to more than one peer.

**Merge Gate (as satisfied):** RFC accepted with one shape selected and dissenting positions recorded; no kernel SPI commits in this gate (decision-only track). **Implementation gate, with two requirements that post-date the RFC:** `AbstractServiceResolverTck` (logical-name resolve, unresolved-name failure, endpoint-health/timeout behavior); a Community binding; identity propagation preserved across resolution (ADR-032 enricher composes after resolution, so a re-addressed request carries the right audience); The Wall preserved (no framework DI / registry type leak into SPI / Core); **plus** a `docs/stability-matrix.md` row and a `stability-surfaces.conf` entry in the same commit, since ADR-065's gate fails the build on an unclassified SPI class and `…spi.http` is `mixed` — the resolver takes its own row rather than inheriting the package tier; **and** the `HttpClientEngine` per-host-vs-per-request binding question settled by the pre-ADR spike, which the RFC names as the ADR-shape blocker rather than a detail.

See also: ADR-034 (`KernelWebClient` facade, superseding ADR-026), ADR-032 (`HttpClientRequestEnricher`); v0.9 §"Security: `IdentityProvider` SPI Direction — RFC Track" (outbound-credential touchpoint).

---

### HTTP: Stream-Route Table Is Exact-Path Only — Generated Per-Action Stream Routes Unreachable

**Gap:** The router's streaming table is exact-match only: `streamRoutes` is a `Map<StreamRouteKey, HttpStreamHandler>` and `resolveStream` / `isStreamRoute` are plain map lookups (`HttpRouter.java:52,80-93`); the `streamRoute(...)` Javadoc pins "exact request path". The W7 `{id}` path-template machinery (`PathTemplateRoute`) applies only to respond-once `route(...)` registrations (`HttpRouter.java:231-237`). The `exeris-tooling` generator emits `streamRoute(POST, "<base>/{id}/actions/<kebab>")` for per-action streams (ADR-044 Slice 2; `KernelApplicationGenerator.java:488`), so the literal `{id}` map key never matches a concrete id and every generated per-action stream 404s on a real boot — the same dead-route failure class T23 fixed one layer below. Aggravating detail: `Builder.streamRoute` silently accepts `{` in a path today, so the dead registration is invisible at build time. Not yet user-visible only because the per-action driver is still a keep-alive scaffold. Surfaced during downstream dogfooding (EV1-stream, 2026-07).

**Owner:** HTTP subsystem (router); shape decision shared with `exeris-tooling` (ADR-044 EV1-stream slice).

**Status (v0.11): DELIVERED — kernel-side template matching.** The streaming table now compiles `{name}`
paths through the same `PathTemplate` the respond-once table uses, with the same exact-before-template
precedence, and captured values reach the handler through the new `HttpStreamExchange.pathParams()` (a
`PathParamStreamExchange` decorator, mirroring `PathParamHttpExchange`). One compiled template type
serves both tables, so they cannot drift into disagreeing about what `/x/{id}` means. The fail-fast half
landed with it: a malformed brace throws at `Builder.streamRoute`, so a silently-dead stream registration
is unrepresentable. This is the default direction below, chosen without waiting on the tooling ruling —
it subsumes the fail-fast property and stays correct under either tooling outcome, exactly as the
alternative anticipated.

**Resolution:** Rule the shape inside the tooling EV1-stream slice, then land the kernel side in v0.11. Default direction — kernel-side template matching: extend the streaming table with the same template semantics the unary table already has (reuse `PathTemplateRoute` compile/match for `streamRoute` paths containing `{name}`; exact stream routes take precedence over template stream routes, mirroring unary precedence; captured params exposed on the streaming path). Alternative — ADR-044 amendment (tooling repo) redesigns per-action stream paths onto exact routes, and the kernel change collapses to a Javadoc clarification plus a build-time guard. **Either way, fail-fast becomes mandatory:** a `{` in a stream path must either resolve via templates or be rejected in `Builder.streamRoute` — a silently-dead registered route must become impossible.

**Merge Gate:** Fail-fast property enforced (template resolution or `IllegalArgumentException` at registration — no third state). If template matching lands: router unit coverage for stream-template resolve + exact-over-template precedence, plus a streaming TCK (or router-level) case proving a `{id}` stream route opens an `HttpStreamExchange` for a concrete id; ADR-043 obligation 7 stays true (streaming resolves only via `resolveStream`, never through `handle`). Tooling lockstep (ADR-044 Slice 2 per-action driver impl) tracked in `exeris-tooling`, non-gating for the kernel merge.

See also: ADR-043 (streaming SPI, obligation 7); ADR-044 (`exeris-tooling` SSE emitter shape — Slice 2 ratified, impl pending); v0.10 §"HTTP: generated-app boot-path reachability" (W7 template routing, T23 cross-repo note).

---

### Transport: Accept-Loop `RuntimeException` Is Swallowed Whole (surfaced 2026-07-31)

**Gap:** `NativeTcpCarrier`'s accept loop binds the exception and never uses it:

```java
} catch (RuntimeException exception) {
    if (connection != null) {
        connection.close();
    } else {
        closeQuietly(currentChannel);
    }
}
```

Anything thrown while setting up an accepted connection — `configureAcceptedChannel`, TLS engine construction, `buildAcceptedStream`, `registerConnection` — is discarded. No log line, no JFR event, no counter. The loop proceeds to the next accept and the client sees a dropped connection.

This is a different kind of silence from the `maxConnections` refusal recorded under §"Road to 1.0". That one hid a **policy** decision at a known limit; this hides **defects**. An allocator exhaustion, a TLS init failure, or a registry inconsistency during accept produces exactly the same externally-visible symptom as a healthy server under no load — and a *repeating* fault here is indistinguishable from an intermittent network problem, which is the failure mode that costs the most triage time.

The asymmetry is local and visible: the sibling paths in the same file close the same resources and then **rethrow**. Only the accept loop swallows.

**Owner:** Transport subsystem.

**Resolution:** Emit a JFR event carrying the exception class (class only — no message, matching `CommunityReactorDispatchFaultEvent`'s secret-safe shape) and count the failure. Do **not** change the recovery behaviour: continuing the accept loop after a per-connection setup failure is correct, and conflating "make it visible" with "make it fatal" would trade a silent drop for an availability regression.

**Merge Gate:** Driver-local JFR test that forces a setup failure on an accepted connection and asserts the event; the fault count exposed alongside the refusal count; `docs/subsystems/transport.md` records both accept-path failure modes together, since an operator sees the same symptom from either.

**Status (v0.11): implemented, merge gate 2 of 3.** Added 2026-08-08 — this section carried no disposition at all while the work it describes merged in #265 on 2026-07-31, which is how it stayed invisible. The silence is gone: `NativeTcpCarrier.recordAcceptFault` emits `CommunityAcceptFaultEvent` carrying the exception's **class name only** (secret-safe, as the Resolution required — no message) plus a running fault total, and recovery is deliberately unchanged, so a per-connection setup failure still continues the loop rather than becoming fatal.

Two gate clauses are **not** met, and neither is cosmetic:
- **No driver-local JFR test.** Nothing forces a setup failure and asserts the event, in the Community suite or the TCK. The emission is verified by reading the call site, which is exactly the standard of evidence this document exists to refuse elsewhere. Same gap as the refusal event under §"Road to 1.0".
- **The fault count is not exposed alongside the refusal count.** `acceptFaults` is a private counter that reaches an operator only inside the JFR event payload; `TransportStats` has no field for it, and it is deliberately *excluded* from `totalRejected` because a setup fault is not declined work. So the two accept-path failure modes are documented together but not *observable* together, which is the half of the gate that matters to the operator the section was written for.

`docs/subsystems/transport.md` §"Accept-path failure modes" does record both together, with a per-event comparison table — that clause is met.

---

### HTTP Client: Every Response Buffer Is Sized For The Largest Possible One (surfaced 2026-08-01)

**Gap:** `CommunityHttpClientEngine.readResponse` allocates its aggregate buffer from
`resolveAggregateCapacity()`, which is derived once from `HttpConfig.maxRequestBodyBytes` and does not
vary per request. Every response — a `HEAD` with no body, a `204` from a `DELETE`, a small JSON reply —
pays the allocation sized for the largest body the engine is configured to accept.

Two separate problems sit in that one line:

1. **Waste.** An engine configured for an 8 MiB ceiling allocates 8 MiB to read a 200-byte `HEAD`
   response. Surfaced by the S3 blob driver, where the object ceiling *is* the engine ceiling, so raising
   the largest storable object also raises the cost of every `stat` — the knob does two unrelated things
   at once. Directly against No Waste Compute, on a path that runs per request.
2. **A misnamed knob.** `maxRequestBodyBytes` is a *server-side request* limit by name and Javadoc; the
   client reuses it as a *response* ceiling. A deployment tuning ingress limits silently retunes its
   outbound client, and neither name says so.

Not a correctness bug: an oversized response is refused loudly (`decodeResponse` throws
`Truncated HTTP response body: expected N bytes but received M`), which is why the blob driver can size
the engine deliberately and rely on the refusal. The cost is memory and clarity, not silence.

**Owner:** HTTP subsystem.

**Resolution:** Size the aggregate from what the response actually declares — read the status line and
headers into a small buffer, then allocate the body from `Content-Length` (bounded by the ceiling), which
is the shape the decoder already computes in `resolveExpectedTotal`. Give the client its own
`maxResponseBodyBytes` rather than borrowing the request limit, and keep the loud refusal on overrun.

**1.0 disposition:** the separate client-side knob is 1.0 (configuration surface — a limit that cannot be
set independently is a limit an operator cannot reason about); the per-response sizing is a No Waste
Compute improvement that can follow.

**Merge Gate:** a `HEAD` and a small `GET` allocate proportional to what arrived, not to the ceiling
(assert on allocator stats, not on timing); the overrun refusal still fires at the configured limit; the
S3 driver's ceiling stops being the engine's ceiling.

---

### Storage: Two Blob Providers, No Way To Choose Between Them (surfaced 2026-08-01)

**Gap:** `CommunityFilesystemBlobStorageProvider` and `CommunityS3BlobStorageProvider` are both
registered in `META-INF/services` at the same Community priority, and nothing in this repository loads
`BlobStorageProvider` through `ServiceLoader` yet. Today that is inert — a deployment gets the store whose
provider it constructs — but the moment a storage subsystem bootstraps the SPI, discovery order decides
which backend a tenant's objects land in. Ordering is not a contract, and the two stores are not
interchangeable: one needs credentials and a reachable endpoint, the other needs a writable directory.

The scheduling subsystem already shows the shape this needs (`SchedulingBootstrap` +
`SchedulingBootstrapSelected` JFR event), with one addition: scheduling had a single provider, so
recording the winner was enough. Storage has two at equal priority, so the choice must be *stated* by
configuration, not merely *recorded*.

**Owner:** Storage / Bootstrap.

**Resolution:** A `StorageBootstrap` in Core plus a Community subsystem binding the selected store into
`KernelProviders`, selecting by an explicit config key rather than by priority or discovery order. An
unset key with more than one provider present is a startup failure, not a default — picking silently is
the failure this entry exists to prevent. Emit the selection as a JFR event, as scheduling does.

**Merge Gate:** binding test proving the configured provider wins with both on the classpath; a test
proving an ambiguous configuration fails at startup rather than choosing; the slot is named `BLOB_*`
and not `STORAGE_*`, so it cannot be confused with ADR-012's isolation carrier; the startup failure
names the key an operator has to set, since a refusal that does not say what to write is a refusal
they cannot act on; `docs/subsystems/storage.md` loses its "provider selection is an open gap"
paragraph.

The generated-application half is deliberately **not** in this gate — it is a code-generation
concern and belongs to whichever slice teaches the emitter about the key. It is stated in the
amendment below so that slice inherits a requirement rather than rediscovering it.

**Two things the Resolution above assumes and does not state** (added 2026-08-27, from the
`exeris-tooling` side of the same gap — its ROADMAP tracks this as the kernel ask that keeps `@Blob`
out of emitted output):

- **There is no `KernelProviders` slot to bind into, and the obvious name is already taken.** The
  registry carries `JOB_SCHEDULER_PROVIDER` + `JOB_SCHEDULER` for scheduling and nothing for blobs;
  `STORAGE_CONTEXT` is ADR-012's isolation-key carrier, not a store. Whatever this slice adds must
  say `BLOB_*` and not `STORAGE_*`, or every reader after it has to check which of the two a given
  `STORAGE_` name means.
- **A generated application cannot be made to boot by naming the subsystem alone.** `Application.main()`
  declares subsystems by name, and a code generator can emit a name. It cannot invent the config key
  this entry makes mandatory — and an unset key with both providers present is, correctly, a startup
  failure rather than a default. So the emitted output has to carry the name *and* the key, or refuse
  at build time and say which key the author owes. This is the asymmetry with scheduling, which a
  generator handles today precisely because `CommunitySchedulingSubsystem` has one provider and a
  fallback (`JobSchedulerConfig.DEFAULT_NAME`). Storage has two at equal priority and can have no
  fallback, so the difference is not a detail of this entry — it is the whole of it.

---

### Events: Durable Emission And Cross-Node Delivery Cannot Be Had Together (surfaced 2026-08-02)

**Gap:** The Outbox and the Kafka driver are described throughout the docs as complementary — durable
emission plus cross-node fan-out — but no deployment can run both. `KafkaEventEngine` starts no outbox
orchestrator: its `EventQueue` slot is a `NoOpQueue`, and `KafkaEventBrokerPort` exists as a built,
Wall-clean adapter that nothing wires into a runtime path. `CommunityEventEngine` constructs
`CommunityEventBusOutboxBrokerPort` inline, so its relay target is the local bus with no seam to
substitute. Provider selection then picks exactly one engine.

The consequence is not a lost event but a lost *guarantee*, and it lands on the deployment that needs
both most: a multi-node operator who adopts Kafka for fan-out silently gives up the transactional
outbox — the crash-survival property that made the event atomic with the entity that caused it. Nothing
warns them; the SPI surface is identical either way. Surfaced while writing the delivery-boundary
section that this milestone's documentation slice required.

**Owner:** Events subsystem.

**Resolution:** Make the broker port a selection, not a construction detail. The two adapters already
implement the same Core port, so the work is a seam plus config, not a new mechanism: let the engine
take its `OutboxBrokerPort` from configuration, and let the Kafka module run the orchestrator with
`KafkaEventBrokerPort` bound. The publish-path duplication needs settling in the same change — with a
Kafka broker port the outbox relay and `KafkaPublishBus` are two routes to the same topic, so one of
them has to become the only one. That the port is unwired is already on record — [ADR-050](adr/ADR-050-events-binding-agnostic-topic.md)
§Consequences notes it, and the module's `package-info` lists it as deferred — so no ADR is contradicted
here; this entry is where the operator-visible *cost* of the deferral is recorded, which neither of
those does.

**Merge Gate:** an integration test proving an event committed in a transaction reaches a *second*
kernel instance after a crash of the first — the property neither engine can demonstrate today; the
delivery-boundary section in `docs/subsystems/events.md` loses its "not composable today" paragraph.

---

### Testkit: No Real-Runtime Fixtures Outside HTTP — Downstream Verifies Against Its Own Stubs (surfaced 2026-08-02)

**Gap:** `exeris-kernel-community-testkit` ships four classes: three HTTP fixtures and `TestJwt`. There
is nothing for persistence, transactions, events, flow, graph, scheduling, storage, or telemetry. A
downstream host runtime binding those SPIs has no way to test against the real engine, so it writes
stubs — and a stub encodes how its author *read* the contract, not how the runtime *behaves*. Ordering,
lifecycle, and threading are exactly the properties a stub cannot get wrong loudly.

This is not speculative. The kernel's own graceful-drain defect (§"Transport: drain in-flight streams"
above, fixed in v0.11) is this class: the drain machinery existed, the SPI documented it, and every
in-repo test passed because the TCK asserted the state machine rather than the semantics. Downstream
reports the same shape repeatedly — compat datasource under load, JWT decoding when the servlet stack
is absent, provider scope inside a filter and inside flow steps, routing with a query string — each
found by a consuming application rather than by any test suite on either side.

The sharpest exposure is transactions: propagation matrix, rollback, the second connection under
`REQUIRES_NEW`, `ScopedValue` resolution inside `doBegin`. That is data-integrity behaviour currently
proven only against hand-written doubles.

**Owner:** Testkit / Community.

**Resolution:** Extend the testkit with fixtures that stand up the *real* Community providers in a
consumer's test scope, following the shape `KernelBootstrapHttpEngineFixture` already establishes —
`ServiceLoader` discovery through a real bootstrap, engine pulled from the bound `KernelProviders` slot.
Priority order by exposure: persistence/transactions first, then events + flow (which compose with it),
then the rest.

**Feasibility is established, not assumed.** `CommunityPersistenceProvider.createEngine(...)` against
`jdbc:h2:mem:…;MODE=PostgreSQL;DB_CLOSE_DELAY=-1` yields a working engine in-process with no
Testcontainers and no external schema step: the engine carries its own DDL
(`db/migration/V0.5.0__create_outbox.sql`, `V0.7.0__create_saga_state.sql`,
`V0.10.0__create_event_log.sql`) and applies it when `run.migrations=true` is set in
`PersistenceConfig.properties()` — it defaults to `false`, which is the only non-obvious step. The
in-repo persistence TCK bindings already do exactly this.

One packaging decision to settle in the implementing PR: H2 is `test`-scoped in
`exeris-kernel-community`, so a fixture defaulting to it must declare the driver `provided`/optional
rather than drag a database into every consumer's classpath.

**Merge Gate:** a downstream-shaped test — outside `exeris-kernel-community`, depending only on the
testkit — that drives a transaction through the real engine and observes a rollback; the fixture
surface documented in `docs/modules/` alongside the HTTP one. No new SPI: the fixtures compose
existing contracts, so nothing here widens the kernel's public surface.

**Status (v0.11): PARTIAL — persistence delivered, the rest open.**

`EmbeddedPersistenceEngineFixture` boots the real `persistence` subsystem (transitively `memory`)
through `KernelBootstrap` + `ServiceLoader`, with `inMemoryH2()` applying the engine's own DDL and
`forJdbcUrl(url, runMigrations)` for a container-backed or pre-migrated database. `runInKernelScope`
carries consumer work to the thread holding the boot, since a `ScopedValue` binding cannot be handed
out — that is what makes the fixture usable by a host runtime's transaction manager, which resolves the
engine from the slot rather than from a reference. Surface documented in `docs/modules/06-testkit.md`,
which also covers the previously undocumented HTTP fixture.

The merge gate is met with one stated compromise: the consumer-shaped test imports only the testkit and
SPI — nothing from `eu.exeris.kernel.community.*` — but it *lives* in that module's test scope, because
the testkit builds before Community in the reactor and cannot depend on a provider. A genuinely
external consumer module would be stronger, and is worth creating if this pattern grows past one or two
subsystems.

**Still open:** events, flow, graph, scheduling, storage, telemetry. Persistence was taken first
because transactions are the sharpest exposure; events + flow are the natural next pair, since they
compose with the engine this slice now makes reachable.

---

### Security: Route Authorization Is a Hardcoded `/secure` Prefix, Not Something an Application Can Declare (surfaced 2026-08-05, ADR-061)

**Gap:** the HTTP admission gate is fail-closed but unaddressable. `CommunityHttpRequestDispatcher`
hardcodes `SECURE_PATH_PREFIX = "/secure"`, `ADMIN_PATH_PREFIX = "/secure/admin"` and the literal scope
names `security:read` / `security:write` (`:50-53`); `requiresAdmission()` is `startsWith("/secure")`
(`:207-209`). Every other path takes the `else` branch of `dispatch()` (`:96`) straight to the handler
— no admission, no `PrincipalContext` bound. An application whose routes live under `/api/**` has no
edge authorization and no supported way to ask for it.

Three findings from the same audit:

- **The gate is unreachable in a default boot.** `CommunityHttpRequestProcessor:83-86` builds the
  `SecurityInterceptor` only when `KernelProviders.SECURITY_PROVIDER.isBound()`, and nothing in
  production binds that slot. `CommunitySecurityProvider` exists and is `ServiceLoader`-registered, but
  Community ships **no security `Subsystem`** — while `bootstrap.md` already places Security in the L1
  parallel-init DAG (`:39`, `:323`, `:552`). So `/secure/*` answers `401` unconditionally and the whole
  Citadel path (interceptor, role mask, `StorageContextBridge`) never executes.
- **`isPublicPath` is unreachable code** (`:211-220`). It is consulted only behind
  `startsWith("/secure")`, so no path can satisfy both conditions. A leftover from when the health
  routes lived in the dispatcher; they now live in `CommunityHttpHealthRoutes`.
- **Half the RBAC machinery is already live.** Contrary to the stale note at `security.md:6`,
  `GeneratedRoleRegistryLoader.load()` *is* wired in production (`CommunityHttpRequestProcessor:86`) and
  `SecurityInterceptor.enrichWithRoleMask` binds a precomputed `roleMask()`. Only the enforcement call
  site is missing — and it cannot be the `methodId` one (see below).

**Owner:** Security / HTTP subsystems; SPI + Core + Community.

**Resolution:** ADR-061. A route-authorization contract in `eu.exeris.kernel.spi.http` behind an
`Optional` slot on `HttpKernelProviders` (the ADR-036 shape), a driver-agnostic decision helper in
`eu.exeris.kernel.core.security` beside `RoleCheckEnforcer` so every transport inherits one decision
layer, rules **declared in code** (ADR-014 §3 rejected a configuration-file rule surface), a Community
security `Subsystem` binding `SECURITY_PROVIDER`, and removal of the prefix constants plus the dead
allowlist. Default is "no policy declared" — behaviour unchanged for an application that declares
nothing.

**Explicitly not a revision of the Sprint-4 descoping.** URL→`methodId` enforcement stays out: `methodId`
is assigned at compile time from alphabetical ordering under `@Retention(SOURCE)`, so the kernel cannot
reconstruct that map at runtime *at all*. The path-based mechanism is the only one the kernel executes
unaided — it does not depend on `exeris-tooling`, which is a helper layer, not a prerequisite. An
application written directly against the kernel, with no annotation processing anywhere in its build,
gets edge authorization. `@RequiresRole` remains the method-level layer above it.

**Merge Gate:** `AbstractHttpRoutePolicyTck` + a Community binding, with the deny paths and the
unmatched-route path as mandatory cases — a suite that only proves admission would pass against an
implementation that admits everything. `ExerisArchitectureTest` green. `security.md`, `http.md` and
`bootstrap.md` updated in the implementing slice — not before it, since they describe what the kernel
does. (The stale `security.md:6` note is a separate matter: it stated something false about the past,
so it was corrected with ADR-061 itself.) Release notes record the default-boot behaviour change:
`/secure/*` stops answering `401` unconditionally once a provider is bound.

**Status (v0.11): DELIVERED.**

`HttpRoutePolicy` + `RouteRequirement` in `eu.exeris.kernel.spi.http` behind
`HttpKernelProviders.HTTP_ROUTE_POLICY`, `RouteAuthorizationEnforcer` in `eu.exeris.kernel.core.security`
beside `RoleCheckEnforcer`, `CommunitySecuritySubsystem` binding `KernelProviders.SECURITY_PROVIDER`,
and `CommunityHttpRequestDispatcher` resolving the route through the policy instead of
`SECURE_PATH_PREFIX`. The unreachable `isPublicPath` allowlist is gone with the prefix constants.

Two defects were caught by the gates rather than by review. `AbstractHttpRoutePolicyTck`'s
`nullAnswerDenies` case failed the first enforcer, which substituted `HttpRoutePolicy.unmatched()` —
that is `authenticated()` — for a `null` policy answer, so a broken policy would have admitted any
logged-in caller to a route that may have demanded an admin scope. And placing new Core code that
Community calls surfaced that **nothing guarded the tier direction at all**: `ExerisArchitectureTest`
lives in `exeris-kernel-tck`, which has only the SPI on its analysis classpath, so a
Core-must-not-depend-on-Community rule written there would have matched zero classes and passed
forever. `KernelTierDirectionArchitectureTest` now holds that line from `exeris-kernel-community`,
mutation-proven non-vacuous.

**Behaviour change:** `/secure/*` no longer answers `401` unconditionally, because a provider is now
bound. An application that declares no policy gets permit-all everywhere, which is what the kernel did
before — and which means **declaring nothing means no edge authorization**. The subsystem docs say so
rather than implying that installing the kernel confers protection.

**Additive follow-on (v0.11): the claims mapping is substitutable.** `ClaimsMapper` has been
documented since 0.10 as the only application-customisable point in the identity pipeline, but
`CommunityOidcIdentityProvider` constructed the Community default inline, so nothing could supply
one — an application needing a different subject or scope shape had to reimplement `IdentityProvider`
outright, and a host-runtime binding had no seam to translate the same token into its own authority
shape. `withClaimsMapper(ClaimsMapper)` mirrors `enforcingSharedScope()`: a new provider rather than a
mutation, because the mapping takes part in a security decision on every request and must be fixed at
construction. Not an issue and not a defect report — the contract was right, the producer just had no
entry point; scoped as additive because the default is unchanged for every existing caller.

Isolation is deliberately **not** part of what a mapper can influence: it produces a
`PrincipalContext` only, and tenant routing stays with `IdentityStorageMapping` on the path every
provider crosses (ADR-012 §4a). Pinned by a Community test whose composition case is mutation-proven —
an `enforcingSharedScope()` that failed to carry the mapper would silently revert it to the default in
one call order and nothing else in the suite would notice.

**Claim-driven scopes and roles: DELIVERED.** `CommunityClaimsMapper` now reads `scope` / `scp` /
`roles` from the verified token, with no fallback — a token declaring nothing grants nothing. This
was the iteration ADR-040 §"Implementation" already specified ("sub → principalId, roles/scopes
claims → PrincipalContext") and the 0.10 refactor deliberately deferred to avoid changing
observable behaviour, so it needed no new ADR. It also makes `security:write` grantable for the
first time: the admission integration suite gains a case where an admin-scoped token reaches
`/api/admin`, which was unwritable while nothing could grant that scope — meaning the existing 403
case would have passed even against a route no caller could ever reach.

**Behaviour change:** every principal used to receive `security:read` unconditionally. Fixtures and
TCK token builders now declare their scopes explicitly, which is what makes them honest about what
they grant.

---

### CI: The Persistence Gate Ran None of the Tests It Is Named After (surfaced 2026-08-05) — DELIVERED

**Gap:** `exeris-kernel-community` held 11 `@Tag("integration")` classes, every one named `*IT`, and the
CI job that exists to run them selected **none**.

`.github/workflows/maven.yml:194-201` runs
`mvn -pl exeris-kernel-community -DincludedGroups=integration -DexcludedGroups= test`. That is Surefire,
whose 3.2.5 defaults are `Test*` / `*Test` / `*Tests` / `*TestCase` — and the module declared no
`<includes>`. So the job named **"persistence RLS integration gate" ran no persistence RLS test**. What
kept it green were three unrelated `*Test` classes that happen to carry the tag (crypto TLS loopback,
transport ingress, carrier pinning).

Dead alongside them: the JDBC event-stream TCK bindings, the graph parity and churn ITs, the S3 blob
TCK, and the Keycloak OIDC conformance test. Several are the only executable evidence behind contracts
ADR-012 and ADR-056 rest on.

**The fix already existed one module away.** `exeris-kernel-community-kafka` added exactly this
`<includes>` block in v0.8 Sprint 6 (Coverage C-P0-02), with a comment describing the same failure —
"the three IT files end in IT and were silently dead before this include addition". It was never
applied to `exeris-kernel-community`, and the Kafka gate has been genuinely running its ITs ever since
while the persistence gate has not.

**Resolution:** the same `<includes>` block, in `exeris-kernel-community/pom.xml`.

**Evidence, by the two commands that matter:**

- `mvn -pl exeris-kernel-community test` selects **zero** `*IT` classes — every one is
  `@Tag("integration")`, so no Testcontainers start in a default build.
- `mvn -pl exeris-kernel-community -DincludedGroups=integration -DexcludedGroups= test` — the gate's
  own command — now runs all 11: **74 tests, 0 failures**. They were never broken. They had simply
  never run.

**Lesson this repeats.** Same shape as the "verify skips PMD" myth (PR #238) and the JaCoCo floor that
only began applying once a module gained its first test: a gate believed because it was green, when
green meant it had nothing to check. A gate's name is not evidence; the `Running …` lines in its log
are.

---


### Diagnostics: The Provider Inventory Reports Nine Of Fifteen Provider SPIs (surfaced 2026-08-27)

**Gap:** `CommunityProviderInventory.discover` makes nine `discover(...)` calls — memory, crypto,
telemetry, persistence, events, flow, transport, graph, security. Community registers **fifteen**
provider SPIs in `META-INF/services`. Diffing the two mechanically:

| Registered but never swept | Kind |
|---|---|
| `HttpProvider` | ordinary driver, and one of the ten bootable subsystems |
| `JobSchedulerProvider` | ordinary driver (v0.11, ADR-057) |
| `BlobStorageProvider` | ordinary driver (v0.11, ADR-056) |
| `SubsystemProvider`, `ConfigProvider`, `KernelDiagnosticsProvider` | bootstrap/meta — plausibly deliberate, but nothing says so |

The consequence is not cosmetic, because `listProviders` is a published contract (ADR-033) and the
out-of-process surface an agent or a code generator reads to decide what the kernel it is talking to
can do. A consumer asking "is a blob backend present?" gets an answer that means "no provider of a
kind this inventory happens to enumerate", and cannot tell that from "no provider". Absence and
unawareness are indistinguishable through the SPI — which is the one property a diagnostics surface
exists to prevent.

`HttpProvider` is the entry that shows this is not a v0.11 oversight to sweep up alongside the two new
drivers. It predates both, it backs the subsystem consumers ask about first, and it has been missing
the whole time. The inventory is a hand-maintained list of `discover(...)` calls with nothing tying it
to the set of SPIs that exist, so it falls behind once per new provider SPI, silently, by
construction. Three of the six may well belong outside a *driver* inventory; the defect is that the
list cannot distinguish "excluded on purpose" from "never added", and neither can a reader.

**Owner:** Diagnostics / Community.

**Resolution:** Add the sweeps that belong, and record the exclusions that do not as named exclusions
rather than as absences. Then close the class instead of the instance: a gate that fails when a
provider SPI has neither a `discover(...)` call nor an entry in an explicit exclusion list.

Enumerate the SPIs for that gate **from their declaration site** — the `*Provider` interfaces in
`exeris-kernel-spi` — and diff against registration, not the other way round. A census taken from
`META-INF/services` cannot see a provider SPI that is dispatched some other way, and there is one:
`IdentityProvider` (ADR-040) is selected through `IdentityProviderRegistry` by priority and
`canAttempt`, and has no services entry at all. A services-file census reports it as not existing
rather than as not enumerable — an error the gate would inherit and then certify.

**Merge Gate:** `listProviders` reports an HTTP provider, a scheduler and a blob store on a default
Community classpath; a gate test that fails when a provider SPI is neither swept nor explicitly
excluded, proven non-vacuous by deleting one sweep and watching it fail.

---

## Known Gaps / Future Work planned for v0.12

### Persistence + Flow: Request-Session Scope Collision, Lost Choreography Wake, Silent Snapshot Refusal

**Gap:** Three independent defects, surfaced by one investigation into why the saga benchmark exhausted a 128-connection pool while the Quarkus and Spring arms peaked at 21 and 47.

1. **The engine keyed the per-request session from two sources.** `openConnection()` always keyed it `"shared"`; `openConnection(StorageContext)` keyed it by tenant. A request touching both mismatched by construction, and a Saga touches both: the flow snapshot store, the outbox adapter and the event log call the no-arg overload while repositories arrive through the context one. Measured on a v0.11 benchmark: 548,683 `BYPASS_SCOPE_MISMATCH`, 2.0 per request session, i.e. double the connection demand.

2. **The bypass was not a neutral fallback.** It reached `openPhysicalConnection()`, which runs no `ConnectionInterceptor`, and `RlsConnectionInterceptor` publishes its session keys with `set_config(..., false)` - session scope, surviving pool checkin. The connection therefore arrived carrying the previous borrower tenant: under RLS a cross-tenant read, and a write judged by the wrong `WITH CHECK`. That is the failure the interceptor was hardened against; the bypass routed around it.

3. **A choreographed wake arriving before the park it answers was destroyed.** `FlowChoreographyBridge` read an absent `lookupParked` as a stale or duplicate event. A third case was unnamed: the instance is live and still inside the step about to `PARK`. `lookupParked` filters on `state() == PARKED`, so it reported that instance absent, `wake()` was never called, and `wakePending` was never armed. One event per business trigger means nothing re-sends it.

4. **A refused durable checkpoint was silent.** `FlowSnapshotSaveFailedEvent` (JFR-091) is emitted from inside the `JdbcFlowSnapshotStore.save` try-with-resources body, so a failure raised by the resource expression itself (`engine.openConnection()`) escapes every catch there. Pool exhaustion is precisely the failure that event cannot see; the only trace was an uncaught exception on the flow virtual thread.

**Owner:** Persistence + Flow subsystems.

**Resolution (v0.12, PR #346):** `openConnection()` resolves `KernelProviders.storageContextOrSystem()` and delegates to the context overload, so both keys come from one source and the un-intercepted acquire disappears with the mismatch. `CommunityGraphSession` routes through the engine rather than asking the session box directly. `FlowChoreographyBridge` delivers the wake on the empty branch as a `wake()` carrying `PARKED` intent, which `resolveParkedInstance` already admits and `beginScheduleAfterWake` defers through `wakePending`. `FlowSnapshotPersistFailedEvent` sits at the call site in `FlowSnapshotWriter`, so a refused checkpoint is recorded whatever the binding, with behaviour unchanged.

**Merge Gate:** full-reactor `mvn clean install` with no skip flags (so Checkstyle and PMD are active), `ExerisArchitectureTest` and the `KernelTierBan` / `KernelTierDirection` suites green, and the tagged `integration` gate green for persistence RLS, the flow snapshot TCK and graph. Each new test verified to have teeth by reverting its own fix and confirming it fails.

**Status: DELIVERED (v0.12).** Three commits, three tests. `CommunityRequestScopeBypassIsolationIT` pins the isolation rule against PostgreSQL with `FORCE ROW LEVEL SECURITY`; `FlowWakeBeforeParkTest` drives the bridge rather than a copy of its logic; `FlowSnapshotPersistFailedEventTest` asserts the event through a `RecordingStream`.

**Carried forward, all three closed in the follow-up (v0.12):**

- The `openConnection()` obligation is now pinned by `AbstractPersistenceEngineTck.openConnectionHonoursAmbientContext`, and it needed **no new SPI**. The obligation is observable through surface that already exists: register a `ConnectionInterceptor` and assert `onConnectionAcquired` is invoked with the ambient `StorageContext`. The earlier note here proposed adding a way to interrogate a connection - that was the wrong shape, and adding it would have put a method on every driver to test something the interceptor seam already reports.
- The park-checkpoint ordering is resolved as retry-and-mark rather than reorder; see the flow subsystem doc. Reordering was measured against and rejected: it converts a transient store outage into a saga lost even without a restart.
- The doubled store read is gone with `FlowScheduler.wake(long, long)`, an additive `default` whose body is the old two-call form, so no implementation changes behaviour without overriding it.

**Superseded, kept for the record:**

- **No `Abstract*Tck` for the new `openConnection()` obligation.** It is only observable against a live RLS database, and the SPI offers no way to ask a connection about its isolation state, so expressing it in the TCK needs new SPI surface - a design decision, not a mechanical addition. Held today by the Community binding IT.
- **The obligation binds implementations outside this repository.** An engine treating the no-arg overload as "no context" carries the same defect.
- **A choreographed wake for a genuinely unknown key costs a second snapshot-store read.** `lookupParked` probes the store and records the miss; `wake()` clears that negative entry on entry, so `loadSnapshot`'s suppression does not catch the second probe. Not clearing it would let a stale negative outlive a park that happened on another engine and refuse a legitimate cross-engine wake, and skipping the delivery is the original bug - so the cost is deliberate. `terminalStateCatalog` short-circuits known terminal keys before either read, confining it to unknown or evicted keys. Threading the probe result through needs SPI surface.
- **`applyParkOutcome` ordering is unchanged.** It sets `PARKED` and registers the instance before persisting, so a refused save leaves memory and the store disagreeing and the exception escapes `runInstance` uncaught. Reversing that alters the durability contract and wants its own TCK coverage; v0.12 only makes the failure observable.

---

### Persistence + HTTP: Connection Lifetime Is Bound to the Request, With No Opt-Out (surfaced by saga-benchmark triage, 2026-08-21)

**Gap:** `CommunityHttpRequestDispatcher.handleWithinRequestSession` binds a `PersistenceSessionBox` around every non-streaming request. The first persistence call takes a pooled connection, `close()` on the handle it hands out is a no-op (`NonOwningPersistenceConnection`), and the pool gets the connection back only from `box.release()` in the handler's `finally`. Connection lifetime is therefore **request** lifetime, not transaction lifetime. The binding is unconditional: no configuration key anywhere in SPI or Community disables it, and no ADR governs it — it is described in `docs/subsystems/persistence.md` §Request Session and nowhere else.

That is a sound design for a handler that returns promptly. For one that blocks it is hold-and-wait on a single pool, because the work that must finish before the handler can return draws from that same pool: flow steps run on bare `Thread.ofVirtual()` (`CoreFlowRuntime.launch`), inherit no `ScopedValue`, and acquire independently — including the park checkpoint write (`applyParkOutcome` → `persistSnapshot`), which every parked saga performs.

**Measured** (cross-runtime saga benchmark; park 1000 ms, pool 32, ~38 sagas in flight, ~165 s, identical for all arms):

| arm | exit | orders | conn-exhaustion |
|---|---|---|---|
| quarkus-lra-jdbc | 0 | 6273 | 0 |
| spring-axon-jdbc | 0 | 6275 | 0 |
| spring-axon-embedded | 0 | 6273 | 0 |
| restate | 0 | 6275 | 0 |
| **exeris-community** | **5** | **286** | **386** |

- **Attribution is the request thread, not the flow engine.** `RequestSessionLifecycle` split by thread kind on a healthy run (park 250 ms, 6273 orders): 37,626 `ACQUIRE` / 37,626 `RELEASE` / 81,523 `REUSE`, **all** on HTTP request-carrier threads and **not one** on a flow virtual thread. p50 3 ms, p90 273 ms, and **6,271 sessions ≥200 ms against 6,273 orders** — of the 6.00 request-sessions each order costs, exactly one spans the park. One pinned connection per saga in flight, counted rather than modelled. **Corrected 2026-08-26 — the "not one on a flow virtual thread" half of this is circular and is withdrawn.** `RequestSessionLifecycleEvent.emit` appears in exactly one class, `PersistenceSessionBox`, and a flow thread never binds `REQUEST_SESSION`, so it reaches `openPhysicalConnection` and emits nothing by construction. Zero flow-thread events is what the instrument produces, not a finding, and "the park itself pins nothing" does not follow. What survives on its own evidence is the request side: a p90 of 273 ms against a 250 ms park, with one long session per order, shows request-thread sessions spanning the park. How much flow threads hold is **unmeasured**.
- **A pool-bound ceiling does not explain the magnitude.** 32 concurrent × 1 s over ~165 s predicts ~5,280 orders; measured 286, an 18× over-prediction. Raising the pool 32 → 128 returns 6,275 orders and zero exhaustions — *offered-load parity*, not the 4× a ceiling would give. A non-linear jump straight to parity is the signature of a released deadlock, not a raised ceiling. The collapsed run's `RELEASE_NO_SESSION` = 15,434 (zero on the healthy run) was read here as "requests whose very first acquire timed out"; **that reading is withdrawn (2026-08-26)**. The event fires in `release()` whenever `session == null`, which covers a request that never touched persistence as well as one whose acquire threw, and the ~40× gap against 386 conn-exhaustions is unexplained rather than corroborating. Reported, and relied on for nothing.
- **Admission control is not the lever, in either direction.** `AdmissionDecision.queueDepth` peaked at **71** against an allowance of `ceil(32 × 8.0)` = 256, so widening the allowance cannot bite — `queueDepthAllowanceRatio=32.0` was cancelled on that evidence rather than run. Tightening to `0.0` (STRICT) did reach the controller — rejections went from ~zero to **23.87 %** (1,490 of 6,240) — and returned **268** orders against 286. **Mechanism corrected 2026-08-26:** `evaluateAdmissionReason` has five arms, and `idle <= 0 && queued > 0` is not one of them. The three that matter are each gated on `queued > queueDepthAllowance(max)` — never true at a measured peak of 71 against 256, so no reject arm could fire on this run. That is the stronger argument. Two things it fixes: `GUARD_BAND_FAIRNESS` **can** fire with idle connections still in the pool (`shouldRejectEarlyInGuardBand` requires `remainingHeadroom > 0`), so "cannot prevent the deadlock forming" was wrong; and the widening direction was **reasoned** closed on the 71-against-256 evidence, not run, so "in either direction" overstates one half — only the tightening arm was measured. and a shed request is a lost order rather than a deferred one, so the shed rate is a straight throughput tax. This closes ADR-035 tunability as a remedy for this failure, measured from both ends.
- **`BYPASS_SCOPE_MISMATCH` = 0.** The v0.11 scope-key collision fixed above does not fire here, and only because the benchmark's security provider returns `ImmutableStorageContext.GLOBAL` — deliberately, so exeris is not credited with tenant isolation the Quarkus and Spring arms do not carry. An exeris arm declaring a tenant on v0.11 takes a second, independent hold-and-wait on top of this one.

**The kernel already names this hazard — for streams only.** `dispatchStream` deliberately omits `REQUEST_SESSION` because "one read inside a live feed would pin a connection for as long as the client stays connected … a design, not a binding copied across". A handler that blocks across a park is the same hazard two orders of magnitude down, and gets the binding anyway. The failure mode is recognised in-tree; the recognition is scoped to the case where the hold time is unbounded and therefore obvious.

**Why the handler was blocking in the first place.** `FlowScheduler` exposes no completion surface (a separate gap, tracked under "Flow: No Way to Await a Flow" below), so an application wanting request/response over a saga resolves it inline in the handler — which is how the benchmark arrived at this hazard. That is the route in, not the defect: the subject here is the connection binding, and it punishes *any* handler that blocks long enough, saga or not. An application returning `202` and polling would never hold the connection. The two are fixed independently and neither substitutes for the other.

**Owner:** Persistence + HTTP subsystems.

**Resolution:** ADR, not a patch — number reserved in the global index before authoring. The question is what connection lifetime the kernel promises. **The costs sketched here were repriced by [RFC-2026-08-26](rfc/RFC-2026-08-26-request-connection-lifetime.md) and three of them were wrong:** the detach seam is **Community-tier, not an SPI obligation** (`PersistenceSessionBox` lives in `community.persistence`); per-route opt-out needs no new mechanism because `HttpRoutePolicy.requirementFor` is **already resolved on every request** and `RouteRequirement` is on the preview surface; and transaction-scoped lifetime costs **no** interceptor consistency, because `RlsConnectionInterceptor.onConnectionAcquired` republishes the session keys on every acquire — so the RLS reasoning is not why "just unbind it" fails. What it actually costs is three things this entry did not name: it retracts the documented "One HTTP request is one connection" (`persistence.md:129`), it inverts `CommunityRequestScopeBypassIsolationIT`'s same-backend-PID assertion, and — because `NonOwningPersistenceConnection.close()` is a no-op — it turns every missed close into a pool leak unless release ownership moves off the handle. **Do not narrow the binding and change admission defaults in the same commit**: the measurements above close admission as a lever, and re-opening it here would confound the fix.

**Merge Gate:** ADR accepted with one shape and dissent recorded. If a surface lands: `docs/subsystems/persistence.md` §Request Session updated to state the chosen lifetime and its opt-out; TCK coverage for the lifetime contract plus a Community binding test; and a test with teeth for the mechanism itself — a handler that blocks while holding a session must not be able to starve the pool its own continuation draws from. Verified by reverting the fix and confirming it fails.

**1.0 disposition:** 1.0-critical. Persistence and HTTP are both in the 1.0 core; the failure is an availability collapse rather than a slowdown; and it is reachable from an ordinary application shape with nothing unusual configured. It is also the first cross-runtime benchmark result where the kernel loses categorically, which makes it a product claim and not only an engineering one.

**Status: OPEN (v0.12).** The option comparison now exists — [RFC-2026-08-26](rfc/RFC-2026-08-26-request-connection-lifetime.md), DRAFT. It recommends **building the per-route lifetime seam and gating the default-flip on a measurement**, on the finding that per-route opt-out and transaction-scoped-by-default are not rival designs: the second is the first with its default inverted, and both need one facet on `RouteRequirement`. The deadlock finding and the pool 32→128 parity result stand. The **attribution does not** — see the correction in the first bullet above — so this entry no longer claims the request session is the whole hold. An ADR number is reserved on RFC acceptance, not before.

---

### HTTP: `WebSocketProvider` SPI (or SSE-Only Commitment)

**Gap:** The `realTimeApi` flag on `DomainMetadata` (SDK) exists in v0.8 but no generator emits real-time wire code, and the kernel has no WebSocket primitive. Real-time delivery splits into two distinct wire shapes: **Server-Sent Events** (pure HTTP/1.1 chunked streaming, runs on existing `HttpServerEngine` with no new SPI), and **WebSocket** (RFC 6455 handshake + frame codec, wire-protocol territory equivalent to HTTP/2 — requires kernel SPI). Without a decision, the `realTimeApi` flag remains aspirational and downstream applications hand-roll incompatible solutions.

**Owner:** HTTP / Transport subsystem.

**Resolution:** Open a short RFC (single option-comparison page) deciding between (a) **SSE-first, WebSocket as a separately-justified follow-up (not pinned to a release milestone)** — minimum scope, zero new SPI, ship an SSE response-writer pattern in Community plus generator emission, or (b) **`WebSocketProvider` SPI lands in v0.12** — full `WebSocketSession`, `WebSocketFrame`, `WebSocketHandler` family in `eu.exeris.kernel.spi.http.websocket`, Community NIO driver, Enterprise `io_uring` driver track. If (b), TCK covers RFC 6455 handshake compliance, frame fragmentation, ping/pong, close-code semantics, masking, and adversarial cases (oversized frame, malformed payload, partial-message split, post-close traffic). Either way, generator emission for the `realTimeApi` flag lands in `exeris-tooling/` in the same window.

**Merge Gate:** RFC accepted with one shape selected and dissenting position recorded; if (b), SPI green on TCK + Community binding green + isolation-key scoping verified (per-tenant WS rooms via `StorageContext.isolationKey`); if (a), SSE pattern documented in operator-facing docs with an explicit "WebSocket = deferred / separately-justified (not milestone-pinned)" note in the Support Matrix (per v0.9 §"Support Matrix Finalization").

**Status (RFC opened 2026-06-18 — shifted earlier than v0.12):** the RFC is `docs/rfc/RFC-2026-06-18-http-streaming-spi.md`. Preferred direction = a variant of option (a): **SSE-first** over a **sibling `HttpStreamExchange`** (not a streaming mode on `HttpExchange`; respond-once invariant preserved), with WebSocket deferred to a later, separately-justified decision. **ADR-043 reserved.** Implementation **brought earlier to v0.10/v0.11** (not v0.12) to unblock the SDK `realTimeApi` / `@Action(streaming)` chain. The eight load-bearing design questions for ADR-043 (exchange surface + disconnect-as-throw, park-the-VT `emit()`, `LoanedBuffer` transfer ownership, streaming-lifecycle JFR, `EX-HTTP-*` taxonomy, router extension, PAQS accounting, JWT-expiry-mid-stream fail-closed) are enumerated in the RFC.

---

### Runtime: `CacheProvider` SPI — RFC Track Only

**Gap:** `DomainMetadata.cacheable` + `cacheRegion` + `cacheTtl` flags exist in v0.8 SDK but no generator wires application services to a cache layer, and the kernel has no cache primitive. Caching is a runtime hot path with plausible alternative drivers (in-process Caffeine vs Redis vs off-heap slab), qualifying as SPI candidate territory; but committing to an SPI contract without a real second-backend pull risks designing the wrong shape — Caffeine-only deployments would carry contract weight they never exercise.

**Owner:** Runtime / Performance subsystem.

**Resolution:** Open an RFC enumerating contract questions: read-through vs read-aside semantics, invalidation strategy (TTL-only vs key-level invalidate vs region-flush), `PrincipalContext` / `StorageContext` scoping (per-tenant cache regions), serialization shape for distributed backends, async vs sync `get` semantics on Loom (blocking-on-Loom is acceptable but the contract must be explicit), interaction with the existing `WatermarkManager` SHED_LOAD decisions. RFC stops at "RFC accepted with preferred shape called out"; SPI lands only when a concrete second-backend pull materializes (downstream consumer requesting Redis, or benchmark demonstrating off-heap slab win). Until then, generator emission for `cacheable` flag remains a tooling-only Caffeine wiring.

**Scope clarification — the cache seam is NOT KV-as-projection.** This `CacheProvider` entry is the **commodity cache seam**: a swappable, *backend-defined* store (Caffeine-class in-process vs Redis vs Hazelcast) with a coherence contract. It is a **different primitive** from the off-heap, log-*derived* last-value view tracked under v0.12 §"Runtime: KV-as-Projection" — that one is a *view on the Events log* (rebuilt from compacted replay), this one is an *independent cache the application reads and writes directly*. The two were previously conflated in this section; they must be designed as distinct contracts. The Community in-process backend here is an ordinary cache (heap or off-heap slab as an impl detail), **not** the Events-log projection.

**Binding-agnostic shape (same open-core pattern as Events `topic`, transport NIO/`io_uring`).** Specify a single `CacheProvider` SPI that is swappable across bindings exactly as K1 proposes for the Events `topic` seam: Community ships an in-process cache behind the SPI; enterprise / adapter bindings provide Redis / Hazelcast. Application code is written against the contract, not the backend. **Distributed invalidation rides the Events bus, not a separate channel:** a region flush / key invalidate is published as an event, so the cache seam reuses the multi-node substrate already in place rather than introducing a second coordination mechanism. For single-node deployments the in-heap `CommunityEventBus` is itself the channel — coherent invalidation with no Kafka dependency; Kafka is the multi-node path only (the Community binding must not require Kafka for correctness). K1 (`topic`) + `CacheProvider` + invalidation-over-events = one coherent multi-node story with zero new data-plane in the kernel.

**Coherence is a `[CONTRACT]` landmine.** Local (in-process) and distributed cache backends have *different* coherence semantics; if the SPI hides this, business code written against the contract is subtly wrong after a backend swap (a stale read that never happens on Caffeine surfaces under Redis). The RFC must resolve this one of two ways: make coherence an explicit part of the contract (stated staleness / read-after-write / invalidation guarantees the caller can rely on regardless of backend), or bind invalidation to the Events bus so coherence is delivered by the same mechanism on every backend. **The invalidation-over-events choice above does NOT discharge this** — corrected 2026-08-07 by [`RFC-2026-08-07`](rfc/RFC-2026-08-07-cache-provider-spi.md). The substrate cannot carry the guarantee: `InMemoryEventBus.publish` starts one virtual thread per handler and returns *before any handler runs*; the bus contract promises no ordering at all (`AbstractEventBusTck`: "No-Ordering by Design (ADR-049) … callers needing ordering use the durable log"); the in-heap bus cannot cross a node boundary; and the Kafka *bus* binding polls with `enable.auto.commit=true`, so a dropped invalidation leaves a permanently stale entry with no error anywhere. The ordered, manually-committed channel does exist — `KafkaEventLog` sets `enable.auto.commit=false` and ADR-049 gives the durable log per-stream total ordering — but that is a different channel from the bus, and conflating the two is what made the discharge look automatic. The RFC resolves this in favour of the first option: an **explicit declared staleness contract**, with the durable log rather than the bus as the channel if one is needed.

**Audit note (2026-06-22), CORRECTED 2026-08-07 by [`RFC-2026-08-07`](rfc/RFC-2026-08-07-cache-provider-spi.md).** The 2026-06-22 note speculated that the second-backend pull "may already be met" and named downstream dogfooding as the puller. Checked against source, it is **NOT met**, and the note is refuted on its checkable half. BudgetHQ — the active consumer, twelve backend poms declaring `exeris-kernel-community` / `exeris-spring-runtime-web` — has zero cache dependency in any pom, zero Spring Cache annotation, and zero Redis client code; it provisions a `redis:8-alpine` container that nothing connects to. Every place it touches caching it recorded a decision *against* a distributed cache, each with an explicit **drop trigger** (widget catalog: "p95 > 100ms OR > 1000 req/min per pod"; sync-service token cache: "when sync-service scales beyond single-replica"; gateway rate limiter: "in-memory is correct and does not require Redis"). A drop trigger is the opposite of a pull — it is the consumer stating in writing that the condition has not arrived, and those triggers are now the observable re-evaluation condition for this gate. The benchmark alternative is also unmet: `exeris-benchmarks` contains no cache or slab-cache benchmark, and its compose README advertises a redis fixture no compose file defines. One further downstream consumer is closed-source and not inspectable from the open tree, so no claim is made about it here — note only that the dogfooding gaps this ROADMAP records from that source contain no cache item.

**What survives of the note:** the kernel cache primitive is confirmed **ABSENT**, and the SDK flags are dead — in fact deader than recorded. Only `@ExerisDomain` reaches the source model; `@Graph`'s cache attributes are read and then discarded (`extractGraphMetadata` returns a `GraphMetadata` with no cache component to receive them) and `@Projection` is never extracted at all. The real surface is five annotations with three boolean names (`cacheable`, `cacheQueries`, `cacheReadModel`, `cached`, `cacheAggregates`) and two incompatible TTL encodings. The line citation `ExerisDomain.java:258,266,274` is two releases stale — current coordinates are `:370,378,386`.

**Status (v0.11): RFC DELIVERED — gate resolved, SPI gate stays CLOSED.** [`RFC-2026-08-07: Should the kernel own a CacheProvider SPI, and what coherence does it promise?`](rfc/RFC-2026-08-07-cache-provider-spi.md) recommends staying RFC-only because the second-backend pull is not met (see the corrected audit note above), and resolves the coherence-semantics requirement in favour of an **explicit declared staleness contract** — the "invalidation bound to the Events bus" alternative the original gate offered turned out not to be available.

**Merge Gate (as satisfied):** RFC accepted with the coherence-semantics contract resolved so a backend swap cannot silently change correctness; no kernel SPI commits in this gate (decision-only track). SPI implementation gate deferred to a future version, conditional on a real second-backend pull — **re-evaluate when one of BudgetHQ's own drop triggers trips**, not on a calendar and not on a recollection: widget-catalog p95 > 100ms or > 1000 req/min per pod, or sync-service scaling beyond a single replica. When it lands, distributed invalidation rides the ordered durable log, not the bus.

---

### Runtime: KV-as-Projection (Off-Heap, Log-Derived Last-Value View) — distinct from the `CacheProvider` cache seam

**Gap:** "KV as a view on the log" — last-value-per-key projection of a compacted stream — is the unification play and is **NOT** the `CacheProvider` cache seam above. The cache seam is a swappable commodity backend; KV-as-projection is an off-heap, deterministic, log-*derived* view that inherits the off-heap / zero-alloc thesis **only if** built on existing machinery rather than a heap map. Code audit (2026-06-22) found the substrate is **further from ready than it looks**:
- `ProjectionEngine` (`exeris-kernel-core/.../events/projection/ProjectionEngine.java`) is REAL but is a **single-aggregate fold over the live bus**: one `AtomicReference<S>` per projection (`Projection.java:55,127`), **heap-resident**, **no keyed / last-value-per-key state**, and **not driven by any `EventStreamReader` replay** (it folds the live subscription, so durable cross-restart rebuild is not wired).
- There is **no off-heap keyed store**: the `MemoryAllocator` slab layer (`exeris-kernel-spi/.../memory/MemoryAllocator.java`) provides buffer/slab primitives only and even names "projection-cache slabs" as a future use (`:114`), but nothing keyed is built on it.
- **Compaction is ABSENT everywhere** (audit): the Postgres outbox is append-only drained by forward polling with per-event delete; Kafka has no `cleanup.policy=compact` and the driver provisions no topic config at all.

So KV-as-projection needs **three net-new mechanisms, not one**: (1) **keyed projection state** (ideally off-heap on the slab layer — else it degrades to a heap `ConcurrentHashMap`, the commodity version that betrays the off-heap thesis); (2) **replay-driven rebuild** (a concrete `EventStreamReader` — today a skeleton, gated on the v0.10 Events ordering/concurrency fundament); (3) **store-side compaction** (keep-latest-per-key — the one genuinely new store mechanism). With these, `get` = O(1) read of current keyed projection state, `watch` = bus subscription, durability = replay of the compacted stream.

**Owner:** Events / Memory subsystem.

**Resolution:** RFC-track (paired with the `CacheProvider` RFC so the two seams are designed as **distinct** contracts, not collapsed). Specify a keyed projection variant (`ProjectionHandler` keyed by an ordinal / `StreamId` → off-heap slab-backed state), a compaction policy on the durable binding (Postgres keep-latest-per-key + Kafka `cleanup.policy=compact`), and the rebuild path over `EventStreamReader`. **Depends on the v0.10 Events ordering/concurrency boundary landing first.**

**Merge Gate:** RFC accepted distinguishing KV-as-projection from the cache seam; if implemented, `AbstractKeyedProjectionTck` covers keyed fold + compacted-replay rebuild + O(1) get; off-heap state asserted (no heap map on the hot path); compaction proven on ≥1 durable binding. Decision-only until the fundament lands.

See also: v0.10 §"Events: Log-Ordering & Optimistic-Concurrency Boundary" (the fundament); v0.12 §"Runtime: `CacheProvider` SPI" (the distinct cache seam).

---

## Road to 1.0 — Differentiator & Table-Stakes Gaps (surfaced 2026-06-22)

> This section captures gaps that make the two load-bearing product claims — **"deterministic runtime"** and **"replaces application + orchestration layer"** — *demonstrable* rather than merely asserted, plus cross-cutting table-stakes that had no owner in this document. Each entry carries an explicit **1.0 disposition** (1.0-blocking / 1.0-recommended / post-1.0). All claims code-verified 2026-06-22.

### Differentiator: Flow/Saga Definition Versioning + In-Flight Migration (the Camunda-wedge enabler)

**Gap:** Replacing an orchestration layer (Camunda/Temporal) is not credible without the one thing those engines all handle: a long-running saga outliving a deploy that changed its definition. Today the kernel has **no versioned flow definition and no in-flight migration**, and the documented safety net does not exist:
- `FlowDefinition` is keyed solely by `String name` (`exeris-kernel-spi/.../flow/model/FlowDefinition.java:34`) — no version field. `FlowSnapshot.schemaVersion` is a CAS optimistic-lock counter, *not* a definition version.
- Resume is **position-bound**: `CoreFlowRuntime.resolvePlanForSnapshot` (`:468`) rebinds a parked saga to whatever plan is currently registered under that name, then replays the persisted `currentStep` `int` into it. Since v0.10 a **bounds/arity** guard rejects an out-of-range index, so the removed-step case fails closed. The **same-arity reorder** is still open and is the data-corruption case: the index stays in range and the saga resumes on a different step. **ADR-062 closes it** by recording the parked step's identity in the snapshot and validating it on wake — the first slice of this epic, and the prerequisite for FlowJournal, since a history recorded in positions becomes false at the next deploy.
- **~~Doc-vs-code drift (correctness landmine)~~ — CORRECTED 2026-08-05.** This bullet claimed `docs/subsystems/flow.md:36`'s `EX-FLOW-7002 / phase="SCHEMA_MISMATCH"` guard was aspirational and that "no such phase or step-bounds validation exists anywhere in code". It shipped in **v0.10**: `CoreFlowRuntime.validateSnapshotStepBounds` (`:504`, called from `:474` and `:484`) rejects a snapshot whose step no longer indexes the plan, emitting `FlowSchemaMismatchEvent` first. The audit that wrote this bullet ran 2026-06-22, before v0.10 (2026-07-02), and nobody revisited it — so the note itself became the drift, accusing the code of a gap the code had closed. Verified by reading `CoreFlowRuntime`, not by re-reading the doc that made the claim.
- `loadByDefinition()` is explicitly deferred to "the definition-versioning epic" (FLOW-101) — an epic never written into this roadmap until now.

**Owner:** Flow subsystem.

**Resolution (two-stage):**
1. **Correctness guard — DONE.** The arity half shipped in **v0.10**: `CoreFlowRuntime.validateSnapshotStepBounds` (`:504`) throws `EX-FLOW-7002 phase=SCHEMA_MISMATCH` when the persisted `currentStep` no longer indexes the plan, so a removed step fails closed and `flow.md:36` is accurate. The **identity** half — a same-arity reorder, where the index stays in range and the saga resumes on a different step — shipped in **v0.11** under **ADR-062**: the snapshot records the parked step's identity and the wake validates it. `AbstractSagaRecoveryTck` covers both reasons, the reorder (`STEP_IDENTITY_MISMATCH`) and the pre-0.11 row that carries no identity (`STEP_IDENTITY_ABSENT`). This item previously read "implement the guard the docs already promise", which stopped being true when v0.10 shipped. A 1.0 that silently mis-replays sagas across deploys is not shippable.
2. **Definition-versioning epic (v0.11) — decided by ADR-064.** Add a version to `FlowDefinition` (name+version key), carry it in `FlowSnapshot`, make resume resolve the *exact* definition version the saga was parked under, and define an in-flight migration contract (migrate a parked saga vN→vN+1 via an explicit testable transform). The concrete blocker is that `planCatalog` is a `ConcurrentMap<String, CoreFlowExecutionPlan>` (`CoreFlowRuntime:52`) keyed by name alone, so two versions cannot coexist — registering a changed definition evicts the one every in-flight saga parked under. `loadByDefinition()` waits on the version key; it does not exist in code today and ADR-064 does not add it.

   Two points ADR-064 rules that this entry previously left open. **No-migration-path is a rejection that does not mutate the row** — the saga stays `PARKED` and stays recoverable, so deploying the missing version or registering the missing transform gets it back; a quarantine `FlowState` is deliberately not introduced, because marking it terminal would be irreversible and would run compensation for a definition the runtime cannot bind. And **a migration's output is validated rather than trusted**, so application code on the resume path is not a new trust boundary — the accepted text named ADR-062's identity check as the whole of that validation, which covered the cursor and not the compensation stack — corrected by A4 and completed by A5 below.

**Merge Gate:** stage 1 — `AbstractSagaRecoveryTck` gains a "definition changed under a parked saga" case asserting fail-closed `SCHEMA_MISMATCH` on a same-arity reorder; `flow.md`'s compatibility matrix updated so the reorder row stops being a warning and becomes a guard (that row is accurate today — it describes an open gap, not drift). Stage 2 — `AbstractFlowDefinitionVersioningTck` covers version-keyed resume, vN→vN+1 in-flight migration, no-migration-path rejection; Community JDBC + Kafka bindings green; additive only.

**1.0 disposition:** stage 1 **1.0-BLOCKING** (correctness); stage 2 **v0.11 differentiator** — the go-to-market wedge; pull earlier if downstream consumers need it. *This is the most urgent item in this section* — it strikes the strongest concrete claim (the Camunda wedge).

**Status (v0.11): stage 1 DELIVERED (ADR-062); stage 2 DELIVERED (ADR-064, both halves, incl. amendments A4 and A5).**

Coexistence: `FlowDefinition` carries a version, the plan catalog is keyed by `(name, version)` so versions coexist, `FlowSnapshot` records the version a saga parked under, and both resume entry points — `wake()` and `schedule()` — bind to that exact version or refuse fail-closed.

In-flight migration: `FlowDefinitionMigration` is registered per `(definitionName, fromVersion)` through `FlowExecutionPlanFactory.registerMigration`; on wake the runtime walks adjacent hops until it reaches a hosted version and stops at the first one, persisting the result before the resumed step runs. A gap in the chain is the no-path rejection that leaves the row intact. `AbstractFlowDefinitionVersioningTck` is the gate for both halves.

Points settled during implementation and recorded as ADR-064 amendments rather than left as silent divergence: migration is scoped to the **resume-restore** path — `wake()` and `lookupParked()`'s durable-store fallback, which is a restore rather than a read and is how choreography wakes a cross-engine saga — while `schedule()` keeps refusing (the resubmit path fixes the target version at the caller's plan, which makes the chain's terminating condition path-dependent); a successful migration is **persisted**, so the chain does not re-run on every wake and transform purity stays an implementation detail rather than an unstated obligation; and `FlowMigrationState` carries the step the saga **parked at**, not the step it would resume into.

**A4 was found while auditing those two carried items and is the reason this entry now names three amendments rather than two.** Obligation 9 claimed a transform's output is validated by ADR-062's identity check; that check reads the cursor only, so two of `FlowMigrationState`'s five components were covered and the compensation stack — the component that drives rollback — was not. The failure mode is not a bad resume but an aborted rollback: `runCompensationStep` resolves entries with `plan.stepAt(entry)` outside its own catch, so a stale entry skips the remaining unwind *and* `finalizeFailedInstance`, leaving the saga mid-compensation with its guard held. The bounds half is enforced on every resume (`COMPENSATION_STACK_OUT_OF_RANGE`), and the identity half by A5 (`COMPENSATION_STACK_IDENTITY_MISMATCH` / `..._ABSENT`).

Carried, not closed:

- **Compensation-stack step identity — DELIVERED (ADR-064 amendment A5).** The stack now carries `compensationStepNames` beside its positions, and resume refuses when the two disagree with the plan it is binding to. Taken inside v0.11 as planned: the record's component list had already changed this milestone, so the third component added nothing to the stability ledger, whereas deferring to v0.12 would have been a fresh change to a surface declared stable since 0.5.0. **A5 also corrected A4's failure-mode sentence.** A4 described a stale entry as producing an aborted rollback; that is what an *out-of-range* entry does — the half A4 closed. An in-range entry addressing a different step throws nothing at all: the unwind silently skips a compensation that was owed, or runs a different step's. So the half carried was the quieter and the less recoverable of the two, and the record did not say so — a compensation is a side effect that has already happened by the time anyone can look, whereas an aborted unwind leaves the parked row intact. Verified by mutation rather than by a green suite: disabling the guard reddens three TCK cases, disabling the pre-transform refusal reddens a fourth with the exact bare exception its assertion predicts, and disabling the *recording* side reddened nothing until a seventh case was added that runs a real saga through park and resume.
- **Miss-suppression on a rejected migration** — previously listed here as a defect; it is not. `loadSnapshot` consults the miss cache *before* reading the store, so suppressing a version refusal would make the documented remedy ("register the missing transform and wake again") silently do nothing. The mechanism to make suppression safe already half-exists: `compile()` clears the tracking via `clearLookupSuppressionAfterPlanCompile`, `registerMigration` does not. Until suppression is wanted, the residual cost is one snapshot-store read per wake of an unmigratable saga.

**FlowJournal follows this, it does not precede it** — and now has an entry of its own rather than living as this paragraph; see ["Flow: FlowJournal — durable saga history"](#flow-flowjournal--durable-saga-history-rfc-track) below. The ordering was wrong in planning for the same reason ADR-062 gave about positions: an entry recording *which step completed* is durable history only if it also records *which version produced it*, or it ages out at the next deploy exactly as a position does. Both prerequisites landed this milestone, so the ordering objection is discharged.

---

### Flow: FlowJournal — durable saga history (RFC track)

**Gap:** the kernel keeps no history of what a saga did. `FlowSnapshotStore` is a **last-value row store, not a log** — `save` upserts one row per instance and `complete()` calls `deleteSnapshot`, so a saga that finishes successfully leaves **no trace at all**. The nearest thing to a transition vocabulary is nine flow JFR event classes — seven driver-agnostic in `core/flow`, two Community/JDBC diagnostics emitted by `JdbcFlowSnapshotStore` — which are diagnostic and ephemeral: they answer "what is this process doing", never "what did this saga do last Tuesday". For an orchestration engine that is a product-level gap, not only an operational one — audit, replay and after-the-fact dispute resolution all need history the runtime does not keep.

**Owner:** Flow subsystem.

**Resolution:** Open an RFC in this repo's `docs/rfc/`. Its prerequisites are met: ADR-062 gives an entry a step identity that survives a deploy, and ADR-064 gives it the definition version that makes the identity meaningful. What is open is the shape — entry contents, SPI-vs-Community placement, durability substrate, retention, and write cost on the checkpoint path — which is an RFC's question, not a decision already made. Two of those the repo can already argue: the substrate has a working candidate in `EventStreamAppender` (append-only, per-stream monotonic head, OCC via `expectedVersion`, TCK-bound on **two** bindings), and placement is hard-constrained by `spi.flow` being `stable` under the ADR-065 gate. One it cannot: **write cost**, and that is the decisive one.

**Why write cost decides it.** `persistSnapshot` has exactly four call sites — `PARKED` (×2), `COMPENSATING`, `FAILED_ROLLEDBACK` — all state-transition-scoped, with **no write on step completion**. So a ten-step saga that never parks writes **zero** durable rows today. A per-step journal makes that ten. That is a change of write-frequency *class*, not an increment, on a runtime whose first rule is No Waste Compute — and there is nothing in the repo to measure it against: `docs/performance-contract.md` carries no flow or saga SLO, and no benchmark touches a durable write at all.

**Status (v0.11): RFC ACCEPTED — shape selected, implementation gated on a measurement.** [`RFC-2026-08-07: What should a durable saga journal record, where should it live, and what may it cost?`](rfc/RFC-2026-08-07-flow-journal.md) selects a **per-step journal on `EventStreamAppender`, opt-in per definition**. Substrate is settled by elimination — `FlowSnapshotStore` is last-value and deletes on success, so it cannot host history. Dissent recorded: the state-boundary-only variant is the cheap answer and is where this should land if the measurement rules the per-step one out.

**Merge Gate (as satisfied):** RFC accepted with one shape selected and dissent recorded; **no kernel SPI commits in this gate** (decision-only track). Implementation gate deferred and conditional on a checkpoint-write measurement that does not exist yet — building that benchmark closes a standing gap in the flow subsystem's performance contract regardless of whether the journal is ever built. When it lands: additive placement only (a new package or reuse of the events log — **not** a component on `FlowSnapshot`, whose component list already moved three times this milestone, and **not** an abstract method on `FlowSnapshotStore` without a `default`, since the compatibility gate is silent about both while neither is free), with the `docs/stability-matrix.md` row and `stability-surfaces.conf` entry in the same commit — an unclassified SPI class fails `--verify-surfaces`.

---

### Differentiator: Deterministic Simulation Testing (DST) Harness — the long-term moat

**Gap:** "Deterministic runtime" today means *local* predictable mechanisms (deterministic deny, net-counter admission without wall-clock, LIFO unwind) — it does **not** mean "run the whole runtime under a controlled scheduler, inject a fault, replay bit-for-bit." That second meaning is what TigerBeetle (VOPR) and FoundationDB (Flow simulation) built their reputations on, and **no JVM runtime has it** — Lincheck/JPF model-check *structures*, not a whole runtime with native IO. Retrofitting determinism onto a mature runtime normally breaks on the absence of a clean scheduler injection point without rewriting the world. **The kernel now has both hard seams already in place and validated:**
- **IO swap — via The Wall:** all IO sits behind SPI, so real transport/persistence/events swap for an in-memory simulation via one binding. Under simulated (deterministic) IO, unmount/park points become deterministic for free.
- **Execution swap — via the PAQS/stream execution seam:** the `research/loom-continuation-locality` branch (v0.6) produced, as its byproduct, an extracted execution backend (M1) **proven refactor-neutral on PAQS/stream** (M2). The M1 seam (`StreamExecutionBackend` + default VT-per-stream backend) is **ported onto the default line in v0.11** (see v0.11 §"Transport: PAQS Execution-Seam Port (M1)") — the injection point now lives in-tree, not only on a prunable research branch. The branch closed **NO_GO on the locality hypothesis**, but that verdict is **config-scoped, not a refutation**: it ran Community-only, on the stock VT scheduler (the affine backend's carrier pinning rode a *reflective* VT custom-scheduler override, absent unless running a Loom custom-scheduler JDK — so the "locality-aware" arm could not actually pin carriers), and without `pullerMode=3`. The Enterprise io_uring track (M4/M5: native reaper + affine + multishot + drain) **never ran**, and io_uring is the regime where continuation-locality effects should be strongest (the split between kernel I/O completion reaping and user-space continuation execution is most pronounced there) — that (io_uring + Loom custom scheduler + `pullerMode=3`) is the materially-different hypothesis the research's own conclusion demanded, now the live locality re-test on the ported seam. The Community null result is separately **orthogonal to DST**: locality asked "does affine scheduling raise RPS?"; DST asks "does deterministic single-stepping give reproducibility?" — not an RPS question, so it is untouched either way. DST's value is *reproducibility, not throughput*.

So the seams — the architecturally risky part — are done. What remains is **work, not architectural risk**: deterministic implementations behind the two seams + an injectable clock (see "Unified Clock" — being unified anyway) + a seeded RNG + a sim-driver steering all of it with fault injection and replay.

**The one load-bearing open question (first RFC decision):** the execution seam gives an injection point at resume continuation. DST needs full **ordering control** — which VT resumes before which, when a timer fires, when a completion is delivered. Part is free under simulated IO; the rest depends on whether the PAQS-seam controls **next-runnable selection (total order)** or only **placement**. **Total-order vs. placement is the question the RFC must resolve first** — it sets how deep into the semi-internal Loom carrier surface the harness must reach.

**Owner:** Runtime / Testing infrastructure (cross-subsystem).

**Resolution:** RFC-track first (`docs/rfc/RFC-2026-06-22-deterministic-simulation-testing.md`) — resolve total-order-vs-placement and pin the Loom custom-scheduler approach before committing. Then: (1) a `SimulationScheduler` behind the existing execution seam driving virtual-thread execution deterministically; (2) the unified injectable `Clock` as time source; (3) a seeded RNG threaded through any nondeterministic choice; (4) in-memory simulation bindings for `TransportProvider` / `PersistenceEngine` / `EventEngine` (The Wall makes these swap-in); (5) a sim-driver with fault injection (drop/delay/partition/crash) and seed-replay. Lean on the already-deterministic primitives (admission net-counter, LIFO unwind) as invariants the sim asserts.

**Merge Gate:** a seeded run reproduces an identical event/transition trace across two executions of the same seed; a fault-injection scenario (e.g. persistence partition mid-saga) is replayable from its seed; the in-memory SPI bindings pass the same Abstract*Tck suites as the real bindings.

**1.0 disposition:** **POST-1.0** — the largest long-term moat, but not a GA gate. Cost/benefit has tilted sharply *toward* it now that the seam removed the most expensive leg. Land the **prerequisites** (unified Clock, seeded-RNG discipline, in-memory SPI bindings) opportunistically so the harness is a small later step, not a rewrite.

See also: v0.11 §"Transport: PAQS Execution-Seam Port (M1)" (seam landed on the default line); research `research/loom-continuation-locality` (locality NO_GO is **config-scoped** — Enterprise io_uring + custom-scheduler re-test still open); "Road to 1.0" §"Cross-Cutting: Unified Injectable Clock Seam".

---

### Cross-Cutting: Unified Injectable Clock Seam (consistency fix + DST backbone)

**Gap:** Time is read **ad hoc** — audit found 95 `System.nanoTime`, 21 `Instant.now`, including **in the SPI itself** (`KernelEvent`, `ExerisKernelException`, `FlowExecutionPlan` deadline). No `Clock`/`TimeSource` abstraction; the only two injectable seams are local one-offs (`FairnessTracker`'s `LongSupplier`, `CommunityRotatingKeySet`'s `java.time.Clock`) using *different* types. Saga TTL, retry backoff, JFR timestamps are all non-injectable. Both a consistency gap (two idioms, no policy) and the missing backbone for DST — you cannot virtualize time you read ad hoc.

**Owner:** Core (cross-subsystem) + SPI.

**Resolution:** one kernel time-source seam (`NanoClock`/`TimeSource` — `long nanos()` + an `Instant` view), constructed at bootstrap, threaded via `ScopedValue` (not `ThreadLocal`), default-bound to the platform clock. Migrate *behavior-affecting* wall-clock paths (saga TTL, retry/backoff, telemetry timestamps); keep admission net-counter clock-free; leave hot-path `nanoTime` micro-measurements out of scope where a seam adds overhead.

**Merge Gate:** behavior-affecting time reads go through the seam; a test binds a virtual clock and drives saga TTL expiry deterministically; no `ThreadLocal` introduced (ArchTest); SPI deadline contract documents the seam.

**1.0 disposition:** **1.0-RECOMMENDED** — cheap, fixes a real consistency gap, de-risks the DST moat. High value-per-effort, not a hard GA gate.

---

### Cross-Cutting: Systemic Flow-Control Contract (subsystems are islands)

**Gap:** Each subsystem has its **own** backpressure — Transport `ResourceArbiter`/`WatermarkManager` + `AdmissionController` net-counter, Persistence admission (`FairnessTracker`, ADR-035), Events `EventQueue` — all confirmed independent. There is **no systemic flow-control contract**: when persistence sheds load, HTTP admission never hears about it, so backpressure does not propagate to where upstream load enters. The only cross-subsystem edge is a transport-local memory→admission wire built inside `NativeTcpCarrier`, never shared. Tellingly, `ResourceArbiter.Context` already enumerates `TRANSPORT_IO` **and** `KERNEL_LOGIC`, but `KERNEL_LOGIC` is wired to nothing — a **latent seam for exactly this**. Under "bounded resource by design + deterministic," end-to-end load propagation + a per-subsystem/per-tenant memory budget is both a consistency fix and a differentiator. (Per-tenant memory budget is also absent — only a per-provider `totalOffHeapBytes` watermark exists.)

**Owner:** Memory / Runtime (cross-subsystem).

**Resolution:** RFC-track. Define a minimal shared load-signal contract (a kernel-level pressure observer the existing per-subsystem shedders both *publish* to and *consult*), reusing the latent `ResourceArbiter.Context.KERNEL_LOGIC` rather than a new bus where possible. Scope: persistence-saturation → HTTP-admission pushback; an optional per-tenant memory/throughput budget layered on existing watermarks; fail-closed defaults (misread signal sheds, never over-admits). Explicitly *not* a new subsystem — a thin contract over the three existing shedders.

**Merge Gate:** RFC accepted with one shape; if implemented, an integration test shows persistence shed → HTTP admission pushback under sustained load; `AbstractAdmissionTck` extended with a cross-subsystem propagation case; constrained-benchmark guard unregressed.

**1.0 disposition:** **POST-1.0** (RFC may open pre-1.0) — the per-subsystem mechanisms already hold; systemic propagation is a differentiator, not a GA blocker.

---

### Table-Stakes: Supply-Chain Integrity (SBOM, signed releases, provenance)

**Gap:** Zero supply-chain integrity in the build — audit found no CycloneDX SBOM, no cosign/Sigstore signing, no SLSA provenance, no reproducible-build config, no GPG. The only release step is an unsigned, unattested `mvn deploy` (the `Publish to GitHub Packages` step in `.github/workflows/maven.yml`). PR-time dependency-review + CodeQL exist, but those are vulnerability scanning, not artifact integrity; Sonar/PMD/JaCoCo are code quality. This is the gap most aligned with the EU/FENG digital-sovereignty narrative — an *argument*, not just hygiene — and it is low-cost.

**Owner:** Build / Release (seam in open-core; richer attestation can be enterprise).

**Resolution:** add to the release pipeline: CycloneDX SBOM per artifact, artifact signing (cosign/Sigstore keyless or GPG for Maven Central), SLSA provenance attestation, a reproducible-build baseline (pinned plugin versions, stripped timestamps). Publish the SBOM alongside the artifact.

**Merge Gate:** every published 1.0 artifact carries a CycloneDX SBOM + verifiable signature + provenance attestation; a CI job verifies the signature on a fresh pull.

**1.0 disposition:** **1.0-BLOCKING** — a credible "1.0 GA" on Maven Central cannot ship unsigned and SBOM-less; also the cheapest strategic win in this list.

**Status (v0.12): PARTIAL — SBOM and reproducible builds delivered on `development/0.12.0`; signing and provenance are not.** Two of the four items the Resolution names are done and gated on every pull request by the `Supply-Chain Gate`: a CycloneDX SBOM per artifact, attached under classifier `cyclonedx` so `mvn deploy` publishes it beside the jar, and a reproducible-build baseline (`project.build.outputTimestamp` plus version pins on the default-lifecycle plugins). The Gap paragraph's "no reproducible-build config" was understated on one point worth keeping: `maven-jar-plugin` carried no version anywhere in the reactor and resolved through Maven's super-POM to 3.1.2, which predates reproducible archive support — so the pins are not hygiene accompanying the timestamp property, they are what makes it do anything. Measured rather than assumed: on the preceding commit two consecutive builds differed in all 9 jars; they are now byte-identical, as are the 11 SBOMs.

**Status (v0.12, second slice): the signing and provenance pipeline is built and gated; nothing has been published through it.** GPG signing covers every file a release carries — jar, sources, javadoc, pom and SBOM — and SLSA provenance is attested through Sigstore keyless signing on the release workflow's OIDC identity, which is the question a GPG signature does not answer (which workflow, at which commit, on which runner). The Merge Gate's "a CI job verifies the signature" is met by `tools/release-readiness`, which verifies each signature against the key *before* upload rather than after; verifying "on a fresh pull" is not yet possible because nothing signed has been published to pull.

**What remains is operational, not engineering:** four repository secrets (`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`) and one manual run. The `eu.exeris` namespace is verified in the Central Portal. The first upload is deliberately `workflow_dispatch`-only with `autoPublish=false`, so it lands as a deployment a human reviews and can drop; a tag trigger is the natural shape only after one release has gone through by hand.

**One coordinate is held back and it is a defect, not a policy:** `exeris-kernel-tck` has only `src/test` and no `src/main`, so its published main jar is 7 files of META-INF while all 492 real classes ship under the `tests` classifier — and neither a sources jar nor a javadoc jar can be produced from empty compile roots, both of which Central requires per artifact. The fix is that the `Abstract*Tck` classes are that module's published API and belong in `src/main`, which changes a coordinate every provider module and the enterprise distribution depend on. That is its own slice. Until then the module is excluded from Central and resolves from GitHub Packages as today.

The sequencing constraint that put this stream early is now discharged: signing precedes the first artifact intended to carry a signature.

---

### Table-Stakes: SPI Binary-Compatibility Gate (revapi / japicmp in CI)

**Gap:** API stability is asserted only in the manual `docs/stability-matrix.md`; there is **no automated API-diff** (no revapi, japicmp, animal-sniffer, bnd-baseline). With out-of-repo Enterprise bindings depending on the SPI, an accidental binary-incompatible change ships undetected. Low-cost, high-value once 1.0 declares SPI stability.

**Owner:** Build / SPI.

**Resolution:** add japicmp or revapi to CI, baselined at the 1.0 SPI surface, failing the build on a binary-incompatible change to `exeris-kernel-spi` (and other published-API modules). Honour the additive-only / pre-1.0 stance until 1.0, then tighten to strict after GA.

**Merge Gate:** CI fails on an unannotated binary-incompatible SPI change; the gate's baseline is the published 1.0 SPI; the stability matrix is cross-checked against tool output.

**1.0 disposition:** **1.0-BLOCKING** — declaring SPI stability without automated enforcement is a promise you can't keep.

---

### Table-Stakes: Kernel-Owned Table Schema Evolution

**Gap:** Auto-DDL creates `exeris_saga_state` / `exeris_outbox` / `exeris_outbox_dlq` via hand-rolled `CREATE TABLE IF NOT EXISTS` (opt-in, default off), with a hardcoded 2-file list and **no versioned evolution path** — no schema-version ledger, no ALTER ordering, no Flyway/Liquibase. If a kernel release changes the `saga_state` schema while old-schema in-flight sagas exist, `IF NOT EXISTS` is a silent no-op: the change never applies, and version-blind resume (see Flow versioning above) reads old-shape rows through new code. **Correctness, not convenience** — a schema change breaks in-flight sagas on upgrade.

**Owner:** Persistence subsystem.

**Resolution:** a minimal versioned migration runner for kernel-owned tables — a `schema_version` ledger tracking applied migrations, ordered apply-once `V*.sql` application (not re-run-all), and a documented upgrade contract for in-flight rows. Scoped to kernel-owned tables; operators still bring Flyway/Liquibase for their own schema (existing "initContainer" guidance stays). Pair with the Flow definition-versioning epic so in-flight sagas survive both code and schema change.

**Merge Gate:** an upgrade test creates rows under schema vN, applies the vN+1 migration, resumes the in-flight sagas correctly; double-application is a no-op via the ledger; `AbstractSagaRecoveryTck` gains a cross-version-upgrade case.

**1.0 disposition:** **1.0-BLOCKING** (correctness) — minimally a schema-version ledger + apply-once ordering; full in-flight row transforms can ride the Flow versioning epic.

---

### Table-Stakes: `SecretProvider` SPI

**Gap:** Secrets (JWKS signing keys, DB password, TLS key material) enter **purely as plain config** — `PersistenceConfig.password` is a plaintext `String` protected only by toString-redaction; JWKS is a config URI or a static in-memory map. No secret-resolution seam; "Vault" appears only in Javadoc prose. Production needs a Vault/ASM/env-backed seam.

**Owner:** Security / Config (seam in open-core; Vault/ASM drivers can be enterprise / community-add-on).

**Resolution:** define a `SecretProvider` SPI (`resolve(SecretRef) → secret`, env + static drivers in Community; Vault/AWS-SM as additional drivers). Route DB password, JWKS key source, TLS key material through it instead of raw config. Fail-closed on unresolved required secrets.

**Merge Gate:** `AbstractSecretProviderTck` covers resolve/missing/rotation; DB + JWKS + TLS read through the seam; no plaintext secret retained in a config record beyond the resolved-handle boundary; Wall preserved.

**1.0 disposition:** **1.0-RECOMMENDED** (B2B production blocker) — stage-able if 1.0 docs explicitly state "secrets via config + external injection" as the supported 1.0 posture, with the SPI in v0.11.

---

### Table-Stakes: Per-Tenant Rate Limiting / Quota

**Gap:** `isolationKey` isolates tenant *data*, not tenant *throughput*. Audit found only global admission/throttle + connection-level Rapid-Reset defense — **no per-tenant request quota or rate limit** (no token bucket, no per-tenant counter). A B2B SaaS substrate (BudgetHQ) needs per-tenant throughput limiting so one tenant cannot starve others.

**Owner:** Security / Transport.

**Resolution:** a per-tenant rate/quota contract keyed on `PrincipalContext` / `isolationKey` (token-bucket or net-counter per tenant), integrated with admission, emitting a structured `EX-*` on limit. Keep the limiter pluggable (in-process default; distributed counter later, riding the cache/coordination seams).

**Merge Gate:** `AbstractTenantQuotaTck` covers per-tenant limit enforcement + fairness across tenants under contention; integration test shows one tenant's burst does not shed another's traffic.

**1.0 disposition:** **POST-1.0** (v0.11) — important for the B2B story, not a kernel-correctness GA gate; document the absence explicitly in the 1.0 support matrix.

---

### Scope Discipline & Declared Stances

**1.0 = narrow, deep, defensible core.** v0.11/v0.12 stack a row of new SPIs — `BlobStorageProvider`, `JobScheduler`, `CacheProvider`, `WebSocketProvider`, `ServiceResolver`, cross-node coordination — each a real subsystem. For a solo founder, bundling these into 1.0 is scope-explosion risk. **Decision: explicitly mark all of these as post-1.0.** *Clarified 2026-08-07 (see [`RFC-2026-06-29`](rfc/RFC-2026-06-29-webclient-service-addressing.md)):* this holds out each named item **as a new SPI/subsystem**. It does not hold out fixing the shape of a subsystem 1.0 already commits to — so `ServiceResolver` stays post-1.0 while multi-peer addressing on the existing `spi.http` client surface is 1.0 scope. Reading the two as one item would let the ruling quietly narrow `http` rather than narrow the SPI count, which is not what it decides. 1.0 is the narrow, deep, *unfalsifiable* core — `transport / http / security / persistence / flow / events` (+ `memory` foundation) — plus the cross-cutting 1.0-blocking items above (preview-clean baseline, supply-chain integrity, SPI binary-compat gate, schema evolution, flow-resume correctness). Better 1.0 unbreakable on six things than shallow on fifteen. RFC-gating already applies to most of these — this makes the post-1.0 tag explicit rather than implied.

**Graph in 1.0 — DECIDED: stays in 1.0.** The earlier keep-or-split question is closed. Graph is substantially complete well before 1.0 and its claims are TCK-backed like the rest of the core: GRAPH-111 (v0.8 Sprint 7) delivered `ExecutionGraphZeroAllocTck` + `GraphChurnRatioTck` with Community bindings (`CommunityExecutionGraphZeroAllocTckTest`, `CommunityGraphChurnRatioTckIT`). No dilution-of-focus concern — Graph is a finished in-scope subsystem, not an open scope risk.

**STS two-branch tax — CI discipline.** The GA-clean-substitution-on-`main` + `preview`-branch plan (Platform Baseline below) is sound, but every change to the four STS sites is now done twice, and the `preview` branch rots without CI. **Mandate:** keep the `main`↔`preview` delta mechanically minimal (substitution confined to the concurrency-policy method boundary), and run the `preview` branch in CI **every commit** so "the intended future `main`" never silently stops compiling.

**Native-image / GraalVM — declare a performance contract, not just a yes/no.** The mechanism is concrete: Panama performs better on HotSpot because FFM downcall stubs and `MemorySegment`/`VarHandle` access are intrinsified and C2-runtime-optimized; the FFM path under native-image is younger and, without PGO, more conservative. The same logic extends to the **zero-alloc / No-Waste-Compute contract**: scalarization via Escape Analysis is peak-tier C2, profile-driven — under AOT without profiles the decisions are more conservative. PGO closes part of the gap but is operationally heavy (instrumented build → profile → optimized build). This is not "native-image is worse"; it is **"native-image is a different performance contract."** Edge/lightweight (startup without warmup, small footprint, small image) is where native-image *wins* and is part of the thesis; the throughput tier stays HotSpot/C2. The decision is *which contract*, not *whether it builds* — a build was already confirmed to compile.

**Actions:** (1) **Pin the zero-alloc / No-Waste-Compute contract explicitly to HotSpot-C2** in `docs/performance-contract.md` — otherwise someone benchmarks the claim under native-image, sees it not hold, and concludes it is *false*; scoping the contract defends the claim. (2) Declare the 1.0 stance in the support matrix: **edge/lightweight = native-image target (enablement is a post-1.0 gated track); throughput tier = HotSpot/C2** — explicitly stated, not silently absent. (3) Track native-image *enablement* as a separate gated track (post-1.0): reachability metadata for FFM downcalls, reflection config for reflective loaders (`GeneratedRoleRegistryLoader` resolves FQNs via reflection), `ServiceLoader` registration, and JFR feature-parity (JFR-first telemetry + `RecordingStream` in TCK — *not* zero-risk; verify custom events and streaming under SubstrateVM).

**1.0 disposition:** declare the **contract** in 1.0 (cheap, defends the claim — the `performance-contract.md` pin is near-term); native-image **enablement** is a **post-1.0** gated track.

### Transport: Connection Cap Refuses Silently (surfaced by load-test triage, 2026-07-31)

**Gap:** `NativeTcpCarrier`'s accept loop reserves a slot against `TransportConfig.maxConnections()` (default `HttpConfig.DEFAULT_MAX_CONNECTIONS` = 1000, `http.maxConnections`). When the cap is reached it calls `closeQuietly(currentChannel)` and continues: the connection is **accepted at TCP level and then closed with no log line and no JFR event**. From the client the socket opens and dies; from the server nothing happened.

Sharper than "no counter", which is how this was first written: `TransportStats` already carries `totalRejected` — *"cumulative number of connections/streams rejected (load shedding)"* — and the carrier fed it from the PAQS load-shedder alone. The one field an operator consults when asking whether the server is turning work away therefore read **zero while every connection was being refused**. That is worse than absent telemetry, because a zero is read as evidence the fault lies elsewhere.

This is the only refusal path in the kernel with no telemetry at all, which makes it the hardest failure to diagnose and the easiest to misattribute. A load test that trips it sees connection-level errors with a clean server log and will reasonably conclude the fault is elsewhere — a shape that has already cost triage time (see below). It also breaks the Glass-Box premise: every other shed or deny in the kernel emits an event, and admission decisions in persistence emit `AdmissionDecisionEvent` specifically so operators can attribute them.

Two properties make this cap easy to hit unexpectedly: it counts **concurrent connections, not rate**, so a transient overlap of two client phases can cross it at a request rate that is otherwise comfortable; and 1000 is low enough that a keep-alive client pool plus a draining previous pool can exceed it without either alone coming close.

**Owner:** Transport subsystem.

**Resolution:** Emit a JFR event on refusal (`schedulerName`-style shape: bind address, active count, configured cap) and expose the refusal count in transport stats; the event must be single-phase and must not carry peer identity beyond what the transport already records. Independently, review whether a default of 1000 is right for the reference deployment, and whether an accept-time cap is the correct mechanism at all versus admitting and shedding at request level where the response can carry a status. **Do not change the cap's behaviour and its observability in the same commit** — the silence is the defect being fixed; the policy is a separate decision.

**Merge Gate:** Refusal event registered in `docs/subsystems/telemetry.md` §Required Events and asserted by a driver-local JFR test that drives the cap to its limit; transport stats expose a monotonic refusal counter; `docs/subsystems/transport.md` documents the cap, its default, and what a client observes when it trips.

**1.0 disposition:** 1.0-recommended. Transport *is* in the 1.0 core, and an undiagnosable refusal path is the kind of thing that turns a support conversation into an accusation.

**Status (v0.11): observability half DELIVERED; policy half open.** The previous text read "implemented in a separate change and **not yet on the development branch**" and was **true when written and stale by the next morning**: #265 merged 2026-07-31. Corrected 2026-08-08 during the pre-cut audit, and worth keeping as the cautionary example — the sentence was carefully hedged against an unverifiable claim and still ended up asserting something false, because a status pinned to a moment needs re-reading at the cut, not just honest phrasing at the time.

What is on `development/*`: `CommunityConnectionRefusedEvent` and `CommunityAcceptFaultEvent`, both emitted from `NativeTcpCarrier`, and `TransportStats.totalRejected` now summing the accept-time refusals (`refusedConnections`) together with PAQS load-sheds instead of counting only the latter. One deliberate asymmetry is recorded in the event's own javadoc: an accept **fault** is excluded from `totalRejected`, because that field means work the engine *declined*, and a setup failure is not a decision. **Not covered by any test** — neither event has a `RecordingStream` assertion in the Community suite or the TCK, so the emission is verified by reading the call sites only; that gap is real and is the first thing to close if either event is relied on.

The policy half is open and deliberately unbundled: whether an accept-time cap is the right mechanism against request-level shedding that can answer with a status, and whether 1000 is the right default for the reference deployment.

---

### Graph: Heterogeneous Multi-Hop Traversal (surfaced by dogfooding, 2026-07-31)

**Gap:** `GraphTraversal` (`exeris-kernel-spi/.../graph/model/GraphTraversal.java:28`) carries exactly **one** `GraphEdgeDescriptor`, and every `GraphSession` entry point that consumes a traversal takes that single-edge shape: `traverseBreadthFirst(GraphTraversal)`, `streamBfsJson(GraphTraversal)`, and `findShortestPath(GraphEdgeDescriptor, source, target)`. There is no method accepting a heterogeneous path. A two-hop query over different relationship types — `User -[PURCHASED]-> Product -[SIMILAR_TO]-> Product` — is therefore not expressible as one request; a caller must issue hop one, materialise the intermediate node set, and issue hop two per node.

That is not merely inconvenient. It moves the join into application code, which (a) costs one round trip per hop and an N+1 fan-out on the second, (b) puts the intermediate result set on the heap, against the No-Waste-Compute contract the graph subsystem otherwise honours through `streamBfsJson`'s `LoanedBuffer`, and (c) leaves the second hop's tenant scoping to the caller rather than to the engine. Recommendation traversal is the canonical graph use case, so this is closer to a missing primitive than to a missing convenience.

**Owner:** Graph subsystem.

**Resolution:** RFC before ADR — the option space is open and the choice is a contract shape, not a defect fix. Options to compare: (a) a `GraphPathSpec` carrying an ordered `List<GraphEdgeDescriptor>` with a per-hop depth, consumed by a new `traversePath(...)`; (b) generalising `GraphTraversal.edgeDescriptor` to a set with a per-hop predicate; (c) declining the primitive and documenting the client-side composition as the supported pattern, on the grounds that arbitrary path expressions are a query-language problem the kernel deliberately does not own. Any option that lands must state how depth interacts with hop count (a five-hop path with `maxDepth=3` needs a defined meaning), and must keep `StorageContext` scoping engine-side on every hop.

**Merge Gate:** RFC accepted with one shape and dissent recorded. If a primitive lands: `AbstractGraphSessionTck` covers a heterogeneous two-hop path, a hop that matches nothing (empty result, not error), depth interaction, and a cross-tenant probe proving the second hop cannot escape the caller's isolation key; both bindings green; the zero-copy streaming variant covered, not only the `List<UUID>` one.

**1.0 disposition:** post-1.0 — but the reason is narrower than "graph is not 1.0", which would contradict the **"Graph in 1.0 — DECIDED: stays in 1.0"** ruling in this same section. Graph the subsystem *is* in 1.0: it is substantially complete and TCK-backed. What is post-1.0 is *this contract widening*. The 1.0 decision was taken about the graph surface as it stands, on the strength of GRAPH-111's zero-alloc and churn-ratio TCKs; a new traversal primitive is new surface that ruling never assessed, and adding it inside the 1.0 window would re-open a scope question that was deliberately closed.

---

### Flow: No Way to Await a Flow (surfaced by dogfooding, 2026-07-31)

**Gap:** `FlowScheduler` exposes `schedule(FlowExecutionPlan, FlowContext)` returning `void`, plus `park`, `wake`, and `lookupParked`. Neither it nor `FlowEngine` offers any completion surface — no handle, no join, no completion callback, no terminal-state future. A caller that starts a flow has no supported way to learn that it finished, short of polling a snapshot store or subscribing to an event the flow itself must be written to emit.

This is a genuine product-SPI gap rather than a stylistic one. Request/response over a flow — "run this saga, answer the HTTP call when it settles" — is a shape downstream applications reach for immediately, and today it has no kernel answer at all. Every consumer invents its own correlation and waiting mechanism, which is the duplicated-unaudited-code failure the kernel exists to remove.

**Owner:** Flow subsystem.

**Resolution:** RFC. The design question is not "add a future" — it is what completion *means* for a durable, parkable, cross-restart flow, and that is exactly where a naive API would mislead. Points the RFC must settle: whether awaiting is even coherent for a flow that may park across a restart (the awaiting caller does not survive it); whether the surface is a terminal-state observer rather than a join; the relationship to the existing terminal-state catalog and to `FlowSnapshotStore`; the timeout contract and what a caller observes when a flow outlives its awaiter; and whether this composes with, or duplicates, the choreography wake path. **Must not acquire a `CompletableFuture`** — banned on orchestration paths — nor a new `StructuredTaskScope` site, per the Platform Baseline below.

**Merge Gate:** RFC accepted with one shape and dissent recorded. If a surface lands: `AbstractFlowSchedulerTck` covers completion after a normal terminal state, after a compensating/failed terminal state, a timeout, an awaiter racing a park, and an awaiter that gives up before the flow settles (no leak, no orphaned registration); Community binding green.

**1.0 disposition:** 1.0-recommended. Flow *is* in the 1.0 core, and "replaces the orchestration layer" is one of the two load-bearing product claims — a flow nobody can wait on weakens it. Sequenced behind the v0.11 flow-versioning and continuity work rather than ahead of it.

---

### Cross-Cutting: Operational Limits With No Configuration Path (surfaced 2026-07-31)

**Gap:** Several protective limits are compile-time constants with no key, no override, and no way to disable them. They are reasonable *defaults* and poor *only settings* — a deployment cannot raise one for scale, lower one for a constrained node, or switch one off to measure something else.

**PAQS has no configuration surface at all.** `AdmissionController` is constructed as `new AdmissionController(arbiter)` — no config object — and neither the transport scheduler package nor the community events package reads the config provider once. There is no `paqs.*` or `events.*` key anywhere.

| Constant | Value | Location | Note |
|---|---|---|---|
| `MAX_ACTIVE_STREAMS` | 5 000 | `AdmissionController` | Its own Javadoc: sheds "regardless of memory pressure" |
| `SPIN_THRESHOLD` | 10 000 | `PaqsScheduler` | |
| `MAX_HEADER_BLOCK_SIZE` | 65 536 | `Http2HeaderBlockAssembler` | HTTP/1 equivalent **is** configurable |
| `MAX_STRING_LITERAL` | 65 536 | `HpackDecoder` | HTTP/1 equivalent **is** configurable |
| `TRANSLATION_CACHE_MAX_ENTRIES` | 1 024 | `JdbcPersistenceConnection` | |
| `DEFAULT_NETWORK_OFF_HEAP_THRESHOLD` | 32 KiB | `CommunityMemoryAllocator` | |
| `FLOW_PROGRESS_ORDINAL_PROBE_LIMIT` | 32 | `FlowProgressPublisher` | |
| `MAX_RECLAIM_CADENCE_MS` | 5 000 | `CommunityTenantPoolRegistry` | |

The HTTP/1 ÷ HTTP/2 rows are the sharpest: `http.maxRequestHeaderSize` and `http.maxRequestHeaderCount` are honoured on HTTP/1 and silently stop applying once a client negotiates h2. Nothing on the config surface says so.

**A second class is worse than hardcoding — knobs that are not configuration.** Four settings are read straight from `System.getProperty`, so they sit outside the config provider: not hot-reloadable, invisible to `KernelConfigRegistry`, and undocumented beside the `http.*` / `transport.*` keys — `exeris.transport.maxTlsRecordsPerRead` (32), `exeris.transport.queueBackpressureEnabled` (false), the memory JFR sampling interval, and the socket-backend selector. These *look* configurable to whoever wrote them and are undiscoverable to whoever operates them.

**Owner:** Transport / HTTP / Persistence / Memory, coordinated — the shape of the answer should be one convention, not four.

**Resolution:** Promote the operational limits above onto the config provider under their subsystem namespaces, and decide **once** what "disable" means for a protective limit — an explicit unbounded sentinel, or a documented refusal to offer one. That decision is the substantive part: an admission controller with no ceiling is a legitimate configuration for a JVM-controlled deployment and a foot-gun for a shared one, and the contract should say which it supports rather than leaving it to whether a constant happens to be reachable. The four system properties either become real keys or are documented as deliberate escape hatches; the present state is neither. Protocol invariants (HPACK table shapes, status-code ranges, UTF-8 boundaries) stay hardcoded and are explicitly out of scope.

**Merge Gate:** Each promoted limit has a key, a documented default, and a stated disable semantics; TCK coverage where the limit changes observable behaviour under load (admission and the HTTP/2 header limits at minimum); `docs/subsystems/*.md` config tables updated; no remaining `System.getProperty` reads for operational policy in `src/main`.

**1.0 disposition:** **1.0-blocking.** A runtime that cannot be tuned for the deployment it runs in is not operable, and the HTTP/2 asymmetry means an operator can believe a limit is set while it is not. Both are the kind of thing that has to be right *before* external consumers exist, because changing a limit's default or its disable semantics afterwards is the change nobody can absorb quietly.

---

## Platform Baseline for 1.0 GA — JDK 25 LTS + Preview-Clean Critical Path (decouple `StructuredTaskScope`)

### Runtime / Platform: a 1.0 GA artifact must not require `--enable-preview` of its consumers

**Gap:** The build today targets JDK 26 with `--enable-preview` enabled globally (`pom.xml` `maven.compiler.release=26` + `--enable-preview`). The **only** preview API on which the runtime depends is `StructuredTaskScope` (still preview in JDK 26, continuing the multi-release JEP 453→462→480→499→505 preview chain); every other Loom/Panama primitive the kernel stands on is already **GA**: virtual threads (JEP 444, GA in 21), `ScopedValue` (JEP 506, GA in **25 LTS**), and the Foreign Function & Memory API (JEP 454, GA in 22). `StructuredTaskScope` is on a long preview track (seventh preview in JDK 27, with the exception model still changing — `Joiner` join-exception type parameter, `FailedException` → `ExecutionException`) and its own design lead calls a JDK 29 exit from preview "optimistic". JEP 401 (value classes) is *also* only a preview in JDK 28. So neither of the two headline JDK 28 features is GA in 28.

This matters because `--enable-preview` is **not** a per-library opt-in — it is a whole-compilation + whole-JVM flag, and preview bytecode is stamped (`minor_version = 0xFFFF`) and pinned to one exact major: a class built with preview on 26 will not load on 25/27/28 *even with the flag*. For an open-core artifact published to Maven Central this **inverts the dependency contract** — every downstream application would have to build and run its *entire* codebase with `--enable-preview`, be pinned to our exact JDK, and accept that all of *its* code falls under the "may change/disappear next release" preview contract. Many enterprises ban preview in production outright, which would exclude exactly the LTS segment that `exeris-kernel-enterprise` targets. **A "1.0 GA" that depends on a preview API on its critical path is internally contradictory.**

**Verified scope (narrower than "the whole runtime", larger than "one bootstrap seam"):**
- The **SPI is already preview-clean for `StructuredTaskScope`**: zero `import …StructuredTaskScope` across `exeris-kernel-spi/src/main`; every mention is descriptive javadoc (`{@code StructuredTaskScope}`, not `{@link}`), so a consumer compiling against the SPI is **not** forced into `--enable-preview` by STS. The Wall already holds here.
- The preview *taint* lives in **shipped Core/Community bytecode**. Load-bearing `StructuredTaskScope` type usage (actual imports, verified on HEAD) is in exactly **four** default-path sites — `core/bootstrap/SubsystemOrchestrator` (parallel subsystem start), `core/events/InMemoryEventBus`, `core/events/outbox/OutboxOrchestrator`, and `community/events/CommunityEventLoop`. These compile with `--enable-preview` and ship as major-pinned bytecode.
- **Anticipated, not yet present:** the planned `KafkaEventLoop` (ROADMAP §"Events: Kafka/Redpanda Driver — Core-Shared Implementation Model", step 4) *intends* to wrap the consumer poll cycle with `StructuredTaskScope`, but it is **not implemented** today (zero `StructuredTaskScope` in `exeris-kernel-community-kafka/src/main`). Build it on the GA structured-concurrency layer from the start so it never becomes a fifth taint site.

**Track policy, stated as a rule rather than left implicit (settled 2026-08-06).** The two lines select
their JDK by opposite criteria, and every guardrail below reads differently depending on which one it is
about:

| | Default line (`main`) | `preview` branch |
|---|---|---|
| JDK | **LTS only** — JDK 25 today, 29 next | **Latest, LTS or not** — JDK 28 today |
| Preview flags | none; `--enable-preview` is the thing being removed | `--enable-preview`, by definition |
| Concurrency | virtual threads + explicit `ScopedValue` rebind (both GA) | `StructuredTaskScope` |
| Carriers | records / immutable finals, Valhalla-*ready* by discipline | JEP 401 value classes, once exercised |
| Artifact | the distributable `1.0` | `1.0-preview`, for JVM-controlled deployments |

The preview branch is not a research fork: it is the intended future `main`, and it converges at an LTS.
If `StructuredTaskScope` exits preview in **JDK 29 LTS**, the preview line *becomes* the GA line at that
release — which is why "track the newest JDK" is a requirement on it and not an indulgence. It has to
have already absorbed the API churn by the time the LTS lands.

**JDK 28 confirmed (checked 2026-08-04), and it does not move the GA floor.** Targeted JEPs: **401 Value
Objects (Preview)**, **539 Strict Field Initialization in the JVM (Preview)**, 535 Shenandoah
generational-by-default. Both Valhalla-relevant JEPs are **preview**, so 28 changes nothing for `main` —
it is the preview branch's next JDK, exactly as step 3 anticipated. JEP 539 was not in the original
analysis and is worth naming: strict field initialization is the companion that makes value classes
sound, so the "Valhalla-ready carriers" guardrail moves from a style rule to something **executable on
the preview branch** once that branch is on 28. Testing it there is the point of the branch existing;
`main` keeps the discipline without the flag.

**Resolution (target: complete before 1.0 RC; phased across v0.10–v0.12):**
1. **Baseline at JDK 25 LTS**; compile the *default* artifacts **without** `--enable-preview`. (First confirm via audit that no *other* preview API is on the critical path or in shipped bytecode — STS is believed to be the only one; treat this as the first acceptance step.) A syntactic audit of **JEP 507** (primitive types in patterns / `instanceof` / `switch`, preview in 25/26 — the obvious second candidate given the kernel's primitive density) found **zero** usage in `*/src/main`: no primitive `instanceof`, no `case <primitive> var` type patterns, and all 37 `switch` selectors are over enum / `String` / `char` / `int` (i.e. pre-JEP-507 GA forms — no `long`/`float`/`double`/`boolean` selector). So STS remains the sole *known* preview dependency; the **definitive** confirmation is a clean no-`--enable-preview` compile of the default reactor (the gate below).
2. **GA-clean substitution at the orchestration seam the bootstrap already exposes — for the distributed `main` only** (The Wall): a thin GA implementation — `fork` / `join-as-unit` / `cancel-on-failure` over **virtual threads** (GA 21) with **`ScopedValue`** binding-propagation re-established manually in the fork helper (`ScopedValue.where(...).call(...)` around each child, since automatic inheritance is the part ergonomically fused to STS). Substitute it at the four STS sites on `main` at the existing concurrency-policy method boundary (e.g. the private `startParallel` in `SubsystemOrchestrator`) — not a new injectable strategy, and `main` holds only the substitution, never two implementations side by side. This is a **temporary GA-clean cut, not a permanent replacement**: `StructuredTaskScope` stays the *intended* implementation (carried on the preview branch, step 3) and returns to `main` once STS is GA. Determinism + per-phase custom JFR startup events make the substitution a usable property in its own right, but it is not positioned as the long-term default.
3. **The `StructuredTaskScope` version (and, later, the JEP 401 value-class version of the carriers) lives on a thin, temporary `preview` branch — the *intended* future `main`, not an adapter artifact.** Direction matters: `preview` is the richer, target kernel; `main` is its preview-clean cut (step 2), not the other way round. The preview branch builds with `--enable-preview` on the **newest** JDK, LTS or not, and **tracks the moving STS API across releases** (26 → 27 → 28 …) — a separate build line on its own toolchain absorbs that naturally, where a same-reactor module would not (`--enable-preview` pins bytecode to one major). Tracking the newest is a requirement, not a preference: this branch converges into `main` at an LTS, so it must have absorbed the churn before that LTS lands, not after. It is published as a separate `1.0-preview` artifact for JVM-controlled deployments (BudgetHQ and a closed-source consumer) and EA testing. When STS — and value classes — reach GA, **`preview` replaces `main`** and the branch dissolves. Because the preview-bearing code is never on `main`, the default tree cannot be accidentally contaminated (there is nothing to pull in).
4. **Certify on JDK 29 LTS from launch.** 28 is an STS (~6-month) release; the enterprise segment runs LTS. Since the GA primitives in 25 are also present in 29 (29 ⊇ 28 ⊇ 25 for GA features), certifying 29 is near-free — do **not** pin 1.0 solely to JDK 28. "Ready for JEP 401 value classes on 28" stays a forward-portability / marketing-position story, not a floor pin.

**Note on the CLAUDE.md guardrail:** the repo strong-default "prefer `StructuredTaskScope` for orchestration concurrency" remains the *design ideal* and stays in force for JVM-controlled deployments and the `preview` branch (which is, after all, the intended future `main`). This baseline is the justified exception for the **distributable default artifact**, where the preview-distribution constraint (whole-app flag + bytecode major-pinning + enterprise preview bans) outweighs the ergonomic preference. The exception is scoped to the four sites on `main`; everywhere a JVM is controlled, STS stays preferred.

**Merge Gate:** the default Core + Community reactor compiles and all TCK/CI gates pass on **JDK 25 LTS with no `--enable-preview`**; two **airtight, binary** acceptance checks are CI-enforced — (1) **no preview type in any exported SPI signature or in generated (codegen) code**, and (2) **the default `main` artifact contains zero preview bytecode** (`minor_version 0xFFFF` scan). There is no adapter dependency to exclude: the preview-bearing code is not on `main` at all (it lives on the `preview` branch), so the contamination paths that would otherwise need guarding — co-packaging, eager `ServiceLoader` discovery, a stray transitive dependency — cannot arise on the default tree. The preview branch builds separately with `--enable-preview` on the STS-target JDK. The gate applies to **shipped `main` bytecode only**: test and TCK fixtures continue to compile under `--enable-preview` exactly as today (they are not distributed), so STS may stay in test scope.

**ADR:** the GA-clean orchestration substitution on `main` + the `preview`-branch distribution model (`preview` = intended future `main`) is an architecture decision and warrants a dedicated ADR — **reserve the next free number in `~/exeris-systems/exeris-docs/adr-index.md` when the design firms up** (do not claim a number now). It ties to the existing bootstrap-orchestration-seam direction.

**Status (v0.11): DELIVERED.** Surfaced 2026-06-18 from the JEP 401 + `StructuredTaskScope` GA-timeline analysis; closed 2026-08-08 under **ADR-066**. The distributed artifact now builds on **JDK 25 LTS with no `--enable-preview` on main sources** and imposes neither on consumers.

**Measured, not asserted.** Acceptance step 1 asked for "a clean no-`--enable-preview` compile of the default reactor" as the definitive confirmation that `StructuredTaskScope` was the only preview dependency. Run on a real JDK 25 LTS, the two blockers proved separable and very unequal:

- **The JDK-26 target cost nothing.** The full reactor is green at `--release 25` on JDK 25 — no JDK-26-only API is used anywhere, so the `26` was never load-bearing. What it did cost was four constants that fail the build *before* any preview error appears, and therefore mislead anyone attempting this without measuring first: `@SupportedSourceVersion(SourceVersion.RELEASE_26)` in both `build-config` annotation processors, and a hardcoded `--release 26` in two processor tests. A fifth, found only by running it: **`--enable-final-field-mutation=ALL-UNNAMED` in CI's `MAVEN_OPTS` is a JDK 26 flag and JDK 25 refuses to start with it.**
- **The preview flag was the whole of the work**, and `exeris-kernel-spi` compiled clean without it — upgrading "the SPI is already preview-clean" from a grep result to a compiler result.

**Two of the four sites did not become forks, and that is the finding worth carrying forward.** `OutboxOrchestrator` and `CommunityEventLoop` moved to `StructuredScope.openWithoutBindings()` — not a compromise, since both loop threads are plain virtual threads holding no bindings, so the scope they replace inherited an empty set anyway. The other two could not be ported to *any* fork-based helper:

- **`InMemoryEventBus.publishAndAwait`** — `AbstractEventBusTck`'s golden case binds a `ScopedValue` created inside the test and asserts the handler reads it. A `Carrier` can only carry values named in advance, so no GA mechanism reaches that contract. Handlers now run on the calling thread, in subscription order; the golden test passes **unchanged**.
- **`SubsystemOrchestrator` phase start** — rebuilding the kernel's own carrier and forking with it was implemented and **failed**: the HTTP subsystem started with no handler bound and every route answered 404. The lost binding was `HTTP_SERVER_HANDLER`, bound by the *application* around `boot()`; `KernelBootstrap` binds `CURRENT_CONFIG` in a second invisible layer. Subsystems now start in dependency-safe rounds on the booting thread.

The rule this establishes: **you cannot propagate what you cannot enumerate.** Forking stays correct where the body needs only kernel-defined slots, which `open(Carrier)` carries explicitly.

**A preview leak no signature audit could have found.** `StructuredTaskScope.open()` defaulted to `Joiner.awaitAllSuccessfulOrThrow()`, so `join()` threw the **preview** class `FailedException` (unchecked, verified by running it). Every non-FOUNDATION phase started in parallel, so a mandatory subsystem failure reached callers as a preview type — `CoreFailurePolicyTckTest` already caught `BootstrapException \| FailedException` for that reason. It also meant `startParallel`'s failure-collection block was **dead code**; a phase now always throws `BootstrapException`.

**Costs, stated rather than buried.** `publishAndAwait` latency is the sum of handler durations rather than the longest. Boot latency is the sum of a phase's subsystem start times rather than the longest — paid once per JVM, and `FOUNDATION` was already sequential.

**Gate:** `tools/preview-bytecode-scan/` reads the published jars — scoped to the reactor's declared modules, so a partial build fails rather than passing on one scanned artifact — and fails on any distributed class carrying `minor_version 0xFFFF` or a class-file major other than the LTS baseline — bytecode rather than a source grep, because the stamp is what a consumer trips over and it survives generated code a grep would miss. Proven to fail on both modes by byte-level mutation, and on a partial build by removing a module's jar. Current state: **15 177 classes scanned across 8 published modules, of which 2 278 are ours — all at major 69, zero preview-stamped**. The larger figure counts the dependencies the diagnostics CLI shades in, which the gate checks for the preview stamp too: a vendored stamped class breaks a consumer exactly as one of ours would. Test fixtures outside those jars remain preview-stamped and are not published.

**Not done here, deliberately:** promotion of `StructuredScope` to SPI. It is a Core class, so ADR-065's compatibility gate does not cover it and it carries no stability row — it is not a supported consumer surface today, and making it one (with or without richer joiner policies) is its own decision.

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
- TCK-complete coverage for all SPI-observable behavior introduced by roadmap items (with Community bindings and explicit out-of-repo Enterprise obligations),
- **a preview-clean critical path on a GA LTS baseline (JDK 25 LTS)** — the default artifact must build and run with **no `--enable-preview`** and impose none on consumers (see "Platform Baseline for 1.0 GA").
- **flow-resume correctness across deploys** — position-bound saga resume must fail closed so a definition change cannot silently mis-replay an in-flight saga. The arity guard shipped in v0.10; the same-arity reorder is closed by **ADR-062** (step identity in the snapshot, validated on wake). This line previously said the `SCHEMA_MISMATCH` guard was "documented-but-absent" — it has existed since v0.10 (see "Differentiator: Flow/Saga Definition Versioning").
- **kernel-owned schema evolution** — a versioned migration runner (schema-version ledger + apply-once ordering) so a kernel upgrade cannot silently break in-flight sagas (correctness, see "Table-Stakes: Kernel-Owned Table Schema Evolution").
- **supply-chain integrity** — published artifacts carry CycloneDX SBOM + verifiable signature + provenance attestation; a "1.0 GA" on Maven Central cannot ship unsigned (see "Table-Stakes: Supply-Chain Integrity").
- **automated SPI binary-compatibility gate** — revapi/japicmp baselined at the 1.0 SPI surface, enforcing the stability declaration in CI (see "Table-Stakes: SPI Binary-Compatibility Gate").
- **a declared native-image / GraalVM stance** in the support matrix (`supported` / `explicitly-not-supported` / `post-1.0`) rather than silence.
- **a narrowed, deep core** — the new v0.11/v0.12 SPIs (Blob, Job, Cache, WebSocket, ServiceResolver, coordination) are explicitly **post-1.0**; 1.0 is unbreakable on `transport / http / security / persistence / flow / events` (+ `memory`) rather than shallow across fifteen surfaces.

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
- JDK 25 LTS baseline + preview-clean GA critical path (GA-clean substitution at the bootstrap seam on `main`; the STS / value-class version lives on a temporary `preview` branch = intended future `main`, published as `1.0-preview`; certify on 29 LTS; preview branch in CI every commit, delta mechanically minimal)
- flow-resume correctness across deploys (fail-closed `SCHEMA_MISMATCH` guard) + kernel-owned schema-evolution runner — both correctness, see "Road to 1.0" section
- supply-chain integrity (CycloneDX SBOM + signed releases + SLSA provenance) — strategic EU/FENG sovereignty argument
- automated SPI binary-compatibility gate (revapi/japicmp baselined at 1.0 surface)
- declared native-image / GraalVM stance + explicit post-1.0 tag on the v0.11/v0.12 new-SPI stack (scope discipline)
- unified injectable Clock seam (consistency + DST backbone) — 1.0-recommended
- refactoring / PMD suppression reduction
- SonarQube
- docs truthfulness
- CI quality hardening
- open-core / enterprise separation
- OpenSSL 4 migration + provider-aware bindings
- off-heap key material zeroization
- FIPS readiness (optional, scope-decided before v0.9 closes)
