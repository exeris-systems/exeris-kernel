# Hypotheses - Loom Continuation Locality

## Primary Hypothesis (H1)

**Statement:** On the Exeris Community stack with NativeTcpCarrier transport,
replacing default virtual-thread execution with a scheduler-aware continuation backend
will improve fixed-rate CPU efficiency by >= 5% under sustained load.

**Rationale:** The default FJP-resume path incurs context-switch and cache-locality costs
that a scheduler-aware alternative can mitigate by co-scheduling on affine threads.

**Success Criteria:**
- Measurable reduction in instructions-per-request at fixed rate.
- Measurable reduction in context-switches per request.
- Internal target CI/relative-error <= 2% across 5+ runs; decision-level acceptance <= 10%.

---

## Secondary Hypothesis (Concurrency Scaling)

**Statement:** The measured improvement will scale proportionally with concurrency level
(concurrency levels at 16, 32, 64 virtual threads).

**Rationale:** Locality effects should be more pronounced as thread pressure increases
and default FJP scheduling becomes more contended.

**Success Criteria:**
- Efficiency gain increases monotonically from 16vt to 64vt.
- Relative gain plateaus by 64vt (scheduler saturation or transport limit).

---

## Control Hypothesis (C0)

**Statement:** The baseline (default VT execution) shows stable, reproducible throughput
and CPU efficiency across runs.

**Rationale:** Validates harness and measurement discipline before introducing changes.

**Success Criteria:**
- Coefficient of variation (CV) < 3% for baseline CPU efficiency.
- No unexplained drift across sequential benchmark runs.

---

## Non-Hypotheses (Out of Scope)

- Enterprise/io_uring efficiency claims
- Multishot or bounded-drain effectiveness
- JDK internal poller tuning
- Lock-free scheduler design validation

---

## Observation: Latency vs Throughput Trade-off

Note: improved CPU efficiency may reduce P50/P90/P99 latency, but the hypothesis
does not require latency improvement. We measure both; latency regression (> 5%)
triggers investigation.
