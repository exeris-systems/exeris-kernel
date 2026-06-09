# RFC-2026-06-08: Identity Provider SPI Shape

| Field             | Value                                                                                                                  |
|:------------------|:-----------------------------------------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                                                           |
| **Author(s)**     | Arkadiusz Przychocki                                                                                                    |
| **Date Opened**   | 2026-06-08                                                                                                              |
| **Date Closed**   | 2026-06-08                                                                                                              |
| **Target ADR(s)** | ADR-040 (reserved — Identity Provider SPI; content + implementation deferred to v0.10) — see `exeris-docs/adr-index.md` |
| **Affected Repos**| `exeris-kernel` (SPI + Community driver, v0.10), `exeris-kernel-enterprise` (overlay driver, cross-repo), `exeris-ai-bridge` / `budgetHQ` (downstream consumers) |
| **Reviewers**     | —                                                                                                                      |

## Question

The kernel needs a stable, pluggable seam from *"incoming request bearing a credential"* to a populated `PrincipalContext` (+ `StorageContext`). Three orthogonal sub-questions must be answered together:

1. **Structural axis.** Do we introduce a **dedicated `IdentityProvider` SPI** that `SecurityProvider` delegates to (validation seam separated from cross-cutting orchestration), **extend the existing `SecurityProvider.authenticate` in place**, or **do nothing** (leave token→identity as host-application territory)?
2. **Reference-driver axis ("which first").** Which validator ships first as the Community reference implementation: **OIDC + JWKS**, **PASETO**, **federation** (multiple shipped IDPs), or **custom-claim** (HMAC + custom JWT shape)?
3. **Outbound axis.** How does identity propagate to downstream service-to-service calls — **parsed-identity headers only**, **pass-through bearer**, or a **token-exchange / on-behalf-of credential seam** — and does `HttpClientRequestEnricher` (ADR-032) need a companion `OutboundCredentialProvider`?

## Context

Two forcing functions make this the top pre-1.0 architectural decision in the security subsystem:

1. **Downstream B2B is blocked.** `PrincipalContext` (UUIDv7 `principalId` + `Optional<UUID> tenantId` + `roles` + `scopes`) is the canonical parsed-identity carrier, but the path from `Authorization: Bearer …` to a populated `PrincipalContext` is entirely host-application territory today. Per `docs/ROADMAP.md` §"Security: `IdentityProvider` SPI + First Driver", this is *"the single largest 'ship a B2B SaaS' blocker for the ecosystem"* (BudgetHQ and downstream SKUs cannot reach `PrincipalContext`-aware code through a kernel-supported path).

2. **The current validator is a single, static, RSA-only point.** `CommunitySecurityProvider` delegates to `CommunityJwksValidator` (Nimbus JOSE 10.8): an **immutable `Map<kid, RSAPublicKey>`** injected at construction — RS256 only, **no key rotation, no JWKS fetch, no EC**. The validation pipeline (kid → key → signature → issuer → audience → expiry) is already ~80% an OIDC resource-server validator, but it is fused into the one `SecurityProvider` implementation and cannot host a second IDP without subclassing or duplicating cross-cutting logic.

The cost of leaving this unanswered is a wrong long-lived contract: `SecurityProvider.authenticate(LoanedBuffer) → AuthenticationResult(PrincipalContext, StorageContext)` already exists and is consumed at the HTTP edge; committing the multi-IDP / rotation / outbound story to the wrong shape now would force a breaking change exactly when external consumers arrive.

> This is a strategic, multi-option question with a *recommended* direction (founder decision 2026-05-18 + structural analysis 2026-06-08) — hence an RFC, not a straight ADR. The decision is recorded below; the ADR (ADR-040) and the SPI implementation land in v0.10.

## Investigation

### Prior art

