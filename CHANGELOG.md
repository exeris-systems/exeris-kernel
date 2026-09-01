# Changelog — Exeris Kernel (open-core)

All notable changes to the open-core kernel are recorded here. The roadmap source of truth is `docs/ROADMAP.md`. Detailed per-release notes live under `docs/release/` (e.g. `docs/release/v0.7.0-release-notes.md`).

This file is intentionally terse: it lists what landed, with a pointer to the release-notes document for the *why* and the merge gates. Per-PR detail is in the git log; do not duplicate it here.

Format follows the spirit of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versions follow [SemVer](https://semver.org/spec/v2.0.0.html), with the pre-1.0 caveat that minor versions may carry observable contract additions while remaining backwards-compatible at the SPI level. Which SPI surfaces are `stable` / `preview` / `experimental`, and what each label commits to for semver, is declared in [`docs/stability-matrix.md`](docs/stability-matrix.md) — the authoritative source for the semver policy.

## [Unreleased] — development/0.12.0

### Changed

- **The HTTP/1 read path parses each request's headers once, not twice.**
  `CommunityHttp1RequestReader` ran `Http1Codec.parseHeaders` for connection state and h2c
  detection, then ran the parser again to build the `List<HttpHeader>` — every field name and value
  materialised twice — and then copied the finished list a third time. `Http1Codec.parseHeaders` now
  accepts an optional `HeaderVisitor`, so connection state and the header list come off one
  traversal. Measured on the read path, exact per-thread bytes: a 16-header request allocated
  **9 848 B and now allocates 5 472 B**, a 44% reduction, of which the collapsed parse is 41%
  (5 784 B) and the dropped list copy the rest. Header order, values, bounds and the `-1` incomplete
  signal are unchanged.

  It also removes a hazard rather than only a cost. Under two passes the enforced header limit
  depended on which pass reached it first unless both were handed identical bounds, which
  [ADR-071](docs/adr/ADR-071-operational-limit-configuration-path.md) fixed and a comment then asked
  future editors to preserve. One pass cannot express that mistake. See ADR-071's amendment of
  2026-09-01.

### Fixed

- **A listener that ran out of file descriptors no longer stays down.** An `IOException` from
  `accept()` — how `EMFILE`/`ENFILE` surface — called `handleAsyncFailure` and returned, clearing
  `running` and closing the server channel, so a process that touched its `ulimit -n` once needed a
  restart to serve again. The acceptor now pauses (`25ms × streak`, capped at 1s) and tries again,
  emitting `CommunityAcceptRetry` with the streak and the pause; 64 consecutive failures are retried
  and the 65th takes the fatal path. What ends a streak is an accept that returned a socket, counted
  at the accept — so a pass that serves a connection and then fails probing for the next, which is
  what descriptor pressure actually looks like, does not accumulate toward the ceiling. Every `IOException` is retried rather than a
  classified subset: Java gives no typed signal, so classification means matching a message, and a
  message that fails to match reinstates the defect — while retrying a genuinely fatal condition
  only costs a bounded delay before the same outcome.

### Added

- **`TimeSource` — the kernel's seam for time it decides on** ([ADR-082](docs/adr/ADR-082-injectable-time-seam.md)).
  `eu.exeris.kernel.spi.time.TimeSource` carries `nanoTime()` and `wallTime()`, is bound through
  `KernelProviders.TIME_SOURCE`, and is read through `KernelProviders.timeSource()`, which falls back
  to the platform clock when unbound. The method names come from `CommunitySchedulerClock` rather
  than being invented, so the scheduler's clock becomes this seam plus its waiting primitives.

  Migrated: the saga TTL path — the expiry comparison in `CoreFlowRuntime` and the wall↔monotonic
  conversions in `RuntimeFlowInstance`. **Not** migrated: reads that measure rather than decide,
  where virtualising would make JFR durations lie. Of 148 time reads in main sources, five are
  comparisons.

  A saga timeout is now drivable: `CoreFlowTimeSourceTest` advances a manual clock and asserts the
  timeout in **0.79s against a 30-second deadline**, with no sleeping.


- **A saga's parked definition version now survives a restart, not only a wake** (ADR-073 merge gate,
  clause 3). `AbstractFlowDefinitionVersioningTck` covers version-bound resume exhaustively and never
  rebuilds an engine; `AbstractSagaRecoveryTck` rebuilds constantly and was version-blind — so the
  upgrade case, where the process that parked the saga is gone and the one that finds the row runs
  different code, was uncovered. `CrossVersionUpgrade` adds both directions: parked-under-v1 resumes
  on v1 across a rebuild that also hosts v2, and an upgrade that drops v1 refuses with
  `DEFINITION_VERSION_UNRESOLVED` while leaving the row recoverable.


- **An in-flight saga is now proven to survive a schema upgrade** (ADR-073 merge gate,
  1.0-blocking). Every migration test started from an empty database — the one case that cannot
  break an in-flight saga, because there is no row to break. `CommunitySchemaUpgradeTest` parks a
  saga under the `V0.7.0` shape, runs the shipped migration set over it, and reads it back:
  `definition_version` backfills to `VERSION_ABSENT` rather than to the first version, and
  `compensation_step_names` to `NULL` — the two migrations' opposite choices, each asserted. It also
  covers the pre-ledger upgrade path, where a database with no history rows replays every resource
  over schema that already exists, which is what makes the `IF NOT EXISTS` guards load-bearing.


- **The storage subsystem boots, and the operator says which driver** (ADR-056). Two Community blob
  drivers — `blob-fs-community` and `blob-s3-community` — register at the same priority and nothing
  loaded them, so a deployment got whichever it constructed by hand. `StorageBootstrap` in Core now
  selects **by configured id**, `CommunityStorageSubsystem` binds the result into
  `KernelProviders.BLOB_STORAGE_PROVIDER` / `BLOB_STORE`, and the choice is recorded on the
  `eu.exeris.kernel.storage.StorageBootstrapSelected` JFR event.

  This is the one bootstrap that does not rank by `priority()`: the two drivers are not
  interchangeable — one needs a writable directory, the other credentials and an endpoint — so
  ranking would decide where a tenant's objects land by ServiceLoader order.

  **`storage.blob.provider` is both the switch and the selector.** Unset means blob storage is off
  and nothing binds, which is what every deployment to date has been doing; set means the choice has
  been stated. An id matching no driver fails at boot with `EX-BLOB-8008`, carrying the key, the
  value and the ids that were available; a classpath with no driver at all is `EX-BLOB-8007`. Also
  reads `storage.blob.location` (required once storage is on) and
  `storage.blob.maxSignedUrlTtlSeconds`. For the S3 driver, `storage.blob.location` is the
  **endpoint** and `storage.blob.s3.bucket` / `.accessKey` / `.secretKey` (plus optional `.region`
  and `.maxObjectBytes`) are forwarded into the driver's properties. The subsystem declares
  `dependsOn("memory")`, because the S3 store stages transfers through the kernel allocator.


