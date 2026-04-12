# Hypotheses - Loom Continuation Locality

## Primary Hypothesis (H1)

**Statement:** In kernel micro JMH runs, replacing `backendMode=default-vt`
with `backendMode=locality-aware` improves scheduler/locality efficiency signals
under controlled synthetic load.

**Rationale:** The default VT resume path can lose continuation/cache locality;
scheduler-aware execution may reduce this mechanism cost.

**Success Criteria:**
- Positive, repeatable micro deltas on primary efficiency metrics.
- Relative error and CI within accepted quality thresholds.
- No artifact mixing across binaries, JVM configs, and host profile.

---

## Secondary Hypothesis (H2: E2E Survivability)

**Statement:** If H1 is a meaningful mechanism signal, it should be at least partially
visible in E2E campaigns executed in exeris-benchmarks for locality A/B legs.

**Rationale:** Pure micro gains that disappear entirely in E2E are insufficient for
progression decisions.

**Success Criteria:**
- E2E runs provide comparison-eligible evidence for both legs.
- No systematic leg failure preventing A/B interpretation.

---

## Control Hypothesis (C0)

**Statement:** Baseline `default-vt` remains reproducible in both tracks
(kernel micro and E2E campaign baseline leg).

**Rationale:** Decision quality depends on baseline stability and reproducibility.

**Success Criteria:**
- Stable baseline in micro repetitions.
- E2E baseline leg completes with valid status/metadata.

---

## Non-Hypotheses (Out of Scope)

- Enterprise/io_uring efficiency claims
- Multishot or bounded-drain effectiveness
- JDK internal poller tuning
- Lock-free scheduler design validation
- Cross-runtime superiority claims from locality exploratory runs

---

## Current Decision Snapshot

Current cycle outcome: NO_GO for progression.

Interpretation:
- Observed results do not show a consistent measurable benefit of
  `locality-aware` over `default-vt`.
- This remains true even under exploratory interpretation.
- Therefore, progression claims are deferred.
