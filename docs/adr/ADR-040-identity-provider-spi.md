# ADR-040: Identity Provider SPI — Pluggable Token Validation + `SecurityProvider` Dispatcher

| Attribute       | Value                                                                                          |
|:----------------|:-----------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                    |
| **Deciders**    | Arkadiusz Przychocki                                                                            |
| **Date**        | 2026-06-24                                                                                       |
| **Scope**       | kernel/security (per-repo; lockstep cross-repo coordination with `exeris-kernel-enterprise` overlay + downstream `budgetHQ`/`exeris-ai-bridge` consumers) |
| **Owning Repo** | `exeris-kernel`                                                                                  |
| **Driven By**   | [RFC-2026-06-08](../rfc/RFC-2026-06-08-identity-provider-spi-shape.md) (ACCEPTED) — Identity Provider SPI Shape |
| **Compliance**  | ADR-012 (Security Trust Model — Fail-Closed); ADR-006 (The Wall); "single largest 'ship a B2B SaaS' blocker" (ROADMAP §Security) |

## Context

The kernel has no kernel-supported path from *"incoming request bearing a credential"* to a populated `PrincipalContext`. Today `CommunitySecurityProvider` delegates to a package-private `CommunityJwksValidator`: an **immutable `Map<kid, RSAPublicKey>`** injected at construction — RS256 only, fused into the one `SecurityProvider` implementation. The validation pipeline (`kid → key → alg → signature → issuer → audience → expiry → subject → isolation-claim`) is already ~80% an OIDC resource-server validator, but it cannot host a second IDP without subclassing or duplicating cross-cutting fail-closed logic. Per ROADMAP §Security this is *"the single largest 'ship a B2B SaaS' blocker for the ecosystem"* — `budgetHQ` and every downstream B2B SKU cannot reach `PrincipalContext`-aware code through a supported path.

Two forces are in tension. First, `SecurityProvider.authenticate(LoanedBuffer) → AuthenticationResult` is already consumed at the HTTP edge; committing the multi-IDP / rotation / outbound story to the wrong shape now hardens a wrong long-lived contract exactly when external consumers arrive. Second, ADR-012 requires that deterministic-deny / fail-closed invariants live **in the kernel**, not in app code — but a one-`SecurityProvider`-per-deployment architecture forces a synthetic app-level `CompositeSecurityProvider` to re-implement those invariants the moment a deployment fronts more than one IDP (Okta employees + Auth0 B2C + custom service-to-service is a routine enterprise topology).

The v0.9 Sprint 4 JWKS-rotation work (`KeyRotationPolicy` / `CommunityRotatingKeySet` / `JwksKeyResolver` / `KeySetSource`) was deliberately built format-blind and earmarked for promotion here. RFC-2026-06-08 (ACCEPTED) selected the shape across three orthogonal axes; this ADR records the decision and governs the v0.10 implementation.

The question this ADR answers: **what is the stable seam from a raw credential to a populated `PrincipalContext`, and where do multi-IDP dispatch, key rotation, and fail-closed isolation mapping live?**

## 🏁 The Decision

**Introduce a dedicated `IdentityProvider` SPI in `eu.exeris.kernel.spi.security.identity` to which `SecurityProvider` delegates validation as a thin dispatcher (RFC Option S-A); ship OIDC+JWKS as the first Community driver (`CommunityOidcIdentityProvider`); keep outbound propagation at parsed-identity headers (RFC O-a) for v0.10 while reserving — not building — the outbound-credential seam (RFC O-c).**

This mirrors what ADR-034/036 did for body codecs: an SPI contract for the per-driver behaviour, a priority-ordered `Registry.of(List<…>)` resolver, and a thin orchestration layer above. `SecurityProvider` keeps its single `authenticate(LoanedBuffer)` entry point but becomes a **router**: it selects exactly one `IdentityProvider`, delegates validation, and the dispatched result carries a `PrincipalContext` (from the per-driver `ClaimsMapper`) paired with a `StorageContext` derived by **one kernel-owned, non-overridable** isolation-mapping function.

**Concrete obligations:**

### 1. SPI surface (new, in `eu.exeris.kernel.spi.security.identity`)

Six tier-neutral types in `exeris-kernel-spi`, added at 0.10.0. No JWT/Nimbus/HTTP/RSA vocabulary crosses The Wall (ADR-006).

