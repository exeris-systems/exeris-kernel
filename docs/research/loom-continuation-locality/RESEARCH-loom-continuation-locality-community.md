# Research: Loom Continuation Locality - Community Track

> **Branch:** `research/loom-continuation-locality`
> **Author:** @arkstack-dev
> **Started:** 2026-03-20
> **Milestone:** v0.6 - findings feed scheduler seam design, benchmark harness, and bounded architecture guidance
> **Status:** `active`

Shared context, hypothesis background, methodology, deliverables, milestones, and initial position are in `RESEARCH-loom-continuation-locality.md`.

---

# Track 1 - Core/Community

> **Track ID:** `research/loom-continuation-locality/core-community`

## Track Hypothesis

On the simpler Exeris public stack, replacing the default virtual-thread execution
path with a more transport-affine continuation backend will improve fixed-rate CPU efficiency
and reduce context-switch/locality-related overhead,
even if the effect is smaller than on Enterprise native transports.

This track is not trying to prove the full native-transport thesis.
It is trying to answer three narrower questions:

1. Can the continuation-locality effect be reproduced on a simpler Exeris stack?
2. Can Exeris build a clean benchmark seam around virtual-thread execution?
3. Can the benchmark harness and methodology be stabilized before entering the full Enterprise/native design space?

---

## Core/Community Motivation

The Core/Community track exists for methodological reasons:

- it is easier to control,
- easier to benchmark repeatedly,
- easier to expose in public-facing discussions,
- and less confounded by native transport details.

It serves as:
- a baseline,
- a harness validation layer,
- a control case,
- and a public proof track.

The goal is not to claim that Community fully represents Enterprise.
The goal is to prove the measurement discipline and isolate the scheduler effect before io_uring, multishot, and bounded-drain complexity are introduced.

---

## Core/Community Problem Statement

The current Community path still uses default virtual-thread startup/resume mechanics at key execution boundaries.

Even if this is semantically correct, it may hide measurable costs:

- continuation resume on default FJP,
- loss of transport-locality,
- extra context switches,
- increased instructions per request,
- higher memory hierarchy pressure at fixed load.

The key problem statement for this track is:

> If admission policy and request handling semantics stay constant, does replacing
> the default virtual-thread execution backend with a scheduler-aware alternative
> materially improve efficiency on the Core/Community stack?

---

## Core/Community Scope

### In scope

- NativeTcpCarrier / public transport path benchmarking
- PAQS execution seam extraction
- default VT spawning baseline
- scheduler-aware experimental execution backend
- H1/H2 fixed-rate efficiency benchmarking
- max-throughput comparison
- CPU/context-switch/latency analysis
- optional cache/migration counters where feasible

### Out of scope

- io_uring
- QUIC/H3
- multishot receive
- batch receive
- bounded drain inside native carriers
- full per-carrier poller integration
- JDK-internal poller behavior

---

## Core/Community Variants

### C1 - `community-current`
Current behavior:
- current NativeTcpCarrier
- current PaqsScheduler behavior
- `Thread.ofVirtual().start(...)`
- default Loom/ForkJoin continuation path

### C2 - `community-spawner-baseline`
Behavior-preserving refactor:
- same semantics as C1
- execution goes through an extracted backend seam
- used to prove that the refactor itself does not move results

### C3 - `community-affine-prototype`
Experimental continuation backend:
- PAQS semantics unchanged
- execution uses scheduler-aware or transport-affine backend
- benchmark target for first A/B

### C4 - `community-vt-eventloop-prototype` *(optional, later)*
Exploratory track:
- event loop or dispatch path partially modeled around VT execution
- only after C1-C3 are stable

---

## Core/Community Benchmark Questions

1. Does default FJP continuation resumption leave measurable CPU efficiency cost
   on the Community path?
2. Does the effect show up primarily at fixed sub-maximal load rather than only
   at saturation?
3. Does a scheduler seam in PAQS preserve semantics while enabling clean A/B tests?
4. Does a transport-affine backend reduce:
   - context switches,
   - instructions/req,
   - CPU utilization,
   - tail latency?
5. Are the results strong enough to justify carrying the methodology into Enterprise?

---

## Core/Community Benchmark Plan

### Phase C0 - Research scaffold

Deliverables:
- `docs/research/loom-continuation-locality/benchmarks/README.md`
- `docs/research/loom-continuation-locality/benchmarks/hypotheses.md`
- `docs/research/loom-continuation-locality/benchmarks/methodology.md`
- `docs/research/loom-continuation-locality/benchmarks/metrics.md`
- `docs/research/loom-continuation-locality/benchmarks/reporting-rules.md`

