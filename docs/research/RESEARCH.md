# Research Framework

Exeris Kernel uses **branch-scoped research** to investigate architectural, performance,
runtime, and native-integration questions before promoting changes into the mainline.

This document defines:

- what counts as research,
- how research is structured,
- how research relates to ADRs and feature work,
- which research tracks are currently active or planned,
- and how findings are promoted back into the main repository.

Research in Exeris is not informal note-taking. It is a controlled mechanism for
evaluating high-risk, high-impact changes without polluting `main` with unstable
assumptions, speculative abstractions, or premature APIs.

---

## Purpose

Research exists to answer questions that are too important to guess at and too
uncertain to merge directly.

Typical examples:

- JVM/runtime evolution that may affect architecture or hot-path behavior,
- scheduler and concurrency model changes,
- native boundary redesign,
- performance-contract validation,
- kernel / transport / crypto capability shifts,
- migration readiness for foundational dependencies.

A research branch should produce evidence strong enough to support one of four outcomes:

1. **Promote to ADR**
2. **Promote directly to feature work**
3. **Park for later**
4. **Abandon**

If a question does not require measurement, architectural validation, or prototype
evidence, it probably does not need a research branch.

---

## Research Model

Every concrete research effort lives in its **own branch**:

- `research/loom-continuation-locality`
- `research/openssl-4-migration-envelope`
- `research/jdk27-preparation`
- etc.

The canonical research document for that effort is stored in the branch itself,
typically as:

- `research.md`
- or a more specific path inside `docs/research/`

Use one canonical path per research effort.
Do not maintain duplicated full copies of the same living research document in multiple locations.

The `main` branch does **not** carry all active lab notes and branch-specific details.
Instead:

- `docs/RESEARCH.md` defines the framework and portfolio,
- each research branch contains the living research document,
- conclusions are promoted back into `main` via ADRs, feature PRs, or summary updates.

This keeps speculative work isolated while preserving a clear, auditable research trail.

---

## When to Open a Research Branch

Open a research branch when **at least one** of the following is true:

- the work may change an architectural boundary,
- the work depends on benchmark or profiling evidence,
- the work involves experimental JVM, kernel, or native-library behavior,
- the work may require prototype-only code not suitable for `main`,
- the work explores multiple competing designs,
- the work needs a formal decision trail before implementation.

Do **not** open a research branch for:

- straightforward bug fixes,
- small refactors with obvious outcomes,
- feature work that already has a settled design,
- generic notes that do not lead to a decision.

---

## Required Shape of a Research Document

Every concrete research document should follow the structure defined in
`docs/RESEARCH-TEMPLATE.md`.

At minimum, a research document must contain:

- a specific hypothesis,
- a reason the question matters now,
- a methodology,
- a scope boundary,
- implementation notes,
- results,
- and a final decision.

A research document is not a vague exploration memo.
It must be decision-oriented.

---

## Relationship to ADRs

Research is upstream of ADRs, not a replacement for them.

Use **research** when:
- the question is still open,
- evidence is still being gathered,
- prototype code may be unstable or disposable,
- multiple designs are still in play.

Use an **ADR** when:
- the decision is ready to be made,
- the evidence is sufficiently conclusive,
- the chosen direction is intended to shape `main`.

A strong research branch often ends with:
- “Promote to ADR”
- plus a follow-up ADR PR summarizing the outcome.

Not every research effort needs an ADR.
If the result is implementation-local and does not affect architecture, it may go
straight to feature work.

---

## Relationship to Feature Work

Feature branches implement decisions.
Research branches validate whether those decisions should exist at all.

The correct order is usually:

1. research branch
2. findings
3. ADR or direct decision
4. feature branch / PR

This order is especially important for:

- scheduler changes,
- native-boundary changes,
- crypto upgrades,
- new transport geometry,
- JVM preview or incubator features,
- major dependency migration preparation.

---

## Research Quality Bar

A research branch is considered healthy only if it has:

- a falsifiable or confirmable hypothesis,
- explicit baseline and target metrics where measurement applies,
- clear scope boundaries,
- reproducible methodology,
- raw artifacts where possible (`.jfr`, JMH JSON, perf outputs, benchmark logs),
- and a decision path at the end.

Weak research usually looks like one of these:

- “let’s explore X”
- no benchmark harness
- no baseline
- no out-of-scope boundary
- no recorded findings
- no final decision

That is not enough.

---

## Evidence Expectations

The required evidence depends on the research type.

### Performance / scheduler / runtime research

Expected evidence:
- JMH or dedicated benchmark harness
- JFR
- perf / async-profiler / OS counters where applicable
- fixed-rate and/or saturation comparisons
- baseline and candidate comparisons

### Native dependency / migration research

Expected evidence:
- compatibility inventory
- API impact analysis
- prototype wrappers or spike notes
- lifecycle and ownership analysis
- migration risk matrix

### JVM / JDK preparation research

Expected evidence:
- JEP review
- compatibility audit
- prototype or spike notes if needed
- impact on build, runtime, and architecture
- migration envelope proposal

Not every research effort needs all tool types.
But every effort must produce evidence appropriate to its claims.

---

## Active Research Portfolio

The following research tracks are currently recognized as load-bearing for Exeris Kernel.

### 1. OpenSSL 4.0 Migration Envelope for Native Crypto