- **OIDC resource-server validation** (RFC 7519 JWT, RFC 7517 JWKS, RFC 8414 discovery). Issuer-keyed JWKS endpoints with `kid`-selected rotating keys are the dominant enterprise pattern (Auth0, Keycloak, Okta, Entra ID). Rotation with an overlap window (old + new key-set valid during cutover) is standard and is the v0.9 Sprint 4 work item (`JwksRotationPolicy`).
- **PASETO** (v4 local/public) — a smaller-surface alternative to JWT that eliminates algorithm-confusion attacks. Niche adoption; no JWKS ecosystem.
- **Multi-IDP federation.** A typical enterprise deployment fronts employees (Okta), B2C (Auth0), and service-to-service (custom/internal) simultaneously. Dispatch is by issuer (`iss` peek) or token format (JWT vs PASETO vs opaque) *before* full validation.
- **In-ecosystem registry precedent.** ADR-034 / ADR-036 established the kernel's idiomatic pluggable-driver shape: an SPI contract + a `Registry.of(List<…>)` factory + priority-ordered resolution + per-tier drivers (`HttpRequestBodyEncoder` / `HttpResponseBodyDecoder` / `HttpRequestBodyDecoder`). Any new pluggable-variant SPI should read the same way.

### Constraints

- **ADR-012 (Security Trust Model — Fail-Closed).** Normative and binding: an ordered pipeline (extract → structural → kid/JWKS → signature → issuer/audience/time → deny-state); **deterministic deny under any uncertainty**; **no fail-open** — JWKS/OIDC endpoint unavailability MUST NOT permit fail-open authorization. Secret-safe telemetry (no raw token / key bytes / sensitive claims in diagnostics). Isolation-claim contract: `KernelIsolationClaims.{ISOLATION_STRATEGY, SCHEMA_NAME, DATASOURCE_KEY}` read *after* cryptographic verification, mapping to `ImmutableStorageContext.{shared, separatedSchema, dedicated}`; fail-closed default = `SHARED`.
- **The Wall (ADR-006).** SPI/Core must stay Spring-free and host-runtime-blind; no DI container, no servlet types. Validator libraries (Nimbus, a PASETO lib) are Community-tier dependencies, never SPI.
- **Performance contract.** Token validation is a per-request edge cost, not a zero-GC hot path, but it must not allocate pathologically; raw token bytes arrive as a `LoanedBuffer` (off-heap, owned) — the contract must keep that ownership discipline.
- **`PrincipalContext` is additive-only** pre-1.0 (TRL-3) — the carrier shape does not break; new mapping needs are met by additive claims, not field changes.

### Data gathered (existing seam — verified against source 2026-06-08)

| Contract | Shape |
|---|---|
| `SecurityProvider` | `AuthenticationResult authenticate(LoanedBuffer rawToken)`, `StorageContext systemStorageContext()`, `providerId/providerName/priority` |
| `AuthenticationResult` | `record(PrincipalContext principal, StorageContext storage)` |
| `PrincipalContext` | `principalId():UUID`, `tenantId():Optional<UUID>`, `roles():Set<String>`, `scopes():Set<String>`, claims, zero-alloc `hasAnyRole/hasAnyScope` |
| `StorageContext` | `isolationKey():Optional<String>`, `strategy():{SHARED, SEPARATED_SCHEMA, DEDICATED}`, `schemaName()`, `dataSourceKey()` |
| `KernelIsolationClaims` | `x-exeris-isolation-strategy / -schema / -datasource` |
| ScopedValue slots | `PRINCIPAL_CONTEXT`, `STORAGE_CONTEXT`, `SECURITY_PROVIDER` |
| `EX-SEC-*` | `2001` principal missing · `2002` token validation failed (rawArgs `[0]`=tokenType `[1]`=reason) · `2003` RBAC insufficient · `2004` storage context missing |
| Community impl | `CommunitySecurityProvider` → `CommunityJwksValidator` (Nimbus 10.8, RS256, **static immutable kid→RSAPublicKey map**) |

