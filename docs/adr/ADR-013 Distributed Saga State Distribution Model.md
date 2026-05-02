# ADR-013: Distributed Saga State Distribution Model

| Attribute      | Value                                                                                  |
|:---------------|:---------------------------------------------------------------------------------------|
| **Status**     | **PROPOSED**                                                                           |
| **Deciders**   | Arkadiusz Przychocki                                                                   |
| **Date**       | 2026-05-01                                                                             |
| **Driven By**  | ADR-007 (next-gen runtime), ADR-008 (open-core), `docs/subsystems/flow.md`, `docs/subsystems/events.md`, ROADMAP DIST-301 |
| **Compliance** | [Strategic Pillar: Distributed Saga Recovery](../whitepaper.md)                        |

## 1) Context and Problem Statement
- Single-node `FlowEngine` already supports PARK/WAKE recovery via `FlowSnapshotStore` (in-memory) and choreography wake via the in-memory parked-instance index.
- Distributed deployments must coordinate saga state across multiple kernel instances that share business workflows: a saga parked on node A may receive a wake-trigger event on node B after a restart of either side.
- Two coordination problems must be solved together: (a) durable, cross-restart saga state that survives a single kernel process; (b) cross-service event delivery that drives wake on the node currently owning capacity.
- The decision must keep SPI implementation-blind, must not move broker/database concerns into Core, and must preserve the No-Waste-Compute hot-path discipline on the in-memory fast path.

## 2) Decision Scope
- In scope: durable saga snapshot model, cross-restart recovery, optimistic concurrency on shared snapshot rows, event-driven cross-service wake, error/telemetry contract for distributed coordination.
- In scope: SPI surface area (`FlowSnapshot.schemaVersion` for OCC; a future parked-enumeration entry point on `FlowSnapshotStore` to be designed in Sprint 3 when `JdbcFlowSnapshotStore` lands), Core orchestration responsibilities, Community/Enterprise provider obligations.
- Out of scope: identity/auth concerns for cross-service calls (covered by ADR-012), specific JDBC vendor optimizations beyond Postgres baseline, central saga-coordinator services, multi-region replication.

## 3) Decision — Option A: Shared Durable Store + Event-Driven Choreography Wake
- **Source of truth:** durable `FlowSnapshotStore` shared by all participating kernel instances. The reference Community implementation in 0.7 is `JdbcFlowSnapshotStore` over Postgres.
- **Coordination primitive:** choreography events delivered through the Community Kafka `EventEngine`. There is no central saga coordinator and no distributed lock service.
- **Conflict discriminator:** `FlowSnapshot.schemaVersion` (introduced in 0.7 SPI groundwork) acts as an optimistic-locking version on durable rows. Concurrent advance attempts on the same instance from two kernels resolve to one winner; the loser receives `EX-FLOW-7002 / phase=OPTIMISTIC_LOCK_CONFLICT` and falls back to the engine's existing idempotency guard for retry semantics.
- **Cross-restart recovery:** on kernel start, the engine enumerates previously parked instances from the durable store via the parked-enumeration entry point introduced alongside `JdbcFlowSnapshotStore` in Sprint 3, and re-arms in-memory wake routing. Steady-state choreography continues to use the in-memory parked-instance index as the O(1) fast path; a snapshot-store probe on miss is the bounded fallback (already documented in `flow.md`).

### Rejected alternatives
- **Option B — distributed lock service (ZooKeeper/etcd) for saga coordination.** Adds a new operational dependency, raises bootstrap fragility, and shifts blocking calls onto the saga advance path. Rejected as inconsistent with the No-Waste-Compute contract.
- **Option C — CRDT-based saga-state replication.** Saga compensation stack and step ordering are not commutative; merging concurrent advances at the data-structure level violates correctness. Rejected as semantically unsound for the saga model.
- **Option D — single-leader saga coordinator with consensus (Raft/Paxos).** Acceptable correctness, but reintroduces a stateful central component the open-core kernel deliberately avoids; also inconsistent with ADR-008's preference for keeping Community drivers commodity-grade.

