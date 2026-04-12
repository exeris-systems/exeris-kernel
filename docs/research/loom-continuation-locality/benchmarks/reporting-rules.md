# Reporting Rules - Loom Continuation Locality Benchmark

## Decision Model

This research uses a dual-track decision gate:

- Track A: kernel micro JMH mechanism evidence.
- Track B: exeris-benchmarks E2E survivability evidence.

Final decision is GO only when both tracks are valid.

## Track A Pass Criteria (Micro)

A measurement batch PASSES if:

1. Preflight and matrix invariants valid.
2. Required repetitions and CI/RE quality gates valid.
3. Primary metric and stability requirements satisfied.
4. Artifacts complete and traceable per run.

## Track B Pass Criteria (E2E)

E2E A/B track PASSES only when:

1. Both legs (`default-vt`, `locality-aware`) complete successfully.
2. Both legs are reproducibility-assessable.
3. Both legs are claim-eligible for intended interpretation.
4. No leg is downgraded to `claim_scope=none`/non-assessable.

If any E2E leg fails these conditions, E2E claim is descriptive-only and
cannot support progression.

## Integrated GO/NO_GO Gate

- GO: quality-valid evidence and consistent measurable `locality-aware` benefit.
- NO_GO: no consistent measurable `locality-aware` benefit signal.

## Failure Modes and Escalation

### Micro Gate Failure

- **Cause:** Methodology quality failure (variance, CI, artifacts, invariants).
- **Action:** Fix methodology issue and rerun micro track.
- **Report:** explicit failed gate and corrective action.

### E2E Eligibility Failure

- **Cause:** One or more E2E legs failed, missing, or non-eligible.
- **Action:** fix campaign reliability first; do not force comparative claims.
- **Report:** status evidence per leg (`runner_status`, `final_reason`, `claim_scope`).

### Mixed Evidence

- **Cause:** quality-valid runs but no observable `locality-aware` benefit signal.
- **Action:** keep decision NO_GO and document absence of benefit as primary reason.
- **Report:** explicitly state "no measurable benefit signal" in final rationale.

## Claim Language Policy

- Allowed:
  - "micro mechanism signal observed/not observed"
  - "E2E A/B evidence eligible/non-eligible"
  - "integrated decision: GO/NO_GO"

- Not allowed when E2E is non-eligible:
  - production-readiness claims
  - progression/upgrade recommendations that imply GO
  - cross-runtime superiority claims from exploratory locality runs

## Current Cycle Decision

Current classification: NO_GO.

Rationale:
- No consistent measurable benefit of `locality-aware` over `default-vt`
  was observed in current-cycle evidence.
- E2E eligibility constraints may exist, but they are not the primary NO_GO driver.

## Output Format

Benchmark summary report includes:

```
===== Loom Continuation Locality Benchmark Summary =====

Track A (Micro): <PASS|FAIL>
Track B (E2E): <PASS|FAIL>
Integrated Decision: <GO|NO_GO>

Track A Summary (mechanism):
- key A/B deltas
- CI/RE quality status

Track B Summary (survivability):
- per-leg status and claim eligibility
- blocked-claim reasons if any

Decision Boundary Notes:
- micro-only improvements are not progression approval
- NO_GO when no measurable locality-aware benefit is observed
```

## References

- Hypotheses: hypotheses.md
- Methodology: methodology.md
- Metrics: metrics.md
- Parent research: docs/research/loom-continuation-locality/RESEARCH-loom-continuation-locality-community.md
