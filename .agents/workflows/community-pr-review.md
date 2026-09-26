---
name: community-pr-review
description: General Exeris Community/Open-Core PR review with boundary, contract, performance, verification, and docs/ADR impact verdict.
argument-hint: PR diff or changed files
steps:
  - {skill: exeris-pr-review-waste-hunter}
  - {agent: exeris-architect}
  - {agent: exeris-tck, when: "observable SPI behaviour changed"}
  - {agent: exeris-performance, when: "a hot path, an allocation site or a native lifetime was touched"}
  - {agent: exeris-docs-adr, when: "a subsystem contract or an ADR is affected"}
gates:
  - ci:maven / build-and-verify
  - ci:guardrails / docs
  - ci:guardrails / pr-body
---

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