## Options Considered

### Structural axis

#### Option S-A: Dedicated `IdentityProvider` SPI + `SecurityProvider` as dispatcher *(recommended)*

Introduce `eu.exeris.kernel.spi.security.identity` with `IdentityProvider` (token → `AuthenticationResult`), an `IdentityProviderRegistry` (priority-ordered, `of(List<…>)` factory à la ADR-034), and a `ClaimsMapper` seam. `SecurityProvider` keeps the single `authenticate(LoanedBuffer)` entry point but becomes a **thin dispatcher**: it selects one `IdentityProvider`, delegates validation, then applies the ADR-012 cross-cutting concerns (isolation-claim → `StorageContext`, fail-closed semantics) uniformly to whatever provider was dispatched.

**Pros:** separates IDP-specific validation (algorithm, key source, JWKS lifecycle) from cross-cutting orchestration; multi-IDP federation becomes native (registry dispatch) instead of an app-level `CompositeSecurityProvider` workaround that would have to re-implement ADR-012 invariants; rotation/refresh/EC become per-IDP capabilities sharing a common utility (`JwksRotationScheduler`); symmetric with ADR-034/036 (low reviewer cognitive load); migration is mostly refactor — `CommunityJwksValidator` extracts to `CommunityOidcIdentityProvider`.
**Cons:** one more SPI surface; a second dispatch step; the selection/deny contract must be specified carefully to avoid fail-open (see Risks).
**Cost:** moderate; net-new package + registry + one extracted driver + a new `AbstractIdentityProviderTck`. Bounded by reusing the existing `AuthenticationResult`/`PrincipalContext` targets.

#### Option S-B: Extend `SecurityProvider.authenticate` in place

Keep one SPI; add multi-IDP and rotation inside `SecurityProvider` implementations.

**Pros:** no new surface.
**Cons:** every new IDP must re-implement cross-cutting ADR-012 logic or subclass; multi-IDP forces an app-level composite that owns fail-closed semantics outside the kernel (ADR-012 leak); asymmetric with the rest of the kernel's pluggable SPIs (codec registries) → standing documentation debt.
**Cost:** low now, high later (the wrong contract hardens as consumers arrive).

#### Option S-C (do nothing): token→identity stays host-application territory

**Pros:** zero kernel work.
**Cons:** the #1 B2B blocker stays open; `ai-bridge` / BudgetHQ cannot reach `PrincipalContext` through a supported path; each app reinvents fail-closed validation (security risk). Unacceptable.

### Reference-driver axis ("which first")

- **OIDC-first** *(recommended)* — `CommunityOidcIdentityProvider` (Auth0 / Keycloak / Okta as configured instances of one generic OIDC validator). The current `CommunityJwksValidator` is already ~80% this; extraction is mostly refactor → lowest delivery risk; directly consumes the Sprint 4 JWKS rotation work that current deployments need anyway.
- **PASETO-first** — niche audience; defers the JWKS rotation/refresh work that real deployments require regardless.
- **Federation-first** — sound, but requires a shipped *second* IDP up front → larger v0.10 scope.
- **Custom-claim-first** — a feature-level concern (claim-mapping flexibility in `PrincipalContext`), addressable via `ClaimsMapper` without committing the SPI shape.

The "which first" axis is **orthogonal** to the structural axis: under S-A, each "first" is simply which reference driver lands in Community first. OIDC-first does not preclude shipping a PASETO driver as a second `IdentityProvider` later without touching the contract.

### Outbound axis (service-to-service propagation)