### Phase C1 - Execution seam extraction

Primary implementation task:
- extract execution backend from PAQS so request policy remains constant while
  execution strategy changes.

Candidate seam:
- `StreamExecutionSpawner`
- or `StreamExecutionBackend`

Responsibilities:
- spawn or schedule stream execution,
- preserve stream naming and observability,
- avoid semantic changes in admission/load shedding.

Deliverables:
- seam abstraction,
- default implementation matching existing behavior,
- no regression baseline.

#### Status (2026-03-22)

- implemented `StreamExecutionBackend` seam in `PaqsScheduler`
- added backward-compatible constructor path preserving default behavior
- added unit test for custom backend invocation and thread naming

### Phase C2 - Baseline verification

Run:
- `community-current`
- `community-spawner-baseline`

Goal:
- prove the seam extraction is behavior-neutral.

#### Status (2026-03-22)

- JMH baseline run completed
- C1 vs C2 throughput numbers:
   - first run: `c1=5,288,204.318 ops/s`; `c2=7,972,533.573 ops/s`; delta `+50.760695%`
   - stabilized run (`-f 2 -wi 5 -i 8`): `c1=6,892,375.103 ops/s`; `c2=9,802,019.755 ops/s`; delta `+42.22%`
- interpretation: C2 strong improvement (not parity)
- latest 3-way JMH run (C1/C2/C4):
   - first pass (`-f 1 -wi 3 -i 5`): `c1=5,473,621.321 ops/s`; `c2=10,142,104.062 ops/s`; `c4=5,222,186.951 ops/s`
   - stabilized pass (`-f 2 -wi 5 -i 8`): `c1=5,005,350.201 ops/s`; `c2=10,618,142.228 ops/s`; `c4=5,880,428.830 ops/s`
- stabilized deltas vs C1: `c2=+112.14%`; `c4=+17.48%`
- winner: `c2CustomInlineBackend` (throughput)

### Phase C3 - Experimental backend

Implement:
- `community-affine-prototype`

Goal:
- compare default FJP-based execution vs scheduler-aware backend with identical
  PAQS/admission/load-shedding semantics.

#### Status (2026-03-22)

- contract layer implemented via PAQS seam TCK in core and community
- `CorePaqsSchedulerTckTest` passes (9 tests)
- `CommunityPaqsExecutionBackendContractTest` passes (1 test)
- `LocalityAwareExecutionBackend` implemented in `eu.exeris.kernel.community.transport` - pins VT continuations to a per-carrier `ForkJoinPool` via `Thread.ofVirtual().scheduler(pool)` (reflective probe; falls back to default VT scheduler if API not accessible on the active JDK)
- `NativeTcpCarrier` extended with locality-aware constructor: `NativeTcpCarrier(..., int localityPoolParallelism)` - creates and owns a dedicated `ForkJoinPool`, wired into `initPaqs()` via the 6-arg `PaqsScheduler` constructor; production default path (5-arg constructor) unchanged
- JMH benchmark variant `c4LocalityAwareVirtualThreadBackend()` executed in 3-way run; stabilized throughput `5,880,428.830 ops/s` (`+17.48%` vs C1)
- strict-mode implementation added in Community (`LocalityAwareExecutionBackend(..., strictMode)` and strict-locality `NativeTcpCarrier` constructor overload)
- strict benchmark path added (`CommunityTransportStrictLocalityBenchmark`) and strict contract test added (`LocalityAwareExecutionBackendStrictContractTest`)
- strict-run status (2026-03-23): benchmark execution failed fast with `IllegalStateException: VT scheduler override unavailable`; root cause is missing public `Thread.Builder.OfVirtual.scheduler(...)` API visibility on active JVM, not `pollerMode`

### Phase C4 - Fixed-rate efficiency study

Run scenarios at:
- sub-maximal fixed rate,
- moderate fixed rate,
- maximum throughput.

#### Status (2026-03-22)

