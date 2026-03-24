# EXERIS BENCHMARKS HANDOFF: MICRO LOCALITY SCOPE ONLY

## 1) Scope and objective
This handoff defines a strict micro-benchmark scope for locality research.
Shared benchmark conventions, templates, and cross-project reporting rules are canonical in `~/exeris-systems/exeris-benchmarks/docs`.
This handoff is strictly micro-only and does not delete, replace, or supersede any E2E benchmark scope, artifacts, or decisions.

In scope:
- controlled micro A/B comparisons of `backendMode=default-vt` versus `backendMode=locality-aware`,
- mechanism-focused measurements under fixed synthetic load profiles,
- evidence collection for runtime-level behavior only.

Out of scope for this document and current cycle:
- E2E validation,
- C5 product GO/NO_GO decisions,
- `Phase-HTTP2` and any HTTP/2 progression gate,
- H3 exploration.

## 2) Explicit exclusions and decision boundary
This document must not be used to claim production readiness or to unblock C5.

Rules:
- Micro results are mechanism evidence only.
- Any E2E interpretation is deferred.
- Any C5 decision is deferred.
- `Phase-HTTP2` and H3 are explicitly deferred.
This document narrows execution scope to micro evidence only and does not supersede E2E or protocol-phase planning records maintained in `exeris-benchmarks`.

## 3) Micro A/B matrix
Required matrix dimensions:
- `backendMode`: `default-vt`, `locality-aware`
- `loadProfile`: `sub-max`, `moderate`, `max-throughput`
- `scenario`: `baseline`, `contention`, `allocation-pressure`

Canonical matrix points (18 total):

| backendMode | loadProfile     | scenario             |
|-------------|-----------------|----------------------|
| default-vt  | sub-max         | baseline             |
| locality-aware | sub-max      | baseline             |
| default-vt  | moderate        | baseline             |
| locality-aware | moderate     | baseline             |
| default-vt  | max-throughput  | baseline             |
| locality-aware | max-throughput | baseline           |
| default-vt  | sub-max         | contention           |
| locality-aware | sub-max      | contention           |
| default-vt  | moderate        | contention           |
| locality-aware | moderate     | contention           |
| default-vt  | max-throughput  | contention           |
| locality-aware | max-throughput | contention         |
| default-vt  | sub-max         | allocation-pressure  |
| locality-aware | sub-max      | allocation-pressure  |
| default-vt  | moderate        | allocation-pressure  |
| locality-aware | moderate     | allocation-pressure  |
| default-vt  | max-throughput  | allocation-pressure  |
| locality-aware | max-throughput | allocation-pressure |

Matrix invariants:
- same benchmark binary and revision for both modes,
- same JVM flags except `backendMode`,
- same host, CPU governor, kernel, pinning, and NUMA policy,
- same warmup, measurement duration, and fork policy.

## 4) Required metrics for micro runs
Mandatory per matrix point:
- throughput (`ops/s`),
- latency (`p50`, `p95`, `p99`),
- variance/stability (standard deviation and confidence interval),
- CPU utilization,
- `perf stat` counters: `cycles`, `instructions`, `cache-misses`, `branch-misses` when available,
- error/timeout count (must be reported even when `0`),
- JFR runtime signals relevant to scheduler/locality behavior.

Derived comparisons required:
- delta percent of `locality-aware` versus `default-vt` for throughput and latency,
- relative error for key metrics,
- consistency score across repetitions.

## 5) Quality gates for micro evidence
A micro result is valid only when all gates pass:
- at least 3 independent repetitions per matrix point,
- confidence interval reported for key metrics (target 95% CI),
- relative error for primary metrics <= 10%,
- `perf stat` collected in `no-multiplex` mode,
- no mixed artifacts across builds/revisions,
- strict-locality preflight check performed:
	- verify public availability of `Thread.Builder.OfVirtual.scheduler(...)` before strict runs,
	- if unavailable, mark as `not runnable on this JVM` and do not classify as performance regression,
- no unaccounted environment drift (thermal throttling, host contention, governor changes).

Data failing any gate is excluded from conclusions.

## 6) Artifacts required in handoff package
The micro evidence package must include:
- raw run outputs per repetition (`CSV` or `JSON`),
- aggregated micro report per matrix point,
- A/B delta table for `locality-aware` versus `default-vt`,
- latency summary (`p50/p95/p99`) and throughput summary,
- `perf stat` raw dumps (`no-multiplex`),
- JFR files for representative repetitions,
- strict-locality preflight report with JVM build metadata,
- runbook snapshot: JVM flags, pinning/NUMA settings, host metadata.

## 7) Interpretation policy
Allowed conclusion:
- whether micro evidence supports or does not support a locality mechanism signal under controlled conditions.

Not allowed in this handoff:
- E2E claims,
- C5 unblock recommendations,
- protocol progression claims (`Phase-HTTP2`/H3).

## 8) Deferred items
Deferred explicitly to a later scope:
- E2E benchmark design and execution,
- C5 decision framework and GO/NO_GO recommendation,
- any `Phase-HTTP2` and H3 benchmarking plan.
All deferred E2E, C5, Phase-HTTP2, and H3 material remains tracked in `~/exeris-systems/exeris-benchmarks/docs`.

This handoff is complete when micro artifacts and quality-gated analysis are delivered, with deferred E2E decisions recorded as pending.