- **`http.maxResponseBodyBytes` — the HTTP client stops borrowing the server's ingress limit**
  ([ADR-071](docs/adr/ADR-071-operational-limit-configuration-path.md) amendment). The client sized
  every response read from `maxRequestBodyBytes`, so one key bounded two unrelated things: what this
  server accepts from callers, and what this application reads back from someone else's server. A
  deployment tightening ingress shrank its outbound client; one loosening it grew the buffer every
  response allocates. Neither name said so.

  Both limits default to **10 MiB**, so an untuned deployment sees no change. The pre-0.12
  constructor shape keeps delegating its single ceiling into both —
  a caller who passed one number for an engine they built themselves is not silently lowered to a
  default they never named, which would turn an upgrade into a run-time truncation. The
  configuration keys resolve independently, because an operator setting an ingress limit said
  something narrower than that.

  `HttpConfig` gains the component appended, with the 14-argument shape retained: the SPI gate
  reports an addition on a `stable` surface, `stable-breaks=0`.

  **`-1` is refused on the response key** — the one limit with no unlimited setting. It used to
  resolve to a 64 KiB ceiling, below the default and the smallest value the range could produce, so
  an operator asking for no limit got the tightest one there is. Refused rather than made unbounded,
  because the ceiling bounds how much a remote peer can make this client allocate: a server may
  accept unbounded requests since it controls its callers, a client is exposed to someone else's
  behaviour. A value past `Integer.MAX_VALUE` is refused too, rather than silently clamped — a
  response is assembled into one buffer. Configs built through the pre-0.12 constructor with an
  unlimited request limit still construct: the server half stays unlimited, the client ceiling takes
  the 10 MiB default.

- **The HTTP client stops paying for the largest response it might ever read.** The response read
  moved into `CommunityHttpClientResponseReader`, which starts at 8 KiB and grows to what
  `Content-Length` declares, so `http.maxResponseBodyBytes` bounds growth instead of sizing every
  allocation. Measured on `MemoryStats.peakAllocatedBytes()` against a 10 MiB ceiling: a `HEAD`
  declaring a 10 MiB body went from **10 493 952 bytes to 8 192**, a 200-byte `GET` from
  **10 494 152 to 8 392**, a 100 KiB `GET` from **10 596 352 to 204 843**. The overrun refusal is
  unchanged — a response past the ceiling is still refused rather than truncated — so an engine
  sized deliberately, as the S3 blob driver is, behaves as before.

  A ceiling near the int range no longer wraps negative: the header allowance pushed it past
  `Integer.MAX_VALUE` and the allocator refused the result, so a deployment that set a large limit
  failed on its first response. Clamping is free now that the value is only a bound.

### Changed

- **The connection cap gets one default, and the question it was left open on could not have been
  answered as posed** ([ADR-081](docs/adr/ADR-081-accept-time-connection-cap.md)).
  `HttpConfig.DEFAULT_MAX_CONNECTIONS` moves from **1 000 to 4 096**, matching the standalone
  carrier's `transport.maxConnections`. It is one field enforced in one place; two defaults for it
  were an accident, and 4 096 is the side the evidence pointed to — the project's own benchmark runs
  had to raise the HTTP value to get through.

  The open question asked whether an accept-time cap is right "as opposed to admitting and shedding
  at request level, **where the response can carry a status**". That alternative does not exist here:
  `StreamLoadShedder` closes the stream, and no server path in the kernel produces a 503 at all. The
  two mechanisms are **layers** — connection slots before any bytes, concurrent stream work after the
  stream exists — so neither substitutes for the other, and a status-bearing refusal would be a third
  mechanism rather than a replacement.

  **Check `ulimit -n` against the new default.** The cap refuses cleanly only while it is the ceiling
  reached first; the common limit of 1024 sits below it. `reference-deployment.md` now carries the
  requirement, and the failure mode it guards against — an `IOException` from `accept()` stopping the
  listener permanently — is recorded as an open ROADMAP entry rather than fixed here, since it is a
  behaviour change needing its own tests.

  Which key governs which carrier is now documented: `http.maxConnections` for the HTTP listener,
  `transport.maxConnections` for the standalone carrier, which is `DISABLED` unless `transport.mode`
  is set. Setting the transport key on an HTTP deployment changes nothing and reports nothing.

### Added

- **`TransportStats.acceptFaults`** — the last open clause of v0.11's accept-fault merge gate. A
  setup fault reached an operator only inside the JFR payload, so the two accept-path failure modes
  were documented together but not *observable* together. It stays deliberately out of
  `totalRejected`, which means work the engine *declined*: a setup that broke declined nothing, and
  folding them removes the distinction between a capacity problem and a defect. Appended rather than
  grouped beside `totalRejected`, with the six-argument constructor retained, so no existing
  positional call changes meaning. `AbstractTransportEngineTck` asserts the separation from the side
  an operator reads — a ceiling refusal must leave the fault count at zero — and the Community driver
  test asserts the positive half after forcing a fault.

- **Two owed test cases from the v0.11 sweep, both mutation-checked rather than merely green.**

  `AbstractJobSchedulerTck` gains the repeating half of the fail-closed refusal: a job submitted
  with no context and a fixed interval must **retire**, not come due every interval forever. The
  behaviour was fixed in Community only, so a second `JobScheduler` provider could reproduce the
  original defect — a failure event per interval on work that can never dispatch — while passing the
  contract suite. Nothing the body can observe separates the two cases (the body never runs either
  way), so the contract is asserted on the handle's state, sampled across several advances because a
  wrongly re-queued job spends nearly all its time waiting rather than failed. Restoring the pre-fix
  settle path reddens exactly this case and no other.

- **`CommunitySeparatedSchemaPoolRecycleIT`** — the integration half of ADR-012's pool-recycle
  isolation. The existing unit test asserts the SQL the interceptor *emits* against a mock; this
  asserts what a live PostgreSQL does with it when one physical connection is handed between
  tenants. The pool is one connection deep on purpose: `persistence.perTenantPooling` defaults to
  `false`, so tenants share connections, and a deeper pool would let the two tenants land on
  separate ones and pass while the republish did nothing. Three cases, each covering a direction the
  others do not — tenant to tenant and back, `current_schema()` following the acquire, and a SHARED
  acquire resetting the path a SEPARATED_SCHEMA acquire left set.

  Not a row-visibility hole, stated precisely: RLS keys on `exeris.tenant_id`, republished on the
  same acquire, so a stale `search_path` misdirects **name resolution** rather than exposing another
  tenant's rows. Two mutations prove the split: removing the RESET arm reddens only the shared case;
  making SEPARATED_SCHEMA stop setting its own path reddens only the other two.

### Changed

- **The GA line no longer builds with `--enable-preview`, in any scope** (ADR-066 Amendment A1).
  The ten remaining fixtures that used `StructuredTaskScope` moved to `TckScope`, and the flag is
  gone from the POMs, the surefire JVMs, the JMH forks, `MAVEN_OPTS` in both workflows, and the SPI
  API-diff tool. No source in this repository uses a preview API.

  **No published artifact changed.** The TCK test-jar — the only one built from test sources —
  already measured 0 preview-stamped classes of 467; the 56 stamped ones sat in core and community
  test-classes, which nothing publishes. What changes is the build: `--enable-preview` is legal
  only when `--release` equals the running JDK, so the repository could be built by JDK 25 and
  nothing else. It now builds on JDK 25 LTS **or newer** — verified by building the full reactor on
  JDK 26, which fails on the unconverted tree with `invalid source release 25 with
  --enable-preview`.

  Three TLS fixtures share the new `BlockingPeerPair`: they drive two blocking OpenSSL peers, which
  must run on platform threads under a deadline, and they report failure in three different
  vocabularies. One security fixture asserted `ScopedValue` inheritance across a fork — a property
  of the preview API rather than of the kernel — and now asserts the explicit carrier rebinding the
  GA line actually offers.

### Fixed

- **Outbound TLS is a decision, not a consequence of crypto booting.** A `CLIENT`-mode transport armed TLS whenever a crypto provider happened to be bound, so a kernel that booted crypto to serve HTTPS could not make a plaintext outbound call at all. `exeris.transport.tls` is the opt-out that was missing, and it covers **server, client and dual**: a listener holding valid certificate and key can now decline TLS, for deployments terminating it at a sidecar. Half-configured material stays a boot failure regardless. A listener that declines while holding material emits `eu.exeris.kernel.transport.TransportTlsDeclined`, because that is the one outcome indistinguishable from any other plaintext socket. Defaults are unchanged.

