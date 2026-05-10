# ADR-014: `@RequiresRole` Compile-Time RBAC Generation

| Attribute      | Value                                                                                  |
|:---------------|:---------------------------------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                                           |
| **Deciders**   | Arkadiusz Przychocki                                                                   |
| **Date**       | 2026-05-02                                                                             |
| **Driven By**  | ADR-007 (next-gen runtime), ADR-012 (security trust model), `docs/subsystems/security.md` (`@RequiresRole` design note), performance contract |
| **Compliance** | [Strategic Pillar: Zero-Reflection Security Hot Path](../whitepaper.md)               |

## 1) Context and Problem Statement
- The kernel's admission path already enforces fail-closed authorization via `CitadelGuard.requireRole()` for dynamic, runtime-decided checks.
- Static, declarative role requirements on method-level entry points (HTTP handlers, command receivers, scheduled tasks) are widely useful and currently have no kernel-blessed mechanism. Reflection-based annotation scanning would violate the No-Waste-Compute contract by allocating on the request hot path and by deferring failure to runtime that should be visible at compile time.
- `docs/subsystems/security.md` already describes a target design that uses an APT-generated `RoleCheckRegistry` and a single bitmask AND on the hot path, but no decision document records the boundary commitments, the open-core placement, the TCK obligations, or the build-system contract.
- This ADR is a pre-implementation gate. Code does not enter the kernel until this decision is signed off.

## 2) Decision Scope
- In scope: the `@RequiresRole` annotation type on the SPI surface, the APT processor placement and ownership, the generated artifact's binary contract, runtime integration with the existing security pipeline, error/telemetry contract, and TCK obligations.
- In scope: interaction with `CitadelGuard` (runtime fallback for dynamic checks) and with the resource-server validation pipeline established in ADR-012.
- Out of scope: dynamic policy decisioning (ABAC/PBAC), per-principal rate limiting (already addressed in `security.md` as a distinct mechanism), role hierarchy semantics beyond bitmask membership.

## 3) Decision — Compile-Time Bitmask Registry
- **Annotation surface (SPI):** `@RequiresRole(String[] value, RoleMatch match = RoleMatch.ANY)` lives in `exeris-kernel-spi` under the security package. The annotation is `@Retention(SOURCE)` because it is consumed at compile time only; no runtime reflection ever inspects it. `RoleMatch` is an enum value carrier (`ANY`, `ALL`).
- **APT processor (build-config):** A `javax.annotation.processing.Processor` ships in `exeris-kernel-build-config` and is wired through `META-INF/services/javax.annotation.processing.Processor`. The processor consumes `@RequiresRole`-bearing elements across consumer modules and emits `eu.exeris.kernel.security.generated.RoleCheckRegistry` — a final class with primitive-only static fields and one O(1) lookup method per method-id.
- **Generated artifact contract:** for each annotated method, the processor reserves a stable `int methodId` (assigned in source-order to keep the build deterministic) and stores two `long` masks: `requiredAny[methodId]` and `requiredAll[methodId]`. The runtime lookup is `(principal.roleMask() & required) != 0` for `ANY` or `(principal.roleMask() & required) == required` for `ALL`. No allocation, no reflection, no `Class.getAnnotation()` on the hot path.
- **Role-name to bit mapping:** a single, kernel-owned canonical mapping ships in SPI (`KernelRoles`) for the small set of system roles. Application-defined roles use a stable hash to bit assignment generated at build time and recorded in the same `RoleCheckRegistry`. The mapping is frozen at build time; mismatch between build and runtime mappings is detected on bootstrap and fails closed.
- **Runtime fallback:** `CitadelGuard.requireRole(String)` remains as the runtime-decided fallback for dynamic checks (e.g., role required is computed from request data). Static `@RequiresRole` checks short-circuit before `CitadelGuard` is consulted.

### Rejected alternatives
- **Reflection-based runtime scanning.** Allocates on the hot path, defers errors past compile time, and is structurally incompatible with the No-Waste-Compute contract.
- **AOP/proxy-based interception.** Drags in a runtime weaving framework, conflicts with ScopedValue propagation discipline, and adds dispatch indirection that the dispatcher already avoided in 0.6.
- **Configuration-file-driven role policy.** Decouples policy from code, increasing drift surface; loses the compile-time error of "annotated method on a class outside the security pipeline".

## 4) Architecture Boundaries (The Wall)
- SPI exposes only the annotation type, the `RoleMatch` enum, and the canonical `KernelRoles` mapping. No registry implementation lives in SPI.
- The APT processor lives in `exeris-kernel-build-config` because it is build-time tooling; it MUST NOT depend on Core or Community runtime packages.
- The generated `RoleCheckRegistry` is loaded by Core at bootstrap as an SPI provider via `ServiceLoader` (or via `LazyConstant.of(...)` at first access, matching the `security.md` description). Core consumes it through an SPI-shaped provider interface so the decision module is replaceable in tests.
- Runtime integration touches admission (transport edge) and `CitadelGuard`; no Community/Enterprise driver touches the registry directly.

