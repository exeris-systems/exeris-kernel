# Changelog — Exeris Kernel (open-core)

All notable changes to the open-core kernel are recorded here. The roadmap source of truth is `docs/ROADMAP.md`. Detailed per-release notes live under `docs/release/` (e.g. `docs/release/v0.7.0-release-notes.md`).

This file is intentionally terse: it lists what landed, with a pointer to the release-notes document for the *why* and the merge gates. Per-PR detail is in the git log; do not duplicate it here.

Format follows the spirit of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versions follow [SemVer](https://semver.org/spec/v2.0.0.html), with the pre-1.0 caveat that minor versions may carry observable contract additions while remaining backwards-compatible at the SPI level.

## [Unreleased] — 0.8.0-SNAPSHOT

### Performance — Zero-allocation ingress read (Sprint 6 PERF-072)

- `NativeTcpStreamPlainSocketIo` seam and NIO read paths no longer allocate a redundant `NativeMemorySegmentImpl` wrapper per read: `target.asSlice(0, maxBytes)` is elided to `target` when `maxBytes == target.byteSize()` (always true for the carrier's full-slab ingress read). Egress sub-range slices are unchanged. Community-internal; no SPI/Core contract change. See `docs/ROADMAP.md` → "Transport: Zero-Allocation Ingress Read".

### Fixed — Flow snapshot store wiring gap (Sprint 0b)

- `CommunityFlowSubsystem.initialize()` now selects `JdbcFlowSnapshotStore` when a Community `PersistenceEngine` is bootstrapped alongside, instead of always falling back to the in-memory `CommunityFlowSnapshotStore`. Parked saga snapshots now survive a kernel restart whenever `flow.persistenceEnabled=true` and a JDBC-backed engine is present (ADR-022 §1). The in-memory store remains the fallback when no engine is available.
- `JdbcFlowSnapshotStore` constructor migrated from `javax.sql.DataSource` to the `PersistenceEngine` SPI — the durable store now routes all connection acquisition through `engine.openConnection()` and the `PersistenceStatement` / `QueryResult` SPI surface (ADR-022 §3).

### Added — Persistence SPI extensions (ADR-022)

- `PersistenceStatement.bindInstant(int, Instant)` — typed `Instant` binder with `null` → SQL NULL semantics (encoded as `TIMESTAMP WITH TIME ZONE` NULL in Community).
- `RowCursor.getInstant(int)` — typed `Instant` reader returning `null` for SQL NULL (reference-typed; no NPE coercion). Caller maps NULL to a sentinel value (e.g. `Instant.MAX` for "no timeout") at the call site.
- Both methods are additive — existing SPI implementations only require a forward-compatible compile-time addition; no behavioural change at the call site for non-timestamp columns.

### Sprint 0a — TCK-064 transport stress + Surefire IO corruption fixes

- Configurable test-scale knobs on `NativeTcpTransportStressTest` (`exeris.tck.transport.stress.clients` / `serverReactors` / `clientTimeoutSeconds`) — enable operators to scale the stress matrix without recompilation.
- Configurable drain budget on `TransportCarrierPinningTck` (`exeris.tck.transport.drainTimeoutSeconds`) — accommodates constrained CI runners under thread pressure (default 5s for local boxes, override to 30s on 2-vCPU GitHub Actions).
- New `transport-stress-gate` CI job (`.github/workflows/maven.yml`) bumps VT carrier pool to `parallelism=16 / maxPoolSize=64` — works around blocking-`recv()` pinning in the Panama FFM ingress pump (mid-test jstack diagnosis: client ingress VTs pin carriers on `recv()` syscall; default 2-vCPU CI carrier pool exhausts before all clients connect). Production fix (non-blocking `recv()` + selector wakeup) deferred to Sprint 6 transport hardening.
- HotSpot `-Xlog:jfr+startup=off` in root POM — suppresses native log stream that bypassed Surefire IO redirect and surfaced as "Corrupted channel" warnings.

## [0.7.0] — 2026-05-10

### Added — Distributed saga state (EPIC-1)

- `FlowSnapshotStore.listParked()` SPI extension with default returning `List.of()` for in-memory implementations (Sprint 1, ADR-013 §3).
- `FlowSnapshot.schemaVersion` field for optimistic-locking CAS at the JDBC layer (Sprint 1, ADR-013 §3).
- `JdbcFlowSnapshotStore` Community implementation backed by `exeris_saga_state` DDL with auto-DDL bootstrap (Sprint 3).
- `lookupParked` snapshot fallback for cross-restart choreography wake (Sprint 4).
- Bounded terminal-state catalog retention (`FlowEngineConfig.terminalCatalogMaxSize`) with snapshot-store-backed idempotency fence (Sprint 4).
- Saga TTL enforcement (`FlowEngineConfig.defaultSagaTimeoutShort` / `defaultSagaTimeoutLong`) and `FlowTimeoutEvent` JFR (Sprint 4).
- `FlowEngineShutdownEvent` JFR with operational fields (Sprint 4).
- `AbstractDistributedFlowSnapshotStoreTck` + `AbstractSagaRecoveryTck` abstract suites with Community JDBC bindings (Sprint 3, Sprint 4).
- ADR-013 *Distributed Saga State Distribution Model* signed off (Sprint 1, decision recorded 2026-04-27).

### Added — Distributed events (EPIC-2)

- `EventStreamReader` and `EventStreamAppender` SPI skeletons (implementation-blind) (Sprint 5a, EVENT-203).
- `KafkaEventEngine` Community provider in new `exeris-kernel-community-kafka` module (Sprint 5b, EVENT-204).
- `EventEngine` backpressure knob and Community-tier delivery semantics (Sprint 5b).
- `AbstractKafkaEventEngineTck` + Community Testcontainers Kafka 3.x binding (Sprint 5b, EVENT-206).
- TCK closure for registry ordinal conflict (`EX-EVENT-6003`) and backpressure (`EX-EVENT-6002`) `rawArgs` contracts (Sprint 5a, EVENT-205).

### Added — Flow + Events distributed integration (EPIC-3)

- Kafka choreography smoke integration test (Sprint 6a).
- Kafka empty-subscription guard and re-enabled wake-on-event integration test (Sprint 6b).
- Cross-engine choreography end-to-end test demonstrating two-process saga handoff (Sprint 6c, DIST-302 closure).
- Distributed-saga JFR events per ADR-013 §8 (Sprint 6 telemetry).

### Added — Telemetry, observability, exceptions (Sprint 7)

- Environment-aware exception disclosure mode with `AbstractDisclosureModeTck` (Sprint 7a).
- `HealthEndpointHandler` + `HealthProbe` SPI for embedded readiness/liveness on the Community runtime (Sprint 7b).
- `AsyncTelemetrySink` dispatcher with bounded ring and explicit drop policy; `AbstractTelemetryRingBufferTck` and `TelemetryZeroAllocTck` Community bindings green (Sprint 7c).
- Prometheus metrics export baseline (Sprint 7d).

### Added — Compile-time RBAC (Sprint 8)

- `@RequiresRole` annotation in `exeris-kernel-spi` (Sprint 8a, ADR-014 §3).
- APT processor in `exeris-kernel-build-config` generating `RoleCheckRegistry` for compile-time bitmask O(1) checks (Sprint 8b-i, ADR-014 §3 + §8).
- Runtime decision integration with `RoleCheckEnforcer.isAllowed`; zero-allocation hot-path TCK (Sprint 8b-ii, ADR-014 §5–§6).
- ADR-014 *@RequiresRole Compile-Time RBAC* signed off (Sprint 8a, decision recorded 2026-04-27).

### Changed / Quality

- Performance follow-up sweep: PERF-061 per-frame HTTP/2 buffer reuse, PERF-062 TLS plaintext slab, PERF-063 NIC reactor MPSC bounded queue (`MpscUnboundedArrayQueue`), PERF-064 `LongAdder.reset()` race fix via baseline snapshot per generation (Sprint 2).
- TCK-064 transport stress harness rework + `transport-stress-gate` job (Sprint 2).
- HEUR-061 transport class decomposition (`NativeTcpCarrier` / `NativeTcpStream` size + collaborator review) (Sprint 2).
- TCK-061 `BootstrapProviderSelector` enforces `TransportProvider.isAvailable()` filter (Sprint 1).
- DOC-061..064 subsystem doc fixes (`persistence.md`, `transport.md`, `security.md`, `crypto.md`) (Sprint 1).
- SQ-005..011 quality batch: `catch(Throwable)` → `catch(Exception)` + semantic suppressions, `RuntimeFlowInstance` constructor arity reduction, `Math.clamp` adoption with `S2184` overflow guard, `tools/jfr-reporter` SonarCloud cleanup, `Thread.sleep` → `LockSupport.parkNanos` + bounded settle-window in tests, S5778 single-throwing-call discipline (Sprint 8c).
- QA-008 `CommunityGraphCypherHelper` decomposed into `CypherExecutor` interface + `CommunityGraphCypherReader` + `CommunityGraphCypherWriter` + `CypherIdentifiers`; orchestrator preserves transactional cohesion via shared executor (Sprint 8d).
- Hot-Path Collections Review (Sprint 8e) — design note recorded; no further runtime changes justified for v0.7 (`docs/release/hot-path-collections-review.md`). v0.8 watch-items: idempotency-guard packed `long[]`, `pendingWriteInterest` identity keyset, miss-cache ring rewrite.
- Security: `kafka-clients` 3.9.1 → 3.9.2 (CVE-fixed: GHSA-5qcv-4rpc-jp93 producer message corruption via buffer pool race; GHSA-wf66-mphr-4c4r sensitive info exposure in DEBUG logs). 3.9.x line retained for broker compatibility; 4.x bump deferred to v0.8.
- CI: `.github/workflows/claude-code-review.yml` — added `id-token: write` to job permissions. The action falls back to OIDC even with `claude_code_oauth_token` configured; missing permission caused "Failed to get OIDC token" check failures.

### Deferred — re-triaged to v0.8

- QA-009 PMD suppression count target ≤ 100 — partial close; carries forward as QA-010..018 (nine remaining `GodClass` decompositions, one PR each).
- Hot-path collections watch-items above (idempotency packed bitmap; reactor `pendingWriteInterest`; miss-cache ring) — deferred until profile signal justifies.

### Notes

- See `docs/release/v0.7.0-release-notes.md` for the full per-EPIC narrative, gate-by-gate readiness summary, and the single-release-vs-patch-sequence decision (single 0.7.0).
- ADR-013 / ADR-014 are tracked under `docs/adr/`.
- v0.6.0 is the previous shipped baseline; commits under `5.x` correspond to the v0.6.0 cycle.
