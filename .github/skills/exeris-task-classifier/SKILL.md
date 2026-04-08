---
name: exeris-task-classifier
description: 'Router/Planner triage skill for Exeris Kernel. Classifies task type, scope, severity, and recommends primary agent based on primary risk.'
argument-hint: 'Task description + changed files/modules (if known) + expected outcome'
user-invocable: true
disable-model-invocation: false
---

# Exeris Task Classifier

## Purpose
Classify incoming work before execution starts.

This is a triage skill: it does not implement changes. It identifies task class, scope, and likely ownership.

## Output Contract
Return exactly:
1. `task_class` (`ARCHITECTURE` | `IMPLEMENTATION` | `CONTRACT_VERIFICATION` | `PERFORMANCE_REVIEW` | `DOCS_ADR` | `MULTI_DOMAIN`)
2. `scope` (single-module | cross-module | single-subsystem | cross-subsystem)
3. `severity` (low | medium | high | critical)
4. `primary_risk`
5. `recommended_primary_agent`

## Classification Heuristics
- `ARCHITECTURE`: boundaries, module placement, dependency direction, ADR alignment.
- `IMPLEMENTATION`: code change with stable boundaries and contracts.
- `CONTRACT_VERIFICATION`: observable behavior or SPI/TCK-sensitive behavior.
- `PERFORMANCE_REVIEW`: hot-path allocation/copy/concurrency risks.
- `DOCS_ADR`: docs contract drift, ADR updates, subsystem doc synchronization.
- `MULTI_DOMAIN`: at least two classes above are first-order concerns.

## Guardrails
- Preserve The Wall in every classification.
- If uncertain between two classes, emit `MULTI_DOMAIN` and state both dominant concerns.
- Prefer the smallest sufficient docs set (`docs/modules`, `docs/subsystems`, then ADRs if needed).

## Completion Criteria
Classification is complete only if all five output fields are present and justified in 1-2 concise bullets.
