# ADR-061: Replace the hardcoded `/secure` prefix with a declarable HTTP route-authorization policy

| Attribute       | Value                                                                                       |
|:----------------|:--------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                |
| **Deciders**    | Arkadiusz Przychocki                                                                        |
| **Date**        | 2026-08-05                                                                                  |
| **Scope**       | `kernel/security`                                                                           |
| **Owning Repo** | `exeris-kernel`                                                                             |
| **Driven By**   | Code audit of the Community HTTP admission path (2026-08-05); `docs/subsystems/security.md` §"Kernel-edge methodId enforcement is descoped"; ADR-014 §9 anti-drift block, which lists "runtime admission integration" as still planned |
| **Compliance**  | [Fail-Closed Architecture](../subsystems/security.md) §3; [No Waste Compute](../whitepaper.md) |

## Context and Problem Statement

The kernel has a real, fail-closed admission gate at the HTTP edge, and it is steered entirely by
constants compiled into the Community driver. `CommunityHttpRequestDispatcher` declares
`SECURE_PATH_PREFIX = "/secure"`, `ADMIN_PATH_PREFIX = "/secure/admin"`, and the literal scope names
`security:read` / `security:write` (`:50-53`). `requiresAdmission(path)` is
`path.startsWith("/secure")` (`:207-209`), and `isAuthorized(path)` (`:222-232`, called at `:124`)
grants on `hasAnyScope("security:write")` under `/secure/admin` and `hasAnyScope("security:read")`
elsewhere under `/secure`.

The consequence is not that the gate is weak — under `/secure` it is strict — but that an application
cannot address it. A route policy is a property of the application's own URL space, and the kernel
offers no way to state one. Every path outside the prefix takes the `else` branch of `dispatch()`
(`:86-96`) straight to the handler: no admission, no `PrincipalContext` bound. An application whose
routes live under `/api/**` therefore has no authorization at the edge at all, and no supported way to
ask for it. The `security:read` / `security:write` scope names are equally unaddressable — they are the
kernel's own names, not the application's.

Three further findings from the same audit sharpen the problem:

1. **The gate is unreachable in a default boot.** `CommunityHttpRequestProcessor:83-86` constructs the
   `SecurityInterceptor` only when `KernelProviders.SECURITY_PROVIDER.isBound()`. Nothing in production
   code binds that slot. `CommunitySecurityProvider` exists and is `ServiceLoader`-registered, but the
   Community bootstrap package ships no security `Subsystem` to bind it — while
   `docs/subsystems/bootstrap.md` already places Security in the L1 parallel-init DAG (`:39`, `:323`)
   and uses it in the DAG-cycle diagnostic example (`:552`). So the interceptor is `null`, `/secure/*`
   answers `401` unconditionally, and the entire Citadel path — token authentication, role-mask
   binding, `StorageContextBridge` derivation — never executes.
2. **The public-path allowlist is unreachable code.** `isPublicPath` (`:211-220`) lists `/health`,
   `/health/live`, `/health/ready`, `/db/ping` and `/db/roundtrip`, but is called only from
   `requiresAdmission`, behind `startsWith("/secure")`. No path can satisfy both conditions. The health
   routes moved to `CommunityHttpHealthRoutes`; the allowlist stayed behind.
3. **Half the RBAC machinery is already live, and the other half cannot be.** Contrary to the older
   note at `security.md:6`, `GeneratedRoleRegistryLoader.load()` *is* wired in production
   (`CommunityHttpRequestProcessor:86`), and `SecurityInterceptor.enrichWithRoleMask` binds a
   `MaskedPrincipal` carrying a precomputed `roleMask()`. What is missing is an enforcement call site —
   and the missing one cannot be `RoleCheckEnforcer.check(methodId, …)`, because `methodId` is assigned
   at compile time from alphabetical ordering under `@Retention(SOURCE)`. The kernel has no way to
   reconstruct a URL→`methodId` map at runtime. `security.md:220-226` already recorded this as
   descoped.

The question this ADR answers: **how does an application tell the kernel which of its HTTP routes
require what, without a configuration file, without compile-time `methodId` routing, and without each
transport driver growing its own answer?**

## 🏁 The Decision

**Route authorization becomes a contract the application declares in code, evaluated by a
driver-agnostic Core helper, with the hardcoded `/secure` convention removed.**

The mechanism stays path-shaped, because that is what the kernel can evaluate unaided. It is not a
stand-in for the descoped `methodId` path and does not wait on any build-time tooling: an application
written directly against the kernel, with no annotation processor and no code generation anywhere in
its build, declares its policy and gets edge authorization. `@RequiresRole` remains the method-level
layer above it, unchanged.