### Added

- **The PAQS admission ceiling becomes configurable, and the constant beside it is documented as
  deliberately not** (ADR-071 amendment). `transport.paqs.maxActiveStreams` carries the cap on
  concurrently admitted streams on `TransportConfig`; `AdmissionController` enforces the configured
  value instead of a private `5_000`. `-1` (`TransportConfig.UNBOUNDED_ACTIVE_STREAMS`) removes the
  count ceiling — the memory-pressure arbiter still decides every stream, so this is not an
  unguarded engine — and `0` is refused at startup, by the config record and again by the
  controller, because a ceiling of zero admits nothing.

  **Both sites that build a `TransportConfig` read it**: the transport subsystem and the HTTP
  listener's own carrier. A key honoured on one of them is indistinguishable, from the outside, from
  a key that does not work — the failure this ADR exists because of.

  `PaqsScheduler.SPIN_THRESHOLD` did **not** become a key. It is reachable only from `close()` and
  bounds how the shutdown drain spends CPU while waiting, under a drain deadline already documented
  as deliberately fixed; the ROADMAP entry that listed it as an operational limit is corrected
  rather than implemented.

  A caller that passes no ceiling is unaffected: the 8-argument `TransportConfig` constructor
  remains as a bridge applying the same 5 000 the scheduler already enforced, which is also what
  keeps the SPI gate at zero `stable` breaks for a `stable`-classified surface.

  Coverage: `AbstractPaqsSchedulerTck$ConfiguredAdmissionCeiling` (a lowered ceiling bounds
  concurrent service under load and the slots come back; the sentinel admits past the default),
  `TransportConfigActiveStreamsTest`, `AdmissionControllerTest$ConfiguredCeiling`, and one wiring
  test per construction site.

- **A route can declare that it blocks, and stop pinning a pooled connection while it does**
  (ADR-077). `RouteRequirement` gains an execution facet — `PROMPT` (the default) or `LONG_RUNNING`
  via `longRunning()`. On a `LONG_RUNNING` route `CommunityHttpRequestDispatcher` binds no
  `PersistenceSessionBox`, so each persistence call acquires and releases through the engine instead
  of holding one connection across a block whose own work draws from the same pool.

  **The facet names the route, not a connection.** `spi.http` must stay blind to what a driver holds,
  so the Community dispatcher draws the persistence consequence; another driver may draw a different
  one, or none. That is why the enum is `Execution` and not `ConnectionLifetime` — the alternative
  was costed in the ADR and rejected as a Wall breach.

  **Nothing moves for a route that says nothing.** Every existing factory returns `PROMPT`, the two
  scope-free shapes hand back shared constants so the wither stays allocation-free, and
  `persistence.md`'s "One HTTP request is one connection" is narrowed to `PROMPT` routes rather than
  retracted — `CommunityRequestScopeBypassIsolationIT`'s backend-PID assertion is untouched and still
  passes as written.

  Two costs are accepted by name rather than discovered later. A `LONG_RUNNING` handle is **owning**,
  so a missed `close()` is a real pool leak — the rule every non-request path already runs. And a
  declaration can go **stale**: `eu.exeris.kernel.http.RouteExecution` carries the handler duration on
  every such request so the mismatch is detectable, with no threshold baked in, because what counts
  as "too fast to be blocking" is a deployment's judgement. It is the first kernel JFR event to carry
  a request path, which its Javadoc says out loud instead of leaving in a field list.

  Coverage: `AbstractHttpRoutePolicyTck` gains the orthogonality contract (a requirement and its
  `LONG_RUNNING` twin must decide identically on every shape and every principal, plus a non-vacuity
  case so an admit-everything binding cannot pass it); `RouteRequirementTest` pins the carrier;
  `CommunityRouteExecutionBindingTest` pins the dispatcher's decision in the default build; and
  `CommunityRouteExecutionIsolationIT` pins its consequence in backend PIDs against a live pool.
  `RouteExecutionEventTest` runs the mitigation the ADR records as unproven, so a documented signal
  cannot ship without firing.

  All three dispatcher-side suites are mutation-checked, each with the asymmetry that proves the
  branch rather than the plumbing: binding the session unconditionally reddens the `LONG_RUNNING`
  cases while the `PROMPT` ones stay green, and deleting the JFR emit reddens the signal test through
  its *positive control* — the assertion that tells silence from a deaf recording.

- **HTTP/2 stops silently ignoring the header limits an operator configures.** ADR-071 left this as
  its stated out-of-scope tail: `http.maxRequestHeaderCount` / `…Size` are honoured on HTTP/1 and
  the h2 path referenced neither, so a limit could be believed set while it was not. Closing it
  needed no new mechanism — `Http2Settings` already carried `ID_MAX_HEADER_LIST_SIZE` (RFC 9113
  §6.5.2) and the server was sending an **empty** SETTINGS frame, telling a peer nothing at all.
  It now advertises a bound it enforces, and `Http2HeaderBlockAssembler`'s 65 536 becomes the
  documented default of a new `http.maxHeaderBlockSize` key rather than a constant. (Which bound
  is advertised is settled below: not this one.)
  **A separate key, not a product of the other two, and the reason is measured**: those describe a
  per-field size and a field count on HTTP/1, so multiplying them out yields ~800 KiB at the shipped
  defaults — a twelvefold *loosening* of a protective bound, which is the wrong direction for a
  limit whose job is to refuse a header bomb. Protective per ADR-071, so `0` and negatives are
  refused rather than read as unlimited.

  **Three keys, not one, because HTTP/2 bounds three different quantities.** `http.maxHeaderBlockSize`
  is the COMPRESSED block above. `http.maxHeaderListSize` is the CUMULATIVE DECODED field section —
  what RFC 9113 §6.5.2 actually defines SETTINGS_MAX_HEADER_LIST_SIZE against, and what the HPACK
  decoder enforces; it is therefore the value advertised. Advertising the block bound instead would
  hand a peer a number nothing checks, and asymmetrically: lowering the key would merely be
  conservative while raising it would be believed and ignored — and raising it is the only reason to
  touch it. `http.maxStringLiteralSize` is one decoded name or value, checked against the declared
  length before the bytes are read, which is what refuses an oversized allocation up front rather
  than after. Compression is what makes the first two independent; neither can be derived from the
  other. This closes ADR-071's HTTP/2 tail completely — both constants it named by name, plus the
  third bound it did not know it was leaving behind — and the ROADMAP's Operational Limits table
  loses both HTTP/2 rows.

- **`KernelWebClient.withAuthority(String)`** — the typed surface can now name its peer, which is
  what makes ADR-074's "one engine serves many peers" reachable rather than merely decided. Until
  this, every call built through the façade resolved to the engine's configured default, so the
  argument that decided the carrier shape held only at the `HttpClientEngine` level. A derived view
  rather than four addressed overloads: the peer is usually fixed for a run of calls, and the name
  mirrors `HttpRequest.withAuthority` so there is one vocabulary rather than two. It deliberately
  does **not** validate — the shape is checked at `HttpConfig` construction and at send, and a third
  copy of that rule inside a façade would be the fourth hand-synced version of it.
- **A request can name the peer it is sent to** (ADR-074). `HttpRequest` gains a nullable
  `authority`, `HttpConfig` gains `defaultAuthority`, and both keep their previous canonical
  constructor as a bridge — the SPI gate reports `stable-breaks=0` against `v0.11.0`, measured.
  A new `http.client.defaultAuthority` key supplies the peer for requests that name none.

### Fixed

