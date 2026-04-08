---
name: exeris-doc-impact-triage
description: 'Router/Planner docs skill for Exeris Kernel. Classifies expected documentation/ADR impact and required review level.'
argument-hint: 'Task summary + changed behavior/contracts + touched modules/subsystems'
user-invocable: true
disable-model-invocation: false
---

# Exeris Doc Impact Triage

## Purpose
Quickly detect documentation and ADR drift risk in triage.

## Output Contract
Return exactly one status:
- `NO_DOC_IMPACT`
- `MINOR_DOC_IMPACT`
- `DOCS_REVIEW_REQUIRED`
- `ADR_REVIEW_POSSIBLE`

And return:
1. `why`
2. `suggested_doc_action`

## Triage Rules
- No behavior/contract/boundary change -> likely `NO_DOC_IMPACT`.
- Terminology/config/default changes -> often `MINOR_DOC_IMPACT`.
- Observable behavior change or subsystem contract drift -> `DOCS_REVIEW_REQUIRED`.
- Architectural intent/boundary meaning changes -> `ADR_REVIEW_POSSIBLE`.

## Completion Criteria
Classification is complete only if status and action are both explicit.