- **O-a: parsed-identity headers only** *(recommended for v0.10)* — downstream re-validates via its own IDP. This is the *current* ADR-032 behavior: `HttpClientRequestEnricher` (via `CommunityKernelContextEnricher`) propagates `X-Tenant-Id` / `X-Principal-Id` read from `PRINCIPAL_CONTEXT`; the kernel holds the parsed `PrincipalContext`, **not** the raw token, so no bearer is forwarded. The two directions meet only at `PrincipalContext` (no `KernelWebClient`→IDP compile edge; Wall preserved).
- **O-b: pass-through bearer** — requires `IdentityProvider` to retain/expose the raw credential for the enricher to forward. Increases the kernel's secret-handling surface (raw token retained beyond validation) — at odds with ADR-012 secret-safety. Reserved, not default.
- **O-c: token exchange / on-behalf-of / client-credentials** — `IdentityProvider` exposes an outbound credential seam (`outboundCredential(PrincipalContext) → Optional<Credential>`) that a companion `OutboundCredentialProvider` (or the enricher) consumes. The correct long-term answer for zero-trust service meshes; larger scope.

## Recommendation

**Introduce a dedicated `IdentityProvider` SPI to which `SecurityProvider` delegates validation (Option S-A); ship OIDC+JWKS as the first Community driver; keep outbound propagation at parsed-identity-headers (O-a) for v0.10 while reserving the outbound-credential seam (O-c) as the documented growth path.**

This mirrors what ADR-034 did for codecs: an SPI contract for the validation behavior, pluggable per-IDP implementations, and a thin orchestration layer above. The justification spans five dimensions:

1. **Separation of concerns.** `SecurityProvider.authenticate` today fuses *IDP-specific validation* (signature algorithm, key source, JWKS lifecycle) with *cross-cutting orchestration* (isolation strategy, storage routing, fail-closed semantics). Splitting them lets each `IdentityProvider` focus only on validation, while `SecurityProvider` stays a thin coordinator applying ADR-012 concerns uniformly to every dispatched result.
2. **Multi-IDP federation is a production scenario, not hypothetical.** Okta (employees) + Auth0 (B2C) + custom (service-to-service) coexist routinely; dispatch by issuer is the standard pattern. A one-`SecurityProvider`-per-deployment architecture forces a synthetic external `CompositeSecurityProvider` that must re-implement deterministic-deny / fail-closed itself — breaking ADR-012's guarantee that those invariants live in the kernel. An `IdentityProviderRegistry` (à la `HttpResponseBodyDecoderRegistry.of`) makes federation native.
3. **Rotation / refresh / EC are IDP-specific.** Each IDP has its own JWKS endpoint, rotation cadence, and key algorithms (RSA for Auth0, ES256 for some Keycloak setups, custom for PASETO). With validation isolated per-IDP, those capabilities specialize per driver while shared mechanism (a `JwksRotationScheduler` utility) stays a library each driver can reuse — instead of forcing every new `SecurityProvider` to re-implement rotation.
4. **Symmetry with ADR-034 / ADR-036.** The kernel has an established shape: SPI + `of(List<…>)` registry + priority resolution + per-tier drivers. Making `IdentityProvider` read the same lowers reviewer cognitive load; an IDP that uniquely *didn't* support pluggable variants would be standing documentation debt ("why does codec have a registry and IDP doesn't?").
5. **`PrincipalContext` is already a good mapping target.** `IdentityProvider.authenticate(LoanedBuffer) → AuthenticationResult(PrincipalContext, StorageContext)` is literally what `SecurityProvider.authenticate` returns today. Migration is less drastic than it looks: `CommunitySecurityProvider` becomes the dispatcher; JWKS logic extracts to `CommunityOidcIdentityProvider implements IdentityProvider`. Test surface keeps continuity — `AbstractSecurityProviderTck` covers dispatch + cross-cutting; a new `AbstractIdentityProviderTck` covers the per-IDP pipeline (kid → key → signature → issuer → audience → expiry).

### Why not the alternatives?