- **The graph churn-to-data TCK measured a coin flip, not a ratio.** `GraphChurnRatioTck` is the
  executable half of graph.md's `< 20x` Community / `< 1x` Enterprise contract. It failed
  intermittently locally and never in CI, and neither fact was evidence about the graph path. Its
  numerator summed `weight` from `jdk.ObjectAllocationSample` — the sampler's extrapolation, which
  arrives in a near-constant ~261 KB quantum — so the number was `quantum ×` *(times the sampler
  happened to land on a kernel object)*, a Poisson count. Its denominator ran 1 000 iterations while
  the workload ran 10 000, and that factor of ten is exactly what scaled one sampler hit to 2.04
  ratio units and left the 20.0 threshold sitting between the ninth hit and the tenth. **The test
  failed when the sampler drew ten**, at an observed mean of ~4.6 — a few percent per run, which is
  all "flakes here, never there" ever was.

  Two further defects meant it could not have measured the contract even correctly summed. The
  numerator was filtered to `eu.exeris.*` types while graph.md attributes the ~15x to the *driver*;
  on a 500-id traversal that filtered signal is **empty** — three consecutive measurements sampled
  zero such events, which the shipped arithmetic reports as a perfect `0.00x`. And a traversal
  returning one id spends ~11.7 KB of allocation on 16 bytes of payload, so the workload measured
  session and protocol setup rather than churn per data byte.

  The numerator is now the exact per-thread allocated-bytes delta, driver included; the denominator
  derives from the iterations actually run and the result-set size actually returned; the traversal
  carries a 500-id fan-out so the fixed per-round-trip cost amortises.

  **What the corrected instrument found on its first honest run is the substantive part.** The
  Community Bolt path has two allocation regimes — ~142 000 bytes/traversal (17.4–18.0x) and
  ~166 000 (20.5–20.8x) — separated by a flat 17% and **fixed per JVM**: three processes ran six
  windows of 300 traversals each and every window in a process stayed in one regime. Roughly two
  runs in seven take the slow one (4 of 14 observed processes), so graph.md's published `< 20x` is met in one regime and breached
  in the other. The TCK's Community bound is therefore **23x as a regression bound**, ~10% above the
  observed slow-regime ceiling and tripped by a mutation adding 128 bytes per returned row (26.6x);
  graph.md's 20x stays the contract and is reported alongside every measurement. Which number is
  honest to publish, and whether the slow regime can be removed, is recorded in the ROADMAP as
  1.0-blocking on the claims side. Separately, graph.md's assertion that the TCK emits
  `EX-GRPH-5005` is corrected: nothing throws `ExcessiveAllocationException` anywhere.

- **The diagnostics CLI died on `listProviders`, and took the caller's session with it.** The
  shipped executable pinned its own Jackson 2 for the NDJSON codec while the kernel it boots uses
  Jackson 3. Those are not independent: both resolve `com.fasterxml.jackson.core:jackson-annotations`,
  of which Maven keeps one version, and the CLI's direct Jackson 2 outranked the kernel's transitive
  Jackson 3 by proximity — pinning annotations to 2.18.7, below the 2.21 `jackson-databind` 3.x
  needs. Every kernel mapper built at runtime then failed on `NoClassDefFoundError: JsonSerializeAs`,
  which killed `listProviders` (the only method that instantiates every provider) and, because the
  dispatch guard caught only `JsonProcessingException`, the process. A consumer that caches the child
  across calls lost every later request too, not just that one. The CLI now uses the kernel's Jackson,
  declared directly so it cannot be outranked again; `handle()` catches `LinkageError` and
  `ServiceConfigurationError` alongside `RuntimeException`, so one broken provider degrades one
  method; and the shaded jar shrinks by 2.4 MB with the second databind gone. Affected 0.10.2 through
  0.12.0-SNAPSHOT — on 0.10.2 masked by the preview-flag failure that ADR-066 closed at 0.11.0.

- **Nothing ever ran the artifact this module ships.** Its only test drove `handle()` against a fake,
  so no kernel booted, no provider class initialiser ran, and the defect above stayed green through
  three published versions. Two live suites now close that: `DiagnosticsCliLiveKernelTest` boots the
  kernel in-process on the resolved classpath (default build), and `DiagnosticsCliShadedJarIT` spawns
  the packaged jar over stdio at `verify` — where shading, the merged `META-INF/services`, and the
  manifest are first observable, and where the distributed artifact's preview-cleanliness (ADR-066)
  becomes an executed claim rather than a scanned one.

- **The HTTP client dialled the address its own server listened on.** Not "single-host", which is
  what every document said: `CommunityHttpClientEngine` has no public constructor, its only
  reachable path took `targetHost` from `HttpConfig.bindHost` — documented as the SERVER/DUAL
  *listener* address — and no client-target key existed anywhere. An application could not address
  the *first* external peer, and `HttpConfig.defaultClient()` (bindHost `null`, port `-1`) produced
  an engine that could not send at all. An unaddressed request is now refused rather than sent
  somewhere the caller never named, and `Host` follows the request's authority instead of
  `TransportConnection.remoteAddress()`, whose SPI contract documents it as an *address*
  (`e.g. 192.168.1.1`) — building the header that selects a name-based virtual host out of an
  address breaks vhosting by construction.
- **The S3 blob-storage driver and the OIDC JWKS fetch both reached their endpoint by the same
  coincidence**, and both now state it. Each built a CLIENT engine with `bindHost` set to the
  address it wanted to dial. `CommunityS3Client`'s own javadoc gave that as the load-bearing reason
  it owns a private engine — "an engine is bound to a single host, so a shared application client
  cannot address the storage endpoint at all" — which ADR-074 retires; the remaining reason,
  head-of-line isolation for large object transfers, stands on its own and is now the one stated.

### Decided

