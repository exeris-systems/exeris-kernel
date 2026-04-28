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

### Cross-Cutting Quality: Sprint 5 Deferred Items Carried Into v0.7

**Gap:** Sprint 5 was closed with P1 gates green; the remaining P2/P3 quality items were intentionally deferred and must stay visible in v0.7 release planning. Source of truth: [`docs/release/sprint5-quality-gate-report.md`](./release/sprint5-quality-gate-report.md).

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

---

### Flow + Events: Distributed Saga State and Orchestration — Implementation Backlog

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