- **Option S-B (extend in place)** — hardens the wrong contract: every new IDP re-implements cross-cutting logic and multi-IDP leaks ADR-012 invariants to app code.
- **Option S-C (do nothing)** — leaves the #1 B2B blocker open and pushes fail-closed validation onto every application.
- **PASETO-first / federation-first / custom-claim-first** — each is reachable under S-A *after* OIDC-first without a contract change; OIDC-first is the lowest-risk first driver because the code mostly exists.
- **Outbound O-b (pass-through bearer)** — retains raw tokens in the kernel beyond validation, against ADR-012 secret-safety; only adopt if a concrete deployment requires it.

### Proposed contract sketch (non-binding — ADR-040 locks the detail)

```
package eu.exeris.kernel.spi.security.identity;

interface IdentityProvider {
    String providerId();
    int    priority();                              // ServiceLoader / registry ordering
    boolean accepts(TokenPeek peek);                // cheap pre-validation selection (issuer / format / kid)
    AuthenticationResult authenticate(LoanedBuffer rawToken);  // TERMINAL: throws on invalid (fail-closed)
    // v0.10+ growth: Optional<Credential> outboundCredential(PrincipalContext ctx);  // Outbound O-c seam
}

interface IdentityProviderRegistry {              // à la HttpResponseBodyDecoderRegistry
    static IdentityProviderRegistry of(List<IdentityProvider> providers) { … }
    Optional<IdentityProvider> select(TokenPeek peek);  // selection only — never validates
}

interface ClaimsMapper {                          // claims → PrincipalContext (+ isolation claims → StorageContext)
    PrincipalContext toPrincipal(VerifiedClaims claims);
}
```

### Claims → `PrincipalContext` mapping contract

After cryptographic verification succeeds: `sub` → `principalId` (UUIDv7; mapper coerces non-UUID subjects deterministically), tenant claim → `Optional<UUID> tenantId`, `roles`/`groups` → `roles`, OAuth2 `scope` → `scopes`. Isolation claims (`KernelIsolationClaims.*`) → `StorageContext` variant per ADR-012 §4a (fail-closed default `SHARED`). `ClaimsMapper` is the per-IDP seam that normalizes vendor claim differences (Keycloak `realm_access.roles` vs a flat `roles`) into the canonical `PrincipalContext`.

### Multi-IDP composition

Selection is by **issuer / token-format peek before validation** (`IdentityProviderRegistry.select`). Per-tenant IDP routing composes with storage isolation: the selected IDP populates `tenantId` + isolation claims, which drive `StorageContext.isolationKey` / strategy (ADR-012). Default composition is **per-tenant via the verified token's claims**, not a per-request header (a header-driven IDP choice would be attacker-controllable).

### Failure-mode classification (fail-closed, terminal)

| Failure | Outcome | Code |
|---|---|---|
| No `IdentityProvider` `accepts()` the token | reject (deny) | `EX-SEC-2002` (reason `unrecognized`) |
| Selected, signature invalid | **terminal deny — no fall-through** | `EX-SEC-2002` (`signature`) |
| Selected, claims malformed | terminal deny | `EX-SEC-2002` (`malformed`) |
| Selected, token expired | terminal deny | `EX-SEC-2002` (`expired`) |
| IDP / JWKS unreachable | **fail-closed deny** (never fail-open, ADR-012 §5) | `EX-SEC-2002` (`idp-unreachable`) |
| Validation OK, `PRINCIPAL_CONTEXT`/`STORAGE_CONTEXT` slot unbound downstream | `EX-SEC-2001` / `EX-SEC-2004` | existing |

HTTP edge: authentication failure → 401, insufficient scope/role → 403 (existing split). JFR `IdentityValidationEvent` / `IdentityRejectionEvent`, secret-safe (reason code only, no token/key bytes).

### TCK strategy

