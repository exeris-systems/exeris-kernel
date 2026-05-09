# ADR-012: Security Trust Model Upgrade for Resource-Server Validation and Fail-Closed Runtime

| Attribute      | Value                                                                                  |
|:---------------|:---------------------------------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                                           |
| **Deciders**   | Arkadiusz Przychocki                                                                   |
| **Date**       | 2026-03-31                                                                             |
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
- `KernelIsolationClaims` is the normative definition of JWT claim names for storage isolation routing; SecurityProvider implementations MUST read `KernelIsolationClaims.ISOLATION_STRATEGY`, `SCHEMA_NAME`, and `DATASOURCE_KEY` after cryptographic verification succeeds.
- `SecurityProvider.authenticate()` MUST produce the correct `ImmutableStorageContext` variant based on the claim value:
  - `SHARED` or absent/unrecognized → `ImmutableStorageContext.shared(tenantId)`
  - `SEPARATED_SCHEMA` + `x-exeris-isolation-schema` present → `ImmutableStorageContext.separatedSchema(tenantId, schemaName)`
  - `DEDICATED` + `x-exeris-isolation-datasource` present → `ImmutableStorageContext.dedicated(tenantId, dataSourceKey)`
- Fail-closed rule: if `ISOLATION_STRATEGY` is absent, unrecognized, or the required accompanying claim (`SCHEMA_NAME` / `DATASOURCE_KEY`) is missing → produce `SHARED`; this is the deterministic-deny analog for the storage boundary.
- `StorageContextBridge` in Core is SHARED-only by design and MUST NOT be used when `SEPARATED_SCHEMA` or `DEDICATED` strategy is required; the bridge is a fallback for system/anonymous paths only.

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
- `AbstractSecurityProviderTck.IsolationStrategyContract` codifies all five isolation claim resolution paths (SHARED default, SEPARATED_SCHEMA, DEDICATED, fail-closed on missing sub-claim, fail-closed on unrecognized strategy value).
- `AbstractPersistenceEngineTck.DedicatedRoutingContract` codifies DEDICATED pool routing, EX-PERS-5006 on unknown key, and RLS interceptor bypass for DEDICATED strategy.

## 10) Implemented vs Planned (mandatory anti-drift block)
- Implemented now (repository state): security/transport/persistence/core/tck modules and contracts exist in active refactor trajectory; current repository state may include transitional placements and placeholders.
- Implemented now (repository state): Community provider path enforces JWT/JWKS resource-server checks (`kid` resolution, RS256 signature verification, issuer/audience/expiry validation) with fail-closed deny semantics and HTTP admission behavior split as 401 (authentication failure) versus 403 (insufficient scope).
- Implemented now (repository state): `KernelIsolationClaims` defines normative JWT claim names for storage isolation strategy routing.
- Implemented now (repository state): Community SecurityProvider reads isolation claims and produces all three `ImmutableStorageContext` variants after JWT validation.
- Implemented now (repository state): Community `PersistenceEngine` routes DEDICATED strategy to per-tenant pools from `PersistenceConfig.dedicatedDataSources()`.
- Repository-state disclaimer: this ADR defines target contract semantics even where implementation is currently partial, staged, or temporarily embedded.
- Planned target state: unified JWT/JWS/JWKS/OIDC resource-server trust pipeline with deterministic deny on uncertainty, fail-closed lifecycle gates, explicit rotation TTL/staleness/outage semantics, and mandatory typed telemetry categories.
- Anti-drift rule: if code differs from ADR text, update implementation plus TCK or amend ADR before merge.

## 11) Consequences and Trade-offs
- Benefit: stronger security posture under outage or partial subsystem failure via deterministic deny.
- Benefit: clearer trust boundaries and incident triage through stable EX-SEC taxonomy and typed telemetry.
- Cost: stricter readiness can increase startup failures for misconfigured deployments.
- Cost: stronger contract constraints reduce provider-level shortcut flexibility.

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
