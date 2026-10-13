---
title: "ADR-100: The SPI surfaces that 1.0 declares stable"
type: adr
slug: adr/ADR-100
visibility: public
owning-repo: exeris-kernel
status: active
---

# ADR-100: The SPI surfaces that 1.0 declares stable

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (2026-10-08)                                                                 |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-10-07                                                                                |
| **Scope**       | `kernel/spi`                                                                              |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md), which this ADR closes; issue #610 |
| **Compliance**  | [docs/stability-matrix.md](../stability-matrix.md), `tools/spi-api-diff/stability-surfaces.conf`, [ADR-065](ADR-065-spi-compatibility-gate.md) |

## Context and Problem Statement

0.13.0 is the last minor release before 1.0.0-RC, and the release candidate freezes every surface
labelled `stable` under the compatibility gate of ADR-065. A surface that 1.0 does not declare
`stable` at that point stays `preview` until a 1.x minor promotes it. So the set has to be decided
before the release candidate, and every change a promoted surface still needs has to land on the
0.13 line.

RFC-2026-09-02 sorted the blockers of each `preview` surface into kinds — an absent anchor ADR
(`…spi.events`, `…spi.graph`), enforcement too narrow for a settled shape (`…spi.crypto`), a
scheduled addition (`…spi.security` with `SecretProvider`) — and left three questions open. Its
answers, as measured on `development/0.13.0`, are recorded in the RFC and summarised here:

1. **HTTP body codecs.** The reason the matrix gives for holding the quadrant at `preview` — the
   server-side generator that consumes the request decoder had not landed — no longer holds:
   `KernelHandlerGenerator` and `KernelApplicationGenerator` in exeris-tooling resolve
   `HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY`. One item remains open on the surface:
   the decode methods take a raw `Class<?>`, so a parameterized target such as `List<Widget>` loses
   its element type (ROADMAP §"HTTP Client: Generic-Element Decode"). Its resolution is an additional
   decode path, which is an addition to the surface rather than a change of it.
2. **`SecretProvider`** is outside the security surface 1.0 publishes. At 1.0 secrets reach the
   kernel through configuration and external injection. Adding the seam to a `stable` surface later
   is additive; what would not be additive — routing existing credential-carrying contracts through
   it — is not planned.
3. **Consumers waiting on promotion.** The matrix records, for every surface that stays `preview`,
   the consumers that build on it.

One structural defect sits outside the RFC's frame. A `stable` signature that names a `preview` type
makes every change to that type a change to the `stable` signature, which the gate then either fails
or — because it labels by declaring class — never sees. Measured on `development/0.13.0` by reading
every public signature (`javap -public`) of every class in a package that is `stable` today or
promoted below, and listing each reference to a package that stays `preview`:

| Stable member | Preview type it names | First released |
|---|---|---|
| `KernelProviders.EXECUTION_CONTRACT`, `executionContract()` | `spi.contract.ExecutionContract` | unreleased (0.13) |
| `KernelProviders.GRAPH_PROVIDER` | `spi.graph.GraphProvider` | 0.5 |
| `KernelProviders.GRAPH_ENGINE`, `graphEngine()` | `spi.graph.GraphEngine` | 0.5 |
| `KernelProviders.TIME_SOURCE`, `timeSource()` | `spi.time.TimeSource` | 0.12 |

No other class in the target `stable` set names a type from `…spi.contract`, `…spi.graph`,
`…spi.time` or `…spi.websocket`. `HttpProvider` returning the `preview` codec registries is the one
documented asymmetry today, and it disappears when the codecs are promoted.

## Decision

### 1. The `stable` set at 1.0