```java
// Top SPI — the seam SecurityProvider dispatches to.
public interface IdentityProvider {
    String providerId();
    String providerName();
    default int priority() { return 0; }

    // Routing pre-check ONLY — peeks unverified structure (format / iss) to choose a
    // candidate. Grants NOTHING. All trust comes from authenticate(...). MUST be cheap
    // and side-effect-free.
    boolean canAttempt(LoanedBuffer rawToken);

    // Full pipeline. Returns a populated result or throws SecurityAuthenticationException
    // (EX-SEC-2002). Never returns null, never fail-open.
    AuthenticationResult authenticate(LoanedBuffer rawToken);
}

// Priority-ordered selection — mirrors HttpResponseBodyDecoderRegistry exactly.
public interface IdentityProviderRegistry {
    IdentityProvider select(LoanedBuffer rawToken);          // highest-priority canAttempt; null if none
    static IdentityProviderRegistry empty() { … }
    static IdentityProviderRegistry of(List<IdentityProvider> providers) { … }
}

// Cryptographic-verification seam (kid → key → alg → signature → issuer → audience → time).
public interface TokenValidator {
    VerifiedClaims validate(LoanedBuffer rawToken);          // throws on any failure
}

// Format-blind verified-claims carrier — output of TokenValidator, input to ClaimsMapper.
public interface VerifiedClaims {
    String subject();
    String issuer();
    Set<String> audience();
    Optional<Instant> expiresAt();
    Optional<String> claim(String name);                     // single-valued string claim
    Set<String> stringSetClaim(String name);                 // multi-valued (roles, scopes)
}

// Identity-mapping seam — the ONLY app-customisable mapping point.
public interface ClaimsMapper {
    PrincipalContext map(VerifiedClaims claims);             // identity only — NOT storage
}

// Promoted verbatim from eu.exeris.kernel.community.security (already format-blind, @since 0.9.0).
public record KeyRotationPolicy(Duration overlapWindow, Duration staleFetchBudget) { … }
```

### 2. Fail-closed dispatch contract (the load-bearing invariant — ADR-012)

1. **Single-provider selection, no fallback-on-failure.** `SecurityProvider` selects exactly one provider via `IdentityProviderRegistry.select` (highest `priority()`, ties by registration order, first whose `canAttempt` returns true). Once selected, that provider's `authenticate` failure is **terminal**: the dispatcher MUST NOT try the next candidate. Re-dispatch on failure is fail-open — a token rejected by its rightful issuer could be accepted by a laxer provider (token-confusion).
2. **No candidate → deny.** `select` returning `null` is a terminal `EX-SEC-2002` deny (reason `no-identity-provider`). Never fail-open.
3. **`canAttempt` is routing, not trust.** It peeks unverified structure (JWT `iss`, token format) only to choose a provider; it grants nothing. A provider whose `canAttempt` is `true` but whose `authenticate` throws is a deny, with no re-dispatch.
4. **Storage isolation mapping is kernel-owned and non-overridable.** The `KernelIsolationClaims` → `StorageContext` mapping (the S-P0-07 fail-closed logic: declared-but-unrecognised / malformed isolation claim ⇒ terminal deny; SHARED only on genuinely-absent intent) lives in a **single shared SPI function** `IdentityStorageMapping.fromClaims(VerifiedClaims, UUID subjectId, String tokenType)`. (It sits in `…security.identity` rather than as a method on `KernelIsolationClaims` to avoid a `security ↔ security.identity` package cycle — `KernelIsolationClaims` stays free of any identity-package import.) `ClaimsMapper` maps identity (`PrincipalContext`) **only** — it never produces a `StorageContext`. `IdentityProvider.authenticate` composes `new AuthenticationResult(claimsMapper.map(claims), IdentityStorageMapping.fromClaims(claims, principalId, tokenType))`. No driver re-implements isolation mapping; the fail-closed branch exists in exactly one place.
5. **Anti-confusion ordering preserved.** The per-driver pipeline keeps the v0.9 `kid → key → alg → signature` order; algorithm is pinned **before** signature verification, never inferred from the token header alone.

### 3. First Community driver — `CommunityOidcIdentityProvider`

1. `CommunityJwksValidator` extracts to `CommunityOidcIdentityProvider implements IdentityProvider`, splitting into a `TokenValidator` (the Nimbus pipeline) + a default `ClaimsMapper` (sub → `principalId`, roles/scopes claims → `PrincipalContext`).
2. A real OIDC/JWKS `KeySetSource` lands, fetching over `KernelWebClient` / `HttpClientEngine` (ADR-034) and feeding the v0.9 `CommunityRotatingKeySet`. The **static-map path is byte-for-byte unchanged** (anti-regression).
3. `META-INF/services/eu.exeris.kernel.spi.security.identity.IdentityProvider` registration; `priority() == 0`. Multi-IDP issuer-dispatch routes via `canAttempt` (unverified `iss` peek); per-tenant routing uses `StorageContext.isolationKey`.
4. JWKS fetch is fail-closed: an unreachable/untrustworthy endpoint denies (`jwks-stale` once past the stale-fetch budget with no in-overlap generation), never fail-open.

