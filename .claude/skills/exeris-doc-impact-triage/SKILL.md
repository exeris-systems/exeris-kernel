---
name: exeris-doc-impact-triage
description: Quick check of whether an Exeris Kernel change needs documentation/ADR updates and at what level (none / minor / docs-review / ADR-review). Use during triage to decide if a full exeris-docs-adr-check review is warranted before merging.
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
- No behavior/contract/boundary change → likely `NO_DOC_IMPACT`.
- Terminology/config/default changes → often `MINOR_DOC_IMPACT`.
- Observable behavior change or subsystem contract drift → `DOCS_REVIEW_REQUIRED`.
- Architectural intent/boundary meaning changes → `ADR_REVIEW_POSSIBLE`.

## Completion Criteria
Classification is complete only if status and action are both explicit.
