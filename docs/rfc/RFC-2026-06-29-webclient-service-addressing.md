# RFC-2026-06-29: How should `KernelWebClient` resolve a *logical* service name to a concrete endpoint at call time — or should the kernel resolve it at all?

| Field             | Value                                                                 |
|:------------------|:----------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                            |
| **Author(s)**     | arkstack-dev                                                          |
| **Date Opened**   | 2026-06-29                                                           |
| **Date Closed**   | —                                                                    |
| **Scope**         | substrate / Tier 1 (kernel HTTP client addressing; kernel half of the tooling "mesh" gap T12) |
| **Owning Repo**   | `exeris-kernel` — the addressing seam lives on `KernelWebClient` / the `HttpClientEngine` SPI. Hosted here as a **kernel-transport SPI RFC**, per the convention that each repo holds the RFCs for the SPI it owns (cf. `RFC-2026-06-18-http-streaming-spi`); the eventual ADR is kernel-scoped. A downstream consumer (`exeris-tooling` T12) tracks it from its own docs — the same way tooling's SSE-emitter RFC tracks the kernel HTTP-streaming SPI RFC — rather than the RFC living in `exeris-docs`. |
| **Target ADR(s)** | TBD — a kernel-scope "WebClient service addressing" ADR once accepted; number reserved in the global `exeris-docs/adr-index.md` **only** when the implementation build gate opens (not at RFC time — RFCs carry no registry number) |
| **Affected Repos**| `exeris-kernel` (the resolution seam + any Community driver + the `KernelWebClient` consult point; hosts this RFC and the eventual kernel-scoped ADR), `exeris-tooling` (T12 — generated clients emit a logical peer name instead of a hard-coded host), `exeris-docs` (the global ADR-index row, reserved when the build gate opens). Enterprise registry/mesh drivers are out-of-repo and are referenced descriptively only. |
| **Reviewers**     | —                                                                    |

## Question

`KernelWebClient` (ADR-034) targets a single, statically-configured host: the `HttpClientEngine` is bound to one host at construction and every verb call (`get`/`post`/`patch`/`delete`) takes only a *path* "relative to the engine's target host". There is no seam to turn a **logical** service name (e.g. `billing-service`) into a concrete address at call time. The question: **when an ecosystem splits into N generated applications that call each other, how — if at all — should the kernel resolve a logical service name to an endpoint?** The answer space is a small enumerable set: a static config map, DNS/DNS-SRV, a pluggable `ServiceResolver` SPI seam, or "not the kernel's job — delegate to a service-mesh sidecar".

## Context

This surfaced during downstream dogfooding (the Stellar-Tactics multi-service split, 2026-06; finding **K4**, Medium). It is the kernel-side half of the tooling mesh gap **T12** (owned by `exeris-tooling`): even once the generator can import a *cross-app contract* and emit a typed client for a peer application, that client has **nowhere to resolve the peer's address**. So today every caller hard-codes peer hostnames, which does not survive the moment the ecosystem becomes a mesh of generated apps — exactly the direction the platform is committing to (Tier 3 SKU compositions, Family products, the marketplace of composable units).

The cost of leaving it unanswered is concrete: T12 cannot land a usable cross-app client without picking *some* addressing strategy, and picking one ad-hoc inside the generator (or inside `KernelWebClient`) would bake a deployment-environment assumption into the substrate. The option space is genuinely wide and the strategies have very different boundary costs (a config map vs. a DNS dependency vs. a new SPI vs. an ops/mesh dependency), which is why this is an RFC rather than a straight-to-ADR — no decision is committed yet.

Several cross-cutting constraints bound any answer and must not be lost in the strategy debate: resolution must compose with the request enricher (ADR-032) and ADR-040's outbound-credential audience binding so that **identity survives re-addressing**; the failure modes (name unresolved / no healthy endpoint / resolution timeout) must be classifiable; whether resolution is per-call or cached-with-TTL must be decided; and **The Wall** must hold — no framework DI, no `ThreadLocal`, and resolution must not couple the client to a concrete registry/mesh type.

Note the pre-1.0 honesty discipline: the kernel is TRL-3 with no external SPI consumers. The only consumers of this decision are Stellar dogfooding and T12. So whatever shape is chosen is **designed now, built when T12 / a real multi-app corpus actually consume it** — the same design-now / build-on-usage gate the SDK's universe and presentation RFCs used.

## Investigation

### Prior art

