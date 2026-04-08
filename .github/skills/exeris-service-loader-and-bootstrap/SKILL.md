---
name: exeris-service-loader-and-bootstrap
description: 'Plugin-model and lifecycle PR review for Exeris Kernel. Use for changes touching ServiceLoader semantics, provider discovery, bootstrap DAG, fail-fast/degrade policy, and SPI→Core→Driver boundaries while keeping Core driver-agnostic.'
argument-hint: 'PR scope, changed providers/bootstrap flow, and expected lifecycle behavior'
user-invocable: true
disable-model-invocation: false
---

# Exeris Service Loader and Bootstrap

## Purpose
Enforce Exeris plugin model and lifecycle correctness.

This skill verifies that Core remains driver-agnostic while provider discovery, bootstrap orchestration, and failure policy stay contract-compliant.

## Canon to Load First
- docs/modules/01-spi.md
- docs/modules/02-core.md
- docs/architecture.md
- docs/subsystems/bootstrap.md
- docs/subsystems/config.md
- docs/modules/05-tck.md
- relevant ADRs in docs/adr

## When to Use
- PR touches provider interfaces or provider registration metadata
- PR modifies bootstrap state machine, startup order, or orchestrator behavior
- PR modifies ServiceLoader selection/discovery paths
- PR changes fail-fast/degrade behavior, startup policy, or boot-time error handling
- PR alters wiring between SPI, Core, and runtime drivers

## Required Inputs
- PR diff or changed file list
- Affected provider contracts and discovery points
- Expected lifecycle transitions and failure policy behavior

## Mandatory Checks
1. **ServiceLoader semantics**
   - Verify discovery is contract-driven via SPI interfaces.
   - Verify provider resolution follows deterministic and documented selection semantics.
   - Flag hidden side-channels for provider injection bypassing contract discovery.

2. **Provider discovery integrity**
   - Verify mandatory providers are discovered and validated at T-0.
   - Verify missing/invalid mandatory provider leads to proper bootstrap failure path.
   - Flag ambiguous provider selection that can cause non-deterministic startup.

3. **Bootstrap DAG correctness**
   - Verify subsystem startup ordering remains DAG-valid and lifecycle transitions are coherent.
   - Flag cycles, implicit dependencies, or transitions that skip required validation phases.

4. **Fail-fast vs degrade policy**
   - Verify default and effective policy match contract intent for production safety.
   - Flag silent degrade behavior for mandatory capabilities or production-critical paths.

5. **No hard-coded implementation wiring**
   - Verify Core does not directly import or wire concrete Community/Enterprise classes.
   - Flag any implementation-specific shortcuts that bypass SPI boundaries.

6. **SPI → Core → Drivers boundary integrity**
   - Verify SPI stays implementation-blind.
   - Verify Core orchestrates through SPI contracts only.
   - Verify driver-specific details remain in driver tiers and do not leak upward.

## Review Procedure
1. **Map lifecycle and discovery deltas**
   - Enumerate changed provider contracts, discovery paths, and bootstrap transitions.

2. **Build bootstrap matrix**
   - For each subsystem/provider: discovery source -> validation step -> DAG node -> failure behavior.

3. **Verify policy and determinism**
   - Validate fail-fast/degrade semantics and deterministic provider selection.

4. **Verify architectural boundaries**
   - Audit imports, dependency direction, and wiring for SPI/Core/Driver separation.

5. **Gate outcome**
   - Output `APPROVE`, `CONDITIONAL`, or `REJECT` with minimal root-cause remediation.

## Decision Logic
- **APPROVE**: Discovery, DAG, policy, and boundaries are contract-compliant and deterministic.
- **CONDITIONAL**: Localized lifecycle/discovery drift with clear bounded remediation.
- **REJECT**: Boundary breach, hard-coded driver wiring in Core, invalid DAG/lifecycle behavior, or unsafe fail-fast/degrade handling.

## Completion Criteria
Review is complete only if all are true:
- ServiceLoader and provider discovery semantics reviewed.
- Bootstrap DAG and lifecycle transitions reviewed.
- Fail-fast/degrade behavior reviewed against contract intent.
- Hard-coded implementation wiring check completed.
- SPI→Core→Driver boundary check completed.
- Verdict and required actions documented.

## Review Output Template
1. Scope analyzed (providers, lifecycle nodes, files)
2. ServiceLoader/discovery findings
3. Bootstrap DAG/lifecycle findings
4. Fail-fast/degrade findings
5. Boundary findings (SPI/Core/Drivers)
6. Verdict (APPROVE | CONDITIONAL | REJECT)
7. Required actions (minimal root-cause fixes)

## Non-Negotiable Rules
- Core remains driver-agnostic and contract-driven.
- No direct concrete driver wiring in Core.
- No bootstrap path that starts with missing mandatory providers.
- No policy drift that silently weakens fail-fast safety for production paths.
