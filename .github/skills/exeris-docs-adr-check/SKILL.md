---
# DO NOT EDIT — generated from .agents/skills/exeris-docs-adr-check/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-docs-adr-check
description: 'Documentation and ADR consistency review for Exeris Kernel. Use when PRs may cause doc drift, architectural intent changes, or mismatch between repository reality and documented target-state.'
argument-hint: 'PR scope, changed modules/subsystems, and expected architecture/doc impact'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-docs-adr-check/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Docs ADR Check

## Purpose
Keep code, docs, and architectural decisions synchronized without over-documenting.

## When to Use
- PR changes module placement, boundaries, lifecycle model, provider model, or subsystem contracts.
- PR introduces behavior that differs from documented state.
- PR may require ADR update/new ADR.

## Canon to Check
- `docs/modules/*.md`
- `docs/subsystems/*.md`
- `docs/adr/*.md`
- `docs/architecture.md` / `docs/whitepaper.md` when present and relevant

## Procedure
1. Detect behavior/boundary deltas in code.
2. Map deltas to affected docs and ADRs.
3. Classify outcome:
   - `NO_DOC_CHANGE`,
   - `DOC_UPDATE_REQUIRED`,
   - `ADR_UPDATE_REQUIRED`.
4. Propose minimal patch list (file + section + rationale).

## Mandatory Checks
- Docs do not claim implementation that does not exist in current repository state.
- Planned/target architecture is clearly marked as planned/target/placeholder.
- ADR intent is preserved; deviations are explicit and justified.

## Output Contract
For each finding: drift → impact → minimal doc/ADR action.

## Non-Negotiable Rules
- Do not let docs outrun code.
- Do not silently diverge from accepted ADR intent.
- Do not rewrite unrelated docs for style-only reasons.
