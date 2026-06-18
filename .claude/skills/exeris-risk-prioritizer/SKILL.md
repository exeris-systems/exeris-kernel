---
name: exeris-risk-prioritizer
description: Pick the single primary risk for an Exeris Kernel change when several compete (The-Wall/placement > observable contract drift > hot-path allocation/copy/concurrency > missing verification > docs/ADR drift). Use during triage when a change carries multiple risks and you must decide what to address first.
---

# Exeris Risk Prioritizer

## Purpose
Determine which risk is primary so routing and planning are deterministic.

## Output Contract
Return exactly:
1. `primary_risk`
2. `primary_risk_reason`
3. `secondary_risks` (ordered)

## Default Priority Order (Community/Open-Core)
1. The Wall / placement breach
2. Observable contract drift
3. Runtime hot-path allocation/copy/concurrency risk
4. Missing verification (tests/TCK)
5. Docs/ADR drift

## Decision Rules
- Choose one primary risk only.
- If two risks appear equal, prioritize the one with broader blast radius.
- Prefer contract/boundary safety over implementation speed.

## Completion Criteria
Risk prioritization is complete only if one primary risk is explicit and secondaries are ranked with concise reasoning.