- **Within the ecosystem (decided):** ADR-034 (`KernelWebClient` tier-neutral facade over `HttpClientEngine`, superseding ADR-026's `CommunityWebClient`); ADR-032 (`HttpClientRequestEnricher` — implicit tenant/principal/trace propagation on outbound requests); ADR-040 (`IdentityProvider` SPI — accepted 2026-06-24; outbound-credential audience binding). The kernel already resolves *every* driver via `ServiceLoader` + the Open-Core SPI/Core/Driver split — a pluggable resolver would be idiomatic, not novel.
- **External shape-setters:** service discovery in the wider industry separates into four recurring shapes — (1) **static config** (12-factor env / config maps; trivial, no liveness); (2) **DNS / DNS-SRV** (Kubernetes headless services, Consul DNS — env-provided, weight/priority via SRV records); (3) **client-side registry lookup** (Eureka, Consul agent — the client queries a registry and load-balances); (4) **service-mesh data plane** (Istio / Linkerd — the sidecar owns addressing, mTLS, retries; the app dials `localhost`/a stable name and the mesh resolves). The consistent lesson: **the right strategy is deployment-environment-dependent**, and coupling the client library to one registry type (rather than to a thin resolution interface) is the classic mistake — it forces a code change to move between dev, k8s, and an enterprise registry.

### Constraints

- **The Wall (ADR-006).** No framework DI container, no `ThreadLocal` for context, and the resolution seam must be a logical-name → endpoint *interface* — no Consul/Eureka/Istio/registry concrete type may cross the SPI or Core boundary.
- **Open-Core.** Community must get a working default driver; richer registry drivers belong in the Enterprise tier (out-of-repo). The seam must make that split natural.
- **Identity survives re-addressing.** ADR-032 enrichment and the IDP outbound-credential audience binding must compose *after* resolution, so a re-addressed request still carries the correct principal/tenant/credential (and the correct audience for the resolved peer).
- **No Waste Compute.** Resolution sits on the request hot path; per-call cost and caching/TTL semantics are a first-class design concern, not an afterthought.
- **Pre-1.0 honesty.** No external SPI consumers exist; a published `0.x` resolver surface with no consumer is a regression on arrival. Build gate ties implementation to T12 / real usage.

### Data gathered (code archaeology)

- `KernelWebClient` is single-host, confirmed in source (`exeris-kernel-core/.../http/client/KernelWebClient.java`): the constructor Javadoc reads *"a started client engine targeting a single host"*; `get(path, …)` is *"relative to the engine's target host"*; `buildRequest(...)` passes the caller `path` straight into `HttpRequest` (no host/baseURL parameter, no absolute-URL handling).
- There is **no** `ServiceResolver` / discovery / logical-addressing surface anywhere in `exeris-kernel-spi` or Core today (grep for `discovery`/`serviceName`/`ServiceResolver`/`baseUrl` returns only incidental matches).
- The just-merged W7 boot-path work (path-parameter routing + request-decoder scope) is the **server-side** ingress path and is orthogonal to this **client-side** addressing question.

### Spike outcomes

None. This RFC is design-only per the roadmap K4 gate; no prototype branch was built. The implementation crux (below, in Open Questions) — whether the `HttpClientEngine` binding is per-host or gains a per-request endpoint — is flagged for the spike that would precede the resulting ADR.

## Options Considered

### Option A: Static logical-name → endpoint map in config

`KernelWebClient` (or a thin resolver it holds) reads a `logical-name → endpoint` table from kernel config; the generated client passes a logical name and the table yields a host.

**Pros:**
- Zero new runtime dependency; no liveness machinery; trivially Wall-clean.
- Works in every environment including local dev; matches 12-factor config.

**Cons:**
- No dynamic membership or health — topology changes need a reconfig/redeploy.
- No load-balancing across replicas beyond what a single endpoint encodes; does not scale to a dynamic mesh.

**Cost:** Low — a config-shape decision plus a small lookup. No SPI surface if done as a `KernelWebClient` constructor variant.

### Option B: DNS / DNS-SRV resolution

The logical name is a DNS name; `KernelWebClient` resolves it (A/AAAA, or SRV for host+port+weight+priority) at call time. Kubernetes headless services and Consul DNS provision exactly this.

**Pros:**
- Standard and env-provided; no kernel registry; the topology lives in the platform's existing DNS.
- SRV carries port + weight + priority for client-side balancing.

**Cons:**
- DNS/JVM caching and TTL semantics are subtle (stale endpoints, negative caching).
- No application-level health beyond what DNS removal provides; SRV is not universally provisioned; ties the kernel to DNS as the resolution substrate.

**Cost:** Medium — DNS-SRV lookup + caching/TTL handling; failure-mode mapping for NXDOMAIN / empty SRV / timeout.

### Option C: `ServiceResolver` SPI seam

A new SPI interface — `ServiceResolver` (logical name → endpoint) — that `KernelWebClient` consults before each call. Community ships drivers for the static map (Option A) **and** DNS-SRV (Option B); Enterprise/registry drivers (Consul/Eureka/k8s-API/custom registry) ship out-of-repo behind the same interface. Resolution strategy becomes a deployment-time driver swap, never a client code change.

**Pros:**
- The only option under which the *same* generated client runs unchanged across dev (static), k8s (DNS-SRV), enterprise (registry), and mesh (a pass-through driver) — the idiomatic kernel `ServiceLoader`/Open-Core model.
- Subsumes A and B as drivers rather than competing with them; Wall-respecting (a resolution interface, not a concrete registry type).
- The natural seam for caching/TTL, health, and failure-mode classification to live behind one contract; future registry/mesh-aware drivers slot in without touching `KernelWebClient`.

**Cons:**
- A new SPI surface to design, TCK, and maintain — the heaviest upfront cost.
- Needs an explicit failure-mode taxonomy, a caching/ownership contract, and a defined ordering against ADR-032 enrichment + IDP credentials.
- Risks over-engineering if T12 only ever needs a static map.

**Cost:** High upfront (SPI + `AbstractServiceResolverTck` + ≥1 Community binding + identity-propagation pinning), amortised across every deployment environment and the Enterprise tier.

### Option D: Delegate entirely to a service-mesh sidecar

The kernel stays single-host. A generated app dials a stable local name; an Istio/Linkerd sidecar resolves it to the peer, and owns mTLS, retries, and observability. Addressing becomes an ops concern.

**Pros:**
- Zero kernel work; no new SPI surface.
- Leverages a mature data plane (mTLS, retry, telemetry "for free").

**Cons:**
- Hard dependency on a mesh being deployed — abandons every non-mesh deployment (local dev, simple k8s, edge, single-box).
- The logical-name → local-endpoint mapping still has to come from *somewhere* (env/sidecar config), so the problem is displaced, not solved.
- The platform loses the ability to let a generated app **self-describe its peers**, which is core to the composable-unit / marketplace direction.

**Cost:** Low for the kernel, high for the operator and for any consumer without a mesh.

### Option E (do nothing)

Leave `KernelWebClient` single-host; every caller hard-codes peer hostnames.

**Cons:**
- T12 stays blocked at the kernel seam; the multi-application mesh direction the ecosystem is committing to cannot be demonstrated end-to-end.
- Not acceptable as a standing answer — which is why this RFC exists rather than being withdrawn.

## Recommendation

**Adopt Option C — a `ServiceResolver` SPI seam — with the static map (A) and DNS-SRV (B) as the two first-party Community drivers, and the mesh case (D) reframed as a thin pass-through driver rather than a kernel non-feature.**

Option C is the only shape that keeps a generated client's *source* identical across every deployment environment while letting the *resolution strategy* be a deployment-time driver swap — which is precisely the Open-Core, `ServiceLoader`-driven model the kernel already uses for every other driver (config, persistence, transport, HTTP, codecs). It does not compete with A and B; it *subsumes* them as its first two Community drivers, which conveniently gives the abstract TCK real two-binding contract pressure rather than single-implementation guesswork (the same gate discipline the symmetric-body-codec work used). Enterprise registry drivers and a mesh pass-through driver then slot in behind the same interface with no change to `KernelWebClient` or to generated code.

Crucially, C is the shape that preserves the platform's ability to let a generated application **self-describe its peers** by logical name — the property the composable-unit / cross-app-contract / marketplace direction depends on. A and B each hard-bind the substrate to one environment; D hands peer addressing entirely to ops and so removes that self-description property for any non-mesh deployment.

The cost is honestly the highest of the options — a new SPI plus its TCK and identity-propagation contract — and it is only justified because the build gate ties implementation to real T12 / Stellar consumption: design the seam in the resulting ADR now, ship the static-map + DNS-SRV Community drivers when T12 actually emits logical-name clients, and leave registry drivers to Enterprise. Residual uncertainty is real and lives in §Open questions — chiefly the `HttpClientEngine` per-host-vs-per-request binding question, which the pre-ADR spike must settle.

### Why not the alternatives?

- **Option A (static map)** — locks the substrate to a static topology with no dynamic membership or health; correct only as *one driver* of C, not as the whole answer.
- **Option B (DNS-SRV)** — locks the substrate to DNS semantics and caching quirks with no app-level health; again correct as a *driver* of C, not the answer.
- **Option D (mesh sidecar)** — abandons every non-mesh deployment and strips the platform of peer self-description; valuable only re-expressed as a pass-through C driver for mesh-deployed operators.
- **Option E (do nothing)** — leaves T12 and the multi-app mesh direction blocked at the kernel seam.

### Risks of the recommendation

- **SPI surface before a second external consumer.** Mitigated by shipping two Community drivers (static + DNS-SRV) so the contract is pressured by real divergence, and by gating implementation on T12 consumption.
- **Hot-path resolution latency.** Per-call lookups can add latency; the resolver (not the client) should own caching + TTL to keep `KernelWebClient` stateless and No-Waste — but the cache-invalidation / health-recheck contract must be specified or it becomes a stale-endpoint footgun.
- **Identity / credential ordering.** Resolution must run *before* ADR-032 enrichment and IDP outbound-credential binding (resolve → enrich → send), so a re-addressed request carries the correct principal/tenant and the correct *audience* for the resolved peer; an unspecified ordering risks leaking a credential minted for the wrong audience.
- **Failure-mode ambiguity.** Name-unresolved vs. no-healthy-endpoint vs. resolution-timeout must map to distinct, documented error codes, or callers cannot react correctly (retry vs. fail-fast vs. circuit-break).
- **Transport security after re-addressing.** When the resolved endpoint differs from any configured host, TLS SAN / certificate matching for the resolved address must be defined (especially against the future Enterprise QUIC/mTLS path).

## Decision Record

<Filled in when status reaches ACCEPTED / REJECTED / WITHDRAWN.>

| Field            | Value                                                                  |
|:-----------------|:-----------------------------------------------------------------------|
| **Outcome**      | —                                                                     |
| **Date**         | —                                                                     |
| **Resulting ADR(s)** | —                                                                 |
| **Notes**        | —                                                                     |

## Open questions / follow-ups

- **`HttpClientEngine` binding model (implementation crux — ADR-shape blocker).** `KernelWebClient` holds one engine bound to one host. Does resolution (a) hand the engine a resolved endpoint per `send`, (b) maintain a per-host engine/connection-pool behind the resolver, or (c) make the client hold a resolver + an engine factory? The SPI surface differs *materially* between (a)/(b)/(c), so this **must be settled in the pre-ADR spike before the ADR can be drafted** — it is the gate condition on the resulting ADR, not a detail. — owner: `exeris-kernel` HTTP subsystem.
- **`ServiceResolver` return type — single vs. weighted set.** Does `resolve(logicalName)` return one `Endpoint` or a `List<WeightedEndpoint>`? Option B's DNS-SRV carries weight/priority for client-side balancing, so this decides whether load-balancing lives in `KernelWebClient` or behind the resolver driver, and shapes the SPI signature. The resulting ADR must pick one; scope it in the spike. — owner: resulting ADR.
- **Cache ownership + TTL / health-recheck contract.** Roadmap leans toward driver-owned caching; pin the invalidation and health-recheck semantics. — owner: resulting ADR.
- **Identity / IDP audience binding across re-addressing.** Lock the resolve → enrich → send ordering and the audience-binding rule against ADR-040 (`IdentityProvider` SPI) outbound-credential audience binding. — owner: security + HTTP, resulting ADR.
- **Failure-mode taxonomy → error codes (and ADR-045 interaction).** Define distinct codes for unresolved-name / no-healthy-endpoint / resolution-timeout, and specify whether the ADR-045 `HttpRetryPolicy` participates in "no-healthy-endpoint" retries or whether re-resolution is the resolver's responsibility. — owner: resulting ADR.
- **Transport security after re-addressing.** SAN/cert matching for a resolved endpoint, including the future Enterprise QUIC/mTLS path. — owner: crypto + HTTP, resulting ADR.
- **T12 contract-import shape.** How the generated client names a peer (the cross-app contract's logical name) and threads it into the resolver call — tracked in `exeris-tooling` (T12), must align with whatever name shape this RFC's seam accepts. — owner: `exeris-tooling`.
