# Exeris Kernel — SPI Stability Matrix

**Status:** Tracked / consumer-facing
**Applies to:** open-core kernel (`exeris-kernel-spi` surface)
**First published:** v0.9.0
**Roadmap source:** `docs/ROADMAP.md` §"SPI Stability Declaration"

---

## What this document is

This matrix is the **single authoritative declaration** of which kernel SPI surfaces are
`stable`, which are `preview`, and which are `experimental`. It is a **forward-looking signal
for consumers** — primarily `exeris-ai-bridge`, downstream host runtimes, and provider
implementors — telling them how much they can lean on each contract today and what the
compatibility intent is for v1.0.

It is **not** a present-day semver guarantee. Exeris is pre-1.0 / TRL-3, and per `CHANGELOG.md`
minor versions may still carry observable contract additions. The maturity labels below describe
**stability intent**, not a binding patch-line promise. See [Semver policy](#semver-policy) for
exactly what each label commits to.

Intent alone is not checkable, so it is paired with evidence: every release transition is diffed at
the bytecode level and published in [`release/spi-api-history.md`](./release/spi-api-history.md), and
an incompatible change to a surface declared `stable` fails CI ([ADR-065](./adr/ADR-065-spi-compatibility-gate.md)).
The gate asks that in both senses — binary *and* source. They diverge on the change a stability
promise most needs to catch: adding an abstract method to an interface is binary-compatible, since an
existing implementor's class file still links and only fails at invoke time with `AbstractMethodError`.
Checking binary compatibility alone speaks to callers and says nothing to implementors.
Consumers upgrading across several minors should start from
[`release/upgrade-0.5-to-0.10.md`](./release/upgrade-0.5-to-0.10.md).

> **One namespace, one source of truth.** When a subsystem or module doc says a surface is
> `stable` / `preview` / `experimental`, it MUST match this table. If they ever disagree,
> this table wins and the drifting doc is the bug.

---

## Maturity levels

| Level | Meaning | Compatibility intent |
|---|---|---|
| **stable** | Contract shape is settled; covered by an accepted ADR and executable TCK. Safe to build on. | Semver-binding from v1.0. Breaking change requires a major bump + deprecation window. |
| **preview** | Shape is largely settled but a known, scheduled change is still in flight (a sprint or ADR that will touch the contract). Build on it, but pin and watch the changelog. | Semver-binding from a follow-up version (typically the version that promotes it to `stable`). Breaking change is allowed pre-stable. |
| **experimental** | Seam exists but the shape is not yet exercised end-to-end or has no committed consumer. | No guarantees in any version. Use at your own risk; expect churn. |

A `mixed` package contains surfaces at more than one level — see the per-surface rows.

---

## SPI surface matrix

Package root: `eu.exeris.kernel.spi.*`. "TCK" lists the `Abstract*Tck` contract suites in
`exeris-kernel-tck` that pin observable behavior. "Enterprise overlay" notes whether a
higher-priority Enterprise provider is expected (cross-repo, `exeris-kernel-enterprise`);
this is informational and **not** a dependency of the open-core surface.

| SPI package | Level | Since | Anchor ADR | TCK coverage | Enterprise overlay |
|---|---|---|---|---|---|
| `…spi.diagnostics` | **stable** | 0.9.0 | ADR-033 | `AbstractKernelDiagnosticsTck` (+ JSON schema fixture) | `KernelDiagnosticsProvider` priority=100 (follow-up) |
| `…spi.persistence` | **stable** | 0.5.0 | ADR-022, ADR-080 [^rowcursor] | `AbstractPersistenceProviderTck`, `…EngineTck`, `…OutboxGuaranteeTck`, `AbstractRowCursorTypeSetTck`, +6 | yes (slab/FFM tier) |
| `…spi.flow` | **stable** | 0.5.0 | ADR-013 | `AbstractFlowEngineTck`, `…SagaRecoveryTck`, `…IdempotencyGuardTck`, +3 | v0.11 record-component note below |

> **`spi.flow` in v0.11 — `FlowSnapshot` gains three record components.** `currentStepName` (ADR-062), `definitionVersion` (ADR-064) and `compensationStepNames` (ADR-064 amendment A5) move the canonical constructor from eleven parameters to fourteen. The 0.10.0 constructor descriptor is **restored as an overload**, so code compiled against 0.10.0 still constructs snapshots — and all three new components default to their fail-closed sentinels (`Optional.empty()`, `VERSION_ABSENT`, an empty identity array), so the bridge buys compilation and never a bypass of the resume guards those decisions added (`AbstractFlowDefinitionVersioningTck$StabilityCompatibility` asserts both halves). Because 0.11 is unreleased, the intermediate twelve- and thirteen-parameter descriptors never shipped, so no further bridge is owed — one overload covers the whole milestone.
>
> `FlowMigrationState` also gains a component in A5 and deliberately gets **no** overload: the record is `@since 0.11` and unreleased, so nothing compiled against a released artifact constructs it, and a bridge defaulting the identities to absent would hand a migration transform the one input the new guard exists to refuse.
>
> What the overload cannot restore, stated rather than glossed: adding a component changes the record's **component list**. Record deconstruction patterns and reflection over `RecordComponent[]` observe a different shape, and no overload can hide that — it is irreducible for a record.
>
> **No automated gate reports this.** A binary-compatibility diff (japicmp) finds nothing, because nothing was removed: the 0.10.0 constructor descriptor is still present and the new components only add accessors. A source diff of the record declaration does show the components arriving, but not that anything downstream depends on their number or order. So this row is the record — not a redundant human note ahead of a machine that would have caught it anyway.
>
> The same asymmetry governs `FlowExecutionPlanFactory.registerMigration`, added to this package in v0.11: an abstract method on an interface is *binary*-compatible (implementors link until it is invoked) and *source*-incompatible. It ships with a refusing default so implementors keep compiling — again a change no binary gate would have flagged.
> 
> **`spi.flow` in v0.12 — `FlowDefinitionBuilder.version(int)`.** Same shape, same reason: a `default` method rather than an abstract one, so out-of-tree builders keep both linking and compiling. The default *throws* instead of returning a value, which is the deliberate difference from `registerMigration`'s and `FlowExecutionPlan.definitionVersion()`'s: a builder that silently ignored a requested version would produce a v1 definition claiming to be v3 — the exact confusion ADR-064 exists to prevent. Additive in both senses the gate asks, so it passes clean; the note is here because the *behaviour* of the default is the contract, and no diff reports that.
| `…spi.memory` | **stable** | 0.5.0 | — (foundational) | `AbstractMemoryAllocatorTck`, `…LoanedBufferTck`, `…MemoryGovernorTck`, +5 | yes (slab pools) |
| `…spi.transport` | **stable** | 0.5.0 | — (foundational) | `AbstractTransportProviderTck`, `…EngineTck`, `…StreamTck`, `…ConnectionTck` | yes (`io_uring`/QUIC) |
| `…spi.exceptions`² | **stable** | 0.5.0 | — (Glass-Box contract); ADR-083 (fault origin) | `AbstractDisclosureModeTck` (+ `…GlassBoxTckTest` in TCK, incl. `$FaultOriginContract`) | — |
| `…spi.telemetry` | **stable** | 0.5.0 | — (Glass-Box contract) | `AbstractTelemetryProviderTck`, `…SinkTck`, `…RingBufferTck`, `…JfrTelemetrySinkTck` | yes (binary glass-box sink) |
| `…spi.bootstrap` | **stable** | 0.5.0 | ADR-007 | `AbstractBootstrapOrchestratorTck`, `…SubsystemLifecycleTck`, `…FailurePolicyTck`, +5 | — |
| `…spi.context` | **stable** | 0.5.0 | ADR-007 (ScopedValue propagation) | exercised via bootstrap/diagnostics TCKs | — |
| `…spi.config` | **stable**¹ | 0.5.0 | — | `AbstractConfigProviderTck`, `…DynamicConfigRegistryTck` | — |
| `…spi.events` | **preview** | 0.5.0 | — | `AbstractEventBusTck`, `…EventLoopTck`, `…KafkaEventEngineTck`, +5 | — |
| `…spi.graph` | **preview** | 0.5.0 | — | `AbstractGraphProviderTck`, `…GraphEngineTck`, `…GraphDialectTck`, +3 | — |
| `…spi.security` | **preview** | 0.5.0 | ADR-014 (RBAC) | `AbstractSecurityProviderTck`, `…RequiresRoleTck`, `…CitadelGuardTck`, +6 | — |
| `…spi.security.identity` | **preview** | 0.10.0 | ADR-040 | `AbstractIdentityProviderTck` | — |
| `…spi.crypto` | **preview** | 0.5.0 | ADR-008 (TLS engine) | `AbstractCryptoEngineTck` | yes (FFM crypto) |
| `…spi.scheduling` | **preview** | 0.11.0 | ADR-057 | `AbstractJobSchedulerTck` | — |
| `…spi.storage.blob` | **preview** | 0.11.0 | ADR-056 | `AbstractBlobStorageTck` | — |
| `…spi.time` | **preview** | 0.12.0 | ADR-082 | — (no provider contract; `TimeSource` is bound, not discovered) | — |
| `…spi.websocket`³ | **preview** | 0.12.0 | ADR-084 | `AbstractWebSocketExchangeTck` | — |
| `…spi.http` | **mixed** | 0.5.0 | ADR-009 / ADR-032 / ADR-034 / ADR-043 | see per-surface rows below | yes (HTTP/3 path) |
| `…spi.util` | _internal_ | 0.5.0 | — | — | — |

² `exceptions`: ADR-083 (0.12.0) added `FaultOrigin` and a non-final `ExerisKernelException.faultOrigin()`
to this `stable` surface. Both are **additive**: the method carries a default, so every existing
subclass compiles and behaves exactly as before, and the compatibility gate reports
**`stable-breaks=0` / `stable-src-breaks=0`** against `v0.11.0`. The residual risk the gate cannot
see is source-level and out-of-tree, in the same way ADR-074's was: an out-of-repo subclass that
already declares its own `faultOrigin()` with an incompatible return type would stop compiling.
Nothing in this repository does — checked, not assumed. The Glass-Box contract itself (error code,
`rawArgs`, disclosure) is unchanged and remains ADR-less by design.

³ `websocket`: `preview` for a stated reason rather than a default one. The merge gate is a
TCK and a binding, and **a contract test proves a shape is honoured, not that it survives** — for a
long-lived duplex protocol that is exactly where the two diverge. Promotion to `stable` is gated on
benchmark evidence (concurrent connections, frame throughput, backpressure under a slow reader,
teardown of a dead peer), not on the TCK going green (ADR-084 §10). Its first-party consumers —
Platform LSP and Studio — are therefore building on a surface declared to move, which is recorded
rather than left to be discovered.

¹ `config`: `ConfigProvider` / `KernelProfile` / `Dynamic` are mature 0.5.0 contracts and treated
as `stable`. The `@Immutable` annotation + watcher-refusal semantics (since 0.9.0, v0.9 Sprint 5) are
**additive** and classified `preview` — enforced by `ImmutableConfigProcessor` (compile-time) and
`DynamicConfigFileWatcher` (runtime `EX-CFG-1004` refusal), pending a dedicated `AbstractConfigProviderTck` binding.

### v0.12 re-assessment: `…spi.scheduling` and `…spi.storage.blob`

Both were re-read at the v0.12 cut, because both gained bootstrap wiring during the milestone and the
question was whether that graduates them. **It does not, and the reason matters more than the answer:**
this table's `preview` definition turns on *"a known, scheduled change still in flight"*, not on how
completely a surface is wired. Bootstrap wiring is delivery, not contract movement.

What the re-assessment did find is that neither surface has such a change named anywhere:

| | anchor ADR | executable TCK | scheduled contract change on record |
|---|---|---|---|
| `…spi.scheduling` | ADR-057 | `AbstractJobSchedulerTck`, 20 cases across 4 groups | none named |
| `…spi.storage.blob` | ADR-056 | `AbstractBlobStorageTck` | none named — provider selection closed in v0.12 |

Under this table's own definition of `stable` — an accepted ADR **and** an executable TCK — both
therefore qualify on the stated criteria while carrying the `preview` label. That is a discrepancy
between the criteria and the labels, and it is deliberately **left standing here rather than resolved
by editing a cell.** Promotion is semver-binding, so it belongs to the decision that owns the
question: [`RFC-2026-09-02`](rfc/RFC-2026-09-02-preview-spi-promotion.md). Recorded at the cut so the
next reader inherits the finding instead of rediscovering it.

### `…spi.http` per-surface breakdown

> **ADR-074 added a component to two `stable` records without breaking either, and it was measured
> rather than asserted.** `HttpRequest` gained `authority` and `HttpConfig` gained
> `defaultAuthority`; both retain their previous canonical constructor as a bridge, so the SPI
> compatibility gate reports **`stable-breaks=0` / `stable-src-breaks=0`** against `v0.11.0`.
> The residual risk the gate cannot see is source-level and out-of-tree: a record **deconstruction
> pattern** (`case HttpRequest(m, p, v, h, b)`) or a `HttpRequest::new` canonical-constructor
> reference would not compile. Neither appears anywhere in this repository — checked, not assumed.

Because this package is `mixed`, the breakdown is **exhaustive**: every class in
`eu.exeris.kernel.spi.http` appears in exactly one row. A class named in no row would be neither
gated nor reported by the compatibility gate, so completeness here is enforced, not aspirational —
`spi-api-diff.sh --verify-surfaces` checks per fully-qualified class name.

| HTTP surface | Level | Since | Anchor ADR | TCK |
|---|---|---|---|---|
| `HttpClientEngine`, `HttpServerEngine`, `HttpProvider`, `HttpExchange`, `HttpHandler` | **stable** | 0.5.0 | ADR-009 | `AbstractHttpClientEngineTck`, `…HttpServerEngineTck`, `…HttpProviderTck`, `…HttpExchangeTck`, `…HttpHandlerTck` |
| Request/response carriers: `HttpRequest`, `HttpResponse`, `HttpTypedResponse`, `HttpStatus`, `HttpMethod`, `HttpVersion`, `HttpHeader` | **stable** | 0.5.0 | ADR-009 | exercised through the engine/exchange/handler TCKs above |
| ↳ `HttpRequest.authority()` / `HttpConfig.defaultAuthority()` / `HttpClientEngine.defaultAuthority()` | **stable** | 0.12.0 | ADR-074 | `AbstractHttpClientEngineTck$PeerAddressing`, `AbstractHttpProviderLoopbackTck`, `KernelWebClientRetryTest#enricherObservesTheResolvedAuthority` |
| `HttpConfigValidation` | _internal_ | 0.12.0 | ADR-074 | — (package-private; `HttpConfig`'s own construction-time validation, extracted rather than published) |
| Engine wiring: `HttpConfig`, `HttpMode`, `HttpKernelProviders` | **stable** | 0.5.0 | ADR-009 | `AbstractHttpProviderTck`, `…HttpProviderLoopbackTck` |
| `HttpClientRequestEnricher` | **stable** | 0.8.0 | ADR-032 | `AbstractHttpClientRequestEnricherTck` |
| Body codecs: `HttpRequestBodyEncoder`, `HttpRequestBodyDecoder`, `HttpResponseBodyEncoder`, `HttpResponseBodyDecoder`, `HttpRequestBodyEncoderRegistry`, `HttpRequestBodyDecoderRegistry`, `HttpResponseBodyEncoderRegistry`, `HttpResponseBodyDecoderRegistry`, `HttpRequestEncodingContext`, `HttpRequestDecodingContext`, `HttpResponseEncodingContext`, `HttpResponseDecodingContext`, `HttpEncodedBody` | **preview** | 0.8.0 | ADR-034 / ADR-036 | `AbstractHttpRequestBodyEncoderTck`, `…RequestBodyDecoderTck`, `…ResponseBodyDecoderTck` |
| Client retry: `HttpRetryPolicy`, `RetryDecision`, `HttpAttemptOutcome` | **preview** | 0.10.0 | ADR-045 | `AbstractHttpRetryPolicyTck` |
| Route authorization: `HttpRoutePolicy`, `RouteRequirement` | **preview** | 0.11.0 | ADR-061, ADR-077 | `AbstractHttpRoutePolicyTck` |
| `HttpStreamExchange` / `HttpStreamHandler` / `StreamEvent` (SSE server-push) | **preview** | 0.10.0 | ADR-043 | `AbstractHttpStreamExchangeTck` |

> One asymmetry is deliberate and worth stating, because it looks like an error: `HttpProvider` is
> `stable` while the four codec `…Registry` types it returns are `preview`. Every such method is a
> `default` returning `empty()` / `Optional.empty()`, so the codec seam is opt-in and a provider
> implementor inherits no obligation from it — the settled part of `HttpProvider` is unaffected by
> the quadrant still moving. If the registries' shape changes, the gate reports it without failing
> the build, which is the intended reading of `preview` and not a hole in `stable`.

> The body-codec quadrant has an **accepted ADR (ADR-034)** and executable TCKs, so it is past
> `experimental` — but the server-side generator that consumes the request decoder lands in a
> later cycle, so the contract is held at `preview` until that loop closes.

> The streaming surface is wired end-to-end and TCK-pinned, but changes are still in flight rather
> than merely possible: the wire framing is HTTP/1.1 close-delimited today (per-event chunked framing
> and an HTTP/2 `DATA` path are follow-ups), the JWT-expiry deadline is built and TCK-pinned but not
> yet passed by production dispatch, and 0.11 added `pathParams()` to the interface. `preview` is the
> honest label until those close — see [`subsystems/http.md`](./subsystems/http.md) for the per-item
> delivery status.

`util` is excluded from the consumer matrix: `eu.exeris.kernel.spi.util` currently contains only
`SpiDiagnostics`, an internal helper — not a consumer-facing SPI surface.

---

## Semver policy

This is the canonical statement of what the maturity labels commit to. It supersedes any prose
elsewhere; `CHANGELOG.md` links here for the authoritative version.

- **stable** — Semver-binding **from v1.0**. After 1.0, a breaking change to a `stable` surface
  requires a **major** version bump and a published deprecation window. Pre-1.0, additive evolution
  is still possible per the project-wide pre-1.0 caveat, but the *shape* is considered settled.
- **preview** — Becomes semver-binding **from the follow-up version that promotes it to `stable`**
  (e.g. a surface that is `preview` in v0.9 and `stable` in v1.0 is binding from v1.0). Breaking
  changes are permitted while the label is `preview`; they will be called out in release notes.
- **experimental** — **No guarantees in any version.** A breaking change can land in any release,
  including a patch. The Javadoc carries an explicit "use-at-own-risk" disclaimer.

### Pre-1.0 / TRL-3 framing

Exeris is pre-1.0 / TRL-3, and no SPI consumer is under a support contract today. This matrix is a
**statement of intent for v1.0**, not a present-day patch-line guarantee — a patch release may still
carry an observable contract addition. v1.0 release notes will restate this framing explicitly.

"No support contract" is not the same as "no consumers": integrations against released versions
exist, both inside the ecosystem (`exeris-ai-bridge`, host runtimes) and outside it. The matrix
exists so that everyone integrating shares one honest picture of what is settled and what is still
moving — and, since v0.11, so does the generated compatibility record that backs it.

Where that record and this table disagree, the record wins and this table is the bug. The gate that
produces it (`tools/spi-api-diff/`) reads its labels from
`tools/spi-api-diff/stability-surfaces.conf`, which must be updated in the same commit as any
maturity change here.

---

## Maintenance

- This file is **tracked** (not LOCAL). Changes go through normal PR review.
- When a surface changes maturity (e.g. a `preview` surface promoted to `stable` after its sprint
  lands), update **this table first**, then the relevant `docs/modules/*.md` and
  `docs/subsystems/*.md` cross-references.
- The drift gate: every `## Stability` / `[stable|preview|experimental]` mention in module and
  subsystem docs must resolve to a row here.

---

## See also

- `docs/modules/*.md` — per-module stability cross-references.
- `docs/subsystems/*.md` — per-subsystem SPI status tags.
- `docs/ROADMAP.md` §"SPI Stability Declaration" — the originating gap entry.
- `CHANGELOG.md` — release history with pre-1.0 semver caveat.

[^rowcursor]: **Behavioural note, 0.12 (ADR-080).** `RowCursor.getString` narrows from "whatever the
    driver returns" to "the server's rendering for a measured type set, and a typed refusal
    (`EX-PERS-5008`) outside it". No signature changes, so the API-diff gate sees nothing — a total
    function narrowed to a partial one has the same signature, which is why this is recorded here
    rather than left to the gate. On PostgreSQL, reading a Tier C column (arrays, ranges, `inet`,
    `tsvector`, native `enum`, composites) through `getString` now throws where it previously
    returned a value. Other engines are unaffected: the guarantee is scoped to the server the set was
    measured on.