**Concrete obligations:**

1. **The contract lives in `eu.exeris.kernel.spi.http`, behind an `Optional` slot on
   `HttpKernelProviders`.** It is HTTP-shaped — it matches on method and path — so it belongs with the
   HTTP surface, not with `spi.security`. The precedent is ADR-036's `HttpRequestBodyDecoderRegistry`
   and `HTTP_REQUEST_BODY_DECODER_REGISTRY`: a registry contract in `spi.http`, resolved through an
   `Optional` accessor whose empty case means "the application supplied none". It is **not** a new
   `KernelProviders` slot. The requirement carrier it returns may reference `spi.security` types
   (scopes, roles) — SPI-to-SPI within one module, so no boundary is crossed.
2. **The decision helper lives in `eu.exeris.kernel.core.security`, alongside `RoleCheckEnforcer`.**
   Core is driver-agnostic, so every transport inherits one decision layer instead of each driver
   growing its own. This is the placement ADR-014 §4 already anticipated: "Runtime integration touches
   admission (transport edge) […]; no Community/Enterprise driver touches the registry directly." The
   Community dispatcher calls the helper; it does not decide.
3. **Rules are declared in code. There is no configuration-file rule surface, and none may be added
   under this ADR.** A property or YAML key may gate *whether* the mechanism is active; the rules
   themselves come from the supplied contract. ADR-014 §3 rejected "Configuration-file-driven role
   policy" for drift surface and loss of compile-time error; that rejection stands (see
   §"Non-revisions").
4. **Community gains a security `Subsystem` that binds `KernelProviders.SECURITY_PROVIDER`.** This is
   drift repair, not a new decision: `bootstrap.md` already specifies Security as an L1 subsystem. Its
   DAG position is the one that document already assigns — L1, parallel with Persistence.
5. **The hardcoded prefix constants and the unreachable `isPublicPath` allowlist are removed from
   `CommunityHttpRequestDispatcher`.** The allowlist is deleted rather than repaired: what counts as a
   route needing no principal becomes a statement the policy makes, so a second, driver-local notion of
   "public" would be a competing answer to a question the contract now owns.
6. **Admission semantics are unchanged.** No principal established → `401`. Principal established but
   lacking the declared requirement → `403`. These are the codes `security.md:166` already documents
   and ADR-012 already fixed; this ADR introduces no new HTTP status behaviour and no new error code.
7. **The default is "no policy declared", and it preserves today's behaviour.** An application that
   supplies nothing sees exactly what it sees now. The mechanism is opt-in, consistent with the
   ROADMAP's discipline that new SPI surface arrives as an option rather than a new default cost.
8. **`AbstractHttpRoutePolicyTck` plus a Community binding are the merge gate.** New SPI surface
   without executable contract coverage does not merge. The TCK must assert the deny paths and the
   unmatched-route path, not only that a matching rule admits — a policy suite that only proves
   admission would pass against an implementation that admits everything.

## Non-revisions

Each of the following could be mistaken for a silent rollback of an accepted decision. None is revised
here, and a future PR that appears to revise one needs its own ADR.

- **The Sprint-4 descoping of kernel-edge `methodId` enforcement stands** (`security.md:220-226`). The
  reason has been strengthened rather than weakened: `methodId` is a compile-time artefact of
  `@Retention(SOURCE)` annotation processing, so the kernel cannot reconstruct it at runtime at all —
  not merely "not yet". A URL→`methodId` routing table remains a codegen concern owned by
  `exeris-tooling`. This ADR adds no such routing to the dispatcher.
- **ADR-014 §3's rejection of configuration-file-driven role policy stands.** Obligation 3 is written to
  honour it.
- **ADR-012's trust model and admission semantics are untouched.** The new security `Subsystem` binds
  `SECURITY_PROVIDER`, which sits upstream of `IdentityStorageMapping.fromClaims`; isolation mapping
  behaviour, the §4b shared-scope rules, and the fail-closed lifecycle are unaffected.
- **ADR-014 §9's anti-drift block is completed, not contradicted.** It lists "runtime admission
  integration" as planned. This ADR delivers the path-based half and leaves the `methodId` half where
  `security.md` put it.

Also corrected **in this slice**, because it misled the very analysis that produced this ADR:
`security.md:6` advertised an enforcer "auto-bind landing" that `:220-226` had already superseded, and
claimed registry wiring was still pending when Sprint 4 had shipped it. That is a factual error about
the past, not target state, so it is fixed now rather than deferred to the implementation — leaving a
known-false sentence in place while citing it as misleading would be indefensible.

## Consequences

### ✅ Positive Outcomes

