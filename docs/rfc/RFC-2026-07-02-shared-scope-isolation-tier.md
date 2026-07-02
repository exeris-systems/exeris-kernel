# RFC-2026-07-02: Should the kernel add a shared-scope tier to its isolation model — a third mutually-exclusive tier, or an orthogonal row-visibility dimension?

| Field             | Value                                                                                                                                                                                                                                                          |
|:------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                                                                                                                                                                                                   |
| **Author(s)**     | Arkadiusz Przychocki                                                                                                                                                                                                                                           |
| **Date Opened**   | 2026-07-02                                                                                                                                                                                                                                                     |
| **Date Closed**   | 2026-07-02                                                                                                                                                                                                                                                     |
| **Target ADR(s)** | **Amendment to ADR-012** (isolation-model contract, §4a) — in-place `Amended` line, same protocol as the 2026-06-10 §4a amendment; **no new `adr-index` number** (an amendment to an existing ADR does not consume a fresh slot).                               |
| **Affected Repos**| `exeris-kernel` (**owner** — `StorageContext` SPI + Persistence RLS + Security claim mapping), `exeris-sdk` (design-time scope expression — companion `RFC-2026-06-24`), `exeris-tooling` (maps author intent onto the kernel carrier this RFC picks)             |
| **Reviewers**     | —                                                                                                                                                                                                                                                              |

## Question

