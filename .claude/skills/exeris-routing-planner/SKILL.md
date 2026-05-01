---
name: exeris-routing-planner
description: Router/Planner skill for Exeris Kernel. Produces primary agent, required secondary handoffs, execution order, and minimal next action.
---

# Exeris Routing Planner

## Purpose
Build the lightweight execution route after classification.

This skill plans handoffs; it does not execute implementation/review steps itself.

## Output Contract
Return exactly:
1. `primary_agent`
2. `required_secondary_handoffs` (ordered)
3. `execution_plan` (4-6 steps)
4. `minimal_next_action`

## Routing Rules
- Primary risk decides first owner.
- If boundary risk is primary, route to `exeris-architect` first.
- If hot-path risk is primary, route to `exeris-performance` or `exeris-implementer` with mandatory perf handoff.
- If observable contract changes, include `exeris-tck` handoff.
- If docs/ADR drift is plausible, include `exeris-docs-adr` handoff.

## Sequencing Constraints
- Preserve dependency order between steps (no parallelization of blocking reviews).
- Prefer minimal chain that still covers all primary and secondary risks.
- Avoid speculative handoffs without identified risk.

## Completion Criteria
Plan is complete only if sequence is actionable end-to-end and every handoff has one-line rationale.