**Branch:** `research/openssl-4-migration-envelope`

Focus:
- OpenSSL 4.0 readiness,
- opaque types,
- const-correct API drift,
- lifecycle/init/cleanup behavior,
- provider-era assumptions,
- shared crypto boundary across Core/Community and Enterprise.

Why it matters:
- OpenSSL is foundational across the stack,
- migration debt grows if 3.x assumptions continue to leak upward,
- native crypto boundary must be hardened before 4.0 becomes unavoidable.

Expected outcome:
- migration envelope,
- native crypto boundary hardening guidance,
- possible ADR if the shared crypto boundary must change materially.

### 2. JDK 27 Preparation

**Branch:** `research/jdk27-preparation`

Focus:
- preparation for JDK 27 language/runtime/toolchain changes,
- preview/incubator maturity tracking,
- Loom / FFM / GC / runtime behavior shifts,
- compatibility and migration planning across build and runtime surfaces.

Why it matters:
- Exeris is intentionally close to modern JVM capabilities,
- delayed preparation creates architectural and operational debt,
- runtime assumptions must be revalidated before adoption.

Expected outcome:
- migration-readiness matrix,
- compatibility inventory,
- adoption / defer / watch recommendations per JEP or feature area.

---

## Concluded Research

### 1. Loom Continuation Locality

**Branch:** `research/loom-continuation-locality`

**Status:** `concluded`

Focus:
- continuation locality,
- default Loom/ForkJoin resume behavior,
- transport-affine execution,
- Core/Community and Enterprise benchmark tracks,
- scheduler seam extraction,
- possible bounded-drain event-loop follow-up.

Outcome:
- scheduler seam was successfully extracted and kept as useful infrastructure,
- Community measurements did not show a material E2E payoff for `locality-aware`,
- latest Community `shop-order-saga` run stayed at throughput parity while increasing CPU cost,
- Enterprise escalation was not justified in the current cycle.

Final decision:
- primary disposition: **Park**
- cycle decision: **NO_GO**
- follow-up: keep the seam and findings as evidence in research branch,
  with no H2 and no Enterprise escalation in the current cycle; any future Enterprise-side
  locality work requires a materially different hypothesis.

Note:
Any future Enterprise-side locality experiment is not a continuation of the closed
Community payoff track. It is a separate, native-transport-specific follow-up motivated
by materially different execution geometry (`io_uring` / `IOCP`, poller interaction,
wakeup costs, and scheduler/carrier coupling).

---

## How Results Flow Back to Main

When a research branch concludes, one or more of the following should happen:

### Option 1 — Promote to ADR
Use when findings imply a durable architectural decision.

### Option 2 — Promote to Feature
Use when findings are conclusive but architecture does not need an ADR.

### Option 3 — Update `docs/RESEARCH.md`
Add status update, final disposition, or successor work.

### Option 4 — Open follow-up issue/discussion
Use when work is promising but deferred.

The branch itself remains the primary historical record of the investigation.

---

## Research Status Conventions

Use one of the following statuses in each research document:

- `active`
- `concluded`
- `abandoned`

Recommended interpretation:

### `active`
Work is ongoing; notes, prototypes, and measurements are still evolving.

### `concluded`
Evidence is sufficient and a final decision has been made.

### `abandoned`
The hypothesis was falsified, superseded, or no longer worth pursuing.

If a research effort is merely paused, keep it as `active` and explain that in the
Decision section.

---

## Recommended Branch Lifecycle

1. Create `research/[slug]`
2. Add `research.md` from `docs/RESEARCH-TEMPLATE.md`
3. Define hypothesis and methodology before large prototype work
4. Collect evidence and update implementation notes continuously
5. Record results
6. Make a final decision
7. Promote findings through ADR / feature PR / issue / summary update

This sequence is preferred because it keeps branch work decision-oriented rather
than drifting into undocumented experimentation.

---

## Naming Conventions

### Branch name
Use:

`research/[slug]`

Examples:
- `research/loom-continuation-locality`
- `research/openssl-4-migration-envelope`
- `research/jdk27-preparation`

### Document title
Use:

`# Research: [Short Title]`

### Main branch documentation
- `docs/RESEARCH.md` — framework and portfolio
- `docs/RESEARCH-TEMPLATE.md` — canonical template

### Branch-local document
Prefer:
- `research.md`

unless the branch has a compelling reason to use a more specific path.

---

## What Research Must Not Become

Research branches must not become:

- undocumented long-lived forks,
- feature branches pretending to be research,
- ADR substitutes,
- dumping grounds for random notes,
- unbounded prototype branches with no decision pressure.

If the branch has no hypothesis, no methodology, and no decision path, it should
either be fixed or closed.

---

## Decision Framework

Every research branch should end in exactly one primary disposition:

- **Promote to ADR**
- **Promote to Feature**
- **Park**
- **Abandon**

These are intentionally strict.
A research effort that never reaches a disposition is incomplete.

---

## Template

Use the canonical template in:

- `docs/RESEARCH-TEMPLATE.md`

for all new research branches.

---

## References

- `docs/RESEARCH-TEMPLATE.md`
- `docs/architecture.md`
- `docs/performance-contract.md`
- `docs/adr/ADR-007*`
- `docs/adr/ADR-008*`
