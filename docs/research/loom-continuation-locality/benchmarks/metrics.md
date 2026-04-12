# Metrics - Loom Continuation Locality Benchmark

## Track A Metrics (Kernel Micro JMH)

## Primary Metric: Efficiency Signal

**Definition:** primary efficiency ratio defined by the active micro harness
(for example instruction-normalized cost per operation/request equivalent).

- Collected from micro harness outputs and external perf/JFR side artifacts.
- Compared as delta `%` of `locality-aware` vs `default-vt`.
- Reported with confidence bounds and relative error.

## Secondary Metrics

### Throughput (`ops/s`)

- Unit: operations per second.
- Used as supporting signal; not a standalone progression criterion.

### Latency (`p50`, `p95`, `p99`)

- Reported per matrix point and as A/B deltas.
- Latency regressions trigger investigation and caveats.

### Stability and Variance

- Standard deviation and confidence interval per key metric.
- Relative error for primary efficiency metric.

### System Counters (when available)

- `perf stat`: cycles, instructions, cache-misses, branch-misses
  in no-multiplex mode.
- CPU utilization and error/timeout count.

### Runtime/JFR Correlation

- JFR runtime signals used for qualitative interpretation of scheduler/locality behavior.

## Track B Metrics (E2E Campaign Eligibility)

These metrics decide whether E2E outputs can be used for A/B interpretation.

Required status fields per leg:
- `runner_status`
- `reproducibility_status`
- `final_reason`
- `claim_scope`
- `benchmark_exit_code`
- `json_present` and key counters

Interpretation:
- `claim_scope=none` or nonzero benchmark exit on either leg blocks
  comparison-eligible E2E conclusion.
- Partial/non-assessable E2E outputs remain descriptive only.

## Aggregation Rules

**Micro track:**
- Mean and 95% CI for key metrics.
- Relative error threshold enforced per reporting gates.

**E2E track:**
- No averaging across ineligible legs.
- Only claim-eligible rows enter A/B summary interpretation.

## Integrated Decision Metric

Integrated decision state:
- `GO`: quality-valid evidence with observable `locality-aware` benefit signal.
- `NO_GO`: no consistent measurable `locality-aware` benefit signal,
  regardless of exploratory framing.

Current cycle state: `NO_GO`.

## Output Requirements

Reports must include:
- micro A/B deltas by matrix point,
- E2E per-leg status/eligibility evidence,
- explicit statement whether integrated decision is GO or NO_GO,
- explanation of blocked claims when decision is NO_GO.
