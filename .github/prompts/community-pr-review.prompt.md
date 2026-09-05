---
# DO NOT EDIT — generated from .agents/workflows/community-pr-review.md (agents-md-schema.md rule 7). Edit the source.
name: community-pr-review
description: 'General Exeris Community/Open-Core PR review with boundary, contract, performance, verification, and docs/ADR impact verdict.'
argument-hint: 'PR diff or changed files'
---
<!-- DO NOT EDIT. Generated from .agents/workflows/community-pr-review.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review this PR as an Exeris Community/Open-Core reviewer.

Priorities:
1. Boundary integrity (SPI/Core/Community/TCK)
2. Contract integrity (subsystem docs + ADR intent)
3. Runtime efficiency on hot paths
4. Verification impact proportional to behavior change
5. Documentation drift if architecture or subsystem reality changed

PR scope:
$ARGUMENTS

Please produce:
- Summary
- Blocking issues
- Non-blocking issues
- TCK/test implications
- Performance/memory implications
- Docs/ADR implications
- Final verdict: APPROVE / CONDITIONAL / REJECT
