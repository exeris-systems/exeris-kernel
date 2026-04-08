---
name: exeris-subsystem-scope-detector
description: 'Optional Router/Planner scope skill for Exeris Kernel. Detects impacted subsystem(s), required contract docs, and single-vs-cross subsystem scope.'
argument-hint: 'Changed files/symbols + task description'
user-invocable: true
disable-model-invocation: false
---

# Exeris Subsystem Scope Detector

## Purpose
Identify subsystem scope early to reduce routing ambiguity.

## Output Contract
Return exactly:
1. `impacted_subsystems`
2. `required_contract_docs`
3. `scope_type` (`single-subsystem` | `cross-subsystem`)

## Rules
- Prefer `docs/subsystems/*.md` as first contract source for subsystem scope.
- If no clear subsystem match is possible, emit `scope_type=cross-subsystem` and explain uncertainty.
- Do not infer implementation details across The Wall from filename alone.

## Completion Criteria
Scope detection is complete only if affected subsystem set and docs list are explicit.
