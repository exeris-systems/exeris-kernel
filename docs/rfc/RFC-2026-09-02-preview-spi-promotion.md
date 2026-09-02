# RFC-2026-09-02: What blocks each `preview` SPI from becoming `stable`, and which of them 1.0 owes?

|                    |                                                                          |
|:-------------------|:-------------------------------------------------------------------------|
| **Status**         | **OPEN** — investigation framed, options laid out, decision not taken     |
| **Author(s)**      | Arkadiusz Przychocki                                                     |
| **Date Opened**    | 2026-09-02                                                               |
| **Date Closed**    | —                                                                        |
| **Target ADR(s)**  | Undecided — see Options. Two of the options need an ADR *per surface*, which is itself the finding |
| **Affected Repos** | `exeris-kernel` (authoritative); `exeris-docs` (HLA capability map is the consumer that makes this urgent) |
| **Reviewers**      | —                                                                        |

## Question

Six kernel SPI surfaces carry the `preview` label. The HLA's Tier 2 capability map builds
`commercial` capabilities on four of them. **For each surface: what is actually preventing promotion
to `stable`, and is that blocker something v1.0 owes?**

The question is deliberately not "which surfaces should be stable at 1.0". Answering that without
knowing what holds each one back produces a schedule rather than a decision.

## Context

### What the labels commit to

`docs/stability-matrix.md` is the authoritative statement, and two of its clauses do the work here:

- **`stable`** — "Contract shape is settled; covered by an accepted ADR **and** executable TCK.
  Safe to build on." Semver-binding **from v1.0**.
- **`preview`** — "Shape is largely settled but a **known, scheduled change is still in flight** (a
  sprint or ADR that will touch the contract)."

So `preview` is not a hedge. It is a claim that we know the contract will move. Anything built on it
is built on a surface we have said will change.

### Why this is a 1.0 question rather than a housekeeping one

The HLA (§3.2) declares a Tier 2 capability ecosystem; `cap-license-registry.md`, which is the
authority for status, carries **54 capabilities — 53 `specified`, one (`exeris-caps-cors-policy`)
`scaffolded`, none implemented**. So every dependency below is a dependency of something not yet
written, which is precisely why the kernel-side decision is still cheap to take. Cross-referencing
every `@Requires` that names a kernel SPI against the matrix:

| kernel SPI | label at 0.12 | Tier 2 capabilities naming it |
|---|---|---|
| `…spi.persistence` | **stable** | `ai-vector-store`, `idempotency`, `usage-metering` |
| `…spi.flow` | **stable** | `workflow-engine`, `order-lifecycle` |
| `…spi.transport` | **stable** | `gateway-core` |
| `…spi.telemetry` | **stable** | `observability-bridge` |
| `…spi.crypto` | **preview** | `gateway-core`, `tls-termination`, `bot-fingerprinting` |
| `…spi.security` (+ `.identity`) | **preview** | `multi-tenancy`, `rbac-policy`, `service-identity` |
| `…spi.graph` | **preview** | `contact-graph` |
| `…spi.http` | **mixed** — body codecs, retry, route authorization and SSE are each `preview` | `gateway-core`, `rest-emission`, `graphql-emission`, `openapi-emission` |

**Seven capabilities across four layers depend on a surface we have declared will move**, and one of
them — `gateway-core` — is the Layer-1 aggregate the entire Gateway SKU family composes. A
`commercial` capability whose kernel contract is scheduled to change is not a product that can be
sold against a 1.0.

**And the exposure is wider than Tier 2.** HTTP body codecs are `preview`, and *every generated
Exeris application* binds them: the generated `parseBody` resolves
`HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY` per request. That is not a hypothetical
consumer waiting on a capability repository — it ships in every application the pipeline emits today.

### What the compatibility gate does and does not settle

`tools/spi-api-diff` reads its labels from the matrix and fails the build on a `stable` break. It
compares **signatures**, so it is exactly as good as the labels are honest, and it cannot see a
contract change that keeps its shape — a relaxed null check, a widened precondition, a changed
default. Promoting a surface to `stable` therefore transfers real enforcement to the gate for shape,
and transfers nothing for meaning. Meaning stays with the TCK.

## Investigation

The blockers are not the same kind of thing per surface, and that is the most useful thing this
investigation found. They fall into three kinds.

### Kind 1 — no accepted ADR exists, so promotion is definitionally impossible

`…spi.events` and `…spi.graph` both carry `—` in the matrix's anchor-ADR column. Both have
executable TCKs (`AbstractEventBusTck`, `AbstractGraphProviderTck` and siblings). Under the matrix's
own definition of `stable` — an accepted ADR **and** an executable TCK — **neither can be promoted
until an ADR is written**, regardless of how settled the code is.

This is not a formality. Writing the ADR is the forcing function that establishes whether the shape
*is* settled: `…spi.events` has been `preview` since 0.5.0 and has absorbed Kafka bindings, the
transactional outbox and the payload-codec surface in that time, and `…spi.graph` carries the
heterogeneous multi-hop traversal question that the ROADMAP moved **into 1.0** on 2026-08-27 — a
contract widening on a surface that has no ADR fixing its current shape.

### Kind 2 — the shape is settled; the enforcement is not broad enough

`…spi.crypto` has ADR-008 and `AbstractCryptoEngineTck`. What the ROADMAP records as outstanding is
not a contract change but coverage: an `AbstractAbiSymbolResolutionTck` enumerating every native
symbol the kernel calls, so a binding proves cold-start resolvability rather than discovering a
missing symbol at first handshake. TLS also auto-skips on Windows and needs a Linux host for full
native coverage.

