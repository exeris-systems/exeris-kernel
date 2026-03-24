# Reporting Rules - Loom Continuation Locality Benchmark

## Pass Criteria

A measurement batch PASSES if:

1. **Preflight Validation:** All API preflight checks green (both implementations discovered, SPI calls succeed).
2. **Baseline Stability (post-filter CV sequence):**
  1. Collect 5 runs.
  2. Remove runs with GC pause > 500ms.
  3. If remaining n < 3, fail batch and rerun.
  4. Compute CV on remaining runs.
  5. CV < 3% passes; otherwise extend to 10 reps.
3. **Measurement Quality:** Internal target relative error (95% CI) <= 2% for primary metric (IPR); decision-level acceptance <= 10%.
4. **No Dropouts:** < 5% of requests dropped or timed out per run.
5. **GC Outlier Policy Applied:** Runs with GC pause > 500ms are removed; batch remains valid only if filtered n >= 3.
6. **Statistically Significant Improvement (H1):** IPR_E < IPR_C * 0.95 at 64 virtual threads.
7. **Monotonic Scaling (Concurrency):** Efficiency gain increases or plateaus from 16vt to 64vt (no regression at higher concurrency).
8. **Latency Regression Guard:**
  - P50_E must not increase by more than 5% vs C.
  - P50 regression 5-10%: WARN (efficiency-latency tradeoff; allowed with caveat).
  - P50 regression > 10%: ALARM; defer pending investigation.
  - P99_E regression up to +20% is acceptable only if the P50 guard passes.

## Claim Eligibility Mapping

- Batch passes all gates -> `claim_scope="comparison_eligible"`.
- Any hard gate fails -> `claim_scope="descriptive_only"`.
- Aggregate mixes eligible and non-eligible inputs (for example, one side not eligible) -> `claim_scope="descriptive_partial"`.
- Compare E vs C only when both sides are `comparison_eligible`.

## Failure Modes and Escalation

### Preflight Fails

- **Cause:** SPI discovery, provider binding, or contract violation.
- **Action:** Fix provider configuration or SPI contract before re-running.
- **Report:** "Preflight Failure - <detail>" in benchmark output.

### Baseline Instability (post-filter CV > 3%)

- **Cause:** Noisy measurement environment, GC pauses, or harness jitter.
- **Action:** Collect 5 runs, remove runs with GC pause > 500ms, fail and rerun if n < 3, then compute CV on remaining runs. If CV remains > 3%, extend to 10 reps.
- **Report:** "Baseline Instability - post-filter CV=<value>% (> 3% threshold); rerun/extend to 10 reps."

### Measurement Noise (RE > 2%)

- **Cause:** Insufficient iterations or high variance hardware.
- **Action:** Increase repetitions to 10, apply outlier filters, rerun.
- **Report:** "High Measurement Variance - RE=<value>% (above internal target, not necessarily decision-level blocker); expanded to 10 reps."

### No Improvement (IPR_E >= IPR_C * 0.95)

- **Cause:** Experimental backend provides no efficiency gain at 64vt.
- **Action:** Review experimental implementation, capture detailed JFR traces, check affinity assumptions.
- **Report:** "No Statistically-Significant Improvement - IPR improvement < 5% at 64vt; defer phase."

### Latency Regression Guard

- **Cause:** Experimental backend trades latency for throughput.
- **Action:**
  - P50 +5-10%: keep result as WARN with caveat, capture JFR trace, and document tradeoff.
  - P50 > +10%: ALARM, defer decision pending investigation.
  - P99 up to +20% is allowed only when P50 <= +5%.
- **Report:** "Latency Guard - P50 delta=<value>%, P99 delta=<value>%, status=<PASS|WARN|ALARM>; review <JFR-path>."

## Output Format

Benchmark summary report includes:

```
===== Loom Continuation Locality Benchmark Summary =====

Preflight Status: PASS
Baseline Stability: PASS (C CV = 2.1%)

Results (Mean ± 95% CI, Relative Error):

Concurrency 16vt:
  Baseline (C)   IPR: 4200 ± 45 cycles/req (RE=1.1%)
  Experimental (E) IPR: 3900 ± 48 cycles/req (RE=1.2%)
  Improvement: 7.1% (PASS)

Concurrency 32vt:
  Baseline (C)   IPR: 4400 ± 55 cycles/req (RE=1.2%)
  Experimental (E) IPR: 3850 ± 60 cycles/req (RE=1.4%)
  Improvement: 12.5% (PASS)

Concurrency 64vt:
  Baseline (C)   IPR: 4650 ± 70 cycles/req (RE=1.5%)
  Experimental (E) IPR: 4100 ± 75 cycles/req (RE=1.6%)
  Improvement: 11.8% (PASS)

Overall: H1 PASS (>= 5% at 64vt), Concurrency scaling PASS (monotonic scaling to 32vt, plateau at 64vt)
Final Status: PASS

JFR Files (for post-run analysis):
  benchmark-16vt-c-run1.jfr ... benchmark-64vt-e-run5.jfr
```

## Regression Detection

If current measurement differs from last approved baseline by > 20% absolute IPR:
- Halt and report as "Regression Candidate."
- Require manual review and re-baseline before merging experimental changes.

## Archival

All benchmark results (output summary, JFR files, perf-stat logs) are archived to:
`target/benchmark-results/loom-continuation-locality/<YYYY-MM-DD-HH-MM-SS>/`

with symlink to latest:
`target/benchmark-results/loom-continuation-locality/latest/`

## References

- Hypotheses: hypotheses.md
- Methodology: methodology.md
- Metrics: metrics.md
- Parent research: docs/research/loom-continuation-locality/RESEARCH-loom-continuation-locality-community.md
