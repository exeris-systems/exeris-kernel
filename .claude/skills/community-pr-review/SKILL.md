---
name: community-pr-review
description: General Exeris Community/Open-Core PR review with boundary, contract, performance, verification, and docs/ADR impact verdict.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/community-pr-review.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
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
