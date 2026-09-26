---
name: community-provider-serviceloader
description: Review Exeris Community provider loading and ServiceLoader semantics, including registration risks, boundary risks, and test implications.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/community-provider-serviceloader.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Review this Exeris Community change as a provider-loading and ServiceLoader task.

Focus on:
- provider discovery through SPI contracts,
- avoiding hard-coded runtime wiring in Core,
- registration correctness,
- preserving Community as a replaceable implementation,
- keeping observable provider behavior testable through TCK or binding tests where applicable.

Change:
$ARGUMENTS

Output:
1. Loading model check
2. Registration/provider risks
3. Boundary risks
4. Test implications
5. Minimal safe fixes