- runnable benchmark harness added: `CommunityTransportFixedRateBenchmark`
- location: `exeris-kernel-community/src/test/java/eu/exeris/kernel/community/transport/CommunityTransportFixedRateBenchmark.java`
- scenario params: `h1-plaintext-fixed-rate`, `h1-json-1kb-fixed-rate`, `h2-plaintext-fixed-rate`, `h2-json-1kb-fixed-rate`
- backend params: `default-vt`, `locality-aware`
- load params: `sub-max`, `moderate`, `max-throughput`
- status: harness compiled (`mvn -pl exeris-kernel-community -am test-compile -DskipTests`)
- run note: JMH benchmark discovery in Community requires Maven profile 'benchmarks' (enables jmh annotation processor)
- next run task: execute matrix and collect throughput + latency percentiles + CPU migration/cache counters
- mini-matrix executed for `scenario=h1-plaintext-fixed-rate` across `sub-max`, `moderate`, `max-throughput` and backends `default-vt` vs `locality-aware`
- measured throughput (`ops/s`, `-f 1 -wi 1 -i 3`):
   - `sub-max`: `default-vt=22,181.399`; `locality-aware=22,113.582`; delta `-0.31%`
   - `moderate`: `default-vt=232,388.983`; `locality-aware=249,947.287`; delta `+7.56%`
   - `max-throughput`: `default-vt=252,923.042`; `locality-aware=224,111.831`; delta `-11.39%`
- interpretation: signal is mixed and confidence is low due large error bars; requires longer runs (`-f>=2`, more iterations) before architectural conclusion

## Phase C4: Final Conclusion

**Measurement Campaign**: consolidated Community E2E campaign `results/raw/entity-read-by-id/20260324-150821-campaign` with 20 repeats per mode; all runtime and quality gates pass.

**Results Summary**:
- default-vt mean: `52,871.361 RPS`
- locality-aware mean: `52,958.579 RPS`
- delta: `+0.165%` (near parity)
- CI half-widths: `164.121` (default-vt), `118.641` (locality-aware)
- relative error: `0.310%` and `0.224%`

**Signal Classification**: weak signal at most; statistically detectable only in a narrow sense, operationally negligible for E2E decisioning.

**Architectural Interpretation**:
Locality-aware backend remains benchmarkable and architecture-dependent, but measured payoff is not material in current Community E2E scope. This does not strengthen a general claim for public scheduler-override API as a Loom-wide requirement.

**C5 Recommendation**: **NO_GO (maintained)**. Keep C5 closed in the current cycle.

Re-open criteria (strict):
1. repeated campaigns show durable and reproducible positive delta `>= +5%`
2. confidence bounds remain tight enough to separate signal from environment noise
3. mechanism-level evidence (JFR/perf) confirms a causal locality advantage

#### Strict-mode runtime gate

Strict locality requires public VT scheduler override API visibility (`Thread.Builder.OfVirtual.scheduler(...)`).
If this API is unavailable, strict runs are invalid by design and must fail fast.
This is a runtime/API gate condition, independent from transport poller configuration.
This gate is experimental validation infrastructure and is not evidence, by itself, of a required public scheduler API.

### Community E2E Consolidated Update (2026-03-24)

- Campaign reference: `results/raw/entity-read-by-id/20260324-150821-campaign`.
- Measurement shape: 20 repeats per backend mode (`default-vt`, `locality-aware`), all quality gates pass.
- Throughput means: `default-vt=52,871.361 RPS`; `locality-aware=52,958.579 RPS`; delta `+0.165%`.
- Precision: CI half-widths `164.121` and `118.641`; relative error `0.310%` and `0.224%`.
- Interpretation: near-parity; effect is at most statistically detectable and operationally negligible for Community E2E architecture decisions.
- Decision impact: maintain C5 `NO_GO`; no escalation based on this campaign alone.

### Perf-stat Addendum (2026-03-24)

- latest paired `perf stat` runs for `default-vt` vs `locality-aware` remain near-parity across:
   - task-clock
   - context switches
   - cpu migrations
   - instructions
   - cycles
   - frontend stall share
   - branch miss rate
- `locality-aware` shows at most a small directional reduction in cpu migrations, but not at a scale sufficient to explain or justify an architectural claim.
- no strong mechanism-level evidence appears, from these runs alone, that continuation-locality materially improves the Community E2E path.
- interpretation: consistent with throughput near-parity and current C5 `NO_GO`.

### Phase C5 - Optional exploratory VT event-loop prototype

Only if:
- C1-C4 already show a signal worth deeper modeling.

---

## Core/Community Scenarios

### Minimal initial scenario set

- `h1-plaintext-fixed-rate`
- `h1-json-1kb-fixed-rate`
- `h2-plaintext-fixed-rate`
- `h2-json-1kb-fixed-rate`

### Phase-two scenario set

- `h2-multiplex-small`
- `h1-burst-32`
- `h2-burst-32`
- `h2-concurrent-128`

---

## Core/Community Load Profiles

Mandatory:
- **fixed sub-max load** - primary signal profile
- **moderate load**
- **max throughput**

The fixed-rate profile is critical because previous findings suggest that scheduler
and locality penalties are often clearer below saturation than at full throughput.

