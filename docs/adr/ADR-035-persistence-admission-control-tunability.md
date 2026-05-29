# ADR-035: Persistence Admission Control Tunability and Small-Pool Recalibration

**Status:** Accepted
**Date:** 2026-05-29
**Owner:** kernel/persistence
**Visibility:** public
**Scope:** kernel SPI contract (`PersistenceEngine#canServiceRequest`) + Community implementation + admission TCK
**Authors:** Arkadiusz Przychocki
**Driven By:** constrained `entity-read-by-id` benchmark regression (run `20260529T202221Z-constrained-smoke`, ~92% 503 error rate)
**Cross-references:** ADR-006 (Spring-Free Kernel Boundary — "The Wall"), ADR-005 (JFR-first telemetry), ADR-008 (Open-Core Strategy)

## Context

The `exeris-benchmarks` constrained-smoke profile (`runtime-constrained-256m-1vcpu-v1`: `-XX:MaxRAM=256m`,
`-XX:ActiveProcessorCount=1`, 16 wrk connections) regressed to a **~92% `error_rate_pct`** on the
read-only `entity-read-by-id` scenario. The v0.5 baseline (run `20260331T105908Z`, on an even tighter
128MB / 94%-CPU-throttle envelope) had **0% errors** at ~2100 rps. PostgreSQL was healthy throughout
(`pg_stat_statements` mean ≈ 0.01ms); only ~41k of 554k requests ever reached the database.

Root cause (three interacting facts):

1. **Pool collapses to its floor.** `CommunityPersistenceConfigResolver.computeAdaptivePoolSize()` is
   `clamp(availableProcessors() * 2, 2, 32)`. Under `-XX:ActiveProcessorCount=1` that is **2 connections**,
   against 16 concurrent clients.
2. **A 503 admission gate was added in v0.6.0** (PR #82). `CommunityHttpRequestDispatcher.dispatch()`
   calls `persistenceEngine.canServiceRequest()` before handling and returns
   `503 Service Unavailable` + `Retry-After` when it is `false`. This gate did not exist in v0.5.
3. **The gate sheds on the first queued acquire.** `CommunityPersistenceAdmissionController` rejected when
   `active/max ≥ 0.90` or when `idle == 0 && queued > 0`. With a 2-slot pool and 16 clients those
   conditions are essentially always true, so ~14/16 ≈ 88% of requests were shed — matching the observed 92%.

The thresholds were hard-coded `static final` constants, so an operator could not even tune their way out.
The gate's intent (prevent ThreadPark storms when the HTTP sender outruns the persistence layer) is sound,
but the calibration mistook a transient queue draining sub-millisecond for genuine saturation. Shedding on
a forming queue was also baked into the **cross-tier admission TCK** as if it were a universal contract,
when it is really a Community-specific heuristic.

## 🏁 The Decision

**Make the Community admission thresholds operator-tunable, and recalibrate the default so a small pool
absorbs a transient queue proportional to its drain rate instead of shedding on the first waiter — while
preserving real backpressure once the queue is genuinely deep. Demote "shed on a forming queue" from a
universal SPI contract to a tunable, tier-specific policy.**

### What changes

- **`CommunityAdmissionConfig`** (new, Community-internal immutable `record`) carries the thresholds
  (`hardSaturationThreshold`, `guardBandThreshold`, fairness params, early-guard-band headroom) plus a new
  **`queueDepthAllowanceRatio`**. The controller reads these instead of `static final` constants.
- **Recalibrated reject rule.** A full/saturated pool now sheds only once
  `pendingAcquires > queueDepthAllowance(maxPool)`, where
  `queueDepthAllowance = ceil(maxPool × queueDepthAllowanceRatio)`. Allowance scales with pool size because
  a larger pool drains a queue proportionally faster for the same expected wait. **Default ratio `8.0`**:
  for `maxPool=2` the allowance is 16 (covers the 16-client burst → admit, queue briefly, 0% shed); for
  `maxPool=256` it is 2048 (≈ 4–8ms expected wait for sub-ms queries — within the No-Waste-Compute bound).
  Setting the ratio to `0.0` restores the exact pre-035 strict behavior.
- **Tunability + hot-reload.** Thresholds resolve from `persistence.admission.*` config keys at bootstrap.
  The live config is held in a `@Dynamic static volatile CommunityAdmissionConfig CURRENT` (the kernel's
  first production consumer of `@Dynamic`), read at each decision call site. Per the `@Dynamic` contract:
  **Community is startup-configurable** (`ConfigProvider.watch` is a no-op); **Enterprise hot-reloads** the
  pointer atomically on file change. No `PersistenceConfig` (SPI) field is added — the config stays entirely
  in Community, so The Wall is untouched (the only SPI change is Javadoc).
- **TCK contract relaxed.** `AbstractPersistenceEngineAdmissionControlTck` no longer mandates immediate
  shedding on a forming queue or at ≥90% saturation. The universal invariants are: capacity → admit;
  closed → shed/throw; decision is non-blocking and consistent; decision flips to admit when capacity frees.
  Strict shed assertions move to the Community tests, which exercise both the strict (ratio 0) reject machine
  and the recalibrated default (small-pool transient queue admits; deep queue sheds).

### Config keys

| Key (`persistence.admission.*`) | Default | Meaning |
|---|---|---|
| `queueDepthAllowanceRatio` | `8.0` | pending acquires tolerated per unit of pool size before shedding; `0` = strict pre-035 |
| `hardSaturationThreshold` | `0.90` | `active/max` at which a queued pool sheds |
| `guardBandThreshold` | `0.85` | early-fairness band entry ratio |
| `fairnessStressThreshold` | `0.90` | fairness ratio declaring sustained stress |
| `fairnessQueueDepthThreshold` | `1` | queue depth considered for fairness stress |
| `earlyGuardBandHeadroomRatio` | `0.15` | headroom fraction for early guard-band reject |
| `earlyGuardBandHeadroomCap` | `3` | absolute cap on the early guard-band window |

## Consequences

- The constrained benchmark is expected to return to ~0% errors with the default config (verify against the
  benchmark, confirming reject reasons disappear via `AdmissionDecisionEvent` / JFR).
- Admission semantics change for **all** Community deployments, not just the constrained profile: a full pool
  with a shallow queue now admits and queues briefly rather than fast-failing 503. Operators who relied on the
  aggressive shed can restore it with `queueDepthAllowanceRatio=0`.
- Residual risk: for genuinely slow queries, a proportional allowance can let expected wait approach
  `ratio × serviceTime`. Mitigated by tunability and the retained fairness/wait-time stress signal; a future
  refinement may gate on observed `queueWaitP95` rather than depth.
- The fix corrects a dangling reference: `PersistenceEngine#canServiceRequest` previously cited "ADR-010",
  which is actually *Host Runtime Model* (exeris-spring-runtime); it now cites this ADR.

## Out of scope

- The benchmark harness reports `runner_status: success` and a headline `throughput_rps` despite a 92% error
  rate. Gating/flagging high-error runs as invalid is tracked separately in `exeris-benchmarks`.
- The adaptive pool-sizing formula itself (`cores × 2` under `ActiveProcessorCount=1`) is left unchanged;
  operators on CPU-constrained profiles may still raise `persistence.maxPoolSize` explicitly.