- **ADR-077 — a route declares how it executes, and the dispatcher draws the connection
  consequence.** Closes RFC-2026-08-26 (now ACCEPTED) and the cycle's only 1.0-CRITICAL item.
  `CommunityHttpRequestDispatcher` binds a `PersistenceSessionBox` around every non-streaming
  request, unconditionally and with no configuration key anywhere, so a handler that blocks holds its
  pooled connection across the block while the work it waits on draws from that same pool — the first
  cross-runtime benchmark result where the kernel loses categorically rather than by a margin.

  The decision builds the seam and leaves the default: `RouteRequirement` gains an execution facet,
  `PROMPT` (today's behaviour, the default) or `LONG_RUNNING`. **`spi.http` does not name a
  connection** — the facet describes the route, and the Community dispatcher makes the inference,
  because an HTTP SPI naming a pool concept is a Wall breach. Every existing static factory keeps
  returning `PROMPT`, so a policy implementation that never names execution is unaffected. The
  request-scoped promise in `persistence.md` is narrowed to `PROMPT` routes rather than retracted,
  and `CommunityRequestScopeBypassIsolationIT`'s backend-PID assertion is not edited.

  Two questions the RFC handed the ADR by name are settled. **Release ownership never moves to the
  handle** — a `LONG_RUNNING` route has no box, so `openConnection` returns an owning handle, which
  is not a new model but the one every non-request path already runs, flow threads included. And the
  **default flip is gated on three named artefacts**, not on "a measurement": the acquire-rate
  multiplier inside `[1.0×, 3.17×]` measured as reuses crossing a transaction boundary, the
  interceptor's session-key cost at that rate, and a saga-benchmark re-run with `ConnectionHold`
  enabled so the request-side and flow-side holds are apportioned rather than assumed.

  Re-verifying the RFC's six constraints at their declaration sites produced one correction:
  `NonOwningPersistenceConnection` is a private static nested class inside `PersistenceSessionBox`,
  not a top-level type — nothing outside the box can reference it, which is exactly why release
  ownership cannot migrate to the handle. Decision only; no source touched.

- **Heterogeneous multi-hop graph traversal moves into 1.0 scope; not into 0.12.** The ROADMAP
  entry (surfaced by dogfooding, 2026-07-31) previously read *post-1.0*, on the ground that graph
  the subsystem is in 1.0 while this particular contract widening is surface the scope ruling never
  assessed. Two things that post-date that reading overturn it. The benchmark track hit the same
  wall from the cost side: the graph arm used to run inside the saga scenario, and splitting the
  two left a standalone graph scenario that cannot run yet, because client-side hop composition is
  precisely what it would be measuring. And the corrected churn TCK (above) prices the unit — a
  1-hop traversal returning one id costs ~11.7 KB of allocation for 16 bytes of payload, so
  composing a two-hop query client-side over 500 intermediate nodes costs ~5.8 MB where an
  engine-side path costs ~142 KB. A fortyfold allocation penalty on the canonical graph use case is
  a No-Waste-Compute question, not a scope one. Recorded with the correction that half the original
  ruling's evidence — "GRAPH-111's zero-alloc and churn-ratio TCKs" — included the churn TCK shown
  above to have measured a sampler draw. The RFC that entry requires is still unwritten, so the
  commitment is to the GA cut, not to a named release.

- **ADR-074 — a request names its own peer.** Discharges the one question RFC-2026-06-29 left
  explicitly owed: its split disposition made multi-peer addressing 1.0 scope but fixed *when*, not
  *how*. A code spike moved the problem before the option table was written. Every document here
  calls the client *single-host*; it is narrower. `CommunityHttpClientEngine` has **zero public
  constructors**, its only reachable path takes `targetHost` from `HttpConfig.bindHost` — documented
  as the SERVER/DUAL **listener** address — and no client-target configuration key exists anywhere in
  the tree. The client dials the address its own server listens on, so an application cannot address
  even the *first* external peer. Decision: `HttpRequest` gains a nullable `authority` component with
  the previous canonical constructor retained as a bridge; `Host` and TLS peer verification follow
  the authority rather than the connection; the enricher observes the final authority so an outbound
  credential's audience can bind to the peer it is sent to. Two spike findings decided it against the
  per-host-engine alternative: `send` already opens a fresh connection per call, so there is no pool
  the alternative would preserve, and `HttpClientRequestEnricher.enrich` receives only the request,
  which makes audience binding structurally inexpressible when the peer lives on the engine.
  Implementation follows; this slice is the decision.

### Added

- **The Maven Central publication path exists, is gated, and has produced nothing yet.** Every
  coordinate now carries the metadata Central validates per artifact (`url`, `scm`, `developers`,
  `organization` — none of which existed before), a `release` profile produces sources and javadoc
  jars and GPG-signs every file including the SBOM, and `central-publishing-maven-plugin` uploads
  with `autoPublish=false` so a human reviews the portal's validation before anything becomes
  permanent. SLSA build provenance is attested through Sigstore keyless signing on the release
  workflow's OIDC identity, which answers what a GPG signature does not: which workflow, at which
  commit, produced the file. **No release has been published through it** — the secrets are not set,
  and the first one is manual by design.
- **A release-readiness gate**, `tools/release-readiness/release-readiness.sh`, asserting that every
  coordinate has a pom, jar, sources jar, javadoc jar and SBOM and that each verifies against the
  signing key. It found two real defects on its first run, both of which would have failed the
  upload rather than the build — see below.
- **A digest assertion tying the uploaded artifacts to the gated ones.** Maven re-runs the lifecycle
  up to `deploy` and central-publishing stages its bundle during that phase, so there is no way to
  upload previously built artifacts — the release workflow necessarily builds twice, and without
  this the gate and the provenance attestation would describe a different build than the one Central
  receives. The second build now runs `clean` and every jar, pom and SBOM is asserted byte-identical
  to the gated set.

### Fixed

- **The release build produced no SBOMs at all, and the SBOM gate could not see it.**
  `cyclonedx-maven-plugin`'s `skipNotDeployed` defaults to `true`, and
  `central-publishing-maven-plugin` sets `maven.deploy.skip` because `<extensions>true</extensions>`
  makes it replace the deploy step. So `mvn -P release verify` emitted eleven signed artifacts and
  zero SBOMs, while the CI SBOM gate — which runs the ordinary build, where nothing skips deploy —
  stayed green. Every Central release would have shipped SBOM-less with nothing reporting it.
  `skipNotDeployed` is now `false`, and the release-readiness gate checks the SBOM alongside the
  files Central itself requires.
- **The shaded CLI jar was not reproducible across a second build over a dirty `target/`.** Two
  clean `-P release` builds are byte-identical across all 69 files except the `.asc` signatures,
  which carry a creation timestamp by design. A second build without `clean` re-shades an
  already-shaded jar, and `exeris-kernel-diagnostics-cli` came out different — caught by the digest
  assertion on its first run, which is what that assertion is for.

### Changed

- **The kernel is now Apache License 2.0, unmodified.** The Commons Clause condition is gone,
  so the open kernel is open source by the OSI definition for the first time — the previous
  terms withheld the right to sell the software as a competing product, which is a condition
  Apache-2.0 does not carry and which corporate licence scanners correctly flagged as
  non-permissive. Every source file now carries `SPDX-License-Identifier: Apache-2.0` (1 285
  files) in place of a six-line prose header, and `LICENSING.md` maps the open-core boundary
  for a legal review while `TRADEMARK.md` states what Apache-2.0 §6 explicitly does not grant:
  the name.
- **The Apache 2.0 text itself was wrong, and that is the larger half of this change.** What
  shipped through v0.11.0 as "Apache License Version 2.0" was an abridged paraphrase, not the
  licence. It dropped the definition of "submitted" and the "Not a Contribution" carve-out from
  §1, two sentences from §4(d) including "the contents of the NOTICE file … do not modify the
  License", and the whole "Notwithstanding the above" sentence from §5 — the clause that lets a
  separate contributor agreement coexist with the inbound-equals-outbound default. `LICENSE`
  now matches the canonical text byte for byte, verified against four independent Apache-2.0
  copies rather than against a transcription. The five files that carried it (root plus the
  four module copies) are identical again.
- **The licence header check now covers the module that ships the header.**
  `exeris-kernel-build-config` is parented to the root POM while the check is declared in
  `exeris-kernel-parent`, so its own four sources sat outside the gate — and kept the old
  header through a reformat that rewrote 1 275 files. `tools/jfr-reporter` was outside it too
  and carried no header at all. Both now declare the check themselves, reading the one header
  file rather than a second copy.
- **Two published coordinates declared no licence at all.** `<licenses>` lived in
  `exeris-kernel-parent`, but `exeris-kernel-bom` and `exeris-kernel-build-config` parent to the
  root POM — so their effective POMs carried none, while every other module inherited one
  (`help:effective-pom` resolved 1 for spi, 0 for both of those). The block moves to the root,
  where all ten coordinates inherit it. This is also what Maven Central validates: per artifact,
  not per reactor.

### Added
- **`FlowDefinitionBuilder.version(int)`** (ADR-064 amendment). ADR-064 made `(name, version)` the
  plan's identity and called the version "explicit, application-declared" — but the builder, the only
  supported way to assemble a definition and the one every generated saga uses, had no way to set it.
  Every definition built through the fluent API was version 1. The workaround — build unversioned,
  rebuild the record by hand through the five-argument `FlowDefinition` constructor — worked only by
  side effect, because a definition's transitions are recorded when `build()` runs and read back at
  compile: a hand-built record for a name never built through a builder compiled into a plan with
  steps and **no declared edges**, silently — which is not a stalled saga but a linearised one, since
  a step with no outgoing transition falls back to `index + 1`. A definition whose edges are already
  sequential is unaffected; one that declares a skip or a branch runs a path it never declared. The new method is `default` and throws
  (out-of-tree-implementation compatibility, same constraint as `FlowExecutionPlan.definitionVersion()`);
  unlike that one it refuses rather than returning a default, because silently ignoring a requested
  version would build a v1 definition claiming to be v3. `AbstractFlowDefinitionVersioningTck` now
  assembles every plan through the builder and pins the builder → definition → plan carry.
- **The two PostgreSQL session keys the RLS contract rests on are constants**
  (`ConnectionInterceptor.SESSION_KEY_TENANT_ID`, `SESSION_KEY_SHARED_SCOPE`). The name is a contract
  between code the kernel ships and SQL it does not — the SPI itself says it cannot introspect a
  deployment's policy — so both sides spelled the string by transcription with no compiler across the
  gap. When they disagree the failure is the worst available: zero rows read, every write refused,
  nothing pointing at the five characters responsible. A generator or migration tool can now reference
  them; `RlsConnectionInterceptor` builds its statement from them.
- **Every published coordinate carries a CycloneDX SBOM, and builds are reproducible.** The SBOM is
  attached under classifier `cyclonedx`, so `mvn deploy` publishes it beside the jar with no
  workflow change; the invariant is exceptionless, and the pom-packaged coordinates carry an SBOM
  with an empty component list rather than an exemption. Reproducibility is the half that had to
  come first: an SBOM describing an artifact nobody else can rebuild identically documents a jar
  instead of attesting to one. `project.build.outputTimestamp` alone would not have done it —
  `maven-jar-plugin` carried no version anywhere in the reactor and resolved through Maven's
  super-POM to 3.1.2, which predates reproducible archive support, so the pins are what make the
  property hold rather than hygiene standing next to it. Measured, not assumed: on the previous
  commit two consecutive builds differed in all 9 jars; they are now byte-identical, as are the 11
  SBOMs. A new `Supply-Chain Gate` runs `artifact:check-buildplan` and `tools/sbom-gate` on every
  pull request. Artifact signing and provenance attestation are the next slice and are **not** in
  this one — an SBOM establishes what an artifact contains, not who built it.

### Fixed
- **A malformed HTTP request body is answered `400` again, not `500`** (ADR-036 amendment). Since
  ADR-036 landed, every request body that failed to parse reached the caller as a server error: the
  decoder contract asked drivers to wrap binding exceptions in a `java.*` `RuntimeException` and named
  `IllegalStateException`, which is also what a *missing* decoder raises — so the status mapping the
  same ADR places on the handler ("bad bytes ⇒ 400, no codec ⇒ 5xx") had one type for two meanings and
  could not be performed. A body the decoder cannot bind now surfaces as
  `spi.exceptions.http.RequestBodyDecodeException` (`EX-HTTP-4013`; `rawArgs` carry the target type
  name and body size, never body content); a missing or unresolvable decoder stays
  `IllegalStateException`. The TCK asserts the classification rather than the type family, and the
  wrapping rule now reads `java.*` **or** SPI-owned — driver opacity never required the former.
  Generated handlers need no change: their existing catch order already maps everything that is not an
  `IllegalStateException` to `400`.
- **The RLS policy the kernel publishes now matches the one it tests.** The conforming-policy example
  in `RlsConnectionInterceptor`'s Javadoc — the thing a deployment copies into its own migrations —
  showed `CREATE POLICY` and nothing else, while all three integration tests holding this contract
  issue `ENABLE` **and** `FORCE ROW LEVEL SECURITY` and connect as a non-owner role. PostgreSQL exempts
  a table's owner from its own policies unless the table is forced, so an application connecting as the
  role that owns its tables — the default in every quick-start — got a policy that is enabled, listed in
  `pg_policies`, and never applied: no error, other tenants' rows in every read. The example also
  compared `tenant_id` to `current_setting(...)` without stating that it assumes a `TEXT` column; a
  deployment casting to `uuid` needs the empty-string guard on the tenant arm too, since the interceptor
  publishes the key unconditionally. Both are now stated. `docs/subsystems/persistence.md` carries the
  enforcement requirement.
- **Two versions of one flow definition no longer consume each other's declared edges.** The handover
  from `FlowDefinitionBuilder.build()` to `compile()` was keyed by definition *name* while the plan
  catalog is keyed by `(name, version)`, so building two versions before compiling either — the shape
  ADR-064 coexistence asks for, and one only reachable once the builder could express a version —
  left the second plan with no declared edges. Not a stalled saga: a step with no outgoing transition
  falls back to `index + 1`, so a sequential definition is unaffected and one declaring a skip or a
  branch silently runs a path it never declared. Keyed by `(name, version)` now, pinned by a TCK case
  whose declared edge skips a step, because a sequential one cannot observe the loss.

### Fixed

- **`transport.idleTimeoutMillis` reclaims idle connections, which it had never done.** The key was
  read by both config resolvers, validated against `>= 0`, carried into `HttpConfig`, copied into
  `TransportConfig` and printed by `toString()` — and compared to nothing. No code outside
  construction read `TransportConfig.idleTimeoutMillis()`, so no connection was ever closed for
  idleness at any setting, while `HttpConfig`'s javadoc documented the semantics of the limit in
  detail (*"0 = no timeout"*). A carried knob is worse than a missing one: the operator sets it,
  sees it echoed back, and diagnoses the resulting connection leak against `maxConnections`, which
  is enforced.

  `NativeTcpIdleReaper` now sweeps each reactor's own selector keys after dispatch — one instance
  per reactor, no timer thread, no scheduled task, no shared state — closing streams whose last
  read or queued write is older than the configured span. The sweep is gated to `idleTimeout / 4`
  clamped to [250 ms, 5 s], the same cadence shape `CommunityTenantPoolRegistry` uses and for the
  same reason: a reactor returns from `select` thousands of times a second under load, and an
  ungated O(keys) walk would put a scan of every connection on the hot path to enforce a limit
  measured in seconds.

  **What counts as activity is asymmetric between the directions, deliberately.** On ingress only
  an attempted `read()` counts, never bytes merely arriving — which is what makes the same stamp
  bound a slow-loris hold, since dribbling a byte a second is precisely what defeats a timeout that
  trusts arrival. On egress both the attempted write and bytes actually leaving the socket count,
  so a large response draining to a slow client keeps its own connection alive instead of being cut
  off mid-transfer. The first cut of this had the egress half wrong in a way worth recording,
  because it is this entry's own subject one layer down: `write()` reaches `queueWrite` **only on
  the TLS branch**, so on a plaintext stream — `CommunityHttpExchange`'s path, and the default one —
  answering a request moved bytes without counting as activity, and idle reclamation silently
  became a property of whether TLS was configured. Found in review of the PR that fixes it.

  Teardown reuses `closeKeyStream` and is abortive, for the reason that method's contract already
  states: a graceful close waits on queued egress, and a peer quiet enough to be reclaimed may
  never drain it.

  `0` still means no timeout (ADR-071's capacity/timeout class), and negatives are still refused by
  `HttpConfig`. New JFR event `eu.exeris.kernel.transport.CommunityConnectionIdleTimeout` carries
  the observed idle span *and* the configured limit, because the ratio is what tells an operator
  whether a fleet is being trimmed on a threshold or is reclaiming dead peers.

  Coverage: `NativeTcpIdleTimeoutTest` — reclamation with the event asserted, `0` reclaiming
  nothing, **a connection reading every 166 ms under a 500 ms timeout surviving**, and **a
  connection the server only writes to surviving**. The last two are what make the first two mean
  anything: a carrier reclaiming every connection on a timer passes reclamation-and-disabled
  identically, and the read-driven case alone leaves the entire egress half of the contract
  unexercised by data movement — which is exactly how the plaintext `write()` gap survived the
  first cut of this change.

### Fixed — verification

- **Four carrier-level TLS tests had never run, anywhere, and reported success by skipping.**
  `NativeTcpClientServerE2e{,MultiReactor2,MultiReactor4}IntegrationTest` read their server
  certificate from `../native-libs/certs/server.{crt,key}` behind an `assumeTrue`. That directory is
  **in no commit, no `.gitignore`, no script, no document and no workflow** — it was one developer's
  local path that never entered the repository, so the assumption never held for anyone. The classes
  carry no `@Tag`, so they run in the default `mvn clean install`: every build executed them, every
  build skipped them, and every build reported green. A skip is the one outcome that reads like a
  pass in a summary line.

  The material is now generated per run by `TlsTestCertificate` (BouncyCastle `bcpkix-jdk18on`,
  **test scope only**, pinned to the `bcprov` version already managed by the BOM) into a JUnit
  `@TempDir`. Generating beats committing on two counts: a checked-in `PRIVATE KEY` block trips
  secret scanners for no benefit, and a checked-in certificate expires — turning a passing suite
  into a dated time bomb. The seven tests in those three classes now run with **zero skips**.

- **The established-callback one-shot is guarded for the first time**
  (`NativeTcpTlsEstablishedOnceIntegrationTest`). `NativeTcpStream.fireEstablishedOnce()` is
  CAS-guarded, and the guard was untested because of a path asymmetry: on plaintext the method is
  reached from `markRegistrationReady()`, once per connection, so the CAS is unreachable a second
  time; on TLS it is reached from `readTlsIngressFromFd()`, which the reactor calls per drained
  record. `NativeTcpCarrierIngressIntegrationTest` asserts "exactly once" but runs plaintext, so
  **deleting the CAS leaves that entire class green** — the assertion could not fail. The new test
  drives spaced TLS records and reddens under the same mutation with `expected: 1 but was: 3`,
  which is one connection announcing itself three times to a `ConnectionHandler`.
### Changed

- **Two operational knobs stop being reachable only by `-D`, and two are refused with a reason**
  (T1-4, ADR-071). `transport.socket.backend` and `memory.jfr.sampleEvery` now resolve through
  `ConfigProvider` first, falling back to the system properties that were the published surface —
  `-Dexeris.community.transport.socket.backend` (with its two env aliases) and
  `-Dexeris.community.memory.jfr.sampleEvery` — so nothing that worked stops working. What changes
  is that the keys are reachable from a config file and the environment at all, and that they
  appear in `config.md`, which had never listed either. Both objects are built inside the boot
  scope, so `CURRENT_CONFIG` is bound where they read it; a driver constructed outside a boot
  falls through to the property exactly as before.

  **`transport.maxTlsRecordsPerRead` and `transport.queueBackpressureEnabled` are not promoted, and
  the reason is lifecycle rather than taste.** They are `static final` fields on `NativeTcpCarrier`
  and `NativeTcpStream`, resolved when the class loads — before any `ConfigProvider` exists, and
  once per JVM for whatever touches the class first. Reading the provider at class initialisation
  would not fix that; it would freeze whatever happened to be bound at that moment, which is worse
  than an honest `-D` because it looks configurable and is not. Doing it properly means moving them
  to instance state on the ingress path, which is a hot-path change owing a measurement. They join
  `http.stream.creditWindowBytes` and `transport.acceptedSendBufferBytes` in the documented
  direct-`-D` category instead, where their siblings already were.

  Recorded while cataloguing them: with `queueBackpressureEnabled` at its default `false` the TLS
  ingress queue is **count-unbounded**, not merely large. That is defensible rather than a defect —
  every entry is an off-heap loan the watermark arbiter accounts for, so PAQS still sheds under
  memory pressure — but the default deserves to be stated rather than discovered.

  Coverage: `CommunityMemoryJfrSamplingConfigTest` (provider wins over `-D`, absent key falls
  through to `-D`, neither set yields the default, and no provider bound still resolves `-D`) and
  three cases in `NativeTcpCarrierBackendSelectionTest`. Both promotions are mutation-checked: with
  the provider read disabled, exactly the provider-dependent cases redden and the fallback cases
  stay green.
### Added

- **The SQL translation cache gets a bound an operator can raise, and the other three constants get
  reasons** (T1-4 close). `persistence.sqlTranslationCacheMaxEntries` replaces a private `1024` in
  `JdbcPersistenceConnection`. The limit matters more than its size suggests, because **the cache
  never evicts**: past the bound an application retains the earliest statements it happened to see
  rather than the hottest, and re-translates everything else on every call. `0` disables caching —
  coherent for a workload with unbounded statement variety — and a negative value is refused rather
  than replaced by the default, which would leave a misconfigured deployment believing it had set
  something.

- **`eu.exeris.kernel.flow.ProgressDisabled`** — `FlowProgressPublisher` probes a bounded window of
  hash-derived event ordinals and, when every candidate collides, disables `FlowProgress`
  publication for the life of the process. Nothing recorded that: `publishProgress` afterwards
  returns on a cached sentinel, so a subscriber never receives anything, which is indistinguishable
  from a system in which no flow ever terminated. Emitted once, at the transition, not per call.

### Decided

- **Three of the four catalogued "hardcoded limits" did not become keys, and the reasons differ.**
  `DEFAULT_NETWORK_OFF_HEAP_THRESHOLD` needs no key because one exists —
  `MemoryProviderConfig.networkOffHeapThreshold` is an SPI record component that the Community
  allocator *refuses* any non-default value for, explicitly, and reads nowhere at runtime; a new key
  would publish a setting the driver declines. `FLOW_PROGRESS_ORDINAL_PROBE_LIMIT` bounds a
  collision probe an operator has no basis for sizing — its defect was silence, now fixed above.
  `MAX_RECLAIM_CADENCE_MS` clamps a *derived* value (`tenantIdleTtl / 4`) whose input is already
  configurable; a knob on the output would let the two disagree. With `PaqsScheduler.SPIN_THRESHOLD`
  from #372, that is **three of twelve catalogued items that were not what the catalogue said** —
  recorded because the pattern matters more than any one of them.

### Security

- **The four dependencies carrying published advisories are bumped, and one of them was not
  four packages.** `jackson.version` 3.1.1 → 3.2.2, `postgresql.version` 42.7.11 → 42.7.13, and
  `lz4.version` 1.10.2 → 1.11.2 in `exeris-kernel-bom`, which is what fixes them for every
  distributed coordinate; `tools/jfr-reporter` moves 2.18.7 → 2.22.2 on its own pinned Jackson 2.
  The lz4 pin is not decorative — `at.yawk.lz4:lz4-java` reaches the tree only as a runtime
  transitive of `kafka-clients`, and it is this BOM entry that fixed it at the vulnerable version.

  **The alert count describes `main`, not this line.** Dependabot scans the default branch, which
  still carries 0.11.0, so seven of the alerts it reports are against a
  `exeris-kernel-diagnostics-cli` that no longer exists as described: the CLI's Jackson 2 was
  removed on this branch when the module moved to the kernel's Jackson 3. Those seven close on
  release integration, with no bump owed. What was actually open here is the BOM and the reporter.

- **`tools/jfr-reporter` stays on Jackson 2, deliberately.** The case for finishing the migration
  was that the distribution should not carry two Jackson generations — and it no longer does: the
  reporter has its own POM, inherits neither the parent nor the BOM, is outside the reactor, and is
  never deployed. It is built and executed only by the JFR job in CI. Rewriting a report writer
  whose output feeds that job, to remove a dependency no consumer resolves, buys nothing a version
  bump does not. Verified by running the shaded jar against a real recording rather than by
  building it: 180 allocation events through the streaming generator, all five JSON artefacts
  written, exit 0 — the check the CLI regression taught, where a jar that assembled was executed
  nowhere.

## [0.11.0] — 2026-08-11

### Added
- **The distributed artifact no longer requires `--enable-preview`, and baselines on JDK 25 LTS**
  (ADR-066). `--enable-preview` is a whole-compilation and whole-JVM flag whose bytecode is stamped
  and major-pinned, so a published artifact carrying it forces every consumer's entire build under the
  same flag and the same exact JDK. As of 0.11.0: 2 286 classes of ours across the eight published modules, class-file major 69, zero preview-stamped — and 15 185 classes scanned in total, because the gate checks the dependencies the diagnostics CLI shades in as well: a vendored stamped class breaks a consumer exactly as one of ours would. Enforced by a CI gate that reads the published
  JARS rather than the sources, scoped to the reactor's declared modules so a partial build fails
  instead of passing on whatever is on disk (`tools/preview-bytecode-scan/`). The JDK baseline moves 26 → 25, which is a widening for
  consumers. A second artifact, `0.11.0-preview`, carries `StructuredTaskScope` on the newest JDK for
  JVM-controlled deployments.
- **Flow resume binds to identity, not position** — `FlowSnapshot` gains three components across v0.11:
  `currentStepName` (ADR-062), `definitionVersion` (ADR-064) and `compensationStepNames` (ADR-064
  amendment A5). Each replaces an inference the runtime used to make from a bare index: which step a
  saga parked at, which definition version it parked under, and which steps its compensation stack
  would roll back. All three fail closed on resume when absent or contradicted, and the 0.10.0
  constructor descriptor is retained as an overload so code compiled against it still builds. A
  parallel `FlowMigrationState` component makes a migration transform declare — and the runtime
  validate — the identities behind any stack it renumbers. Durable stores gain `step_name`,
  `definition_version` and `compensation_step_names` columns (`V0.11.0`–`V0.11.2`).
- **SPI compatibility gate** — `tools/spi-api-diff/` compiles `exeris-kernel-spi` at any two revisions
  straight from git (the module depends only on `java.*`/`jdk.*`, so every revision in history builds
  with nothing but a JDK) and diffs them with japicmp, classifying each finding by the maturity label
  declared in `docs/stability-matrix.md`. An incompatible change to a surface declared `stable` fails
  CI (`spi-compatibility-gate` job); `preview`/`experimental` changes are reported, not gated. The
  gate asks that in **both** senses — binary and source — because they disagree on the change a
  stability promise most needs to catch: adding an abstract method to an interface is binary
  compatible (an existing implementor's class file still links, then fails at invoke time with
  `AbstractMethodError`), so a binary-only check speaks to callers and says nothing to implementors.
  `FlowExecutionPlan.definitionVersion()` landed abstract on a `stable` surface during this milestone
  and the binary-only gate reported it green.
  A second check fails the build when any SPI class resolves to no maturity label — which is how
  `spi.scheduling` and `spi.storage.blob` were found missing from the matrix, and then 25
  unclassified `eu.exeris.kernel.spi.http` classes (including the `HttpRequest` / `HttpResponse` /
  `HttpStatus` carriers the `stable` engine contracts are written in terms of) once the check was
  tightened from package to class granularity (ADR-065).
- **Generated compatibility record** — `docs/release/spi-api-history.md`: one row per release
  transition from 0.5.0 to 0.10.2, produced by the gate rather than by review. No `stable` surface has
  taken a binary-incompatible change since the stability matrix was first published in v0.9.0.
- **Upgrade guide 0.5.x → 0.10.x** — `docs/release/upgrade-0.5-to-0.10.md`, covering the three
  breaking transitions in that range and what to write instead.
- **Reconstructed v0.6.0 release notes** — `docs/release/v0.6.0-release-notes.md`. v0.6.0 shipped
  without notes and predates this changelog, while carrying the only pre-declaration removal of
  `stable`-surface SPI types; the record is now closed.

### Fixed

Found by a pre-release review of the cut itself. Grouped because they share one shape: in most
cases the code was right and the thing that should have caught it could not see it.

- **Two off-heap refcount leaks in the in-memory event bus.** A `Throwable` escaping a handler left
  every unreached wrapper open in `publishAndAwait`, and the identical defect sat five lines away in
  `publish`, where a failing `retain()` or a thread that cannot start stranded every ref taken so far.
- **A settled job kept the application's body and the submitter's identity**, once per job the
  scheduler had ever run, for the scheduler's whole life. A *repeating* job refused for having no
  captured context also never retired — and since the context is captured once at submission, it was
  re-refused on every interval forever.
- **A failed standalone write poisoned its pooled connection.** With the pool's `autoCommit=false`
  baseline every statement opens a real transaction; there was no rollback counterpart on the failure
  path, so the connection returned to the pool inside an aborted transaction and the next request to
  receive it died on its first statement. An RLS `WITH CHECK` rejection — the security control working
  as designed — reached this the ordinary way.
- **Streaming routes were not authorized.** The stream branch returned before the request dispatcher
  was reached, so a bound route policy and the security interceptor were both skipped and an SSE
  handler ran with no principal bound. See the security section below.
- **The blob store's private files were reachable as objects**, because staging and sidecar files were
  named by appending a suffix to the object's own path — endings a tenant may legitimately use.
- **A migrated saga's first checkpoint lost the optimistic-lock race**, arriving one version behind
  the row the migration had just written.
- **Four drain defects**, from an engine that could not be stopped to a request dropped in silence.
- **Signed-URL validity below one second** was rejected by one driver and silently mishandled by
  another; the floor now lives in the SPI and is anchored in the TCK.

### Fixed — verification

The review's recurring finding was not in the code:

- **The preview-bytecode gate could report success on work it had not done.** It scanned a directory
  glob, and `exeris-kernel-tck` has no `src/main` at all — so 55 of its classes shipped
  preview-stamped for the whole milestone that advertised the opposite, invisible by construction. It
  now reads the published jars, scoped to the reactor's declared modules.
- **The flow-versioning TCK covered migration exhaustively while every case passed against a real
  bug**, because `FlowSnapshot` lets an in-memory store ignore `schemaVersion` and every binding's
  store does. A version-enforcing store now ships in the TCK so bindings inherit the instrument.
- **The security-interceptor TCK's propagation cases asserted a property of `ScopedValue`, not of the
  kernel** — a `ThreadLocal`-based implementation would have certified green. They now assert what
  separates the two: the binding is gone once the request is.

### Changed
- **Stability matrix** — added the missing `…spi.scheduling` and `…spi.storage.blob` rows (both
  `preview`, since 0.11.0, ADR-057 / ADR-056); the `…spi.http` per-surface breakdown is now
  **exhaustive** (every class in the package appears in exactly one row), which added rows for two
  surfaces it had never described — client retry (ADR-045) and route authorization (ADR-061); and
  the pre-1.0 framing now distinguishes "no consumer under a support contract" from "no consumers",
  and points at the generated record.

- **`EventBus.publishAndAwait` runs handlers on the calling thread**, in subscription order (ADR-066).
  Handler durations now sum rather than overlap, and a slow handler delays its successors; `publish`
  is unchanged. This is what preserves the path's `ScopedValue` contract without a preview API — a
  handler observes every value the publisher bound, including ones the kernel cannot name, which no
  fork-based GA mechanism can deliver.
- **Subsystem start runs on the booting thread** in dependency-safe rounds, so a phase takes the sum
  of its subsystems' start times rather than the longest — once per JVM, and `FOUNDATION` was already
  sequential. Forking with a rebuilt kernel carrier was implemented and booted the HTTP subsystem with
  no handler bound, because `HTTP_SERVER_HANDLER` is bound by the application around `boot()`.
- **A failed subsystem in a parallel phase now throws `BootstrapException`** instead of escaping as
  the unchecked preview type `StructuredTaskScope.FailedException` — which had also made the
  orchestrator's own failure-collection path unreachable. Callers catching that preview type should
  remove it.

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