- **[+] Applications can express their own URL space.** The question "which of my routes need
  authentication, and what do they require?" becomes answerable for the first time.
- **[+] One decision layer for both consumers.** Because the helper is in Core rather than in the
  Community dispatcher, a future `exeris-spring-runtime` DSL translates onto it instead of building a
  second authorization mechanism beside it. That is the point of obligation 2, and the reason it is
  worth doing the kernel work first.
- **[+] The Citadel path stops being dead code.** Binding `SECURITY_PROVIDER` makes the interceptor, the
  role-mask population seam and `StorageContextBridge` reachable in a default boot — machinery that
  already has TCK coverage but no production execution.
- **[+] Two silent defects are closed.** An unreachable allowlist and a permanently-401 route family
  both disappear, rather than being documented as quirks.

### ⚠️ Trade-offs

- **[-] Path matching is a new runtime cost on the admission path.** The current check is one
  `String.startsWith`. A declared policy must resolve a request to a rule, and that resolution sits on
  every request. The implementation must keep the matched-route decision allocation-free on the accept
  path, in line with ADR-014 §5's constraint on the RBAC decision; a policy that allocates per request
  would fail the performance contract even while satisfying this ADR.
- **[-] Two authorization layers now exist.** Path-shaped rules at the edge, `@RequiresRole` at the
  method. Two layers mean two places to look when a request is denied, and the documentation has to say
  plainly which answers what.
- **[-] The default leaves applications unprotected.** Obligation 7 keeps behaviour unchanged for
  applications that declare nothing — which means an application that never declares a policy still has
  no edge authorization. That is deliberate for an opt-in seam, but it is a real exposure, and the
  subsystem doc must state it rather than imply that installing the kernel confers protection.
- **[-] Binding `SECURITY_PROVIDER` changes what a default boot does.** Requests under `/secure` that
  answered `401` unconditionally will now be authenticated. Pre-1.0 with no external SPI consumers this
  is low-risk, but it is a behaviour change and belongs in the release notes.

### 📋 What is NOT in scope

- **The `exeris-spring-runtime` DSL.** Translating Spring-shaped rules onto this decision layer is a
  separate decision in a separate repo, and gets its own ADR.
- **URL→`methodId` routing** and any form of per-method RBAC at the edge. See §"Non-revisions".
- **A configuration-file rule surface.** See obligation 3.
- **Non-HTTP transports.** The contract is HTTP-shaped by obligation 1. Extending an equivalent to
  other transports is a later question; the Core placement is what keeps that door open.
- **Changing the `401`/`403` mapping, adding error codes, or altering `CitadelGuard`.**

## Cross-references

- ADR-012 (Security trust model, resource-server validation, fail-closed runtime) — admission
  semantics and isolation mapping this ADR must not disturb.
- ADR-014 (`@RequiresRole` compile-time RBAC generation) — §3 rejects config-driven role policy, §4
  places runtime integration at the transport edge, §5 fixes the hot-path constraint, §9 lists the
  integration this ADR half-completes.
- ADR-036 (Server-side request-body decoder SPI) — the `spi.http` contract + `Optional`
  `HttpKernelProviders` slot shape obligation 1 follows.
- [`docs/subsystems/security.md`](../subsystems/security.md) — Citadel model, `401`/`403` mapping
  (`:166`), and the descoping note (`:220-226`).
- [`docs/subsystems/bootstrap.md`](../subsystems/bootstrap.md) — the L1 subsystem DAG that already
  includes Security.
- [`docs/subsystems/http.md`](../subsystems/http.md) — the HTTP surface the contract joins.

## Engineering Protocol

The codebase is not yet compliant; this ADR is prescriptive, not descriptive. Enforcement on landing:

1. **`AbstractHttpRoutePolicyTck` in `exeris-kernel-tck` plus a Community binding**, per obligation 8.
   Deny paths and the unmatched-route path are mandatory cases.
2. **`ExerisArchitectureTest`** continues to guard the Wall for the new SPI and Core packages; it is run
   explicitly, not assumed from CI.
3. **Subsystem docs updated in the implementing slice, not this one** — `security.md` (route policy
   replaces the prefix convention), `http.md` (the new `HttpKernelProviders` slot), `bootstrap.md`
   (Security subsystem now present in Community, not only in the contract). They describe what the
   kernel *does*; updating them before the code lands would make the docs outrun it, which this repo
   treats as a defect in its own right. The one exception is the `security.md:6` correction above,
   which removes false text about the past rather than adding text about the future.
4. **Release notes record the default-boot behaviour change** from obligation 4, per the trade-off
   above.
5. **A follow-up ADR is required** before any `exeris-spring-runtime` binding, and before any change
   that would touch a non-revision above.
