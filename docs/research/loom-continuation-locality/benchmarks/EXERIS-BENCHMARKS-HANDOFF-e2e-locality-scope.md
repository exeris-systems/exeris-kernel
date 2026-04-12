# EXERIS BENCHMARKS HANDOFF: LOCALITY DUAL-TRACK SCOPE

## 1) Scope and objective
This handoff defines the real dual-track scope used in the locality research.
Shared benchmark conventions, templates, and cross-project reporting rules are canonical in `~/exeris-systems/exeris-benchmarks/docs`.
This handoff does not delete, replace, or supersede baseline governance in `exeris-benchmarks`.

In scope:
- kernel-side micro A/B comparisons of `backendMode=default-vt` versus `backendMode=locality-aware`,
- benchmark-lab E2E A/B campaigns for survivability validation,
- integrated GO/NO_GO decision based on both tracks.

Out of scope for progression claims:
- cross-runtime superiority claims from exploratory locality runs,
- `Phase-HTTP2` and any HTTP/2 progression gate,
- H3 exploration.

## 2) Decision boundary
This document must not be used to claim production readiness when E2E eligibility is broken.

Rules:
- Micro results are mechanism evidence.
- E2E results are survivability/eligibility evidence.
- Progression requires both tracks to pass.
- If one E2E leg is non-eligible or failed, integrated decision is NO_GO.

## 3) Kernel micro A/B matrix
Required matrix dimensions:
- `backendMode`: `default-vt`, `locality-aware`
- `loadProfile`: `sub-max`, `moderate`, `max-throughput`
- `scenario`: `baseline`, `contention`, `allocation-pressure`

Canonical matrix points (18 total, mechanism track):

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

## 4) Required metrics
Mandatory per matrix point:
- throughput (`ops/s`),
- latency (`p50`, `p95`, `p99`),
- variance/stability (standard deviation and confidence interval),
- CPU utilization,
- `perf stat` counters: `cycles`, `instructions`, `cache-misses`, `branch-misses` when available,
- error/timeout count (must be reported even when `0`),
- JFR runtime signals relevant to scheduler/locality behavior.

Mandatory for E2E campaign legs:
- status and eligibility fields per leg (`runner_status`, `reproducibility_status`, `final_reason`, `claim_scope`),
- run success/exit metadata for both `default-vt` and `locality-aware`.

Derived comparisons required:
- delta percent of `locality-aware` versus `default-vt` for throughput and latency,
- relative error for key metrics,
- consistency score across repetitions.

## 5) Quality gates
Micro result is valid only when all mechanism gates pass:
- at least 3 independent repetitions per matrix point,
- confidence interval reported for key metrics (target 95% CI),
- relative error for primary metrics <= 10%,
- `perf stat` collected in `no-multiplex` mode,
- no mixed artifacts across builds/revisions,
- strict-locality preflight check performed:
	- verify public availability of `Thread.Builder.OfVirtual.scheduler(...)` before strict runs,
	- if unavailable, mark as `not runnable on this JVM` and do not classify as performance regression,
- no unaccounted environment drift (thermal throttling, host contention, governor changes).

E2E result is comparison-usable only when both legs are claim-eligible.

Data failing any gate is excluded from progression conclusions.

## 6) Artifacts required in handoff package
The evidence package must include:
- raw run outputs per repetition (`CSV` or `JSON`),
- aggregated micro report per matrix point,
- A/B delta table for `locality-aware` versus `default-vt`,
- latency summary (`p50/p95/p99`) and throughput summary,
- `perf stat` raw dumps (`no-multiplex`),
- JFR files for representative repetitions,
- strict-locality preflight report with JVM build metadata,
- runbook snapshot: JVM flags, pinning/NUMA settings, host metadata.
- E2E campaign status evidence per leg and scenario.

Real E2E scenario set in this cycle:
- `entity-read-by-id`
- `e2e-shop-order-saga`

## 7) Interpretation policy
Allowed conclusion:
- whether micro evidence supports mechanism signal,
- whether E2E evidence is claim-eligible,
- integrated GO/NO_GO classification.

Not allowed in this handoff:
- C5 unblock recommendations when E2E is non-eligible,
- protocol progression claims (`Phase-HTTP2`/H3).

## 8) Current cycle snapshot
Current cycle decision: NO_GO for progression.

Reason:
- No consistent measurable benefit signal for `locality-aware` versus
	`default-vt` was observed in current-cycle evidence.
- E2E campaign eligibility/stability constraints are secondary and do not
	change the primary decision rationale.

## 9) Deferred items
Deferred explicitly to a later scope:
- C5 decision framework beyond current dual-track NO_GO outcome,
- any `Phase-HTTP2` and H3 benchmarking plan.

This handoff is complete when both micro and E2E artifacts are delivered with explicit integrated decision labeling.
