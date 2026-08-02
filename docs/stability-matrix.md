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

It is **not** a present-day semver guarantee. Exeris is pre-1.0 / TRL-3 with no external SPI
consumers under contract; per `CHANGELOG.md`, minor versions may still carry observable
contract additions. The maturity labels below describe **stability intent**, not a binding
patch-line promise. See [Semver policy](#semver-policy) for exactly what each label commits to.

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
| `…spi.persistence` | **stable** | 0.5.0 | ADR-022 | `AbstractPersistenceProviderTck`, `…EngineTck`, `…OutboxGuaranteeTck`, +6 | yes (slab/FFM tier) |
| `…spi.flow` | **stable** | 0.5.0 | ADR-013 | `AbstractFlowEngineTck`, `…SagaRecoveryTck`, `…IdempotencyGuardTck`, +3 | — |
| `…spi.memory` | **stable** | 0.5.0 | — (foundational) | `AbstractMemoryAllocatorTck`, `…LoanedBufferTck`, `…MemoryGovernorTck`, +5 | yes (slab pools) |
| `…spi.transport` | **stable** | 0.5.0 | — (foundational) | `AbstractTransportProviderTck`, `…EngineTck`, `…StreamTck`, `…ConnectionTck` | yes (`io_uring`/QUIC) |
| `…spi.exceptions` | **stable** | 0.5.0 | — (Glass-Box contract) | `AbstractDisclosureModeTck` (+ `…GlassBoxTckTest` in TCK) | — |
| `…spi.telemetry` | **stable** | 0.5.0 | — (Glass-Box contract) | `AbstractTelemetryProviderTck`, `…SinkTck`, `…RingBufferTck`, `…JfrTelemetrySinkTck` | yes (binary glass-box sink) |
| `…spi.bootstrap` | **stable** | 0.5.0 | ADR-007 | `AbstractBootstrapOrchestratorTck`, `…SubsystemLifecycleTck`, `…FailurePolicyTck`, +5 | — |
| `…spi.context` | **stable** | 0.5.0 | ADR-007 (ScopedValue propagation) | exercised via bootstrap/diagnostics TCKs | — |
| `…spi.config` | **stable**¹ | 0.5.0 | — | `AbstractConfigProviderTck`, `…DynamicConfigRegistryTck` | — |
| `…spi.events` | **preview** | 0.5.0 | — | `AbstractEventBusTck`, `…EventLoopTck`, `…KafkaEventEngineTck`, +5 | — |
| `…spi.graph` | **preview** | 0.5.0 | — | `AbstractGraphProviderTck`, `…GraphEngineTck`, `…GraphDialectTck`, +3 | — |
| `…spi.security` | **preview** | 0.5.0 | ADR-014 (RBAC) | `AbstractSecurityProviderTck`, `…RequiresRoleTck`, `…CitadelGuardTck`, +6 | — |
| `…spi.security.identity` | **preview** | 0.10.0 | ADR-040 | `AbstractIdentityProviderTck` | — |
| `…spi.crypto` | **preview** | 0.5.0 | ADR-008 (TLS engine) | `AbstractCryptoEngineTck` | yes (FFM crypto) |
| `…spi.http` | **mixed** | 0.5.0 | ADR-009 / ADR-032 / ADR-034 / ADR-043 | see per-surface rows below | yes (HTTP/3 path) |
| `…spi.util` | _internal_ | 0.5.0 | — | — | — |

¹ `config`: `ConfigProvider` / `KernelProfile` / `Dynamic` are mature 0.5.0 contracts and treated
as `stable`. The `@Immutable` annotation + watcher-refusal semantics (since 0.9.0, v0.9 Sprint 5) are
**additive** and classified `preview` — enforced by `ImmutableConfigProcessor` (compile-time) and
`DynamicConfigFileWatcher` (runtime `EX-CFG-1004` refusal), pending a dedicated `AbstractConfigProviderTck` binding.

### `…spi.http` per-surface breakdown

| HTTP surface | Level | Since | Anchor ADR | TCK |
|---|---|---|---|---|
| `HttpClientEngine`, `HttpServerEngine`, `HttpProvider`, `HttpExchange`, `HttpHandler` | **stable** | 0.5.0 | ADR-009 | `AbstractHttpClientEngineTck`, `…HttpServerEngineTck`, `…HttpProviderTck`, `…HttpExchangeTck`, `…HttpHandlerTck` |
| `HttpClientRequestEnricher` | **stable** | 0.8.0 | ADR-032 | `AbstractHttpClientRequestEnricherTck` |
| `HttpRequestBodyEncoder` / `HttpRequestBodyDecoder` / `HttpResponseBodyDecoder` | **preview** | 0.8.0 | ADR-034 | `AbstractHttpRequestBodyEncoderTck`, `…RequestBodyDecoderTck`, `…ResponseBodyDecoderTck` |
| `HttpStreamExchange` / `HttpStreamHandler` / `StreamEvent` (SSE server-push) | **preview** | 0.10.0 | ADR-043 | `AbstractHttpStreamExchangeTck` |

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

Exeris is pre-1.0 / TRL-3. There are no external SPI consumers under contract today. This matrix is
a **statement of intent for v1.0**, not a present-day patch-line guarantee — a v0.9.x patch may
still carry an observable contract addition. The matrix exists so that, as the first external
consumer (`exeris-ai-bridge`) integrates, both sides share one honest picture of what is settled and
what is still moving. v1.0 release notes will restate this framing explicitly.

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
