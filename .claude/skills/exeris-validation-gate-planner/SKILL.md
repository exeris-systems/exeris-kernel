---
name: exeris-validation-gate-planner
description: Router/Planner validation skill for Exeris Kernel. Defines required validation layers, merge gates, and optional checks based on risk and scope.
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