## 5) Hot Path Constraints (Performance Contract)
- Authorization decision: O(1) bitmask AND/EQ per request. No allocations, no reflection, no `String` interning at decision time.
- Registry load: one-shot at bootstrap via `LazyConstant`. The fully-loaded registry is then accessed through final-field reads only.
- Principal carrier: `principal.roleMask()` returns a primitive `long` (or `long[]` if more than 64 roles are required, indexed by registry-recorded shard id). The principal record is immutable and Valhalla-ready.
- The decision MUST NOT allocate exceptions for accept paths; rejection raises `EX-SEC-2003` once on the deny path with secret-safe rawArgs.

## 6) Error and Telemetry Contract
- Reuses existing `EX-SEC-2003` (insufficient privileges) for static-check denies. RawArgs include the method id, required mask, and a redacted principal handle (no role names or token bytes).
- JFR-first telemetry: a `RoleCheckDeniedEvent` records method id, denial reason discriminator (`MISSING_PRINCIPAL`, `INSUFFICIENT_ROLES`, `MAPPING_MISMATCH`), and decision latency. No principal identifiers, no role names.
- Build-time errors (e.g., `@RequiresRole` referencing an undeclared role) MUST fail the compile with a clear diagnostic. Build failures are preferred over runtime warnings for this contract.

## 7) TCK Obligations
- `AbstractRequiresRoleTck` (introduced in Sprint 8 alongside the APT) codifies: registry presence after bootstrap, `ANY`/`ALL` matching semantics, fail-closed on missing principal, fail-closed on mapping mismatch, and absence of allocation on the accept path (paired with `RequiresRoleZeroAllocTck`).
- The APT processor itself is covered by unit tests in `exeris-kernel-build-config` that compile representative source fixtures and assert the generated registry contents — both happy path and diagnostic emission for malformed annotations.
- Community binding: `CommunityRequiresRoleTckTest`. Enterprise binding obligation declared; out-of-repo verification on parity.
- Contract changes are incomplete until abstract suites and both binding layers validate observable behavior.

## 8) Build System Contract
- The APT processor is enabled by default for any consumer of `exeris-kernel-spi` that pulls `exeris-kernel-build-config` as a `provided`/annotation-processor-path dependency. Consumers SHOULD wire the `maven-compiler-plugin` `annotationProcessorPaths` block accordingly; the kernel BOM SHOULD provide a default.
- Generated source MUST land in `target/generated-sources/annotations` (or the Gradle equivalent) and MUST NOT be checked into source control.
- Incremental compile MUST regenerate the affected slice of the registry without rebuilding unaffected method ids, preserving the deterministic method-id assignment within a stable canonical ordering of input elements.

## 9) Implemented vs Planned (mandatory anti-drift block)
- Implemented now (repository state): `CitadelGuard.requireRole(String)` runtime check for dynamic role decisions; `AbstractCitadelGuardTck` and Community binding (`CommunityCitadelGuardTckTest`); secret-safe `EX-SEC-*` taxonomy with rawArgs discipline established by ADR-012; `KernelIsolationClaims` for storage-isolation routing.
- Implemented now (repository state, design only): `docs/subsystems/security.md` describes the target `@RequiresRole` mechanism (APT-generated `RoleCheckRegistry`, hot-path bitmask check) but explicitly marks it as "Planned — not yet implemented in this repo". This ADR formalizes that design as the chosen direction.
- Planned (0.7, Sprint 8): `@RequiresRole` annotation type added to `exeris-kernel-spi`; APT processor implemented in `exeris-kernel-build-config`; `RoleCheckRegistry` generation, `LazyConstant` loader, runtime admission integration; `AbstractRequiresRoleTck` and `RequiresRoleZeroAllocTck`; Community binding.
- Planned (post-0.7): role-mapping versioning across rolling deploys; bitmask shard expansion if real-world consumers need more than 64 roles; build-time emit of operational documentation for the resolved role-name → bit assignment.

## 10) Consequences
- A new build-time dependency surface (the APT processor) must be wired into consumer build pipelines. The kernel BOM and reference build will provide opinionated defaults; teams that opt out of `@RequiresRole` lose only the static-check ergonomics, not any existing runtime check.
- Static and dynamic role checks coexist: `@RequiresRole` for declarative method-level enforcement, `CitadelGuard.requireRole()` for code-driven decisions. Both produce the same `EX-SEC-2003` semantics so operators see uniform telemetry.
- The role-name-to-bit mapping becomes a compile-time artifact. Operational changes to roles require a build, which is the intended trade-off for a zero-reflection hot path. Dynamic role policy (if ever required) remains expressible through the `CitadelGuard` runtime path.
- This ADR does not foreclose future ABAC/PBAC layers; it scopes the kernel's contribution to the static, well-bounded RBAC slice that is performance-critical.
