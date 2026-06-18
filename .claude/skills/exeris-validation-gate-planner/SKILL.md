---
name: exeris-validation-gate-planner
description: Decide which validation/merge gates an Exeris Kernel change must pass (TCK, performance/memory, architecture, docs/ADR, local build) based on its risk and scope. Use when planning what must be green before merge, or to confirm no required gate is being skipped.
---

# Exeris Validation Gate Planner

## Purpose
Define merge/validation gates before execution starts.

## Output Contract
Return exactly:
1. `required_validation_layers`
2. `merge_gates`
3. `optional_recommended_checks`

## Gate Selection Rules
- Contract/SPI observable change → include TCK gate.
- Hot-path impact → include performance/memory review gate.
- Boundary/module movement → include architecture review gate.
- Behavior or contract text change → include docs/ADR impact gate.
- Pure local implementation with no observable impact → local build/test gate may be sufficient.

## Completion Criteria
Gate plan is complete only if every required gate maps to one identified risk.
