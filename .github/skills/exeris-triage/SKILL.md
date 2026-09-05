---
# DO NOT EDIT — generated from .agents/skills/exeris-triage/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-triage
description: 'Single-pass triage for an Exeris Kernel task, PR, or request — classify the work, identify subsystem scope and required contract docs, pick the one primary risk, choose the owning specialist plus ordered handoffs, and set the validation gates. Replaces the former task-classifier / subsystem-scope-detector / risk-prioritizer / routing-planner / validation-gate-planner / multi-agent-handoff micro-skills. Use at the START of work when the owner is not obvious, the scope crosses domains, or several risks compete.'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-triage/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Triage

## Purpose
One skill answering the five triage questions in strict order, replacing a six-skill chain. It plans; it does not implement or review.

## Procedure (strict order — each step feeds the next)

### 1. Classify
- `task_class`: `ARCHITECTURE` (boundaries, placement, dependency direction, ADR alignment) | `IMPLEMENTATION` (code change, stable boundaries/contracts) | `CONTRACT_VERIFICATION` (observable or SPI/TCK-sensitive behavior) | `PERFORMANCE_REVIEW` (hot-path allocation/copy/concurrency) | `DOCS_ADR` (doc drift, ADR sync) | `MULTI_DOMAIN` (two or more first-order concerns — if uncertain between two classes, emit this and state both).
- `scope`: single-module | cross-module | single-subsystem | cross-subsystem.
- `severity`: low | medium | high | critical.

### 2. Subsystem scope
- `impacted_subsystems` + `required_contract_docs` (`docs/subsystems/*.md` first; smallest sufficient set).
- Do not infer implementation details across The Wall from filenames alone; if no clear match, say cross-subsystem and state the uncertainty.

### 3. Primary risk (exactly one)
Priority order: **The-Wall/placement breach > observable contract drift > hot-path allocation/copy/concurrency > missing verification (tests/TCK) > docs/ADR drift.**
- Apparent tie → the risk with the broader blast radius wins; contract/boundary safety beats implementation speed.
- Also return `secondary_risks`, ordered.

### 4. Route and handoffs
- Primary risk decides the owner: boundary → `exeris-architect`; hot-path → `exeris-performance` or `exeris-implementer` with a mandatory perf handoff; observable contract change → include `exeris-tck`; plausible docs/ADR drift → include `exeris-docs-adr`.
- Ordering constraints: placement resolves BEFORE implementation; implementation BEFORE final contract verification; performance/memory audit AFTER implementation and BEFORE the merge gate; docs/ADR sync whenever behavior or boundary meaning changes.
- No speculative handoffs — every handoff names its identified risk. Preserve dependency order (no parallelizing blocking reviews).

### 5. Validation gates
Map every gate to one identified risk (a gate without a risk is process inflation):
- Contract/SPI observable change → TCK gate.
- Hot-path impact → performance/memory review gate.
- Boundary/module movement → architecture review gate.
- Behavior or contract-text change → docs/ADR impact gate (`exeris-doc-impact-triage`).
- Pure local implementation, no observable impact → local build/test gate may suffice.

## Output Contract (return exactly)
1. `task_class`, `scope`, `severity`
2. `impacted_subsystems` + `required_contract_docs`
3. `primary_risk` + one-line reason; `secondary_risks` (ordered)
4. `primary_agent` + `handoff_chain` (ordered, one-line rationale each) + `blocking_dependencies`
5. `validation_gates` (each mapped to its risk)
6. `execution_plan` (4–6 steps) + `minimal_next_action`

## Completion Criteria
All six output fields present; the chain is executable end-to-end; every blocking dependency and every gate→risk mapping is explicit.

## Non-Negotiable Rules
- Exactly one primary risk.
- Preserve The Wall in every classification.
- No handoff and no gate without a named risk.
- Triage output is a plan — execution belongs to the routed specialist.
