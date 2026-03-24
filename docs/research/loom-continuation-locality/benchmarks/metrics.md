# Metrics - Loom Continuation Locality Benchmark

## Primary Metric: CPU Efficiency

**Definition:** Instructions per request (IPR).

- Collected via: perf-stat cycles and instructions; request count from harness log.
- Calculation: total_instructions / total_requests.
- Unit: instructions/request (dimensionless ratio).
- Target: baseline C should be stable (CV < 3%); E should be >= 5% lower.

## Secondary Metrics

### Throughput (Requests Per Second)

- Collected via: harness timer; requests_completed / elapsed_seconds.
- Unit: RPS.
- Target: E should not regress vs C (within measurement CV).
- Note: Fixed-rate mode means RPS is near-constant; variance reflects dropped requests or rate-limit spillover.

### Context Switches Per Request

- Collected via: perf-stat context-switches counter.
- Calculation: total_context_switches / total_requests.
- Unit: switches/request.
- Target: E should be measurably lower than C, proportional to IPR improvement.

### CPU Migrations Per Request

- Collected via: perf-stat cpu-migrations counter.
- Calculation: total_cpu_migrations / total_requests.
- Unit: migrations/request.
- Target: E should reduce migrations by reducing continuation wake-ups on unaffine cores.

### Latency Percentiles (P50, P90, P99)

- Collected via: per-request wall-clock latency histogram from load generator.
- Unit: milliseconds.
- Target:
  - P50_E must not increase by more than 5% vs C.
  - P50 regression 5-10% is WARN (allowed with caveat).
  - P50 regression >10% is ALARM and requires investigation.
  - P99_E regression up to +20% is acceptable only if the P50 guard passes.
  (Latency is secondary; efficiency is primary.)

### Virtual Thread Events

- Collected via: JFR ThreadCPULoad, VirtualThreadStart/End, JDKContinuationFork.
- Unit: count, wall-clock time (JFR derived).
- Target: baseline characterization; used for qualitative correlation with perf-stat data.

## Quaternary Metric: Heap Allocation Rate

- Collected via: JFR `JdkThreadAllocationStatistics`.
- Calculation: total_bytes_allocated / total_requests.
- Unit: bytes/request.
- Target: E should not exceed C by more than 10%.
- If exceeded: WARN allocation shift and require JFR deep-dive.

## Aggregation Rules

**Mean:** Arithmetic mean over 5 runs.
**Confidence Interval:** 95% CI using t-distribution (n=5; critical t ~= 2.776).
**Relative Error (RE):** (CI_width / mean) * 100.
**Acceptance Threshold:** Internal target RE <= 2% for primary metric (CPU efficiency); decision-level acceptance RE <= 10%.

**CV (post-filter):**
1. Collect 5 runs.
2. Remove runs with GC pause > 500ms.
3. If remaining n < 3, fail batch and rerun.
4. Compute CV on remaining runs.
5. CV < 3% passes; otherwise extend to 10 reps.

If RE > 2%, rerun measurement with 10 reps and re-aggregate for internal quality objective.

## Outlier Handling

- Remove any run where GC-pause-time > 500ms (indicates unplanned GC event).
- If after removal n < 3, rerun the batch.
- CV is computed only after this filter is applied.
- Document all removals in benchmark output.

## Output Format

Benchmark result includes:
- Variant name (C or E).
- Concurrency level (16, 32, 64 vt).
- Mean and 95% CI for IPR, throughput, context-switches.
- Relative error for IPR.
- Pass/fail status (IPR_E < IPR_C * 0.95 AND P50 guard PASS; P99 guard applied per latency policy).
- Path to JFR files for detailed post-mortem.
