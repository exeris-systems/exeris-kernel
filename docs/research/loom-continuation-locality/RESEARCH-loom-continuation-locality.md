# Research: Loom Continuation Locality

> **Branch:** `research/loom-continuation-locality`
> **Author:** @arkstack-dev
> **Started:** 2026-03-20
> **Milestone:** v0.6 - findings feed scheduler seam design, benchmark harness, and bounded architecture guidance
> **Status:** `concluded`

---

- Community track: `RESEARCH-loom-continuation-locality-community.md`
- Enterprise track: `RESEARCH-loom-continuation-locality-enterprise.md`

## Hypothesis

Exeris currently avoids part of the classical Loom split-pipeline cost by keeping
network carriers outside the default Loom scheduler, but it likely still leaves
measurable performance on the table because **virtual thread continuation execution
remains decoupled from transport locality**.

The central hypothesis is:

> Even when Exeris eliminates the explicit I/O-thread -> task-queue submission handoff,
> default Loom/ForkJoin continuation resumption still incurs avoidable locality loss,
> extra context switching, and excess memory hierarchy pressure relative to a more
> transport-affine execution model.

This research is intentionally split into **two tracks**:

1. **Core/Community Track**
	A simpler, more controllable benchmark and methodology track that validates the
	continuation-locality hypothesis on the public stack.

2. **Enterprise Track**
	A native-transport track that extends the same hypothesis into the real Exeris
	design space: native carriers, io_uring, multishot receive, batching, bounded
	drain, and transport/scheduler coupling.

The two tracks answer different questions, but they share one thesis:

> The remaining performance ceiling is not "virtual threads are slow"; it is that
> default continuation placement and legacy scheduling geometry are not aligned with
> transport locality.

## Motivation

This research began from Francesco Nigro's scheduler work and the broader observation
that naive Loom usage can create a **split pipeline**:

- one pool/thread domain handles I/O,
- another pool/thread domain resumes virtual thread work,
- every request crosses the boundary,
- and the system pays in handoff overhead, voluntary context switches, cache
  locality loss, and capacity inefficiency.

Francesco's findings are important not because they are Netty-specific, but because
they expose a broader structural issue:

- default ForkJoinPool continuation placement is not free,
- the cost is visible not only in throughput but also in CPU efficiency,
- the effect is especially visible at fixed sub-maximal load,
- and the penalty shows up as broad DRAM/cache behavior, not just queue overhead.

Exeris enters this design space from a different angle.

Exeris does **not** use the exact same split as Netty's default event-loop -> FJ model.
In Exeris:

- native carrier loops are standalone OS threads,
- they do not run application logic,
- they reap completions and wake virtual threads,
- virtual thread execution still resumes under the default Loom scheduler.

That means Exeris already removed part of the problem - but likely not all of it.

This creates a uniquely valuable research position:

- Exeris is **not** the classic "double queue handoff" case,
- but Exeris is **still** exposed to continuation locality loss,
- which makes it a strong external validation case for Loom scheduler evolution.

This is why the research should not begin directly with "io_uring optimization".
The broader question comes first:

> How much cost remains when explicit queue handoff is removed, but continuation
> locality is still delegated to the default Loom scheduler?

Only after that baseline is understood does it make sense to expand into the
Enterprise-specific scheduler/transport geometry.

## Relationship to Existing Findings

This research is informed by two key inputs.

### 1. Francesco Nigro's scheduler findings

Key observations from Francesco's work:

- split execution between I/O and default ForkJoin scheduling adds structural cost,
- unified scheduling reduces voluntary context switches,
- locality-preserving continuation execution improves throughput and tail behavior,
- the effect is visible even apart from protocol-specific implementation details.

### 2. CPU efficiency findings already observed around scheduler geometry

The prior efficiency analysis shows a pattern that strongly supports a continuation-
locality research track:

- fixed-rate sub-maximal load is more revealing than saturation alone,
- ForkJoin-based variants consume more CPU per request,
- DRAM/cache cost is broader than a single handoff hotspot,
- continuation thaw and pipeline traversal are locality-sensitive,
- CPU migrations and context switches materially affect efficiency.

These findings strongly suggest that Exeris should benchmark:

- **fixed-rate efficiency**,
- **continuation resume placement**,
- **light/sub-max load**,
- **memory hierarchy effects**,
not just maximum throughput.

## Research Structure

This research note is organized into one shared document and two track-specific documents.

1. Shared context and methodology (this file)
2. `core-community`: `RESEARCH-loom-continuation-locality-community.md`
3. `enterprise`: `RESEARCH-loom-continuation-locality-enterprise.md`

The first track is about **methodological clarity and benchmark harness stability**.
The second is about **real transport design decisions**.

They must share:
- hypotheses,
- metrics,
- naming,
- reporting rules,
- result normalization.

They must diverge where the transport model diverges.

## Shared Benchmark Methodology

### Why fixed-rate first

This research must not start from maximum throughput only.

