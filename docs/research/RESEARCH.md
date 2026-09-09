---
title: "Research Framework"
type: methodology
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

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
- `research/http1-header-allocation`
- `research/http-header-name-table`
- etc.

The canonical research document for that effort is stored in the branch itself. Every research
branch that has adopted this framework's documentation convention has used a dated, slug-specific
path inside `docs/research/` —
`docs/research/RESEARCH-2026-09-01-http1-header-allocation.md`,
`docs/research/loom-continuation-locality/RESEARCH-loom-continuation-locality.md` — never a bare
`research.md` at the branch root. This convention postdates at least one earlier research branch:
`research/0.9.0-tls-records-per-event` predates the framework and recorded its findings only in
test names and commit messages (tagged `PERF-RESEARCH`), with no dedicated document anywhere under
`docs/research/` — that directory on the branch holds only this framework file.

Use one canonical path per research effort.
Do not maintain duplicated full copies of the same living research document in multiple locations.

The `main` branch does **not** carry all active lab notes and branch-specific details.
Instead:

- `docs/research/RESEARCH.md` defines the framework and portfolio,
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
the canonical template at `exeris-docs/templates/RESEARCH-TEMPLATE.md`.

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

As of this writing, no research branch is open against this framework. The two entries this
section used to carry as "currently recognized as load-bearing" do not reflect the repository:

- **OpenSSL 4.0 migration.** No `research/openssl-4-migration-envelope` branch was ever opened —
  the migration was carried directly as roadmap work instead and is **delivered**: `CoreOpenSslLoader`
  moved to the provider-aware `SSL_CTX_new_ex` constructor, library candidate lists resolve
  `.so.4` alongside the retained `.so.3` floor, and ADR-008 was refreshed to declare the new
  baseline (v0.9 Sprint 4b; see `docs/ROADMAP.md` and `docs/adr/ADR-008-*`). Naming an
  unopened branch here as active portfolio work was simply wrong.
- **JDK 27 preparation.** No `research/jdk27-preparation` branch exists either, locally or on the
  remote, and the entry is stale in a second way: the question it names has already been overtaken.
  JVM-preview tracking now runs as a standing process, not a one-off research branch — the `preview`
  branch stays on the newest JDK regardless of LTS status and absorbs `StructuredTaskScope` API churn
  release over release, decided under ADR-066 (`docs/adr/ADR-066-preview-clean-ga-baseline.md`). That
  branch's `pom.xml` targets JDK 28 today (`maven.compiler.release=28`), so it has already moved past
  27 to 28 without this framework ever being used for it. The GA line separately holds at the newest
  LTS (JDK 25, `maven.compiler.release=25` in this repository's own `pom.xml`). A research branch
  under this framework would still make sense for a specific open JVM question — e.g. whether a
  named JEP changes the kernel's architecture — but "JDK 27 preparation" as a general heading is not
  that question any more.

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

### 2. HTTP/1 Read-Path Allocation

**Branch:** `research/http1-header-allocation`

**Status:** `concluded`

Focus:
- per-request heap allocation of the HTTP/1 request read path,
- whether token materialization in `Http1RequestParser.readAscii` dominates it,
- exact per-thread bytes (`ThreadMXBean`) rather than sampled JFR `weight`,
- a sweep of materialize-and-copy sites across the HTTP request and response paths.

Outcome:
- a 16-header request allocated ~9.8 KB of heap to read ~500 B off the wire, roughly 20x its own
  wire size,
- the hypothesis was only partly confirmed: token materialization is under half the cost,
- the rest was structural and the driving plan item did not mention it — the reader parsed the
  header block twice and then copied the resulting list a third time,
- the same materialize-and-copy shape was found at four further per-request sites, so this is a
  subsystem pattern rather than one method,
- no CPU or throughput claim was made; only allocation was measured.

Final decision:
- primary disposition: **Promote to Feature**
- merged into the `development/0.12.0` line, targeted for the v0.12 release (not yet shipped —
  see the note on `CanonicalHeaderNames` below): the double parse was collapsed to one pass and
  the list copy dropped — 9 848 B to 5 472 B for that request, 44%, with ADR-071 gaining a
  2026-09-01 amendment because the change also made the configured header bound structural rather
  than maintained by convention,
- follow-up: the zero-copy header representation was **not** promoted directly from here — it went
  through RFC-2026-09-01 (`docs/rfc/RFC-2026-09-01-http-header-representation.md`) first, because
  its hard question is lifetime: a header held as an offset into a pooled buffer segment can be read
  after that segment is recycled, silently returning another request's bytes rather than throwing.

Note:
The RFC settled the representation question: it rejected the wire-slice option (Option A) for 1.0 on
that lifetime hazard and accepted a canonical name table (Option B) instead, which changes no SPI
contract and needed no ADR. `CanonicalHeaderNames` is implemented and merged into the
`development/0.12.0` line, targeted for the v0.12 release, which has not shipped yet as of this
writing — `main`'s `pom.xml` is still at `0.11.0` and the release-integration PR is still open. It
is measured at 21-25% of the whole request on realistic fixtures (browser, service, health-probe) —
more than the
name-only share of a header field's own bytes, because long values dilute what remains once a name
hits the table. The four remaining sweep sites were taken after the RFC settled, so none of them was
edited twice. Two of the five rows in the original sweep table described their site inaccurately and
are corrected in the note: the router row named a conditional `substring` when the unconditional cost
beside it was a path split run twice per request, and the response-header merge turned out to
allocate nothing removable — the finding that survived there was a duplicated method, not waste.
What is left after the name table is the value half of each header, which the RFC deliberately left
open rather than promoted: its hard question is still lifetime, and no fix has changed that a pooled
segment can be recycled without the read failing loudly.

---

## How Results Flow Back to Main

When a research branch concludes, one or more of the following should happen:

### Option 1 — Promote to ADR
Use when findings imply a durable architectural decision.

### Option 2 — Promote to Feature
Use when findings are conclusive but architecture does not need an ADR.

### Option 3 — Update `docs/research/RESEARCH.md`
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
2. Add a dated research document (`docs/research/RESEARCH-YYYY-MM-DD-<slug>.md`) built from
   `exeris-docs/templates/RESEARCH-TEMPLATE.md`
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
- `research/http1-header-allocation`
- `research/http-header-name-table`

### Document title
Use:

`# Research: [Short Title]`

### Main branch documentation
- `docs/research/RESEARCH.md` — framework and portfolio (kernel-local)
- `exeris-docs/templates/RESEARCH-TEMPLATE.md` — canonical template (platform-wide)

### Branch-local document
Use a dated, slug-specific path inside `docs/research/`, e.g.
`docs/research/RESEARCH-YYYY-MM-DD-<slug>.md`, or a subdirectory under `docs/research/<slug>/` for
an effort that produces more than one document (findings plus a benchmark handoff, for example).
This is the convention every research branch that has adopted this framework has actually used; a
bare `research.md` at the branch root has not been observed. One earlier branch,
`research/0.9.0-tls-records-per-event`, predates the framework and recorded its findings only in
test names and commit messages, with no document under `docs/research/` at all — see
"Research Model" above.

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

Use the canonical platform-wide template in:

- `exeris-docs/templates/RESEARCH-TEMPLATE.md`

for all new research branches.

---

## References

- `exeris-docs/templates/RESEARCH-TEMPLATE.md`
- `docs/architecture.md`
- `docs/performance-contract.md`
- `docs/adr/ADR-007*`
- `docs/adr/ADR-008*`
