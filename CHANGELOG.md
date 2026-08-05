# Changelog — Exeris Kernel (open-core)

All notable changes to the open-core kernel are recorded here. The roadmap source of truth is `docs/ROADMAP.md`. Detailed per-release notes live under `docs/release/` (e.g. `docs/release/v0.7.0-release-notes.md`).

This file is intentionally terse: it lists what landed, with a pointer to the release-notes document for the *why* and the merge gates. Per-PR detail is in the git log; do not duplicate it here.

Format follows the spirit of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versions follow [SemVer](https://semver.org/spec/v2.0.0.html), with the pre-1.0 caveat that minor versions may carry observable contract additions while remaining backwards-compatible at the SPI level. Which SPI surfaces are `stable` / `preview` / `experimental`, and what each label commits to for semver, is declared in [`docs/stability-matrix.md`](docs/stability-matrix.md) — the authoritative source for the semver policy.

## [Unreleased]

### Added
- **SPI compatibility gate** — `tools/spi-api-diff/` compiles `exeris-kernel-spi` at any two revisions
  straight from git (the module depends only on `java.*`/`jdk.*`, so every revision in history builds
  with nothing but a JDK) and diffs them with japicmp, classifying each finding by the maturity label
  declared in `docs/stability-matrix.md`. A binary-incompatible change to a surface declared `stable`
  fails CI (`spi-compatibility-gate` job); `preview`/`experimental` changes are reported, not gated.
  A second check fails the build when an SPI package carries no maturity label at all — which is how
  `spi.scheduling` and `spi.storage.blob` were found to be missing from the matrix (ADR-065).
- **Generated compatibility record** — `docs/release/spi-api-history.md`: one row per release
  transition from 0.5.0 to 0.10.2, produced by the gate rather than by review. No `stable` surface has
  taken a binary-incompatible change since the stability matrix was first published in v0.9.0.
- **Upgrade guide 0.5.x → 0.10.x** — `docs/release/upgrade-0.5-to-0.10.md`, covering the three
  breaking transitions in that range and what to write instead.
- **Reconstructed v0.6.0 release notes** — `docs/release/v0.6.0-release-notes.md`. v0.6.0 shipped
  without notes and predates this changelog, while carrying the only pre-declaration removal of
  `stable`-surface SPI types; the record is now closed.

### Changed
- **Stability matrix** — added the missing `…spi.scheduling` and `…spi.storage.blob` rows (both
  `preview`, since 0.11.0, ADR-057 / ADR-056); the pre-1.0 framing now distinguishes "no consumer
  under a support contract" from "no consumers", and points at the generated record.

## [0.10.2] — 2026-07-19

Patch release. Community-internal allocation discipline on the JSON-encode and memory-allocator hot paths; additive, SPI-unchanged, default byte-identical. Detail + notes: [`docs/release/v0.10.2-release-notes.md`](docs/release/v0.10.2-release-notes.md).

### Changed
- **Zero-copy JSON body encoding** — `JsonBodyEncoder` (#241) and `CommunityJsonRequestBodyEncoder` (#242) stream Jackson straight into the loaned off-heap buffer via `SegmentSink`, dropping the per-request heap `byte[]` + `MemorySegment.copy` (~11432 → ~1192 B/op/encode; byte-identical output, no SPI change).
- **Allocator release accounting** — `CommunityMemoryAllocator` folds per-buffer release bookkeeping into `CommunityLoanedBuffer.onRelease()` via a shared `CommunityReleaseAccounting`, dropping the per-buffer close-action object (−80 B/op per allocation, system-wide; `stats()`/JFR identical) (#244).

### Added
- **JSON-encode JMH benchmark** — `JsonBodyEncoderBenchmark` (streaming vs materialize-and-copy), plus a documented negative result that Jackson `ObjectWriter` reuse is allocation-neutral (#243).

## [0.10.1] — 2026-07-19

Patch release. Community-internal, additive, default byte-identical (no SPI change). Detail + notes: [`docs/release/v0.10.1-release-notes.md`](docs/release/v0.10.1-release-notes.md) (ADR-052).

### Added
- **Configurable Community JSON mapper** — `eu.exeris.kernel.community.json` `JsonMapperCustomizer` (ServiceLoader) + per-quadrant `JsonMapperScope`; `CommunityHttpProvider` / `CommunityEventProvider` source each codec's Jackson `ObjectMapper` through the seam instead of a hardcoded `new ObjectMapper()`, so an application can (e.g.) register Blackbird for `invokedynamic` accessors on the response-encode hot path. With no customizer registered every mapper is byte-for-byte the prior default; Jackson stays a Community driver detail (The Wall holds). Note: "byte-identical" is about serialized output — each HTTP quadrant now builds its own mapper instead of one shared instance, so per-mapper serializer caches are no longer shared across quadrants (bootstrap/JVM-warmup only) (ADR-052).

## [0.10.0] — 2026-07-02

Minor release. SPI-additive (pre-1.0; no external consumers). The **Events** subsystem gains its ordering/OCC and codec fundament; **Security** gains a dedicated identity SPI; **HTTP** gains streaming, retry, and boot-path routing. Detail + merge gates: [`docs/release/v0.10.0-release-notes.md`](docs/release/v0.10.0-release-notes.md).

### Added
- **IdentityProvider SPI + Community OIDC driver** — `eu.exeris.kernel.spi.security` identity dispatcher; OIDC+JWKS first driver; Keycloak IT (ADR-040, #218).
- **HTTP server-push / SSE streaming** — `HttpStreamExchange` SPI + Core SSE framing + Community binding, respond-once invariant preserved (ADR-043, #214).
- **HTTP client retry** — `HttpRetryPolicy` SPI + `KernelWebClient` retry loop + Community default (ADR-045, #220).
- **Event-Payload Codec SPI** — `EventPayloadCodec` + registry + Community JSON driver + TCK; bootstrap binding + encode-failure JFR (ADR-046, #222/#223).
- **Events log-ordering & OCC boundary** — `EventStreamAppender` append-with-expected-version + per-`StreamId` total ordering SPI; Community JDBC/Postgres **and** Kafka bindings (≥2-binding gate closed) (ADR-049, #225–#228).
- **Binding-agnostic `topic`** — optional `EventTypeSpec.topic` (per-type) + `hasTopic()` + 3-arg factories; Kafka honours the override on publish and subscribe (ADR-050, #229).
- **HTTP generated-app boot-path** — `{id}` path-parameter routing + request-decoder request-scope (W7, #224).

### Changed
- **Open-core provider priority aligned** — Community=`0` / Enterprise=`100`; stale `100/200` text swept (#217).

### Fixed
- **Saga version-blind resume** — fail-closed `SCHEMA_MISMATCH` on a version-blind saga resume rather than silent divergence (#215).

### Direction (RFC / decision — implementation in later versions)
- **Shared-scope isolation tier** — `RFC-2026-07-02` (**ACCEPTED**): the shared-world tier is an *orthogonal row-visibility dimension* (`sharedScopeKey`/`SHARED_WORLD`), not a fourth `IsolationStrategy`; kernel-neutral name (game-domain "Universe" stays SDK-side). SPI carrier + RLS mode land later as an ADR-012 amendment.
- **WebClient service addressing** — `RFC-2026-06-29` relocated to the kernel repo (owning-repo-holds-the-SPI-RFC convention, #230).

## [0.9.0] — 2026-06-18

Minor release. SPI-backwards-compatible apart from one pre-1.0 diagnostics trim (no external consumers). Detail + migration notes: [`docs/release/v0.9.0-release-notes.md`](docs/release/v0.9.0-release-notes.md).

### Added
- **Diagnostics SPI + CLI** — `eu.exeris.kernel.spi.diagnostics.*` (first explicitly-**stable** new surface) + `exeris-kernel-diagnostics-cli` artifact (ADR-033).
- **`@Immutable`** config-key sealing — `spi.config.Immutable` + `ConfigProvider.guardImmutable` (preview); compile-time + watcher-refusal enforcement.
- **`TransportStream.reset(long)`** — Wall-safe `default` (graceful); Community abortive (RST) override.
- **`DEGRADED`** reversible subsystem-health state + Community health watcher (Core-internal — no SPI change) → readiness drains, liveness holds.
- Tracked docs: `stability-matrix.md`, `support-matrix.md`, `operations/reference-deployment.md`.
- Security: JWKS key rotation (Community). Crypto: OpenSSL 4 multi-version (3.0–4.x, ADR-008 refresh).
- Operational-continuity CI gate (`recovery-continuity-gate`, Testcontainers restart/degraded ITs).

### Changed
- **Diagnostics SPI trimmed** (pre-1.0): `listCapabilities()` / `CompositionSnapshot` / `CapabilityDescriptor` removed.
- TLS reactor efficiency: closed-engine read-spin fix (#202), egress frame coalescing (#199), ingress loop-drain (#197), reactor dispatch + atomic cleanup (#195/#196).

### Fixed
- **Saga recovery stale-write fence** (#208) — a worker abandoned past `close()` on a closed `CoreFlowRuntime` could re-persist a `PARKED` checkpoint after a rebuilt runtime had already reclaimed the row on `complete()`, resurrecting it (surfaced as a load-sensitive flake on 2-vCPU CI). `persistSnapshot` now fences non-terminal writes from a non-active runtime (`!isActiveLifecycle && !isTerminal`); terminal finalizations are exempt.

### Direction (RFC — implementation in later versions)
- **IdentityProvider SPI** — `RFC-2026-06-08` (ACCEPTED); ADR-040 reserved; SPI + driver in v0.10.
- **HTTP streaming (SSE-first)** — `RFC-2026-06-18`; ADR-043 reserved; brought earlier than the planned v0.12 (target v0.10/v0.11).

## [0.8.1] — 2026-06-08

Patch release. Backwards-compatible SPI addition; no contract removals.

### Added — tier-blind connection unwrap seam (ADR-017 JDBC bridge)

- `PersistenceConnection.unwrap(Class<T>)` — a default, implementation-blind seam
  (`java.sql.Wrapper` idiom, but the SPI names no driver type) returning
  `Optional<T>`. Lets integration bridges reach a provider-specific backing object
  without the SPI referencing JDBC. The Community JDBC connection unwraps to
  `java.sql.Connection`; the per-request forwarding wrapper delegates the unwrap to
  its backing connection so the seam survives request-session wrapping.

### Fixed

- Request-session `PersistenceConnection` (the non-owning forwarding wrapper bound
  by the HTTP dispatcher) now forwards `unwrap(Class)` to its backing connection
  instead of opaquely hiding it. This is the kernel-side SPI enablement: a
  forwarding wrapper no longer severs the unwrap seam, so a provider-specific
  backing object remains reachable while a request session is active. (End-to-end
  JDBC-bridge wiring is completed by the corresponding `exeris-spring-runtime`
  consumer; this release ships only the kernel SPI surface.)
- `KernelStart` JFR now reports the real artifact version. The bootstrap version
  stamp is sourced from a Maven-filtered `exeris-kernel.properties`
  (`kernel.version=${project.version}`) instead of a hand-maintained constant that
  had drifted to `0.7.0-SNAPSHOT`; falls back to `unknown` outside a Maven build.

## [0.8.0] — 2026-06-03

Production-correctness release. Completes the HTTP body-codec **SPI** matrix (all
four {request,response}×{encode,decode} quadrants now have SPI seams; the
server-side generator that consumes the new request decoder is a later cycle),
introduces a
tier-neutral `KernelWebClient` facade in Core, wires compile-time RBAC into the
runtime decision path, and hardens the Transport / Flow / Graph paths with new
TCK suites — plus a non-blocking reactor-driven client ingress fix that removes
the virtual-thread carrier-pinning stall under constrained cores.

See `docs/release/v0.8.0-release-notes.md` for the per-stream narrative, the full
SPI surface delta, the gate posture, and the carry-over to v0.9. Per-PR detail is
in the git log. Compare: `v0.7.1...v0.8.0`
(`https://github.com/exeris-systems/exeris-kernel/compare/v0.7.1...v0.8.0`).

### Added — HTTP body-codec SPI matrix — fourth quadrant (Sprint 6/7, ADR-034 + ADR-036)

- Client-side body codec SPI (ADR-034): `HttpRequestBodyEncoder` (outbound payload
  encode) and `HttpResponseBodyDecoder` (inbound payload decode), each with a
  descending-priority `*Registry` and an encode/decode `*Context` record, in
  `eu.exeris.kernel.spi.http`. With the pre-existing `HttpResponseBodyEncoder`
  (0.5.0, ADR-009) this closes three of the four codec quadrants.
- `KernelWebClient` — tier-neutral typed HTTP-verb facade in **Core**
  (`eu.exeris.kernel.core.http.client`), composing `HttpClientEngine` +
  request-encoder/response-decoder registries + an `HttpClientRequestEnricher`.
  Supersedes the ADR-026 `CommunityWebClient` placement so generated client code
  no longer encodes tier identity in its symbols (ADR-034 §Decision). Its
  `WebClientException` carries status predicates (`isNotFound`/`isClientError`/
  `isServerError`/`isConflict`/`isValidationError`/…).
- Server-side request body decode SPI (ADR-036, Sprint 7 HTTP-138): the fourth and
  final quadrant — `HttpRequestBodyDecoder` + `HttpRequestBodyDecoderRegistry` +
  `HttpRequestDecodingContext` (`method, path, headers, allocator`), mirroring the
  response-decoder triplet. Community driver `CommunityJsonRequestBodyDecoder`
  keeps Jackson behind the SPI; `JsonBodyCodecs` extracted to dedup the JSON
  codec helpers. `AbstractHttpRequestBodyDecoderTck` + Community binding,
  CI-bound. The lockstep `exeris-tooling` generator rewrite (TOOL-139) and the
  Enterprise stub are snapshot-gated and land in a later consumption cycle.
- `HttpClientRequestEnricher` SPI (ADR-032, Sprint 6) — implementation-blind
  `enrich(HttpRequest)` functional interface with `noop()` / `chain(List)`
  factories, for implicit outbound context propagation (tenant / principal
  identity; future W3C `traceparent`). Immutable rebuild, zero body interaction,
  CR/LF/NUL header-value rejection. `AbstractHttpClientRequestEnricherTck`.

### Added — Diagnostics SPI decision (Sprint 6, ADR-033)

- ADR-033 (`KernelDiagnostics` SPI — read-only out-of-process runtime
  introspection) accepted, driven by RFC-2026-05-18, with the ADR-025 link stub.
  The SPI itself (`eu.exeris.kernel.spi.diagnostics`), the Community provider and
  the CLI artefact are scoped to v0.9 — only the decision record lands here.

### Changed — HTTP/2 admission and h2c upgrade conformance (Sprint 5/6)

- HTTP-112: RFC 7540 §5.1.1/§5.1.2 stream-identifier admission on the Community
  HTTP/2 path (odd-ascending client stream IDs; `PROTOCOL_ERROR` on violations).
- HTTP-137: cleartext h2c `Upgrade` path now consumes the connection preface,
  applies the peer `HTTP2-Settings`, and synthesises the original request as
  stream 1 per RFC 7540 §3.2 — `curl --http2` against a cleartext endpoint no
  longer fails with `GOAWAY error=1`. Prior-knowledge / ALPN HTTP/2 unaffected.

### Fixed — Non-blocking client ingress (Sprint 7 TCK-064)

- Production fix for the TCK-064 virtual-thread carrier-pinning stall on the Community TCP **client** ingress path. Previously each outbound stream ran a virtual thread that looped on a **blocking** native `recv()` via Panama FFM: the plain client `SocketChannel` was left in blocking mode after `connect()`, so the seam's `EAGAIN → 0` retry branch was unreachable and the carrier thread was pinned for the syscall duration. On a 2-vCPU runner (default carrier pool = 2) a handful of clients exhausted the pool and deadlocked.
- `NativeTcpCarrier.connect()` now flips the connected channel to **non-blocking** unconditionally (not just for TLS-FD) before registration, and registers the client channel on a **client-side reactor** — CLIENT/DUAL engines stand up the same reactor model previously reserved for SERVER/DUAL. Client ingress is now reactor-driven (OP_READ) against a non-blocking FD; no virtual thread blocks on `recv()`.
- Client **egress** is unified onto the reactor key (`requestWriteInterest` → reactor arms OP_WRITE → `flushStream`), matching the server path. The separate per-stream client ingress/writer virtual-thread pumps (`runClientIngressLoop`, `runClientWriterLoop`, `requestClientWriteFlush`) and their `ChannelRuntimeRegistry` thread plumbing were deleted, removing the split-ownership hazard of a writer VT racing the reactor on one FD.
- CLIENT/DUAL outbound runtime intentionally uses a **single** reactor (server reactor count is unchanged) to avoid spawning surplus reactor platform threads that would oversubscribe constrained cores and re-introduce VT-carrier starvation.
- Community-internal transport change only — no SPI/Core contract change (The Wall, ADR-006), no admission/ADR-010 interaction.
- CI: removed the `-Djdk.virtualThreadScheduler.parallelism=16 / maxPoolSize=64` workaround and the escalated `-Dexeris.tck.transport.drainTimeoutSeconds` overrides from both the `build-and-verify` and `transport-stress-gate` jobs in `.github/workflows/maven.yml`. `CommunityTransportCarrierPinningTckTest` (+ MultiReactor2/4 variants) and `NativeTcpTransportStressTest` pass at the default VT carrier pool and the strict default drain budget.

### Security — @RequiresRole runtime wiring (Sprint 4 SEC-080)

- `eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader` (new, Core) resolves the build-time generated `eu.exeris.kernel.security.generated.RoleCheckRegistry` reflectively (by FQN string — no compile edge on `exeris-kernel-build-config`, preserving the processor's reactor-cycle avoidance) and binds its five static accessors (`requiredAny`/`requiredAll`/`matchIsAll`/`methodCount`/`roleNameToBit`) to `MethodHandle`s once at bootstrap, exposed as a `RoleRegistry`. The per-request accessors use `invokeExact` — no `Method.invoke`, allocation-free. ADR-014 §3.
- When no `@RequiresRole` is compiled anywhere (the common case) the loader returns a **fail-closed empty registry** singleton (`methodCount() == 0`, all required masks `0L`, `matchIsAll == false`, `roleNameToBit == -1`) — never allow-all. A `ClassNotFoundException` or signature mismatch both fall through to the same fail-closed empty registry.
- New one-shot JFR event `eu.exeris.kernel.security.RoleRegistryLoaded` (`@StackTrace(false)`, single-phase commit) records whether the generated class was found and its `methodCount`, letting operators distinguish "no annotations" from "load failed".
- `SecurityInterceptor` gains a `(SecurityProvider, RoleRegistry)` constructor (the existing single-arg constructor now delegates with the fail-closed empty registry). On `intercept(...)` / `interceptPreAuthenticated(...)`, when `methodCount() > 0` it resolves the principal's role names through `registry.roleNameToMask(...)` and binds a new Core-internal `MaskedPrincipal` (delegating `PrincipalContext` wrapper) carrying the precomputed `roleMask()`; an empty registry binds the original principal unchanged (no allocation, mask stays `0L`). `runAsSystem(...)` is left untouched so a pre-masked system principal is never wrapped or downgraded.
- `CommunityHttpRequestProcessor` wires the bootstrap-loaded registry into the constructed `SecurityInterceptor`.
- No SPI change: `RoleRegistry`, `PrincipalContext.roleMask()`, and `RoleCheckEnforcer` are unchanged — Sprint 4 only makes the enforcer's inputs live. New TCK coverage: `AbstractGeneratedRoleRegistryLoaderTck` + `AbstractRoleMaskPopulationTck` with Community bindings; existing `AbstractRequiresRoleTck` stays green.
- **Descoped (Sprint 4 finding):** the kernel HTTP dispatcher remains scope/path-based; kernel-edge URL→`methodId` enforcement for path-routed handlers is a codegen concern owned by `exeris-tooling` (cross-repo), not `CommunityHttpRequestDispatcher`. See `docs/subsystems/security.md`.

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

### Changed — Production-correctness TCK hardening (Sprint 7 GRAPH-111 + FLOW-110)

- GRAPH-111: bound `ExecutionGraphZeroAllocTck` and `GraphChurnRatioTck` with
  Community bindings (`CommunityExecutionGraphZeroAllocTckTest`,
  `CommunityGraphChurnRatioTckIT`); also fixed a latent JDK-26
  `ObjectAllocationSample` defect in the abstract churn TCK. No SPI change.
- FLOW-110: added restart-under-load and outcome-correctness (unresolved vs
  failed vs compensated) Flow TCK suites and made them CI gates. No SPI change.

### Performance — Async telemetry + Kafka decode (Sprint 3)

- PERF-070: `AsyncTelemetrySink` ring migrated from `ArrayBlockingQueue` to an
  Agrona `MpscArrayQueue` for lock-free multi-producer enqueue.
- PERF-071: `KafkaEventCodec` zero-copy decode path (decode directly off the
  consumer record buffer; no intermediate copy).

### Observability — Save/publish JFR + event-queue overflow (Sprint 5)

- JFR-091: publish-side JFR instrumentation plus a non-OCC save-side JFR event on
  the persistence path.
- EVENT-111: `CommunityEventQueueOverflowEvent` JFR (single-phase commit,
  `@StackTrace(false)`) records bounded-queue overflow drops for operator
  visibility.
- DOC-090: HikariCP prepared-statement cache defaults documented + Javadoc.

### Changed — Persistence admission tunability forward-port (Sprint 0b, ADR-035)

- Forward-ported the ADR-035 admission-control recalibration from `main` (v0.7.1)
  onto the 0.8.0 line: operator-tunable `persistence.admission.*` knobs via
  `CommunityAdmissionConfig`, depth-allowance shedding, and the
  `PersistenceEngine#canServiceRequest` Javadoc MUST→SHOULD relaxation. Also
  forward-ported the VT-JFR single-phase-commit fix for the connection-acquire
  event. See `docs/adr/ADR-035-persistence-admission-control-tunability.md`.

### Internal — Quality / decomposition / CI (Sprint 1/3/6)

- `GodClass` decompositions QA-010..018, one PR per class
  (`CommunityPersistenceEngine`, `CommunityHttpRequestProcessor`,
  `Slf4jTelemetrySink`, `NativeTcpCarrier` → `NativeTcpSocketBackend`/`Probe` +
  `NativeTcpReactor`, `OutboxOrchestrator`, `CommunityHttpClientEngine`,
  `NativeTcpStream`, `JdbcFlowSnapshotStore` codec extract,
  `CommunityHttp2SessionProcessor` seams, `SubsystemOrchestrator` +
  ADR-026 amendment record). Refactor-only — see git log for per-PR detail.
- Supply chain: `kafka-clients` → 4.0.2 (SEC-100/BUILD-101 + CVE-2026-35554 producer buffer-pool race fix).
- CI: SNAPSHOT publish to GitHub Packages; JaCoCo per-module + `-Pcoverage`
  (C-P0-01); revived Kafka integration tests in CI (C-P0-02); plus CI hotfixes.

## [0.7.1] — 2026-05-30

Patch release. Persistence admission-control recalibration (ADR-035) plus a JFR/virtual-thread
crash fix on the connection-acquire path. No new SPI surface; one SPI Javadoc contract relaxation
(`PersistenceEngine#canServiceRequest` MUST → SHOULD). See `docs/adr/ADR-035-persistence-admission-control-tunability.md`.

### Fixed

- Connection-acquire JFR event now commits single-phase, avoiding a carrier-bound `EventWriter`
  SIGSEGV when a virtual thread unmounts mid-event under an active Recording (`ConnectionAcquireEvent`).
- Constrained-profile (`-XX:ActiveProcessorCount=1`, 16-client) `entity-read-by-id` regression to
  ~92% `503`s since v0.6.0: the admission gate shed on the first queued acquire while the adaptive pool
  collapsed to 2 connections. The gate now admits while pending acquires stay within a pool-size-scaled
  allowance and sheds only on a genuinely deep queue (ADR-035).

### Changed

- Community admission thresholds are operator-tunable (`persistence.admission.*`) via a new
  `CommunityAdmissionConfig` `@Dynamic` record (first production `@Dynamic` consumer; startup-only in
  Community, hot-reload in Enterprise). Default `queueDepthAllowanceRatio=8.0`; set `0` to restore the
  strict pre-035 "shed on first waiter" behavior (`CommunityAdmissionConfig.STRICT`).
- `AbstractPersistenceEngineAdmissionControlTck` relaxed to cross-tier invariants only; tier-specific
  shed thresholds moved to the Community admission tests. Shedding under a deep queue is now a per-binding
  obligation recorded in ADR-035 §Consequences (Enterprise binding must carry an equivalent shed test).
- `PersistenceEngine#canServiceRequest` Javadoc: dangling "ADR-010" reference corrected to ADR-035.

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
