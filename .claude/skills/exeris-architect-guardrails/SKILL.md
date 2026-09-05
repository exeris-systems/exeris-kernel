---
# DO NOT EDIT — generated from .agents/skills/exeris-architect-guardrails/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-architect-guardrails
description: Architectural review for Exeris Kernel changes that touch module boundaries, SPI/Core/Community/Enterprise placement, dependency direction, provider wiring, or ADR-fixed structure. Use when a change adds or moves interfaces/providers, alters module layering, or risks a The-Wall breach (SPI implementation leak, Core driver leak). Not needed for pure intra-module edits with no boundary impact.
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-architect-guardrails/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Architect Guardrails

## Purpose
Run a strict architectural review for Exeris Kernel changes so every PR remains aligned with:
- `docs/modules/*.md`
- `docs/subsystems/*.md`
- `docs/adr/*.md`
- The Wall boundaries and established open-core structure

This skill is mandatory for PR reviews that touch architecture, module boundaries, subsystem contracts, or dependency wiring.

## When to Use
- Any PR that can affect placement, boundaries, or dependency direction (skip pure intra-module edits with no boundary impact)
- Any change touching SPI/Core/Community/Enterprise/TCK modules
- Any change introducing or modifying interfaces, providers, adapters, transport/security/persistence internals
- Any refactor that can alter dependency direction or module layering

## Required Inputs
- PR diff or changed file list
- Affected modules and subsystems
- Stated intent of the change (what boundary or behavior it modifies)

## Review Procedure
1. **Load architectural canon first**
   - Read relevant module contracts in `docs/modules/*.md`.
   - Read impacted subsystem contracts in `docs/subsystems/*.md`.
   - Read all potentially related ADRs in `docs/adr/*.md`.
   - Treat accepted ADRs as binding constraints, not suggestions.

2. **Boundary audit: The Wall**
   - Verify SPI remains implementation-blind.
   - Verify Core remains orchestration-only and does not leak concrete drivers.
   - Verify implementation-specific concerns stay in Community/Enterprise layers.

3. **Leak detection (hard fail checks)**
   - Reject if SPI references or implies `io_uring`, `OpenSSL`, `QUIC`, `epoll`, OS-native structs, or provider-native flags.
   - Reject if Core exposes or depends on implementation transport/crypto/storage driver details.
   - Reject if public contracts are polluted by provider-specific semantics.

4. **Dependency graph integrity**
   - Confirm no dependency inversion against module architecture.
   - Confirm no new edge that makes upper-level contracts depend on lower-level implementations.
   - Confirm no back-reference from SPI/Core into Enterprise internals.

5. **ADR compliance gate**
   - Reject proposals that effectively roll back accepted ADR decisions.
   - Reject creation of new modules/structure when an ADR already fixes canonical structure.
   - If proposing an exception, require explicit new ADR rather than silent deviation.

6. **Decision and report**
   - Produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.
   - For each issue, map finding → violated document/decision → required corrective action.

## Decision Logic
- **APPROVE**: No boundary leaks, no graph violations, no ADR conflicts.
- **CONDITIONAL**: Fixable architecture drift with clear remediation and no ADR rollback intent.
- **REJECT**: Any The Wall breach, SPI implementation leak, Core driver leak, dependency inversion, or ADR rollback.

## Completion Criteria
A review is complete only if all are true:
- Every affected module/subsystem was checked against corresponding docs.
- SPI/Core/implementation boundaries were explicitly validated.
- Dependency graph direction was validated for changed edges.
- ADR impact was analyzed and documented.
- Final verdict and remediation list were provided.

## Review Output Template
Use this structure in PR feedback:
1. **Scope analyzed** (modules, subsystems, ADRs reviewed)
2. **Boundary findings** (SPI/Core/The Wall)
3. **Dependency findings** (new/changed edges)
4. **ADR findings** (compliant/conflicting)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never allow SPI to know implementation details.
- Never allow Core to leak concrete driver logic.
- Never reverse intended dependency direction.
- Never undo accepted ADR structure without a new ADR.
- Prefer minimal corrective changes that restore architectural integrity at the root cause.