- **`AbstractIdentityProviderTck`** (new): valid token → populated `PrincipalContext`; malformed → reject distinct reason; expired → reject distinct reason; IDP unreachable → **fail-closed** (assert deny, not allow); pipeline ordering (kid → key → signature → issuer → audience → expiry); selection `accepts()` correctness.
- **`AbstractSecurityProviderTck`** (extended): dispatch selects the right `IdentityProvider`; a *selected* provider's deny is **terminal** (no fall-through to a lower-priority IDP — the fail-open federation guard); cross-cutting isolation-claim → `StorageContext` paths unchanged (ADR-012 `IsolationStrategyContract`).

### Risks of the recommendation

- **Fail-open via fall-through (the load-bearing risk).** If the dispatcher retried the next `IdentityProvider` after a *selected* provider's validation failure, a token matching issuer A but failing A's signature could be "rescued" by provider B — a federation fail-open hole. **Mitigation (normative):** selection (`accepts`) is separate from and precedes validation; once a provider is selected, its failure is terminal. `Optional` is permitted only in *selection*, never as "selected-but-invalid". Encoded as a mandatory TCK assertion.
- **Migration churn** of `CommunitySecurityProvider` → dispatcher + extracted `CommunityOidcIdentityProvider`. Mitigated by reusing `AuthenticationResult`/`PrincipalContext` and keeping `AbstractSecurityProviderTck` green throughout.
- **Enterprise lockstep.** A future `EnterpriseIdentityProvider` (priority overlay) is cross-repo; the SPI must land additively so the enterprise build does not break (pre-1.0, no breaking-change framing).

## Decision Record

| Field            | Value                                                                                              |
|:-----------------|:---------------------------------------------------------------------------------------------------|
| **Outcome**      | ACCEPTED                                                                                           |
| **Date**         | 2026-06-08                                                                                          |
| **Resulting ADR(s)** | ADR-040 (reserved — Identity Provider SPI; content + implementation in v0.10)                  |
| **Notes**        | Three axes decided: structural = dedicated `IdentityProvider` SPI + `SecurityProvider` dispatcher; first driver = OIDC+JWKS; outbound = parsed-identity headers (O-a) now, outbound-credential seam (O-c) reserved. v0.9 Sprint 4 JWKS rotation (`JwksRotationPolicy`) is a load-bearing dependency. No kernel code in v0.9 (research/decision track). |

## Open questions / follow-ups

- **EC / ES256 key support** — out of scope for the first OIDC driver (RSA/RS256 parity with today); additive per-IDP capability — owner: v0.10 implementation.
- **`JwksRotationScheduler` as shared utility** — extracted during v0.9 Sprint 4 (`JwksRotationPolicy` + overlap window), reused by `CommunityOidcIdentityProvider` — owner: Sprint 4.
- **Outbound credential seam (O-c)** — `outboundCredential(PrincipalContext) → Optional<Credential>` + companion `OutboundCredentialProvider` for `HttpClientRequestEnricher`; decide concrete shape when a service-mesh deployment requires token exchange — owner: v0.10+ RFC/ADR.
- **`TokenPeek` shape** — how cheaply selection inspects issuer/format without full parse (header slice vs minimal decode) — owner: ADR-040.

## References

- `docs/ROADMAP.md` §"Security: `IdentityProvider` SPI Direction — RFC Track" (v0.9) and §"Security: `IdentityProvider` SPI + First Driver" (v0.10).
- `docs/adr/ADR-012-security-trust-model-upgrade-for-resource-server-validation-and-fail-closed-runtime.md` — fail-closed trust model + isolation-claim contract.
- `docs/adr/ADR-032-http-client-request-enricher-spi.md` — outbound identity propagation seam.
- `docs/adr/ADR-034-client-side-body-codec-spi.md` / `ADR-036-…` — registry/driver SPI precedent.
- `docs/adr/ADR-006.link.md` — The Wall.
- v0.9 Sprint 4 — JWKS key rotation with overlap window (`JwksRotationPolicy`), load-bearing dependency.
- `../../../exeris-docs/adr-index.md` — ADR-040 reservation.
