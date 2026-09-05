---
title: Policy — operating standards for any model
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — operating standards for any model

Process discipline that holds regardless of which model runs the session. Follow these
mechanically — they remove the judgement calls that go wrong first.

1. **Ground truth over meta-docs.** A claim about what the build, CI or tooling *does* is verified
   against the effective source — `pom.xml` `<executions>`, `.github/workflows/*.yml`, the rulesets —
   never against an agent file, a skill or a sibling meta-document. They share ancestry with the
   claim being checked; the "verify skips PMD" myth survived that circular check from the first
   commit until PR #238.
2. **Classify scope first.** Runtime hot path, runtime non-hot, test-tooling, docs-only. Bans and
   review depth follow from the class, and every verdict states which one it assumed.
3. **A claim names the command that proves it.** "Tests pass" cites the exact invocation. A green
   default build says nothing about tagged tests (`exeris-tagged-gate-runner`), and a skip-flagged
   build says nothing about lint.
4. **No performance conclusion from one run, and no cross-driver comparison.** Full discipline:
   `exeris-jfr-perf-research`.
5. **Minimal diffs, matched idiom.** No drive-by refactors, no style rewrites. Comments only per the
   comment policy below.
6. **Never invent target-state.** A missing or stale document is reported as such, and the fallback
   is the source layout. Docs never outrun code.
7. **Findings carry the why.** Every review finding maps to a document, ADR or contract clause, plus
   the smallest corrective action.
8. **Escalate at the seams.** Doubt about placement goes to the architect lens *before* code; a
   touch on SPI or observable behaviour triggers the TCK gate; contract-changing work is never
   self-approved as done.
9. **Report outcome first.** What happened, or what was found, leads the reply. Skipped steps and
   residual risks are stated, never silent.

## Heuristics

Signals, not gates. They open a question; they do not settle it, and a class does not get
decomposed because a number crossed a line.

- A class with more than roughly five meaningful collaborators may indicate software inflation.
- O(n) work on a hot path may indicate latency risk.
- A new abstraction layer must justify measurable value.
- An ADR update may be needed when architectural intent changes.

## Review priorities

In order, when they compete:

1. Boundary integrity — SPI, Core, drivers, The Wall.
2. Contract integrity — subsystem documents and ADR intent.
3. Runtime efficiency on hot paths — allocation, copy and concurrency discipline.
4. Verification impact — unit, integration and TCK coverage proportional to the behaviour change.
5. Style and readability, preferring clarity over dogma.
6. Test coverage, preferring meaningful semantics over line percentage.
7. Documentation updates, preferring the minimum that keeps the docs accurate.
8. PMD, Checkstyle and SpotBugs warnings, preferring a real fix over silencing noise.

## Comment and explanation policy

- Keep code comments minimal.
- Comments are warranted for contract Javadoc, tricky memory arithmetic, ABI and binary-layout
  constraints, and concurrency invariants.
- In a review, explain the finding's "why" grounded in an Exeris document or ADR.

## SonarQube MCP server

Its own file, because a provider adapter renders from it:
[`sonarqube-mcp.md`](sonarqube-mcp.md).
