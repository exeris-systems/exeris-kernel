---
name: exeris-multi-agent-handoff
description: 'Router/Planner coordination skill for Exeris Kernel. Builds ordered handoff chains and identifies blocking dependencies between specialist steps.'
argument-hint: 'Primary risk + primary agent + secondary domains involved'
user-invocable: true
disable-model-invocation: false
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