## 4) Architecture Boundaries (The Wall)
- SPI remains implementation-blind: only `FlowSnapshotStore` and event SPI types appear; no JDBC types (`Connection`, `DataSource`), no Kafka types (`Producer`, `Consumer`, `ProducerRecord`), no broker product names.
- Core remains driver-agnostic: distributed wake is orchestrated through `FlowSnapshotStore`, `EventBus`/`EventLoop`, and `OutboxBrokerPort`. Core may host session-orchestration code (e.g., `eu.exeris.kernel.core.events.kafka.KafkaSessionOrchestrator`) but MUST NOT depend on `org.apache.kafka.*` — all Kafka client interaction stays inside the Community module.
- Community owns the JDBC and Kafka client adapters. The Kafka adapter ships in a dedicated `exeris-kernel-community-kafka` submodule so single-node operators do not transitively pull `kafka-clients` and its dependency chain.
- Enterprise binding remains out-of-repo. The contract obligations defined here apply to Enterprise on parity with Community.

## 5) Optimistic Concurrency Contract
- Every persisted saga row carries a `schema_version` column matching `FlowSnapshot.schemaVersion`. New snapshots use `FlowSnapshot.SCHEMA_VERSION_INITIAL` (`1L`).
- `JdbcFlowSnapshotStore.save()` uses `INSERT ... ON CONFLICT (instance_id_most, instance_id_least) DO UPDATE` guarded by `WHERE schema_version = :incomingVersion`. On a CAS miss the store MUST raise `EX-FLOW-7002` with `phase=OPTIMISTIC_LOCK_CONFLICT` and rawArgs carrying `(engineName, "OPTIMISTIC_LOCK_CONFLICT", "STALE_VERSION", incomingVersion)`.
- On a successful write the store MUST advance the on-disk version by exactly one. The runtime engine receives the new version via `load()` on the next replay and propagates it through `RuntimeFlowInstance.toSnapshot()` so subsequent saves carry the up-to-date version.
- Layered idempotency: the in-memory `IdempotencyGuard` (heap CAS) and the durable `schemaVersion` (DB CAS) MUST agree on terminal states. TCK MUST cover the case where the heap guard reports "already done" but the durable row claims otherwise; the durable answer wins, and the heap state is reconciled on next load.

## 6) Recovery Model
- Engine startup enumerates parked instances through the durable store's parked-enumeration entry point (introduced alongside `JdbcFlowSnapshotStore` in Sprint 3) and rehydrates wake routing for every returned snapshot. Implementations MAY return a fully materialized list; pagination is not required for v0.7 but MAY be added in v0.8 if operator profiles show large parked populations.
- Per-instance snapshot fallback on choreography wake remains unchanged: in-memory miss → `snapshotStore.load(...)` → filter `FlowState.PARKED` → reconstruct `FlowContext`. Repeated unknown-instance misses MUST be negatively suppressed in Core to bound persistence cost.
- Saga TTL is governed by `FlowEngineConfig.defaultSagaTimeoutShort` and `defaultSagaTimeoutLong`. Timeout enforcement on wake compares `snapshot.timeout()` against `Instant.now()` and triggers compensation rather than wake when expired. Operators that override TTL defaults MUST document the operational implication.

## 7) Hot Path Constraints (Performance Contract)
- The in-memory parked-instance index remains O(1) on the steady-state choreography path. The durable store is consulted only on a miss or at startup.
- `JdbcFlowSnapshotStore` MUST NOT use `ThreadLocal` for context propagation. `DataSource` is constructor-injected through `BootstrapContext`; ownership of the pool lifecycle stays with the bootstrapper.
- The Kafka choreography path MUST NOT pin a Virtual Thread carrier across consumer-group rebalance. `FlowCarrierPinningTck` patterns apply.
- `FlowSnapshot.schemaVersion` is a primitive `long`; the addition does not regress Valhalla readiness.

## 8) Error and Telemetry Contract
- New error condition: `EX-FLOW-7002 / phase=OPTIMISTIC_LOCK_CONFLICT` (see §5). No new error code is introduced; the existing `EX-FLOW-7002` taxonomy is reused with a new `phase` value.
- JFR contract: distributed saga events MUST emit JFR-first telemetry on wake-on-load fallback, optimistic-lock conflicts, and parked-enumeration latency. Existing flow lifecycle JFR events (`FlowEngineShutdownEvent`, `FlowTimeoutEvent`) cover steady-state observability.
- Payloads are secret-safe: no payload bytes from `opaqueState`, no JDBC connection strings, no Kafka credentials in emitted diagnostics. `EX-FLOW-7002` rawArgs carries only structural context (engineName, phase, reasonCode, contextVal).

