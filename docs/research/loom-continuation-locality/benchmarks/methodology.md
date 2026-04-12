# Methodology - Loom Continuation Locality Benchmark

## Methodology Overview

This research is executed as two coordinated tracks:

1. Kernel track (micro JMH): mechanism validation.
2. Benchmark-lab track (E2E): survivability validation under realistic flow.

Progression is allowed only when both tracks are quality-valid.

## Track A - Kernel Micro JMH (Mechanism)

### Goal

Measure scheduler/locality signal in controlled conditions for:
- `backendMode=default-vt`
- `backendMode=locality-aware`

### Canonical Micro Matrix

Dimensions:
- `backendMode`: `default-vt`, `locality-aware`
- `loadProfile`: `sub-max`, `moderate`, `max-throughput`
- `scenario`: `baseline`, `contention`, `allocation-pressure`

This forms 18 canonical A/B points.

### Invariants

- Same benchmark binary/revision for both backend modes.
- Same JVM flags except explicit backend mode switch.
- Same host profile, governor, pinning policy, and NUMA policy.
- Same warmup/measurement/fork strategy per compared point.

### Quality Discipline

- Minimum 3 independent repetitions per point.
- 95% CI for key metrics.
- Relative error gate for primary metrics.
- Strict-locality preflight before strict runs.

## Track B - Exeris Benchmarks E2E (Survivability)

### Goal

Verify whether the mechanism signal is observable in E2E execution,
without over-claiming beyond run eligibility.

### Real Scenarios Used

- `entity-read-by-id` campaign track.
- `e2e-shop-order-saga` locality sweep track.

### Real Campaign Pattern

- Two-leg A/B campaign per scenario:
  - leg A: `backendMode=default-vt`
  - leg B: `backendMode=locality-aware`
- Shared infra profile and target app class for both legs.
- Protocol/contract target-derived unless explicitly overridden.
- Claim scope marked exploratory for locality sweeps.

### Real Eligibility Boundary

E2E interpretation is valid only when both A/B legs are run-complete and
comparison-eligible under benchmark status rules.

If one leg is missing/failed/non-eligible, result is descriptive only and cannot
be used for progression claims.

## Integration Rule (Track A + Track B)

- Micro-only positive signal is insufficient for progression.
- E2E-only observations without micro quality are insufficient for causality.
- Decision requires aligned evidence: mechanism signal + E2E survivability.

## Current Cycle Outcome

Current cycle classification: NO_GO for progression.

Reason:
- Across current micro and E2E evidence, no consistent measurable benefit
  signal was observed for `locality-aware` versus `default-vt`.
- Eligibility/stability issues in E2E are secondary and do not change
  the primary NO_GO rationale.