The kernel models tenant isolation through a single primitive — `StorageContext.isolationKey()` (one tenant's RLS key) plus a **physical** `IsolationStrategy` ∈ {`SHARED`, `SEPARATED_SCHEMA`, `DEDICATED`} (ADR-012 §4a). Every strategy is row-private: one tenant never reads another's rows. There is **no scope tier for a shared world** — a dataset many tenants co-inhabit and read across (a common reference set, a shared collaboration space, owner-tagged rows visible cross-tenant). So `isolationKey` is forced to do double duty and either over-isolates (no sharing) or under-isolates (the tenant boundary leaks). **Should the kernel add such a shared-scope tier, and is it a _third mutually-exclusive isolation tier_ (a `SHARED_WORLD` peer of the existing strategies) or an _orthogonal row-visibility dimension_ that composes with any physical strategy?** Naming is a sub-question: the ecosystem placeholder "Universe" is game-domain vocabulary — what is the kernel-neutral term?

## Context

This is the kernel half of a two-repo gap. The SDK's `RFC-2026-06-24` ("how should `@ExerisDomain` express a shared-world / `universe` data-scope") is **DRAFT and explicitly gated on this RFC** — it can pick a design-time annotation shape but cannot commit to a mapping until the kernel decides what carrier the intent lands on. The gap was surfaced by kernel-side downstream dogfooding (a multi-service build; ROADMAP finding **K3, High**) and is logged as a **decision-only** v0.10 item (its merge gate is "no kernel SPI commits in this gate"). This RFC is that decision.

The cost of leaving it unanswered: the SDK cannot close its own RFC (downstream-blocked with no committed shape), and the platform keeps conflating "tenant" and "shared world" onto one key — which the SDK RFC correctly flags as the same anti-pattern as the removed inert attributes and the `realTimeApi`/`@Action(streaming)` saga (a `0.x` surface that lies about what the platform enforces). The cost of the *wrong* answer is structural: if the shared tier is modelled as a fourth `IsolationStrategy` value, it welds a *row-visibility* concept onto the *physical-placement* axis and can never be un-welded without a second isolation-model amendment.

Whichever shape wins, one constraint is non-negotiable and inherited from ADR-012 §4a (the S-P0-07 fail-OPEN fix): **absent** scope intent must mean today's behaviour (tenant-private), and a **declared** shared-scope the running binding cannot enforce (no cross-tenant-readable RLS mode wired) must be a **terminal deny** (`EX-SEC-*`), never a silent widening of visibility. A shared-scope tier that fails open is strictly worse than no tier at all.

## Investigation

### Prior art

- **Within the kernel — ADR-012 §4a.** `KernelIsolationClaims` (`ISOLATION_STRATEGY` / `SCHEMA_NAME` / `DATASOURCE_KEY`) drives `SecurityProvider.authenticate()` to build the correct `ImmutableStorageContext` variant, fail-closed. The three strategies are all *physical* isolation mechanisms (RLS key / schema switch / datasource routing) and all row-private. There is deliberately **no** cross-tenant-readable mode. This RFC extends that contract; it does not reopen the fail-closed decision.
- **The physical vs visibility distinction.** `SHARED` / `SEPARATED_SCHEMA` / `DEDICATED` answer *"where do this tenant's rows physically live?"*. A shared-world tier answers a different question — *"who may read a given row?"* — orthogonal to placement: a shared dataset can sit inside a high-density RLS deployment (`SHARED`) just as well as a `DEDICATED` one.
- **External shape-setters.** Multi-tenancy frameworks (Hibernate `@TenantId` partition strategies; the common GLOBAL / TENANT / SHARED data-residency taxonomies) converge on an *enumerated, mutually-exclusive* discriminator **at the entity-expression layer** — precisely to make the "shared AND tenant-private" state unrepresentable. The SDK RFC adopts this for its annotation surface (rejecting a second independent boolean). Note this is an expression-layer decision, not a claim about the kernel's physical model.
- **The SDK companion (`RFC-2026-06-24`).** Fixes the SDK side: the attribute lives on `@ExerisDomain`, twin on `DomainMetadata`, additive/by-name/round-tripped (ADR-042), ships **reserved/inert** until the kernel affordance lands. It explicitly leaves the kernel carrier shape — "orthogonal `universeKey` vs reserved `isolationKey` sentinel vs composite scope carrier" — as *this* RFC's call.

### Constraints

- **The Wall (ADR-006 / ADR-012 §3).** `StorageContext` is implementation-blind; the Persistence engine reads it with zero coupling to Security. Any new carrier is an outcome accessor (an `Optional<String>` / an enum), never a mechanism (no RLS SQL, no policy object) on the SPI.
- **Fail-closed (ADR-012 §4a, S-P0-07).** Absent intent → tenant-private default; declared-but-unenforceable → terminal deny. Inherited, non-negotiable.
- **Valhalla-ready carrier.** `StorageContext`'s canonical impl (`ImmutableStorageContext`) is a flat record slated for `value record`. A new field must stay a primitive/`String`/enum — no identity-bearing scope object.
- **O(1) hot path (ADR-012 §7).** Scope resolution is per-request; the accessor must be O(1), no lazy lookup.
- **TCK parity (ADR-012 §9).** A new visibility mode requires `AbstractSecurityProviderTck` claim-resolution coverage + `AbstractPersistenceEngineTck` RLS-enforcement coverage across ≥2 bindings before the resulting ADR is complete.

### Data gathered

- Current surface (`StorageContext.java`): `isolationKey()` `Optional<String>`, `strategy()` enum (3 values), `schemaName()`, `dataSourceKey()`, `attributes()` `Map<String,String>`. Greenfield on the kernel side — no `scope` / `shared` / `universe` accessor exists.
- Claim contract (`KernelIsolationClaims`) has room for an additive claim (e.g. a `SCOPE_VISIBILITY` / `SHARED_SCOPE_KEY`) resolved after cryptographic verification, mapped fail-closed exactly like the existing three.
- No cross-tenant-readable RLS policy is implemented in any binding today, so the resulting ADR's own merge gate stays decision-first: the SPI carrier + fail-closed deny path can land ahead of a binding that actually enforces shared visibility, provided absence stays tenant-private.

## Options Considered

### Option A: third mutually-exclusive tier — a `SHARED_WORLD` value on `IsolationStrategy`

Add a fourth `IsolationStrategy` enum value; a `SHARED_WORLD` context routes to a cross-tenant-readable RLS policy.

**Pros:** smallest surface (one enum value); reuses the existing claim (`ISOLATION_STRATEGY`) and fail-closed mapping verbatim.

**Cons:** conflates two axes. `IsolationStrategy` answers *physical placement*; visibility is a different question. `SHARED_WORLD` would be mutually exclusive with `SEPARATED_SCHEMA`/`DEDICATED` — you could not have a shared dataset in a schema-isolated or dedicated-db deployment, which is a real deployment shape. Welding visibility onto placement is unpickable without a second amendment. This is the kernel-level mirror of the "two booleans, nonsensical states" trap the SDK RFC rejects.

**Cost:** tiny diff now; structural debt later.

### Option B: orthogonal row-visibility dimension — a new `sharedScopeKey()` carrier + visibility mode

Add an orthogonal accessor to `StorageContext` — e.g. `Optional<String> sharedScopeKey()` (the shared-world partition a row is tagged into) plus a small row-visibility mode — that **composes with any physical `strategy()`**. Absent `sharedScopeKey` = today's tenant-private behaviour. A new additive `KernelIsolationClaims` claim carries it; the Persistence RLS policy widens read visibility to the shared partition only when present *and* the binding supports it (else terminal deny).

**Pros:** the two axes stay separate — a shared dataset works under `SHARED`, `SEPARATED_SCHEMA`, or `DEDICATED`. Fail-closed by construction (absent → tenant-private). Additive to the SPI record (Valhalla-safe). Maps cleanly onto the SDK's mutually-exclusive *expression* discriminator (per-entity you are tenant-private XOR shared) without forcing the *physical* model to be mutually exclusive — the two layers are consistent, not in conflict.

**Cons:** larger surface than A (new accessor + claim + RLS policy + TCK on two axes); a second scope key to reason about in the isolation model.

**Cost:** moderate; the honest cost of not conflating the axes.

### Option C (do nothing): keep `isolationKey` doing double duty

Leave the model as-is; force operators to encode "shared world" by convention (e.g. a shared sentinel tenant id).

**Pros:** zero kernel change.

**Cons:** perpetuates the K3 finding — either no sharing is possible or the tenant boundary leaks by convention (fail-open by omission). Leaves the SDK RFC permanently blocked. If do-nothing were acceptable the SDK gap would not have been logged; it is not.

## Recommendation

**Option B — model the shared-scope tier as an orthogonal row-visibility dimension (`sharedScopeKey` + visibility mode) that composes with the existing physical `IsolationStrategy`, not as a fourth mutually-exclusive strategy value.**

The decisive argument is axis separation. `IsolationStrategy` is a *physical-placement* discriminator (RLS key / schema / datasource); shared-world is a *row-visibility* semantic. They are independently valid combinations, so encoding one as a value of the other (Option A) destroys a real deployment shape (shared dataset under schema/dedicated isolation) and welds two concepts that a later release cannot cleanly separate. Option B keeps `StorageContext` honest: `strategy()` still answers "where", the new carrier answers "who can read", and their product is well-defined. It is additive and Valhalla-safe, and it inherits ADR-012 §4a's fail-closed posture unchanged — absent scope key is tenant-private, declared-but-unenforceable is a terminal deny. Crucially, Option B is *consistent with* the SDK's mutually-exclusive expression discriminator rather than contradicting it: an entity is tenant-private XOR shared at the design layer, and that choice maps to presence/absence of the kernel carrier — mutually exclusive at expression, orthogonal at the physical layer.

**Naming.** Drop "Universe" from the kernel vocabulary — it is Stellar-Tactics domain metaphor and violates the kernel's domain-neutrality. Recommended kernel-neutral term: **shared scope** (`sharedScopeKey`, `SHARED_WORLD`/`SHARED` visibility mode). "Universe" stays the SDK/game-facing name; the tooling mapping records the equivalence, so the metaphor lives at the SDK edge and never crosses into the kernel SPI.

### Why not the alternatives?

- **Option A** — welds row-visibility onto the physical-placement axis; makes "shared dataset under schema/dedicated isolation" unrepresentable and is unpickable without a second amendment.
- **Option C** — leaves `isolationKey` double-duty (fail-open-by-convention) and permanently blocks the SDK companion RFC; the logged K3 finding already rejects it.

### Risks of the recommendation

- **Hinge ratified (2026-07-02).** The maintainer has ruled the shared-scope tier is an **orthogonal row-visibility dimension** (Option B), not a fourth mutually-exclusive `IsolationStrategy`. Remaining open items are sub-shapes *within* B (below), not the axis question.
- **Two scope keys increase the isolation model's cognitive surface** — mitigated by keeping absence = today's behaviour, so existing deployments are unaffected until they opt in.
- **No binding enforces cross-tenant RLS yet.** The resulting ADR must gate the enforcing binding behind TCK on both axes; the SPI carrier + deny path may land first, but only with absence staying tenant-private (no fail-open window).
- **Carrier sub-shape is deferred** (see open questions) — B fixes "orthogonal dimension"; the exact carrier (`sharedScopeKey` string vs a small `Scope` record) is a follow-up within B.

## Decision Record

| Field                | Value                                                                                                                                                                                                                                                             |
|:---------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Outcome**          | ACCEPTED                                                                                                                                                                                                                                                          |
| **Date**             | 2026-07-02                                                                                                                                                                                                                                                        |
| **Resulting ADR(s)** | ADR-012 amendment (in-place `Amended` line; no new index number)                                                                                                                                                                                                  |
| **Notes**            | Maintainer ruled all three forks: **(1) axis** — orthogonal row-visibility dimension (Option B), not a fourth `IsolationStrategy`. **(2) name** — kernel-neutral **shared scope** (`sharedScopeKey`, `SHARED_WORLD` visibility mode); "Universe" stays the SDK/game-facing name, tooling records the mapping. **(3) write model** — **read + owner-scoped write**: reads widen to the shared partition and any tenant in the scope may write *its own* (owner-tagged) rows; cross-tenant mutation of another owner's row stays out of scope. Fail-closed inheritance from ADR-012 §4a is unchanged (absent `sharedScopeKey` → tenant-private; declared-but-unenforceable → terminal deny). |

## Open questions / follow-ups

Resolved by the Decision Record above: axis (B), name (shared scope), write model (read + owner-scoped write). Remaining, deferred to the ADR-012 amendment / bindings:

- **Carrier sub-shape** — plain `Optional<String> sharedScopeKey()` vs a small composite `Scope` record (owner tenant + shared-scope key + visibility mode). Owner: ADR-012 amendment / kernel.
- **Claim name** — additive `KernelIsolationClaims` constant (`SHARED_SCOPE_KEY` favoured) and its fail-closed mapping after cryptographic verification. Owner: ADR-012 amendment / Security.
- **Owner-scoped-write RLS shape** — the write predicate must pin `owner = current tenant` even as reads widen (so a tenant cannot forge another owner's rows); needs `AbstractPersistenceEngineTck` coverage on both the read-widen and write-pin paths across ≥2 bindings. Owner: Persistence + TCK.
- **SDK companion unblock** — `exeris-sdk/RFC-2026-06-24` transcribes the mapping (its mutually-exclusive expression discriminator → presence/absence of the kernel `sharedScopeKey`) and can move off DRAFT now that this RFC is ACCEPTED. Owner: SDK/tooling.
