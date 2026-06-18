---
name: exeris-subsystem-scope-detector
description: Identify which Exeris Kernel subsystem(s) a change touches, which contract docs to load, and whether the blast radius is single- or cross-subsystem. Use during triage when the impacted subsystem is unclear or a change may span several subsystems.
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