### 4. Outbound (v0.10 = O-a; O-c reserved)

Outbound service-to-service propagation stays at **parsed-identity headers** — the current ADR-032 behaviour (`X-Tenant-Id` / `X-Principal-Id` from `PRINCIPAL_CONTEXT`; the kernel holds the parsed identity, never the raw token, so no bearer is forwarded). The outbound-credential seam (`OutboundCredentialProvider` / token-exchange, RFC O-c) is the documented growth path but is **not built** in v0.10. Pass-through bearer (O-b) is rejected — retaining raw tokens beyond validation is at odds with ADR-012 secret-safety.

### 5. Verification

1. `AbstractIdentityProviderTck`: valid token → `PrincipalContext` populated; malformed → `EX-SEC-2002`; expired → distinct reason; IDP unreachable → fail-fast (no fail-open); multi-IDP composition via `isolationKey`; storage fail-closed (declared-but-unknown strategy → deny) asserted on **every** binding.
2. `AbstractSecurityProviderTck` extended for dispatch + no-fallback-on-failure; the `AlgorithmConfusionContract` stays green as a pin.
3. Keycloak (or RFC-selected default) Testcontainers IT: request bearing a real OIDC token → `PrincipalContext` populated end-to-end.
4. JFR `IdentityValidationEvent` / `IdentityRejectionEvent`: `@StackTrace(false)`, secret-safe (opaque issuer/kid labels + counts, never raw token / key material), single-phase commit (never `begin → blocking-fetch → commit` on a virtual thread).

## Consequences

### ✅ Positive Outcomes

- **[+] B2B unblocked through a supported path.** Downstream SKUs reach `PrincipalContext` via a kernel-owned seam instead of reinventing fail-closed validation per app.
- **[+] Multi-IDP federation is native, ADR-012 stays in the kernel.** Issuer dispatch via a registry replaces an app-level composite that would have leaked deterministic-deny semantics outside the kernel.
- **[+] Rotation / refresh / EC specialise per driver.** The v0.9 rotation seam promotes unchanged; each driver owns its JWKS lifecycle while the fail-closed isolation branch stays singular.
- **[+] Symmetry with ADR-034/036.** Same SPI + `of(List<…>)` + priority shape → low reviewer ramp-up; the divergence ("why does codec have a registry and IDP doesn't?") never accrues.
- **[+] Migration is mostly refactor.** `AuthenticationResult` / `PrincipalContext` targets are unchanged; the static-map path is byte-for-byte preserved.

### ⚠️ Trade-offs

- **[-] One more SPI surface + a second dispatch step.** Justified by the fail-closed and federation requirements; bounded by reusing existing carriers.
- **[-] Storage-mapping uniformity is enforced by a shared helper + TCK, not structurally by the dispatcher.** `IdentityProvider.authenticate` still returns a fully-formed `AuthenticationResult`, so a non-conforming driver *could* hand-roll a `StorageContext`. The contract forbids it (obligation 2.4), the canonical helper is the only public path, and the TCK pins identical fail-closed behaviour on every binding — but structural relocation of storage derivation into the dispatcher is left as a candidate post-1.0 refinement.
- **[-] `canAttempt` peeks unverified claims.** This is acceptable for *routing* only because every grant flows through `authenticate`; the ADR makes the routing-vs-trust boundary explicit to keep it from drifting into a trust decision.

### 📋 What is NOT in scope

- **Outbound-credential seam / token-exchange (O-c)** — reserved, documented, not built in v0.10.
- **Enterprise overlay driver** (`priority() == 100`) — cross-repo follow-up in `exeris-kernel-enterprise`, not v0.10.
- **PASETO / federation / custom-claim drivers** — each reachable under this contract *after* OIDC-first with no contract change.
- **`PrincipalContext` shape changes** — additive-only pre-1.0; new mapping needs are met by additive claims via `ClaimsMapper`, not field changes.
- **Downstream `budgetHQ` integration** — carry-over, cross-repo.

## Cross-references

- [RFC-2026-06-08](../rfc/RFC-2026-06-08-identity-provider-spi-shape.md) — the accepted shape this ADR implements (S-A / OIDC-first / O-a).
- ADR-012 (Security Trust Model — Fail-Closed) — the normative deny contract this ADR threads through dispatch + isolation mapping.
- ADR-006 (The Wall) — SPI/Core stay Spring-free and validator-library-blind.
- ADR-034 (Client-Side Body Codec SPI) / ADR-036 (Server-Side Request Body Decoder SPI) — the `SPI + Registry.of(List) + priority` shape mirrored here.
- ADR-032 (Outbound Request Enrichment) — the parsed-identity-headers behaviour O-a preserves.
- `exeris-kernel/docs/subsystems/security.md` — subsystem contract doc (updated alongside this ADR).