Fixed-rate sub-maximal load is required because:
- scheduler inefficiencies often become clearer when the system has headroom,
- memory locality effects are often more visible below saturation,
- context-switch and migration costs are easier to interpret,
- previous findings already suggest this is the revealing regime.

### Shared Metrics Policy

Every reported comparison should prefer:
- CPU efficiency,
- instructions/req,
- latency distribution,
- context-switch behavior,
- memory hierarchy behavior,

over raw throughput alone.

Throughput remains necessary, but it is not sufficient.

### Shared Implementation Strategy

The codebase should evolve in this order:

1. **Scaffold**
	- research docs
	- scenario naming
	- result schema

2. **Execution seam**
	- extract execution backend
	- preserve current semantics

3. **Baseline validation**
	- prove refactor-neutrality

4. **First A/B**
	- baseline vs affine prototype

5. **Native transport expansion**
	- Enterprise-specific carrier hooks

6. **Drain policy expansion**
	- only after basic scheduler signal is proven

This sequence matters.
Do not start with the full multishot/H3/drain matrix.

## Shared Deliverables

1. shared methodology and reporting rules
2. Core/Community benchmark seam and first A/B comparison
3. Enterprise benchmark seam and first locality comparison
4. follow-up multishot/batching/drain study
5. perf/JFR evidence package
6. internal recommendation memo
7. optional Loom feedback summary only when architecture-dependent evidence is strong enough

## Milestones

### M0 - Research scaffold
- document structure
- hypotheses
- methodology
- metrics
- reporting rules

### M1 - Execution seam
- PAQS/stream execution seam
- default implementation preserving current behavior

### M2 - Core/Community baseline pack
- baseline verification runs
- harness stabilization

### M3 - Core/Community A/B
- baseline vs affine prototype

### M4 - Enterprise baseline A/B
- native reaper + FJP vs native reaper + affine

### M5 - Enterprise ingress/drain matrix
- multishot
- batch receive
- drain policy

### M6 - Findings package
- benchmark report
- perf/JFR evidence
- architecture recommendation

## Current Findings Snapshot (2026-04-12)

- prior Community E2E campaign ref: `results/raw/entity-read-by-id/20260324-150821-campaign`
- prior result remains valid: `default-vt=52,871.361 RPS`; `locality-aware=52,958.579 RPS`; delta `+0.165%`; operationally negligible
- latest Community E2E campaign refs:
	- `results/raw/e2e-shop-order-saga/20260412T075737Z-locality/default-vt`
	- `results/raw/e2e-shop-order-saga/20260412T084454Z-locality/locality-aware`
- latest campaign shape: 3 repeats per mode, `claim_scope=exploratory`; each mode produced 1 `clean` run and 2 `threshold_failure` runs
- latest throughput means stay at parity: `default-vt=194.601 req/s`; `locality-aware=194.566 req/s`; delta `-0.018%`
- latest CPU cost moves against locality-aware: mean `cpu_time_seconds` `193.337 -> 218.423` (`+12.97%`), mean CPU util of one core `28.87% -> 32.59%` (`+12.89%`)
- memory profile remains close: mean RSS `730.1 MB -> 709.1 MB`; mean off-heap estimate `595.6 MB -> 589.0 MB`
- latency also moves against locality-aware in this scenario: mean HTTP duration `4.897 ms -> 6.084 ms`; mean `p95` `11.943 ms -> 13.666 ms`
- interpretation: Community E2E evidence still does not show a material continuation-locality payoff; the latest scenario weakens the hypothesis further because throughput remains flat while CPU and latency regress for `locality-aware`
- claim impact: architecture-dependent effect remains benchmarkable, but current Community evidence does not support a general Loom public scheduler API requirement and does not justify Community-side execution-model escalation

## Decision

**Status:** `concluded`

**Primary disposition:** **Park**

**Cycle decision:** **NO_GO**

The research produced a useful scheduler seam and defensible benchmark methodology,
but it did not produce evidence strong enough to justify continuation-locality-driven
execution-model escalation on the current Exeris stacks.

Community evidence is now sufficient to close the current research cycle:
- the earlier E2E campaign showed only operational near-parity,
- the later `shop-order-saga` scenario showed no throughput win,
- memory remained broadly similar,
- CPU and latency moved against `locality-aware`.

Cycle closure implications:
- no H2 progression on the basis of this track,
- no Enterprise escalation in the current cycle,
- any future Enterprise-side locality work requires a materially different hypothesis,
  not a continuation of the current locality-payoff thesis.

Enterprise follow-up remains parked for this cycle rather than promoted. The branch
therefore concludes with a documented negative or non-material payoff result, not with
an ADR candidate or feature-promotion recommendation.

## Initial Position

The most likely outcome is not that Exeris "switches schedulers everywhere".

The more likely and more useful outcome is:

- Exeris gains a **clean scheduler seam**,
- proves whether continuation locality is materially relevant on its own stacks,
- isolates where the remaining performance ceiling really is,
- and then decides, based on evidence, whether Enterprise should go further into:
  - affine continuation execution,
  - bounded-drain event loops,
  - or deeper transport/scheduler integration.

That is the right order of operations.
