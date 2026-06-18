---
name: exeris-multi-agent-handoff
description: Order the specialist chain and surface blocking dependencies for a multi-domain Exeris Kernel task. Use when work spans architecture + implementation + verification + docs and must run in strict dependency order (e.g. placement before implementation, implementation before TCK/perf, docs/ADR sync when meaning changes).
---

# Exeris Multi-Agent Handoff

## Purpose
Build deterministic handoff order for multi-domain tasks.

## Output Contract
Return exactly:
1. `ordered_handoff_chain`
2. `order_rationale`
3. `blocking_dependencies`

## Ordering Rules
- Resolve architectural placement risk before implementation.
- Resolve implementation before final contract verification where code changes are required.
- Run performance/memory audit after implementation and before final merge gate.
- Include Docs/ADR sync when behavior/boundary meaning changes.

## Completion Criteria
Handoff plan is complete only if chain order is executable and each blocking dependency is explicit.
