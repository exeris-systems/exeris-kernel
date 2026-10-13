---
title: "ADR-012: Security Trust Model Upgrade for Resource-Server Validation and Fail-Closed Runtime"
type: adr
slug: adr/ADR-012
visibility: public
owning-repo: exeris-kernel
status: active
---

# ADR-012: Security Trust Model Upgrade for Resource-Server Validation and Fail-Closed Runtime

| Attribute      | Value                                                                                  |
|:---------------|:---------------------------------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                                           |
| **Deciders**   | Arkadiusz Przychocki                                                                   |
| **Date**       | 2026-03-31                                                                             |
| **Amended**    | 2026-06-10 — §4a/§9: incomplete/unrecognized/malformed isolation claim is now terminal-deny, not SHARED-downgrade (closes the S-P0-07 fail-OPEN storage-isolation finding) |
| **Amended**    | 2026-07-29 — §4a/§4b/§9/§10: adds the **shared-scope tier** as an orthogonal row-visibility dimension (`sharedScopeKey`), rules its carrier shape / claim name / binding-gate interpretation, and re-points the isolation mapping site from `SecurityProvider.authenticate()` to `IdentityStorageMapping.fromClaims` per ADR-040 (implements `RFC-2026-07-02`) |
| **Amended**    | 2026-10-07 — **ACCEPTED** (2026-10-08), §4c/§4b.4/§10/§11: rules the persistence-boundary questions of the 0.13 milestone — no principal session key and no field-level encryption at rest in the kernel at 1.0 (#585), the write check pins the shared-scope tag (#611), what an absent session key yields, and a boot check on a role that bypasses row-level security (#581) |
| **Driven By**  | ADR-007, performance contract, subsystem contracts security/transport/persistence      |
| **Compliance** | [Strategic Pillar: Secure Fail-Closed Resource-Server Trust](../whitepaper.md)        |

## 1) Context and Problem Statement
- Resource-server validation must define explicit JWT/JWS/JWKS/OIDC boundaries so trust decisions are deterministic across transport, core orchestration, and provider implementations.
- Token extraction is required at the transport edge; downstream stages consume normalized token carriers only.
- Authorization must remain fail-closed when uncertainty exists in claims, key material, revocation state, or dependency health.
- This ADR resolves current-state versus target-state drift by defining a single enforceable trust contract for SPI/Core/providers/TCK.

## 2) Decision Scope
- In scope: resource-server validation stages, trust boundaries, fail-closed lifecycle semantics, telemetry/error contract, and TCK conformance obligations.
- In scope: issuer/audience/time claims checks and operational semantics for key rotation under partial outages.
- Out of scope: identity-provider implementation internals, administrative UI workflows, and non-resource-server authentication modes.

## 3) Architecture Boundaries (The Wall)
- SPI remains implementation-blind and exposes outcomes only; no provider-specific JWKS storage/fetch internals leak into SPI contracts.
- Core remains driver-agnostic and orchestrates validation via SPI contracts only; no direct Community/Enterprise internals in core runtime policy.
- Community/Enterprise providers own transport/JWKS retrieval, cache materialization, and persistence interaction while preserving explicit resource ownership.
- Boundary rule: token parsing and extraction at ingress edge, policy and decisioning in core pipeline, provider details behind SPI.

## 4) Validation Pipeline and Trust Boundaries
- Ordered pipeline: token extraction at transport edge -> structural JWT/JWS checks -> key-id selection and JWKS key resolution -> signature verification -> issuer/audience/time claims checks -> deny-state checks.
- JWT/JWS are untrusted until cryptographic verification succeeds; JWKS material is untrusted until source/policy checks pass.
- OIDC resource-server semantics are enforced at claim-validation stage, not inferred from provider internals.
- Any indeterminate state at any stage maps to deterministic deny on uncertainty.

## 4a) Isolation Strategy Claim Contract
- `KernelIsolationClaims` is the normative definition of JWT claim names for storage isolation routing; the isolation mapping MUST read `KernelIsolationClaims.ISOLATION_STRATEGY`, `SCHEMA_NAME`, and `DATASOURCE_KEY` after cryptographic verification succeeds.
- **Single mapping site (amended 2026-07-29, ADR-040 §2.4).** `IdentityStorageMapping.fromClaims` is the *one* kernel-owned claims→`StorageContext` mapping, deliberately not overridable by an application `ClaimsMapper`. Every `IdentityProvider` routes through it, so isolation resolution cannot diverge per driver. The pre-ADR-040 phrasing ("`SecurityProvider.authenticate()` MUST produce the correct variant") described the v0.9 architecture and is superseded: `SecurityProvider` is a dispatcher and MUST NOT grow a second isolation-mapping path. The mapping MUST produce the correct `ImmutableStorageContext` variant based on the claim value:
  - `SHARED`, or `ISOLATION_STRATEGY` absent/blank → `ImmutableStorageContext.shared(tenantId)`
  - `SEPARATED_SCHEMA` + `x-exeris-isolation-schema` present → `ImmutableStorageContext.separatedSchema(tenantId, schemaName)`
  - `DEDICATED` + `x-exeris-isolation-datasource` present → `ImmutableStorageContext.dedicated(tenantId, dataSourceKey)`
- **Fail-closed rule (amended 2026-06-10, S-P0-07):** the *only* permissive fall-through is a genuinely **absent/blank** `ISOLATION_STRATEGY` (no isolation intent expressed) → `SHARED` keyed on the subject. Any **declared** strategy the kernel cannot honour is a **terminal deny** (`SecurityAuthenticationException`, `EX-SEC-2002`), never a downgrade:
  - declared `SEPARATED_SCHEMA`/`DEDICATED` with a missing / blank / wrong-typed required sub-claim → **deny**
  - an unrecognized strategy value → **deny**
  - a wrong-typed `ISOLATION_STRATEGY` claim → **deny**
  - Rationale: producing `SHARED` for a declared-but-broken strategy is **fail-OPEN** — it silently downgrades the tenant to the weakest isolation tier and grants a session on malformed/injected security input. Deny reasons are secret-safe (reason code only, never the claim value). The previous "absent, unrecognized, or missing-sub-claim → SHARED" rule conflated absence (legitimate default) with declared-but-broken (must deny) and is superseded.
- `StorageContextBridge` in Core is SHARED-only by design and MUST NOT be used when `SEPARATED_SCHEMA` or `DEDICATED` strategy is required; the bridge is a fallback for system/anonymous paths only.

## 4b) Shared-Scope Tier — Row Visibility (amended 2026-07-29)

Implements `RFC-2026-07-02` (ACCEPTED 2026-07-02), which ruled the shared-scope tier is an **orthogonal
row-visibility dimension**, not a fourth `IsolationStrategy` value. This section rules the three sub-shapes
the RFC deferred to this amendment. Implementation status per sub-shape is tracked in §10.

### 4b.1 Axis separation (inherited from the RFC, restated as contract)
- `strategy()` answers **where rows physically live** (RLS key / schema / datasource). `sharedScopeKey`
  answers **who may read a given row**. They are independent: a shared dataset is valid under `SHARED`,
  `SEPARATED_SCHEMA`, *and* `DEDICATED`.
- Adding a `SHARED_WORLD` value to `IsolationStrategy` is **rejected** — it would weld visibility onto
  placement and make "shared dataset under schema/dedicated isolation" unrepresentable.

### 4b.2 Carrier shape — RULED: flat `Optional<String> sharedScopeKey()`, no visibility-mode field
- The carrier is a **single additive accessor** `Optional<String> sharedScopeKey()` on `StorageContext`,
  backed by a **6th record component** on `ImmutableStorageContext`. Rejected alternative: a composite
  `Scope` record (owner + key + mode).
- Rationale (a) **Valhalla, §"Valhalla Readiness"**: `ImmutableStorageContext` is a flat record slated for
  `value record`. A nested `Scope` record introduces an identity-bearing object inside the carrier and an
  extra indirection to flatten; a 6th `Optional<String>` component is homogeneous with the four components
  already present.
- Rationale (b) **the composite's other two fields are redundant or harmful**. The owner tenant *is*
  `isolationKey()` — a second copy could disagree with it. And a separate visibility **mode** enum
  reintroduces exactly the representable-nonsense trap the RFC rejected one layer up: `mode=SHARED` with an
  empty key, or `mode=PRIVATE` with a key present, are both constructible and neither has a defined
  meaning. **Presence of `sharedScopeKey` IS the mode** — absent = tenant-private (today's behaviour),
  present = shared-read + owner-scoped-write. One field, no invalid states, no reconciliation rule.
- Rationale (c) **O(1) hot path (§7)**: a plain accessor over a record component; no lookup, no derivation.
- **Constructor invariant (fail-closed by construction):** `sharedScopeKey` present REQUIRES `isolationKey`
  present. A shared scope without an owner identity has nothing for the write predicate to pin to, so it
  MUST be rejected in the compact constructor (`IllegalArgumentException`). This makes
  `ImmutableStorageContext.GLOBAL` (system/tenant-less, `isolationKey` empty) structurally incapable of
  carrying a shared scope.
- The strategy-exclusivity rules in the compact constructor are **unchanged** — `sharedScopeKey` composes
  with all three strategies and adds no exclusion.
- **Migration note:** the canonical constructor arity changes 5 → 6. Every in-repo construction site goes
  through the static factories or the `GLOBAL` singleton (`IdentityStorageMapping`, `StorageContextBridge`),
  so the change is contained; the accessor is additive on the `StorageContext` interface with a
  tenant-private `default`, so no external implementor is forced to change.

### 4b.3 Claim name — RULED: `SHARED_SCOPE_KEY = "x-exeris-shared-scope"`
- One additive constant on `KernelIsolationClaims`, resolved **after** cryptographic verification like the
  existing three, mapped fail-closed at the single site named in §4a.
- **Rejected: `SCOPE_VISIBILITY`.** It names a mode, and §4b.2 rules there is no mode field — a visibility
  claim would have to be reconciled against key presence, recreating the invalid states.
- **Deliberate prefix departure.** The existing three claims are `x-exeris-isolation-*`. Shared scope is
  *not* an isolation-strategy sub-claim, and naming it `x-exeris-isolation-shared-scope` would lexically
  re-weld it to the placement axis this amendment separates. The constant nevertheless stays on
  `KernelIsolationClaims` — that class is the normative home for storage-routing claim names, and moving or
  renaming it is out of scope here.

### 4b.4 Write model — read-widen, write-pin
- **Read:** the predicate widens to the shared partition when `sharedScopeKey` is present — rows tagged
  into that shared scope become readable across the tenants that carry the same key.
- **Write:** the predicate pins `owner = current tenant` **even as reads widen**. A tenant may create and
  mutate only its *own* (owner-tagged) rows within the shared scope; it can never forge or mutate another
  owner's row.
- Cross-tenant mutation of another owner's row is **out of scope** for this contract and MUST NOT be
  introduced by a binding as an extension.
- *(Amendment accepted 2026-10-08: the write pin also covers the shared-scope tag a written row
  carries — see §4c.2.)*

### 4b.5 Fail-closed inheritance (non-negotiable, unchanged from §4a)
- **Absent** `sharedScopeKey` → today's behaviour, tenant-private. Existing deployments are unaffected
  until they opt in.
- **Declared but unenforceable** — a `sharedScopeKey` present while the running persistence binding has no
  owner-scoped-write/read-widen mode wired — is a **terminal deny** (`SecurityAuthenticationException`,
  `EX-SEC-2002`, secret-safe reason `shared-scope-unsupported`), never a silent narrowing to tenant-private
  and never a widening. Silently narrowing would let an application believe it is sharing when it is not;
  silently widening is the S-P0-07 class outright.
- **Ordering consequence:** the SPI carrier + claim + deny path MAY land before any binding enforces shared
  visibility (the RFC grants this sequencing), but only under the rule above — there must be **no window**
  in which a declared shared scope resolves to anything other than deny or correct enforcement.

### 4b.6 Naming boundary
- The kernel vocabulary is **shared scope**. "Universe" is SDK/game-facing domain metaphor
  (`exeris-sdk` `RFC-2026-06-24`) and MUST NOT enter kernel SPI names, Javadoc, or claim strings; the
  tooling mapping records the equivalence at the SDK edge.

### 4b.7 Enforceability signal — RULED: an operator declaration, not a kernel probe (added 2026-07-29)

§4b.5 requires a declared shared scope to be denied wherever it cannot be enforced, which presupposes
knowing whether it can be. This section rules how that is known.

- **The kernel cannot know it.** It ships no RLS policy and cannot introspect the one a deployment wrote —
  the read-widen / write-pin predicates live in the application's DDL. Enforceability is a property of a
  schema the kernel never sees.
- **Ruled:** the deployment asserts it, through configuration key
  `exeris.security.shared-scope.enforced` (`IdentityStorageMapping.SHARED_SCOPE_ENFORCED_KEY`). Absent or
  `false` → a declared shared scope is denied. The flag is fixed at provider construction, never
  reconfigurable behind a live provider, because it participates in a per-request security decision.
- **The key is named, not yet read.** Nothing resolves that property from a configuration source today;
  the assertion reaches the mapping through explicit provider construction, and binding the key to
  configuration lands with the same wiring step that still owns issuer, audience, and JWKS endpoint. The
  constant is introduced now so the name an operator will set and the name the wiring will read are fixed
  together — the alternative is documenting a property that later turns out to be spelled differently.
- **Rejected — a capability on the persistence provider** (e.g. `PersistenceEngine.supportsSharedScope()`
  resolved at bootstrap). It reads as the cleaner, more kernel-owned option and is worse: the engine does
  not write the policy either, so it cannot answer truthfully. Sourcing the value from configuration and
  returning it through an engine method would dress an operator's claim as a kernel guarantee — the same
  category of error as documenting a contract the code does not enforce.
- **Rejected — probing the database** (e.g. reading `pg_policies` for references to the shared-scope
  setting). It is the only option that genuinely verifies, and it fails on every other axis: PostgreSQL-
  specific, defeated by a policy present on some tables and not others, requires a live database during
  bootstrap, and puts DDL introspection inside the kernel.
- **Consequence for the wrong-typed claim.** §4a's enforcement-layer split noted that a wrong-typed
  shared-scope claim collapses to absent and yields tenant-private, and that this was tolerable only while
  every declared scope was denied anyway. Once a deployment enforces, it is no longer tolerable: a caller
  with a malformed scope claim silently loses the visibility it asked for. Type-checking that claim during
  token validation is therefore a `TokenValidator` obligation on the same footing as
  `ISOLATION_STRATEGY` — the mapping structurally cannot make it. **Discharged in the same milestone**
  (deny reason `shared-scope-malformed`, pinned by `AbstractSecurityProviderTck`); the two checks stay
  separate because a wrong-typed strategy weakens the provisioned tier while a wrong-typed scope withholds
  visibility from it, so passing one says nothing about the other.

## 4c) Persistence-Boundary Rulings (amendment ACCEPTED 2026-10-08)

Four questions about what the kernel promises at the database session, raised by #585, #611 (item 4 of
#580) and #581, plus the behaviour of an absent session key. Each states the options, the
recommendation, and the reasons. The owner ruled on 2026-10-08 that each point takes the recommended
option. Nothing in this section is implemented: every behaviour change it names is planned for 0.13.
The points the recommendations left open were ruled by the owner on the same date.

Measured state the rulings build on:

- The kernel publishes exactly two session keys, `ConnectionInterceptor.SESSION_KEY_TENANT_ID`
  (`exeris.tenant_id`) and `ConnectionInterceptor.SESSION_KEY_SHARED_SCOPE` (`exeris.shared_scope`).
- `RlsConnectionInterceptor.publishSessionKeys` binds both on every strategy in one statement,
  `SELECT set_config('exeris.tenant_id', ?, false), set_config('exeris.shared_scope', ?, false)`. The
  third argument `false` makes both settings **session-scoped**, not transaction-scoped. An absent or
  blank value is bound as `''`. The `[none]` string the class also holds is a diagnostic value for
  `EX-PERS-5006` and is never published.
- `StorageContext` carries `isolationKey`, `schemaName`, `dataSourceKey`, `sharedScopeKey` and a
  `String`-valued `attributes()` map, and no principal. The `attributes()` javadoc states that its value
  type is `String` so that no identity object can cross the persistence boundary.
- A tenant-less context is a supported input. `ImmutableStorageContext.GLOBAL` is what
  `KernelProviders` returns when no context is bound, what `StorageContextBridge` derives for a principal
  without a tenant, and the default of `TransactionOrchestrator`. `CommunityPersistenceEngine` skips its
  interceptor-presence check for a blank isolation key.
- The kernel ships no RLS policy (§4b.7). The Community migrations under `db/migration/` define no
  policy and no tenant column. The only policy text the kernel publishes is the reference in the
  `RlsConnectionInterceptor` javadoc, which the four Community integration tests install
  (`CommunityPersistenceTenantIsolationIT`, `CommunityPersistenceSharedScopeIT`,
  `CommunityPersistenceIsolationLeakTckIT`, `CommunityRequestScopeBypassIsolationIT`), all on `TEXT`
  tenant columns.
- The `crypto` SPI package holds TLS transport types only (`KernelCryptoProvider`, `CryptoProviderConfig`,
  `TlsEngine` and its result and state types). No main source names a key registry.
- No main source reads `rolsuper` or `rolbypassrls`. `KernelErrorCodes` defines `EX-PERS-5001` to
  `EX-PERS-5008`; `EX-PERS-5009` is unused.

### 4c.1 Principal session key and field-level encryption at rest (#585)

**Question 1: does a contracted principal session key, for example `exeris.principal_id`, cross the Wall?**

- Option A: the RLS interceptor publishes a third key from a principal field added to `StorageContext`.
- Option B: the kernel publishes a third key from a reserved `attributes()` entry that the security edge
  fills.
- Option C: **no principal key at 1.0.** The kernel publishes the tenant key and the shared-scope key
  only. Policies keyed on a principal (`OWNER`, `TEAM`, `HIERARCHY`, `DEPARTMENT`, and a `CUSTOM`
  predicate that needs one) belong to the application or its generated binding.

**Ruled (2026-10-08): Option C.**

- Option A adds identity to the carrier that is identity-blind by design. `StorageContext` answers where
  rows live and who may read them as a tenancy matter. A principal field makes the persistence boundary
  depend on the security model, which is the coupling the `String`-only `attributes()` map exists to
  prevent.
- Option B moves the same coupling into a map key. The kernel would then promise a key whose meaning is
  a principal, filled by the security edge, read by the persistence edge: a contract across the Wall with
  a weaker type.
- A principal key would also have to meet every guarantee §4c.3 states for the existing two, with
  publication on every strategy, clearing on reuse and a defined absent value, and a TCK matrix to
  prove them. `TEAM`, `HIERARCHY` and `DEPARTMENT` need more than one identifier per request, which
  one text setting cannot carry without the kernel defining an encoding for organisational structure.
- The refusal leaves the route the SPI already has. `PersistenceEngine.registerInterceptor` accepts any
  `ConnectionInterceptor`, so an application can publish its own session key under its own name. That
  key is not a kernel contract: the kernel makes no promise about it, and an interceptor that
  publishes one inherits the obligation `RlsConnectionInterceptor` meets, publishing the key on every
  acquisition, absence included, because a session-scoped setting survives connection reuse.
- The refusal is additive-safe: a principal key added after 1.0 changes no existing signature.

For the SDK: `TENANT_ISOLATION` remains expressible against `exeris.tenant_id`. The principal-scoped
policy values have no kernel contract to compile against. The SDK either drops or narrows them, or
documents them as compiled against an application-owned key.

**Question 2: is field-level encryption at rest with a key registry kernel scope, and in which tier?**

- Option A: a Community SPI seam (an encryption service plus a key registry resolving `@Encrypted.keyId`).
- Option B: an Enterprise-tier capability.
- Option C: **outside kernel scope at 1.0, in every tier.**

**Ruled (2026-10-08): Option C.** The kernel's cryptography is TLS transport. Encryption at rest needs key
storage, rotation, wrapping and an access policy, which is a product of its own, and once a kernel SPI
names a scheme the kernel has to carry it. Tooling must not invent a scheme either, so `@Encrypted` has
no kernel target at 1.0.

- **Ruled (2026-10-08):** Option B stays open after 1.0 without a commitment: it returns, in any
  tier, only through its own ADR. No tier claims it at 1.0.

### 4c.2 The write check pins the shared-scope tag (#611, item 4 of #580)

The reference owner policy pins `tenant_id` on write and nothing else. A tenant can therefore insert or
update a row it owns carrying any `shared_scope` value. That includes a scope it does not hold, which
makes the row readable by that scope's members. A request can widen visibility past what the deployment
published, which §4b.4 forbids for reads and does not address for writes.

- Option (a): **`WITH CHECK` pins the tag.** A written row is either tenant-private (`''`, or `NULL`
  where the schema allows it) or tagged with the scope the request published.
- Option (b): the tag is application-controlled. The reason is recorded, and `docs/subsystems/persistence.md`
  names the consequence: any owner can publish its rows into any scope.
- Option (c): the kernel validates the tag. Rejected: the kernel never sees the statement's column values,
  and §4b.7 already rules that the kernel cannot introspect the deployment's policy.

**Ruled (2026-10-08): Option (a).** It is the fail-closed reading of §4b.4: the widening is a property of
the token (`x-exeris-shared-scope`, verified, mapped at one site, denied when not enforced). Option (b)
would let a row's own column grant visibility that no token carried. The reference owner policy becomes:

```sql
CREATE POLICY tenant_isolation ON table_name
  USING (tenant_id = current_setting('exeris.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true)
              AND COALESCE(shared_scope, '') IN ('', current_setting('exeris.shared_scope', true)));
```

The tenant arm shown is today's `TEXT` form. §4c.3 rules the form the tenant arm takes. Under a published
scope `S` the check admits `''` and `S`. Under a cleared scope (`''`) it admits `''` only. Under a setting
that was never published (`NULL`) a scoped row evaluates to `NULL` and is refused. The `FOR SELECT`
widening policy is unchanged.

Planned for 0.13 with the ruling: the `RlsConnectionInterceptor` reference text,
`CommunityPersistenceSharedScopeIT` installing the same check, and `AbstractSharedScopeAccessMatrixTck`
cells for (i) an insert of an own row tagged with a scope the request does not hold, refused, (ii) a
re-tag of an own row to such a scope, refused, and (iii) an own row written with the published scope
and with `''`, accepted.

- Consequence: `WITH CHECK` sees only the new row, so an owner whose request carries no scope, or a
  different one, cannot update its own row tagged `S` and keep the tag. It must re-tag to `''` or to its
  current scope. A per-deployment trigger comparing old and new rows could relax this. The kernel ships
  no DDL and does not offer one.
- **Ruled (2026-10-08):** an owner may un-share its own row (re-tag `S` → `''`) from a request that
  does not carry `S`. The check allows it, because narrowing one's own row's visibility widens nothing.
  Refusing it would mean comparing the old row, which only a trigger can do.

### 4c.3 What an absent session key yields

Under the reference `TEXT` policy, a request without a tenant publishes `exeris.tenant_id = ''`, the
predicate `tenant_id = ''` matches no real tenant's row, and the statement **returns an empty result
with no error**. A mis-scoped read therefore looks like a read of an empty table. Two further
consequences follow from the policy text. They are derived, not measured on a live database here:

- The same predicate in `WITH CHECK` admits a row whose `tenant_id` is `''`. A tenant-less request can
  write a row that every later tenant-less request reads back.
- On a `uuid` tenant column the unguarded cast `current_setting('exeris.tenant_id', true)::uuid` raises
  `invalid input syntax for type uuid` on `''`. The `RlsConnectionInterceptor` javadoc currently
  recommends `NULLIF(...)::uuid`, which turns that error back into an empty result.

Options:

- Option A: the kernel refuses to open a connection for a tenant-less context while RLS is enabled.
  Rejected: tenant-less contexts are a supported input (system paths, unauthenticated paths,
  `TransactionOrchestrator`'s default), and most of them touch no scoped table. The kernel cannot tell
  which statements will.
- Option B: absence yields an empty result, documented as the contract. This is today's behaviour and
  costs nothing. Confidentiality holds, but a missing tenant binding stays silent, and write-side
  absence is open on `TEXT`.
- Option C: **the kernel's promise ends at publication. The reference policy makes an absent tenant key
  raise.** The shared-scope arm keeps matching nothing, because absent scope is the legitimate
  tenant-private case (§4b.5).

**Ruled (2026-10-08): Option C.** The kernel promises, for every connection an engine hands out while the
RLS interceptor is installed:

1. both session keys are published by this acquisition, on every strategy, and never carry the previous
   borrower's value;
2. an absent or blank value is published as `''`, never as `NULL` and never as a sentinel;
3. a tenant-less context is not refused at the persistence boundary.

What `''` yields is the policy's decision, and the kernel's reference policy, planned for 0.13, decides
it as follows:

- **Tenant arm:** absence raises. On `uuid` columns the unguarded cast does this, and the `NULLIF` advice
  is withdrawn from the reference. On `TEXT` columns, and for a message that names the cause, a
  deployment-defined function fails the statement:

  ```sql
  CREATE FUNCTION exeris_required_setting(key text) RETURNS text
    LANGUAGE plpgsql STABLE AS $$
  DECLARE v text := current_setting(key, true);
  BEGIN
    IF v IS NULL OR v = '' THEN
      RAISE EXCEPTION 'session key % is not published', key USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN v;
  END $$;
  -- USING / WITH CHECK (tenant_id = (SELECT exeris_required_setting('exeris.tenant_id')))
  ```

  The uncorrelated subquery lets the planner evaluate the function once per statement rather than per
  row. The cost has not been measured. Whether a statement that reaches no row still raises depends on
  the plan, and has not been measured either.
- **Shared-scope arm:** `NULLIF(current_setting('exeris.shared_scope', true), '')` is unchanged. An
  absent scope matches no row, which is the tenant-private case.

Reasons: under Option B a missing tenant binding stays silent at the one layer that can see it, and
write-side absence is left open. A raise turns both into a failed statement that the kernel already
reports (`EX-PERS-5003`). Kernel-owned tables carry no policy, so the system paths in Option A's
rejection are unaffected.

- **Ruled (2026-10-08):** raise-on-absence is a **MUST** for a policy that claims conformance.
  `AbstractSharedScopeAccessMatrixTck` and the tenant-isolation suites gain an absent-tenant cell
  asserting a refusal, so Option B is not a conforming deployment choice.
- **Ruled (2026-10-08):** the reference text of `exeris_required_setting` is published in
  `docs/subsystems/persistence.md`, beside the reference policy, for deployments to install. The kernel
  does not install it (§4b.7).

### 4c.4 Boot check on a role that bypasses row-level security (#581)

A role with `SUPERUSER` or `BYPASSRLS` skips every policy, `FORCE`d ones included, so RLS isolation
then rests on application code alone and nothing reports it. Only the engine that owns the connection
can check the role.

Options for the default:

- Option A: **refuse the boot** unless the deployment opts into a downgrade.
- Option B: log an error and record a JFR event, and continue.
- Option C: no check (today).

**Ruled (2026-10-08): Option A**, with a configuration key that downgrades it to Option B. §5 already denies
readiness when a trust prerequisite is missing. An RLS deployment whose role bypasses RLS has the same
standing: the isolation it configured (`persistence.rlsEnabled`) is not applied. A deployment that must
connect that way, for example a local quick-start as the database superuser, says so in configuration
and is told about it on every boot.

Shape, planned for 0.13; the code and the event are **to be registered** with the
implementation:

- **When:** on the first connection of each pool the engine creates while the RLS interceptor is
  installed. Each pool may log in as a different role, and the check costs one query per pool, never per
  request.
- **What:** `SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user`. Either value
  `true` is a bypass. An engine whose database is not PostgreSQL skips the check.
- **Key:** `persistence.rlsAllowBypassRole`, `boolean`, default `false`, immutable. The name follows the
  camelCase `persistence.*` family that `persistence.rlsEnabled` and `persistence.perTenantPooling`
  belong to.
- **Refusal:** `PersistenceProviderException` with a new code **`EX-PERS-5009`**, which takes the next free
  number in the `EX-PERS-5xxx` family. rawArgs: provider name, role name, and which attribute was found.
  A separate code from `EX-PERS-5001` (bootstrap failure), because the operator's remedy is a role
  change, not a driver or connection fix.
- **Downgrade:** with the key `true`, an ERROR log line and a JFR event in the persistence namespace,
  named `eu.exeris.kernel.persistence.RlsBypassRole`, carrying the same three fields.
- **TCK / IT:** a PostgreSQL integration test connecting as a `BYPASSRLS` role sees the refused boot
  by default and the error plus the event with the key set; a `NOSUPERUSER NOBYPASSRLS` role boots
  unchanged.

Limits, stated so the check is not read as more than it is:

- It observes `current_user` at the first connection. A later `SET ROLE` issued by application SQL is
  not observed. Role attributes are not inherited through membership in PostgreSQL, but `SET ROLE`
  assumes them.
- It does not detect the table-owner exemption. A role that owns an un-`FORCE`d table is exempt from
  that table's policies without either attribute. That case stays documented (`FORCE` is required),
  because detecting it needs the policy-catalogue introspection §4b.7 rejects.

- **Ruled (2026-10-08):** the downgrade is the boolean `persistence.rlsAllowBypassRole` (default
  `false`), as the shape above states, not a two-valued `refuse` / `warn` setting.

## 5) Fail-Closed Lifecycle Contract
- Bootstrap readiness is denied if required trust anchors, JWKS resolution path, or validation dependencies are unavailable.
- Runtime degradation (cache corruption, resolver outage, claim-policy backend uncertainty) must fail closed with deterministic deny.
- No fail-open fallback is permitted for resource-server authorization decisions.
- Service returns to ready/serving only after validation pipeline health and trust prerequisites are restored.

## 6) Key Rotation and Failure Semantics
- Key-id selection is mandatory and deterministic; ambiguous or missing `kid` resolution maps to deny.
- Rotation supports overlap window for old/new keys with explicit cutover deadline.
- TTL/staleness policy is explicit: stale JWKS beyond allowed window maps to deny unless policy defines a bounded emergency grace mode.
- Outage behavior is explicit: JWKS/OIDC endpoint unavailability does not permit fail-open authorization.
- Rotation/fetch failures are classified as indeterminate and therefore denied.

## 7) Authorization Hot Path Constraints (Performance Contract)
- Authorization decision checks must be O(1) per request for in-memory policy/key lookup under normal operation.
- No reflection on the hot path.
- No dynamic graph traversal on the hot path.
- No blocking remote key fetch on the request critical path when cache/prefetch contract is active.
- Decision carriers remain immutable and allocation-disciplined for steady-state throughput.

## 8) Error and Telemetry Contract
- Error categories use stable `EX-SEC-*` taxonomy for portable diagnostics across providers.
- Payloads are secret-safe: no raw token, key bytes, or sensitive claims in emitted diagnostics.
- Typed JFR events are required for bootstrap failures, key-rotation transitions, validation-stage denies, and trust-boundary degradation.
- Invalid-token and infrastructure-indeterminate categories remain operationally distinguishable while both enforce deny.

## 9) TCK Obligations
- Abstract TCK suites must codify stage ordering, deterministic deny on uncertainty, lifecycle fail-closed gates, and rotation semantics.
- Community bindings must pass the abstract suites for all resource-server contract scenarios.
- Enterprise bindings must pass the abstract suites for all resource-server contract scenarios.
- Contract changes are incomplete until abstract suites and both binding layers validate observable behavior.
- `AbstractSecurityProviderTck.IsolationStrategyContract` codifies the isolation claim resolution paths: SHARED default (absent claim), explicit SEPARATED_SCHEMA / DEDICATED happy paths, and **terminal deny (`EX-SEC-2002`)** on a missing sub-claim, on an unrecognized strategy value, and on a wrong-typed claim (amended 2026-06-10 — these were previously fail-closed-to-SHARED downgrades; see §4a).
- `AbstractPersistenceEngineTck.DedicatedRoutingContract` codifies DEDICATED pool routing, EX-PERS-5006 on unknown key, and RLS interceptor bypass for DEDICATED strategy.

### Shared-scope obligations (amended 2026-07-29, §4b)
- `AbstractSecurityProviderTck.IsolationStrategyContract` **and** `AbstractIdentityProviderTck` must both
  cover shared-scope claim resolution: absent claim → tenant-private (`sharedScopeKey` empty); present claim
  → carried onto the resolved `StorageContext`; present-but-unenforceable → terminal deny
  (`EX-SEC-2002` / `shared-scope-unsupported`). Both suites are named because §4a routes every provider
  through one mapping site — the contract must be proven from both entry surfaces.
- `AbstractSecurityProviderTck.IsolationStrategyContract` must additionally cover a **wrong-typed**
  shared-scope claim → terminal deny (`EX-SEC-2002` / `shared-scope-malformed`). Like the wrong-typed
  strategy case this is the binding's own obligation, not inherited from the mapping, and it is kept
  separate from that case: both stem from `claim()` reporting a wrong-typed value as absent, but one
  weakens the provisioned tier while the other silently withholds visibility from it, so a binding
  passing one says nothing about the other (§4b.7).
- `ImmutableStorageContext` must have a constructor-invariant case: `sharedScopeKey` present with
  `isolationKey` absent is rejected (§4b.2).
- The persistence TCK must carry a shared-vs-tenant **access matrix**: the read-widen path (a
  tenant reads another owner's row inside the same shared scope) and the write-pin path (a tenant cannot
  write a row owned by another tenant, inside or outside the shared scope). Cross-tenant mutation stays out
  of scope (§4b.4) and MUST NOT be added to the matrix as an allowed cell.
  - **Host corrected 2026-07-29 (v0.11 S3): `AbstractSharedScopeAccessMatrixTck`, not
    `AbstractPersistenceEngineTck`.** This clause originally named the latter. Implementation showed that
    would be self-defeating: the only in-repo binding of `AbstractPersistenceEngineTck` runs on H2 in
    PostgreSQL-compatibility mode, and H2 implements neither `CREATE POLICY` nor `current_setting`, so a
    matrix hosted there could only ever *skip*. That is a vacuous anchor — the same failure class as the
    wrong-typed isolation case, which sat unbound behind a green suite until 2026-07-29. The matrix
    therefore lives in its own abstract suite whose binding is a live-database one, which keeps the
    obligation both abstract and non-vacuous. The requirement is unchanged; only its host is.
- **"Two bindings" — RULED (amended 2026-07-29).** The §9 two-binding language means *every binding that
  exists in-repo, plus a recorded obligation on out-of-repo bindings* — not "block the contract until a
  second in-repo binding is invented". Only one in-repo persistence binding exists (Community/Postgres), so
  the merge gate for the shared-scope tier is: **the Community binding green in CI**, plus an explicit,
  contractual obligation that the out-of-repo Enterprise binding passes the same abstract suites before it
  claims support. This is the Milestone Gate Policy reading (`Abstract*Tck` + Community binding for an SPI
  minor release) and it is recorded here so the obligation cannot be silently dropped when the Enterprise
  binding lands.

## 10) Implemented vs Planned (mandatory anti-drift block)
- Implemented now (repository state): security/transport/persistence/core/tck modules and contracts exist in active refactor trajectory; current repository state may include transitional placements and placeholders.
- Implemented now (repository state): Community provider path enforces JWT/JWKS resource-server checks (`kid` resolution, RS256 signature verification, issuer/audience/expiry validation) with fail-closed deny semantics and HTTP admission behavior split as 401 (authentication failure) versus 403 (insufficient scope).
- Implemented now (repository state): `KernelIsolationClaims` defines normative JWT claim names for storage isolation strategy routing.
- Implemented now (repository state, corrected 2026-07-29): the isolation claims are read and mapped in `IdentityStorageMapping.fromClaims` (SPI, ADR-040) — the single kernel-owned fail-closed site — producing all three `ImmutableStorageContext` variants after JWT validation. Its only production caller is `CommunityOidcIdentityProvider`; no `SecurityProvider` implementation reads `KernelIsolationClaims` any more. The earlier "Community SecurityProvider reads isolation claims" line described the v0.9 architecture.
- **Implemented now (§4b carrier + claim + deny, v0.11 S2):** `StorageContext.sharedScopeKey()` (additive, tenant-private `default`), the 6th `ImmutableStorageContext` component with its `sharedScopeKey`-requires-`isolationKey` constructor invariant, `KernelIsolationClaims.SHARED_SCOPE_KEY`, and the §4b.5 terminal deny in `IdentityStorageMapping.fromClaims` (`EX-SEC-2002` / `shared-scope-unsupported`). The deny did not lag the carrier — both landed in the same change, as §4b.5 requires.
- **Implemented now (§4b.4 enforcement substrate, v0.11 S3):** the Community persistence binding publishes `exeris.shared_scope` alongside `exeris.tenant_id` on every strategy, so a deployment's RLS policy can widen its read predicate while `WITH CHECK` keeps writes pinned to the owner. The setting is published unconditionally — as `""` when absent — because session-scoped settings survive connection reuse and skipping the statement would widen a request that declared no scope. `AbstractSharedScopeAccessMatrixTck` codifies the matrix, bound against live PostgreSQL.
- **Implemented now (§4b.7 enforceability signal, v0.11):** the kernel ships no RLS policy and cannot introspect the deployment's, so the deployment asserts enforceability itself via `exeris.security.shared-scope.enforced` (`IdentityStorageMapping.SHARED_SCOPE_ENFORCED_KEY`). `fromClaims` carries a declared shared scope onto the resolved context where the deployment has opted in, and denies it everywhere else. The tier is reachable end-to-end from that point: carrier, claim, mapping, and RLS enforcement all exist and are connected. Absent opt-in the behaviour is unchanged, so no existing deployment moves off tenant-private.
- **Implemented now (§4b.7 wrong-typed shared scope, v0.11):** the driver-side type check that closes §4b.7's own consequence. `VerifiedClaims.claim` reports a wrong-typed claim as absent, so a malformed shared scope would reach the mapping as "none declared" and resolve to tenant-private — harmless while every declared scope was denied, and not harmless once §4b.7 made that deny conditional, since an enforcing deployment would then silently withhold visibility the caller asked for. The check sits in the binding's token validation next to the `ISOLATION_STRATEGY` one (§4a enforcement layers), denying `shared-scope-malformed`, and is pinned for every binding by `AbstractSecurityProviderTck` rather than left to each one's diligence. Both axes are checked because the structural cause is shared but the damage is not: a wrong-typed strategy weakens the tier, a wrong-typed scope withholds from it.
- Implemented now (repository state): Community `PersistenceEngine` routes DEDICATED strategy to per-tenant pools from `PersistenceConfig.dedicatedDataSources()`.
- **Planned for 0.13 (§4c, ACCEPTED 2026-10-08):** the scope-pinning `WITH CHECK` in the reference policy, the
  Community binding and the shared-scope matrix (§4c.2); the raise-on-absent-tenant reference policy
  (§4c.3); the bypass-role boot check with `persistence.rlsAllowBypassRole`, `EX-PERS-5009` and its JFR
  event (§4c.4). None of it is implemented.
- Repository-state disclaimer: this ADR defines target contract semantics even where implementation is currently partial, staged, or temporarily embedded.
- Planned target state: unified JWT/JWS/JWKS/OIDC resource-server trust pipeline with deterministic deny on uncertainty, fail-closed lifecycle gates, explicit rotation TTL/staleness/outage semantics, and mandatory typed telemetry categories.
- Anti-drift rule: if code differs from ADR text, update implementation plus TCK or amend ADR before merge.

## 11) Consequences and Trade-offs
- Benefit: stronger security posture under outage or partial subsystem failure via deterministic deny.
- Benefit: clearer trust boundaries and incident triage through stable EX-SEC taxonomy and typed telemetry.
- Cost: stricter readiness can increase startup failures for misconfigured deployments.
- Cost: stronger contract constraints reduce provider-level shortcut flexibility.

### Shared-scope tier (amended 2026-07-29)
- Benefit: the placement and visibility axes stay separable, so a shared dataset is expressible under any
  physical strategy — and the SDK's mutually-exclusive design-time discriminator maps cleanly onto presence
  or absence of one kernel field.
- Benefit: no invalid states. One field carries the whole decision (§4b.2), so there is no mode/key pair to
  reconcile and no fail-open gap between them.
- Cost: the isolation model now has two keys to reason about (`isolationKey` = owner, `sharedScopeKey` =
  shared partition). Mitigated by absence meaning today's behaviour exactly.
- Cost: the canonical `ImmutableStorageContext` constructor grows to six components (§4b.2 migration note).
- Cost: the RLS predicate becomes asymmetric (read widens, write pins). This is the honest cost of the
  write model and is why §9 demands an access **matrix** rather than a happy-path case.

### Persistence-boundary rulings (amendment ACCEPTED 2026-10-08, §4c)
- Benefit: the kernel's promise at the database session is stated as a list (two keys, published per
  acquisition, `''` for absence), so a binding knows what it can rely on and what its policy decides.
- Benefit: a token is the only source of widened visibility. A row's own column can no longer grant it
  (§4c.2).
- Cost: principal-scoped policies have no kernel contract at 1.0 (§4c.1). The SDK narrows or drops
  those values, or compiles them against application-owned keys the kernel does not guarantee.
- Cost: an owner whose request does not carry a scope cannot keep its own row in that scope across
  an update (§4c.2).
- Cost: under §4c.3, a tenant-less statement against a scoped table fails rather than returning
  nothing. A deployment that relied on the empty result sees errors after adopting the reference.
- Cost: under §4c.4, a deployment connecting as a superuser with RLS enabled no longer boots until it
  sets `persistence.rlsAllowBypassRole` or changes the role.
- **SPI javadoc drift (correction planned for 0.13, not made here).** `StorageContext` (its strategy
  table and the `isolationKey()` javadoc), `PersistenceEngine.openConnection`'s `@implSpec`,
  `ConnectionInterceptor` (its strategy table and its example), the `KernelProviders` example and the
  `KernelErrorCodes.EX_PERS_5006` javadoc describe `SET LOCAL exeris.tenant_id`, which is
  transaction-scoped. The implementation issues session-scoped `set_config(..., false)`, and §4c.3's
  guarantees depend on that scope. `ConnectionInterceptor`'s table also lists DEDICATED as a no-op,
  where `RlsConnectionInterceptor` publishes both keys on DEDICATED too.

### Dissent recorded
- **On the carrier (§4b.2).** The composite `Scope` record has a real argument: it names the concept
  explicitly and would let a future third visibility mode arrive without another accessor. It was rejected
  on Valhalla flatness and on the redundant-owner / invalid-state grounds above. If a third mode is ever
  genuinely needed, the escape hatch is to promote the carrier then — the accessor is `Optional`-typed and
  additive, so nothing here forecloses it.
- **On the claim prefix (§4b.3).** Departing from the `x-exeris-isolation-*` family costs naming
  consistency in a class literally named `KernelIsolationClaims`, and a reviewer may reasonably prefer
  `x-exeris-isolation-shared-scope`. Axis separation was judged the stronger signal; the counter-argument
  is recorded rather than dismissed.
- **On the binding gate (§9).** Accepting a single in-repo binding weakens the two-binding assurance to a
  contractual promise about out-of-repo code. The alternative — blocking the tier until a second in-repo
  persistence binding exists — was rejected as gating a security contract on unrelated driver work, but the
  weakening is real and is the reason the obligation is written into §9 rather than left implicit.

## 12) Acceptance Criteria and Merge Gates
- ADR approval by architecture/security maintainers.
- SPI/Core/provider behavior aligns with fail-closed and deterministic-deny semantics.
- Abstract TCK suites pass with Community and Enterprise bindings for covered scenarios.
- Typed JFR events and EX-SEC category stability validated with secret-safe payload constraints.
- Required docs list updated to remove contract ambiguity and repository drift.

## 13) Rollout and Migration Notes
- Phase 1: land contract wording and abstract TCK assertions.
- Phase 2: align provider pipelines for `kid` selection, rotation overlap, TTL/staleness policy, and outage deny behavior.
- Phase 3: enforce bootstrap/runtime fail-closed lifecycle gates in serving-state transitions.
- Phase 4: complete telemetry/error taxonomy alignment and lock merge gates.
- Migration principle: preserve secure deny defaults over backward-compatible fail-open behavior.

## Required Follow-up Docs Updates
- docs/subsystems/security.md
- docs/subsystems/transport.md
- docs/subsystems/persistence.md
- docs/modules/01-spi.md
- docs/modules/02-core.md
- docs/modules/03-community.md
- docs/modules/04-enterprise.md
- docs/modules/05-tck.md
- docs/performance-contract.md
- docs/architecture.md
