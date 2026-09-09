---
name: community-provider-serviceloader
description: Review Exeris Community provider loading and ServiceLoader semantics, including registration risks, boundary risks, and test implications.
argument-hint: Provider registration/discovery change scope
steps:
  - {skill: exeris-service-loader-and-bootstrap}
  - {agent: exeris-architect}
  - {agent: exeris-tck, when: "discovery order or a registration failure is observable through the SPI"}
gates:
  - test:ExerisArchitectureTest
  - ci:maven / build-and-verify
---

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
