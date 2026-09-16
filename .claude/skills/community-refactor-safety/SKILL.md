---
name: community-refactor-safety
description: Review Exeris Community refactor safety for hidden contract drift, boundary/dependency impact, and proportional test/doc implications.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/community-refactor-safety.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Review this refactor for Exeris Community/Open-Core safety.

Assume the goal is to preserve behavior unless explicitly stated otherwise.

Refactor scope:
$ARGUMENTS

Please determine:
1. Is this truly refactor-only, or does it affect observable behavior?
2. Does it change placement or dependency direction?
3. Does it alter memory ownership, runtime lifecycle, or provider semantics?
4. Does it require test updates, TCK review, or docs updates?
5. What is the main hidden risk in this refactor?

Be conservative about contract drift, but do not overstate risk for cosmetic changes.