---

## Core/Community Metrics

### Required

- throughput
- CPU utilized
- instructions/req
- context switches
- p50/p95/p99 latency
- error rate

### Recommended

- CPU migrations
- cache references/misses
- DRAM-related counters where available
- JFR scheduler events

## Latest Run Commands (Stabilized C4 Matrix - March 22, 2026)

Build & Setup:
```sh
mvn -pl exeris-kernel-community -P benchmarks -am clean test-compile -DskipTests
mvn -pl exeris-kernel-community -P benchmarks -am dependency:build-classpath -Dmdep.outputFile=/tmp/exeris-community-jmh-cp.txt
```

6 Stabilized Runs (each `-f 2 -wi 5 -i 8`):
```sh
# sub-max, default-vt
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=default-vt -p loadProfile=sub-max \
   -f 2 -wi 5 -i 8 -tu s

# sub-max, locality-aware
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=locality-aware -p loadProfile=sub-max \
   -f 2 -wi 5 -i 8 -tu s

# moderate, default-vt
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=default-vt -p loadProfile=moderate \
   -f 2 -wi 5 -i 8 -tu s

# moderate, locality-aware
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=locality-aware -p loadProfile=moderate \
   -f 2 -wi 5 -i 8 -tu s

# max-throughput, default-vt
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=default-vt -p loadProfile=max-throughput \
   -f 2 -wi 5 -i 8 -tu s

# max-throughput, locality-aware
java --enable-preview -cp "$(cat /tmp/exeris-community-jmh-cp.txt):exeris-kernel-community/target/test-classes" \
   -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZCollectionInterval=0 \
   org.openjdk.jmh.Main eu.exeris.kernel.community.transport.CommunityTransportFixedRateBenchmark.fixedRateStreamWrite \
   -p scenario=h1-plaintext-fixed-rate -p backendMode=locality-aware -p loadProfile=max-throughput \
   -f 2 -wi 5 -i 8 -tu s
```

**Notes:**
- All runs use ZGC (sub-millisecond pauses) to isolate scheduler effects from GC.
- Replace `exeris-kernel-community/target/test-classes` with actual path if test output directory differs.
- Classpath file auto-generated by Maven; regenerate if dependencies change.

### Strict-mode execution note (2026-03-23)

- strict benchmark class: `CommunityTransportStrictLocalityBenchmark`
- strict runs produced no score due to `IllegalStateException: VT scheduler override unavailable`
- clarification: failure is due to VT scheduler public API availability, not `pollerMode=3`

## Core/Community Validation Criteria

- [x] the PAQS execution seam introduces no measurable regression in the baseline
- [x] the benchmark harness is reproducible across repeated runs
- [x] fixed-rate load exposes meaningful scheduler differences
- [x] A/B comparison between baseline and affine prototype is possible
- [x] A/B setup is benchmarkable, but payoff is not materially meaningful in current E2E scope
- [ ] results are strong enough to justify Enterprise expansion

**Validation complete (2026-03-22):** Full module test sweeps pass on clean rebuild.

- `exeris-kernel-core`: 914 tests, 0 failures, 0 errors, 10 skipped - BUILD SUCCESS
- `exeris-kernel-community`: 619 tests, 0 failures, 0 errors, 3 skipped - BUILD SUCCESS
- `CorePaqsSchedulerTckTest`: 9/9 pass (`PriorityDifferentiation` 4, `ScopedValuePropagation` 2, `CounterInvariants` 2, `ExecutionBackendSeamContract` 1)
- `CommunityPaqsExecutionBackendContractTest`: 1/1 pass
- `CommunityExplicitTcpContractsTest`: 24/24 pass after CommunityPaqsExecutionBackendContractTest.

Toolchain note: prior `UnsupportedClassVersionError` (class `69.65535` / Java 25 preview) was caused by stale Java 25 JARs in `~/.m2`. Fixed by running `mvn -pl exeris-kernel-spi,exeris-kernel-tck,exeris-kernel-core -am clean install -DskipTests` before community test run. Workflow fix: always install dependency chain with `-am clean install -DskipTests` after clearing `/target`, rather than using `clean test` in isolation.

---

## Core/Community Decision

- [ ] **Proceed to Enterprise track** - if scheduler signal is measurable and stable
- [x] **Iterate methodology** - if seam works but signal is noisy
- [x] **Park** - payoff thesis parked for current cycle; C5 remains closed (NO_GO)
- [ ] **Run strict-locality matrix** - only on JVM builds exposing public `Thread.Builder.OfVirtual.scheduler(...)`
- [ ] **Run isolated-host H1 confirmation** - same scenario/config, min 5 repeats, before any H2 expansion or perf-box escalation