If that reading is right, crypto is the **cheapest** of the four to promote: the work is TCK breadth
on an unchanged contract, not a redesign. It is also the most urgent, because `gateway-core` and
`tls-termination` are the first bindings expected to land.

### Kind 3 — a scheduled addition would change what the surface contains

`…spi.security` (ADR-014) and `…spi.security.identity` (ADR-040) both have ADRs and TCKs. The
in-flight item is `SecretProvider`, which the ROADMAP carries as a table-stakes gap: a
`resolve(SecretRef)` seam that DB passwords, JWKS and TLS material would read through.

Its consequence for promotion is specific rather than general. A `SecretProvider` added *after*
promotion is an addition to a `stable` surface, which the matrix permits and the gate reports as a
minor bump — the ADR-074 and ADR-083 additions are both worked precedents. What would **not** be
additive is routing existing credential-carrying contracts through the new seam, because that
reshapes what is already there. So the blocker is a scoping decision, not an implementation: **does
the security surface at 1.0 include the secret seam, or does it publish without it?**

### What this investigation did not measure

Three things, named so the RFC is not read as more complete than it is:

- **HTTP codecs.** The surface moved in v0.12 (the request-decode exception type; the decoding
  contexts' allocator becoming optional). Whether anything further is scheduled has not been
  established, and the answer decides whether codecs are Kind 2 or Kind 3.
- **The `…spi.http` breakdown is per-surface**, so "promote HTTP" is not a single act — retry, route
  authorization and SSE are separately labelled and separately consumed, and they do not have to
  move together.
- **Enterprise-tier consumers.** The matrix's "Enterprise divergence" column marks crypto, transport
  and graph as having enterprise implementations. Whether promoting a contract constrains the
  enterprise driver's roadmap is a question this repo cannot answer alone.

## Options Considered

### Option A: promote the four capability-blocking surfaces at 1.0

Crypto, security (+identity), HTTP body codecs, graph — each gets whatever its blocker requires
(ADR, TCK breadth, or a scoping decision), and 1.0 publishes them as `stable`.

**Pros:** the HLA's Tier 2 map becomes buildable as written; `gateway-core` and the Service Boundary
platform caps can be developed against contracts that will not move.

**Cons:** four surfaces of work concentrated into the release that can least afford surprises. Two
of them need an ADR that does not exist yet, and writing an ADR to a deadline is how a shape gets
declared settled before it is.

### Option B: promote by consumer order, not by surface count

Take them in the order their consumers land. Crypto first (Gateway bindings are the first expected
consumers, and crypto is Kind 2 — TCK breadth on an unchanged contract). Then security, then HTTP
codecs. Graph moves when the multi-hop traversal question closes, which is already 1.0 scope for
other reasons.

**Pros:** matches the actual sequencing — not everything in the HLA ships near 1.0. Each promotion
is justified by a consumer rather than by a target. Crypto being both the most urgent and the
cheapest is a coincidence worth spending.

**Cons:** 1.0 ships with some Tier 2 capabilities still resting on `preview`, and the matrix must
say so plainly rather than leaving a reader to cross-reference it against the HLA themselves.

### Option C: promote nothing; restate what 1.0 covers

1.0 is the substrate release. `stable` surfaces are those already labelled so; the capability map is
explicitly post-1.0 and the HLA says which of its `@Requires` are not yet binding.

**Pros:** no shape is declared settled under schedule pressure. Honest about a TRL-3 codebase with
one existing capability repository out of 58.

**Cons:** the marketing surface and the technical surface diverge — the HLA sells a capability
ecosystem whose foundations are all labelled "will change". A buyer reading both documents finds the
contradiction before we do.

### Option D: shrink the declared surface instead of stabilising it

Withdraw or narrow the contracts whose consumers do not exist, so what remains is smaller and can be
promoted wholesale.

**Pros:** the only option that reduces total obligation. Directly answers the tooling repository's
parallel question — whether the annotation surface should shrink toward what the pipeline can
compile rather than the pipeline growing toward the surface.

**Cons:** cannot be evaluated from this repository alone. Which contracts have no consumer is an
ecosystem question, and the HLA's SKU thesis is the thing that would have to give.

## Recommendation

**Not yet made — this RFC is opened to gather the two measurements it is missing** (what, if
anything, is still scheduled for the HTTP codec surface; and whether an enterprise driver's roadmap
constrains a crypto promotion), and to put the scoping question about `SecretProvider` in front of
the person who owns it.

What the investigation does support saying now:

1. The `…spi.events` and `…spi.graph` blocker is **an absent ADR**, not engineering. That is worth
   knowing before any scheduling conversation, because it is the one blocker that cannot be resolved
   by making the surface better.
2. Crypto is plausibly the cheapest promotion and is certainly the most urgent by consumer order.
   Those two facts pointing the same way is unusual and should be used.
3. Whatever is decided, the matrix should state, per `preview` surface, **which HLA capabilities are
   waiting on it**. Today that link exists only by cross-referencing two documents, which is how the
   contradiction in Option C's "Cons" survived unnoticed.

## Open questions

- Does the `…spi.http` codec surface have anything further scheduled, or did v0.12 close it?
- Does promoting `…spi.crypto` constrain the enterprise TLS driver's roadmap?
- Is `SecretProvider` inside or outside the security surface that 1.0 publishes?
- Should the matrix carry a "consumers waiting on promotion" column, so the HLA link is enforced in
  one place rather than reconstructed?