| Surface | At 1.0 | Precondition that must hold before the promotion commit |
|---|---|---|
| `…spi.transport`, `…spi.persistence`, `…spi.flow`, `…spi.memory` | **stable** (unchanged) | — |
| `…spi.telemetry`, `…spi.diagnostics`, `…spi.bootstrap`, `…spi.context`, `…spi.config`, `…spi.exceptions` | **stable** (unchanged) | `…spi.context` carries no preview type (§3) |
| `…spi.http` — the whole package, codecs, retry, route authorization, SSE and stream-route resolution included | **stable** | SSE and stream-route resolution: stream routes resolve over HTTP/2 (#534), so the contract ADR-043 states — "rides the existing HTTP/1.1 + h2 server" — holds when it is frozen |
| `…spi.security` and `…spi.security.identity` | **stable** | none; `SecretProvider` is outside (Context, answer 2) |
| `…spi.events` | **stable** | ADR-101 accepted, and its descriptor and subscription change (#600) landed |
| `…spi.crypto` | **stable** | `AbstractAbiSymbolResolutionTck` exists and is bound; every 0.13 TLS change that touches a crypto signature (#531, #532) has landed |
| `…spi.scheduling` | **stable** | — (anchor ADR-057, executable TCK, no scheduled change on record) |
| `…spi.storage` (it holds only `…storage.blob`) | **stable** | — (anchor ADR-056, executable TCK, no scheduled change on record) |
| `…spi.graph` | **preview** | anchor and multi-hop traversal in ADR-102; promotion in a 1.x minor |
| `…spi.contract` | **preview** | the licence schema it carries is owned by ADR-088/ADR-089 and still moves |
| `…spi.websocket` | **preview** | benchmark evidence per ADR-084 §10 |
| `…spi.time` | **preview** | no provider contract and no TCK |

This is RFC-2026-09-02's Option B, widened: the consumer-ordered promotions (crypto, security, HTTP
codecs) plus every surface the RFC found had nothing left blocking it (scheduling, blob) and events,
whose only blocker was the absent ADR. Graph is the one surface the RFC scheduled for 1.0 that stays
`preview`: multi-hop traversal widens its contract in 0.13, and freezing a contract in the same
release that widens it leaves no release in which the widening was exercised.

A precondition that does not hold at the promotion commit keeps that surface `preview` at 1.0; it
does not hold the other promotions back.

### 2. The generic-element decode path is not a precondition

The decode path for parameterized targets (Context, answer 1) is added as a `default` method beside
the `Class<?>` one, so out-of-tree decoders keep compiling and linking. That is additive to a `stable`
surface, which ADR-065 permits in a minor release. **Ruled (2026-10-08):** it lands in a 1.x minor, not
in 0.13, unless a consumer needs it before the release candidate — the seam is additive either way,
and 0.13's capacity is better spent on the preconditions above.

### 3. A `stable` signature names no `preview` type

Every slot in §Context's table moves to a holder in the package that owns its type, following the
shape `HttpKernelProviders` already has in `…spi.http`:

- `…spi.contract.ContractKernelProviders` — `EXECUTION_CONTRACT`, `executionContract()`.
- `…spi.graph.GraphKernelProviders` — `GRAPH_PROVIDER`, `GRAPH_ENGINE`, `graphEngine()`.
- `…spi.time.TimeKernelProviders` — `TIME_SOURCE`, `timeSource()`.

`EXECUTION_CONTRACT` and `executionContract()` were never released, so they leave `KernelProviders`
outright. The graph and time members were released, so `KernelProviders` keeps them as a bridge:
each field is the **same `ScopedValue` instance** the new holder declares, not a second slot, so a
binding made through either name is visible through both, and each accessor delegates. The bridge
members are `@Deprecated(forRemoval = true, since = "0.13")`.

The bridge members are removed before 1.0.0, so 1.0 freezes a `KernelProviders` that names no
`preview` type. **Ruled (2026-10-08):** they are removed in the first release
candidate, not in 0.13.0 itself (which would leave no bridge release). The release-candidate gate of ADR-065 compares against `v0.13.0`, where the
members are `stable`, so removing them there is reported as a `stable` break; this ADR would then be
the one sanctioned exception, by member, and the release-candidate pull request names it.
That way 0.13.0 ships one release in which
consumers on the old names compile with a deprecation warning — one consumer outside this
repository reads `KernelProviders.GRAPH_ENGINE` today.

The rule is enforced, not only stated: the promotion commit (§5) adds a check that fails when a
public signature of a class labelled `stable` in `stability-surfaces.conf` names a class labelled
`preview` or `experimental` there.

### 4. The matrix records the consumers waiting on promotion

Every row that stays `preview` names the consumers building on it — the HLA capabilities whose
`@Requires` name it, and first-party consumers that reach it without declaring it (for websocket,
Platform LSP and Studio; for graph, `contact-graph`). The column is maintained with the row, so the
link RFC-2026-09-02 had to reconstruct from two documents lives in one.

### 5. The promotion is one commit

`docs/stability-matrix.md`, `tools/spi-api-diff/stability-surfaces.conf`, the subsystem and module
stability tags and the §3 check change together, gated by `spi-api-diff --verify-surfaces` and
`--fail-on-stable` against `v0.12.0`. It lands after the last precondition it carries, and a surface
whose precondition has not landed keeps its `preview` row in that commit.

## Consequences

### Positive

- 1.0 freezes a set every member of which has an accepted ADR and an executable TCK, which is the
  matrix's own definition of `stable`.
- The `stable` set stops depending on four `preview` types; the gate's labels and the frozen
  surface's actual dependencies agree.
- Generated applications, which bind the codec registries per request, build on a frozen contract.

### Negative

- Five surfaces enter the freeze in one release, and each precondition is on the 0.13 critical path
  (events after ADR-101; crypto after the ABI symbol TCK; SSE after #534).
- Consumers of the graph and time slots change an import before 1.0.
- `contact-graph` builds on a `preview` surface at 1.0, and the matrix says so.

### Neutral

- The `HttpProvider` asymmetry note in the matrix is withdrawn with the codec promotion.
- The ordering of 0.13's work follows from §1: the preconditions are SPI-changing work and land
  before the promotion commit.

### Non-Goals

- **`SecretProvider`.** The security surface 1.0 publishes does not include it (Context, answer 2);
  adding it later is additive.
- **The generic-element decode path.** It is additive to a `stable` surface and is not a
  precondition of the codec promotion (§2).
- **Promoting graph, contract, websocket or time.** Each stays `preview` at 1.0 for the reason §1
  gives, and each is promoted by a 1.x minor with its own evidence.
- **Freezing `preview` surfaces at the release candidate.** What the release candidate freezes is
  ruled in ADR-065 amendment A1; this ADR decides only which surfaces carry the `stable` label.

### Risks and Assumptions

- **Assumes:** scheduling and blob have no scheduled signature change on record at the promotion
  commit. **Reversed by:** an accepted ADR, RFC or ROADMAP entry scheduling such a change before
  the release candidate — the surface then stays `preview` at 1.0.
- **Assumes:** each precondition in §1 lands on the 0.13 line before the promotion commit.
  **Reversed by:** a precondition still open when the commit is cut — its surface keeps its
  `preview` row (§1, last paragraph), and the others are promoted without it.
- **Assumes:** the consumers outside this repository that read the graph and time slots move to the
  new holders within the one release the deprecated aliases exist. **Reversed by:** a consumer that
  cannot move by the first release candidate — the removal then waits, and 1.0 freezes those
  aliases as `stable` members naming `preview` types, which §3 exists to prevent.
- **Risk:** five surfaces enter the freeze together, so a defect found in one of them after the
  release candidate is fixed additively or waits for 2.0. The reviewers of each precondition's pull
  request are the first to see it.

## Verification

- §3: the `javap -public` sweep over the promoted set reports no reference to a `preview` package,
  and the check added in §5 fails when one is reintroduced (shown by reverting one moved slot).
- §5: `spi-api-diff --verify-surfaces` passes with no unlabelled class, and `--fail-on-stable`
  against `v0.12.0` reports the promoted surfaces' changes since 0.12 as their last `preview`
  changes, not as `stable` breaks.
- After the promotion commit, `docs/release/1.0-scope.md` lists no open Blocking row for the
  promotion.

## Related

- [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) — closed by this ADR.
- [ADR-065](ADR-065-spi-compatibility-gate.md) — the gate that enforces the labels.
- [ADR-101](ADR-101-events-spi-contract-anchor.md) (events anchor) and
  [ADR-102](ADR-102-graph-spi-anchor-and-multi-hop-traversal.md) (graph anchor and multi-hop traversal, written from the accepted
  [RFC-2026-10-07](../rfc/RFC-2026-10-07-graph-multi-hop.md)) — the two anchors this ADR names as
  preconditions.
- [ADR-008](ADR-008-open-core-strategy-and-commoditization-of-off-heap-tls.md),
  [ADR-014](ADR-014-requiresrole-compile-time-rbac-generation.md),
  [ADR-040](ADR-040-identity-provider-spi.md), [ADR-043](ADR-043-kernel-http-streaming-spi.md),
  [ADR-056](ADR-056-blob-storage-provider-spi.md), [ADR-057](ADR-057-job-scheduler-spi.md),
  [ADR-084](ADR-084-websocket-provider-spi.md), [ADR-088](ADR-088.link.md),
  [ADR-089](ADR-089.link.md).