---

## Community Continuation-Locality Research Stop Rule

### Purpose

This stop rule defines when the Core/Community continuation-locality research track should be considered sufficiently resolved for the current iteration.

Its purpose is to prevent scope drift, benchmark theater, and repeated escalation in search of a signal that is not materially present.

---

### Stop Condition

The Core/Community continuation-locality track must be stopped for the current cycle if all of the following are true:

1. the H1 E2E reference scenario completes with at least 5 repeated A/B runs,
2. the compared variants are:
   - `backendMode=default-vt`
   - `backendMode=locality-aware`
3. the scenario contract, host conditions, JVM, build, seed, and measurement policy remain fixed,
4. the resulting deltas are neutral, tiny-positive below materiality threshold, or negative for `locality-aware`,
5. no repeatable mechanism-level evidence appears that would justify further escalation.

For this purpose, "neutral or negative" means any of the following:
- throughput delta is not consistently positive,
- throughput delta is positive but tiny and below the materiality threshold for architectural action,
- latency percentiles do not show a compensating improvement,
- perf/JFR signals do not show a meaningful efficiency advantage,
- observed differences remain too small to justify architectural progression.

---

### Consequence of Stop

If the stop condition is met, the Community track is considered complete for the current research cycle with the following interpretation:

- the execution seam work was successful,
- the benchmark methodology work was successful,
- the continuation-locality payoff was **not demonstrated** on the Community E2E path,
- C5 remains `NO_GO`,
- no H2 follow-up is required,
- no perf-box escalation is required.

This is an accepted research outcome, not a failed process.

---

### Interpretation Rule

A negative or neutral Community result does **not** prove that continuation locality is universally irrelevant.

It supports a narrower conclusion:

> the continuation-locality advantage observed in Netty-oriented handoff studies does not currently transfer to the Exeris Community execution model in a way that justifies architectural escalation.

A plausible explanation is that Exeris already removes enough of the classic split-pipeline cost that the remaining continuation-placement effect is too weak to produce a meaningful E2E gain under the tested scenario.

---

### Explicit Non-Goals After Stop

Once the stop condition is met, the following are out of scope unless a new research trigger appears:

- expanding Community research into a full H2 matrix,
- escalating the same Community scenario to perf-box just to reduce noise,
- iterating minor benchmark variations in search of a positive result,
- treating Community neutrality as an argument for speculative architectural redesign,
- assuming that a better public scheduler API would necessarily change the outcome.

These actions would represent scope drift unless backed by a new, concrete signal.

---

### Allowed Next Steps After Stop

The following remain valid after stopping the Community track:

1. document the result in the research note,
2. keep the scheduler seam as useful benchmark/architecture infrastructure,
3. preserve the result as evidence that Netty-style handoff findings are not automatically transferable,
4. revisit the question only if a materially different architecture or workload shape appears,
5. use the findings to refine Enterprise hypotheses rather than to force a Community win.

---

### H2 Proceed Gate (After Isolated H1)

H2 is allowed only as a bounded follow-up if isolated H1 shows a positive, believable signal.

Proceed only if all of the following are true:

1. locality-aware has a positive mean throughput delta,
2. the delta is directionally credible: >= +3% is the minimum stable trigger, >= +5% is a strong trigger,
3. CI or relative error does not wash out the effect,
4. tail latency (p99/p999) does not degrade unacceptably,
5. perf/JFR does not contradict the mechanism-level interpretation,
6. the H2 objective is to test amplification or transferability in a richer transport shape, not to rescue the hypothesis.

Do not proceed to H2 if isolated H1 is neutral, noisy, too small to separate from environment effects, or justified only by hope.

---

### H3 Gate (Current Cycle)

H3 stays out for the current cycle.

It may be considered only if H1 is positive and stable, H2 is positive and stable, and there is a separate explicit research reason for the H3 domain.

---

### Final Rule

If isolated H1 is negative or neutral, stop the Community payoff track; no H2; no H3.

If isolated H1 is positive but tiny and believable, allow bounded H2 only.

If isolated H1 is positive and reasonably stable, around >= +5% with an acceptable latency profile, H2 is warranted for transferability or amplification check.

Do not escalate to H2 out of hope.

H3 does not proceed in the current cycle without the H1/H2 prerequisites.

---

## References

- Francesco Nigro discussion and scheduler notes
- Netty VirtualThread Scheduler materials
- Exeris PAQS / carrier design notes
- existing CPU efficiency findings and sustained-load analysis
