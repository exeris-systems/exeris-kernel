# ADR-074: A request names its own peer — the client stops dialling the address its server listens on

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-08-26                                                                                |
| **Scope**       | `kernel/http`                                                                             |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-06-29](../rfc/RFC-2026-06-29-webclient-service-addressing.md) Open Question 1; 1.0 scope per its split disposition |
| **Compliance**  | [docs/subsystems/http.md](../subsystems/http.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

RFC-2026-06-29 was accepted with a **split disposition**: multi-peer addressing is 1.0 scope, the
`ServiceResolver` seam is post-1.0. That record fixed *when*, deliberately not *how*, and said so —
its Open Question 1 is owed "an option table, costs and recorded dissent of its own" before an ADR
may treat the shape as settled. This ADR discharges that.

**A code spike ran first, and it moved the problem.** Every document in this repository describes the
client as *single-host*. It is narrower than that:

- `CommunityHttpClientEngine` has **zero public constructors**. Every one is package-private.
- Its only reachable path is `CommunityHttpProvider.createClientEngine(config)`, which routes through
  the single-argument constructor and sets `targetHost = config.bindHost()`, `targetPort = config.port()`.
- `HttpConfig` documents `bindHost` as *"listener bind address for SERVER / DUAL modes"*. The client
  reads a **listen** address as a **dial** address.
- **No client-target configuration key exists anywhere** in SPI, Core or Community.

So through the supported path the kernel's HTTP client dials the address its own server listens on.
An application cannot address even the **first** external peer, let alone a second, and in
`CLIENT`-only mode `bindHost` is not semantically defined for it at all. The gap is not "we cannot
express a second peer" but "we cannot express any peer".

Two further spike findings bear directly on the option costs, and one of them removes an assumed one:

- **`send` opens a fresh connection per call.** `transport.connect(targetHost, targetPort)` sits
  inside `send`, in try-with-resources. There is no connection pool. An engine is therefore **not**
  naturally host-bound — it is host-bound only because it has nowhere else to read the host from.
- **The `Host` header is derived from the `TransportConnection`**, not from the request
  (`CommunityHttpClientRequestEncoder`). Today the two always agree because the connect target is the
  only notion of a peer. Any shape that separates a *named* peer from a *dialled* endpoint has to
  decide which one `Host` follows.

## Options Considered

The RFC's options A–E all answer *how a logical name resolves*. None evaluates **addressing without a
resolver**, which is what 1.0 needs, so the table below is over a different axis.

### Option 1 — The addressee rides on `HttpRequest` *(chosen)*

Add an `authority` component to the record; one engine serves many peers.

- **Cost:** adding a component to a `stable` record is a **binary break**. Mitigated by retaining the
  previous canonical constructor as a bridge — the pattern used three times on `FlowSnapshot` during
  v0.11, so it is precedented in-tree rather than theoretical.
- **Benefit that decides it:** the engine already connects per call, so this removes an accident
  rather than adding a feature. Nothing is pooled, so nothing is invalidated by varying the peer.
- **It is the only option the enricher can see.** `HttpClientRequestEnricher.enrich(HttpRequest)`
  receives the request and nothing else. Binding an outbound credential's audience to the peer
  (ADR-040) is therefore expressible here and **structurally inexpressible** in Option 2, where the
  peer lives on the engine the enricher never sees.
- **It composes forward.** A future `ServiceResolver` returns an endpoint, which becomes the
  authority on the request. No rework of this decision is implied by the post-1.0 half.

### Option 2 — A per-host engine pool behind the client

`KernelWebClient` keeps a `Map<host, HttpClientEngine>`; `HttpRequest` is untouched.

- **The advantage claimed for it does not exist.** The RFC describes this as maintaining "a per-host
  engine/connection-pool", which reads as preserving something. There is no pool to preserve.
- **Cost:** N peers means N engines, each holding its own `TransportEngine` with a
  `start`/`stop`/`close` lifecycle. For a runtime whose thesis is No Waste Compute, one transport
  engine per peer is the wasteful shape.
- **It does not avoid an SPI change**, it relocates one: engines are unconstructable from outside
  their package, so this needs a public per-host factory on `HttpProvider` — a break on a `stable`
  interface instead of on a `stable` carrier.

### Option 3 — The client holds a resolver plus an engine factory

This is the `ServiceResolver` seam, which the 1.0 = narrow-deep-core ruling explicitly holds out of
1.0 and RFC-2026-06-29's split leaves post-1.0. Listed for completeness; **not available for this
decision** without reopening a ruling this ADR has no mandate to reopen.

### Option 4 — Only fix the self-addressing: a client-target config key

Add `http.client.targetHost` / `…targetPort`, distinct from `bindHost`, and stop.

- Fixes the real defect with the smallest possible change and no SPI break.
- **Does not discharge the 1.0 commitment.** Adopting it means deciding that 1.0 ships a
  single-peer client, which contradicts "unbreakable on `http`" and would require amending the
  ROADMAP rather than satisfying it.
- Rejected as a *terminus*; its substance survives as the default-authority mechanism below.

### Option 5 — Do nothing

The dissent recorded in RFC-2026-06-29 keeps Option E live for the **resolver** half. It does not
apply here: this half has a consumer today, namely any application talking to any peer that is not
itself, and the spike shows that set currently includes every application.

## 🏁 The Decision

**`HttpRequest` gains an `authority` component, and it is the request that names the peer.**

1. **The carrier.** `HttpRequest(method, authority, path, version, headers, body)`. `authority` is
   the RFC 3986 authority — `host` or `host:port` — and may be `null`, meaning *"the engine's
   configured default peer"*. The previous canonical constructor is **retained** as a compatibility
   bridge that passes `null`, so every existing call site compiles and behaves unchanged.

2. **A default peer becomes configurable, and stops being the listener.** The client engine resolves
   its default target from client-scoped configuration rather than from `http.bindHost`. This is
   Option 4's substance kept as the mechanism that makes `authority = null` meaningful — and it is
   what actually closes the self-addressing defect.

3. **`Host` follows the authority, not the connection.** The encoder derives the header from the
   request's effective authority. Today the two agree; stating it now means a resolver that separates
   a logical name from a dialled endpoint inherits a correct rule instead of a latent bug.

4. **TLS peer verification follows the authority too** — SNI and certificate hostname matching are
   performed against the effective authority, for the same reason.

5. **Ordering is fixed as authority-then-enrich-then-send.** The enricher observes the final
   authority, so an outbound credential's audience can be bound to the peer it is actually sent to
   (ADR-040). An enricher that rewrites the authority is out of contract.

6. **Retry (ADR-045) re-sends the same request**, therefore the same authority. Re-addressing on
   failure is a resolver concern and stays post-1.0.

## Consequences

**A `stable` carrier breaks, on purpose, at the last moment it is cheap.** `HttpRequest` is `stable`
since 0.5.0 in `stability-matrix.md`, so ADR-065's compatibility gate will report it and the change
lands with its baseline note in the same commit. Pre-1.0 this costs a bridge constructor; after 1.0
the same change costs a major version. That asymmetry is the argument for doing it now rather than a
reason to avoid it — and this repository does not use "breaking change" framing pre-1.0 for exactly
this reason.

**The TCK gains the contract, not just the field.** `AbstractHttpClientEngineTck` must assert that a
request carrying an authority reaches that peer, that a `null` authority reaches the configured
default, and that `Host` reflects the effective authority — the last one being the assertion that
would fail if an implementation kept deriving it from the connection.

**A documented-but-wrong configuration key stops being both.** `bindHost` returns to meaning what
`HttpConfig` says it means. Any deployment that relied on the client reaching its own server keeps
working by configuring that explicitly, which is the difference between a coincidence and a setting.

**What this does not decide:** whether `resolve` returns one endpoint or a weighted set; cache
ownership and TTL; the failure-mode taxonomy for unresolved names. All three are resolver concerns,
all three stay with the post-1.0 seam, and none is blocked by this decision.

## Dissent recorded

Option 2 has a real advocate case that survives its costed rejection: it leaves the `stable` carrier
untouched, and a project that publishes a stability matrix should be reluctant to break rows in it.
The counter relied on is that it breaks a `stable` *interface* instead, and that it cannot express
audience binding at all — so it trades a mitigable break for an unmitigable gap.

The weaker part of this decision is `authority = null` meaning "default". A nullable component in a
carrier is a smell, and the honest alternative is to require an authority everywhere and take the
call-site churn. It was rejected because that churn falls on downstream code this repository does not
own, for no gain in expressiveness — but if a later revision finds the null carrying meaning it
should not, this is the clause to revisit first.

## Cross-references

- [RFC-2026-06-29](../rfc/RFC-2026-06-29-webclient-service-addressing.md) — the accepted record whose
  Open Question 1 this discharges; its split disposition is what makes this 1.0 scope.
- [ADR-065](ADR-065-spi-compatibility-gate.md) — the gate that will report this change and the reason
  the stability-matrix row lands in the same commit.
- [ADR-040](ADR-040-identity-provider-spi.md) — outbound credential audience binding, whose
  expressibility decides between Options 1 and 2.
- [ADR-045](ADR-045-client-side-http-retry-policy-spi.md) — retry re-sends the same authority.
- [ADR-009](ADR-009-http-codec-module.md) — the codec surface that derives `Host`.

## Engineering Protocol

- The carrier change, the retained bridge constructor, the `stability-matrix.md` note and the
  `stability-surfaces.conf` entry land in **one commit** — ADR-065's gate fails the build on an
  unclassified SPI class, and splitting them means a red build between two green ones.
- The TCK assertion for `Host`-follows-authority must be **mutation-checked** against an
  implementation that derives it from the connection. That is the current behaviour, so the check is
  free: revert the encoder line and the test must redden.
- No `ServiceResolver` type, package, or configuration key is introduced. A resolver-shaped name
  appearing in this slice is scope creep into a post-1.0 seam.