## 9) TCK Obligations
- `AbstractDistributedFlowSnapshotStoreTck` (introduced in Sprint 3 alongside `JdbcFlowSnapshotStore`) codifies: save/load round-trip, delete semantics, parked-enumeration returning only `PARKED` rows, cross-restart simulation, concurrent-save CAS resolution, and `EX-FLOW-7002` on stale-version writes.
- `AbstractSagaRecoveryTck` extension covers cross-restart choreography wake under persistence-enabled engines.
- `AbstractKafkaEventEngineTck` (Sprint 5) covers consumer-rebalance behavior under in-flight choreography.
- Community bindings: `CommunityJdbcFlowSnapshotStoreTckTest` (Postgres via Testcontainers, optional H2 fallback for developer-loop speed) and `CommunityKafkaEventEngineTckTest` (Kafka 3.x via Testcontainers).
- Enterprise bindings: parity obligation declared; out-of-repo verification.
- Contract changes are incomplete until abstract suites and both binding layers validate observable behavior.

## 10) Implemented vs Planned (mandatory anti-drift block)
- Implemented now (repository state, 0.7 SPI groundwork): `FlowSnapshot.schemaVersion` field with `SCHEMA_VERSION_INITIAL = 1L`; convenience constructor preserves the v0.6 call shape so existing in-memory stores compile unchanged. The parked-enumeration entry point on `FlowSnapshotStore` is intentionally deferred to Sprint 3 so its return type and pagination contract can be co-designed with `JdbcFlowSnapshotStore`.
- Implemented now (repository state, pre-0.7): in-memory parked-instance index O(1) fast path; per-instance snapshot fallback on miss with negative suppression; `EX-FLOW-7002` taxonomy with phase discriminator; `AbstractSagaRecoveryTck` covers single-node mid-saga kill / idempotency / compensation.
- Planned (0.7, Sprint 3 — FLOW-103/104/105/106): `exeris_saga_state` DDL with composite PK and partial PARKED index; `JdbcFlowSnapshotStore` with UPSERT + CAS; `lookupParked` snapshot fallback wired to durable store; `AbstractDistributedFlowSnapshotStoreTck` and Community Postgres binding.
- Planned (0.7, Sprint 4 — FLOW-107/108, DIST-303): bounded terminal-state catalog with snapshot-store fallback verification; saga TTL enforcement; `FlowEngineShutdownEvent` and `FlowTimeoutEvent` JFR events.
- Planned (0.7, Sprint 5/6 — EVENT-204, DIST-302): Community Kafka `EventEngine` in `exeris-kernel-community-kafka`; Core `KafkaSessionOrchestrator` (no `org.apache.kafka.*` leak); Flow→Events choreography handoff with `IdempotencyGuard` fence on at-least-once delivery; cross-service E2E recovery covered by integration tests.
- Planned (0.7+, post-merge): the `RuntimeFlowInstance` wire-through of `schemaVersion` from load to save (so non-trivial OCC begins to advance through the lifecycle) lands as part of Sprint 3 alongside `JdbcFlowSnapshotStore`. Pre-Sprint-3 `toSnapshot()` continues to emit `SCHEMA_VERSION_INITIAL`, which is harmless for in-memory bindings.

## 11) Consequences
- Operators of single-node Community deployments are unaffected: the in-memory `CommunityFlowSnapshotStore` ignores `schemaVersion` and is not required to implement parked-enumeration (the entry point is introduced in Sprint 3 alongside the durable JDBC store). No new dependency is pulled.
- Operators of distributed deployments adopt `JdbcFlowSnapshotStore` and the `exeris-kernel-community-kafka` module explicitly; absence of either keeps the system in single-node semantics.
- Enterprise binding inherits the same SPI contract and the same TCK obligations; off-heap zero-copy snapshot writes and broker integrations remain Enterprise-internal but MUST honor the OCC discriminator and the parked-enumeration contract introduced in Sprint 3.
- Future evolution toward streaming `EventStreamReader` / `EventStreamAppender` SPI types (Sprint 5 EVENT-203) is unblocked; this ADR does not foreclose adding those.
