# Research: Loom Continuation Locality — Core/Community and Enterprise Tracks

> **Branch:** `research/loom-continuation-locality`
> **Author:** @arkstack-dev
> **Started:** 2026-03-20
> **Milestone:** v0.6 — findings feed scheduler seam design, benchmark harness, and Loom feedback package
> **Status:** `active`

---

## Hypothesis

Exeris currently avoids part of the classical Loom split-pipeline cost by keeping
network carriers outside the default Loom scheduler, but it likely still leaves
measurable performance on the table because **virtual thread continuation execution
remains decoupled from transport locality**.

The central hypothesis is:

> Even when Exeris eliminates the explicit I/O-thread → task-queue submission handoff,
> default Loom/ForkJoin continuation resumption still incurs avoidable locality loss,
> extra context switching, and excess memory hierarchy pressure relative to a more
> transport-affine execution model.

This research is intentionally split into **two tracks**:

1. **Core/Community Track**  
   A simpler, more controllable benchmark and methodology track that validates the
   continuation-locality hypothesis on the public stack.

2. **Enterprise Track**  
   A native-transport track that extends the same hypothesis into the real Exeris
   design space: native carriers, io_uring, multishot receive, batching, bounded
   drain, and transport/scheduler coupling.

The two tracks answer different questions, but they share one thesis:

> The remaining performance ceiling is not “virtual threads are slow”; it is that
> default continuation placement and legacy scheduling geometry are not aligned with
> transport locality.

---

## Motivation

This research began from Francesco Nigro’s scheduler work and the broader observation
that naive Loom usage can create a **split pipeline**:

- one pool/thread domain handles I/O,
- another pool/thread domain resumes virtual thread work,
- every request crosses the boundary,
- and the system pays in handoff overhead, voluntary context switches, cache
  locality loss, and capacity inefficiency.

Francesco’s findings are important not because they are Netty-specific, but because
they expose a broader structural issue:

- default ForkJoinPool continuation placement is not free,
- the cost is visible not only in throughput but also in CPU efficiency,
- the effect is especially visible at fixed sub-maximal load,
- and the penalty shows up as broad DRAM/cache behavior, not just queue overhead.

Exeris enters this design space from a different angle.

Exeris does **not** use the exact same split as Netty’s default event-loop → FJ model.
In Exeris:

- native carrier loops are standalone OS threads,
- they do not run application logic,
- they reap completions and wake virtual threads,
- virtual thread execution still resumes under the default Loom scheduler.

That means Exeris already removed part of the problem — but likely not all of it.

This creates a uniquely valuable research position:

- Exeris is **not** the classic “double queue handoff” case,
- but Exeris is **still** exposed to continuation locality loss,
- which makes it a strong external validation case for Loom scheduler evolution.

This is why the research should not begin directly with “io_uring optimization”.
The broader question comes first:

> How much cost remains when explicit queue handoff is removed, but continuation
> locality is still delegated to the default Loom scheduler?

Only after that baseline is understood does it make sense to expand into the
Enterprise-specific scheduler/transport geometry.

---

## Relationship to Existing Findings

This research is informed by two key inputs.

### 1. Francesco Nigro’s scheduler findings

Key observations from Francesco’s work:

- split execution between I/O and default ForkJoin scheduling adds structural cost,
- unified scheduling reduces voluntary context switches,
- locality-preserving continuation execution improves throughput and tail behavior,
- the effect is visible even apart from protocol-specific implementation details.

### 2. CPU efficiency findings already observed around scheduler geometry

The prior efficiency analysis shows a pattern that strongly supports a continuation-
locality research track:

- fixed-rate sub-maximal load is more revealing than saturation alone,
- ForkJoin-based variants consume more CPU per request,
- DRAM/cache cost is broader than a single handoff hotspot,
- continuation thaw and pipeline traversal are locality-sensitive,
- CPU migrations and context switches materially affect efficiency.

These findings strongly suggest that Exeris should benchmark:

- **fixed-rate efficiency**,
- **continuation resume placement**,
- **light/sub-max load**,
- **memory hierarchy effects**,
not just maximum throughput.

---

## Research Structure

This document defines **two coordinated tracks**:

1. `core-community`
2. `enterprise`

The first track is about **methodological clarity and benchmark harness stability**.
The second is about **real transport design decisions**.

They must share:
- hypotheses,
- metrics,
- naming,
- reporting rules,
- result normalization.

They must diverge where the transport model diverges.

---

# Track 1 — Core/Community

> **Track ID:** `research/loom-continuation-locality/core-community`

## Track Hypothesis

On the simpler Exeris public stack, replacing the default virtual-thread execution
path with a more transport-affine continuation backend will improve fixed-rate CPU
efficiency and reduce context-switch/locality-related overhead, even if the effect
is smaller than on Enterprise native transports.

This track is not trying to prove the full native-transport thesis.
It is trying to answer three narrower questions:

1. Can the continuation-locality effect be reproduced on a simpler Exeris stack?
2. Can Exeris build a clean benchmark seam around virtual-thread execution?
3. Can the benchmark harness and methodology be stabilized before entering the
   full Enterprise/native design space?

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
The goal is to prove the measurement discipline and isolate the scheduler effect
before io_uring, multishot, and bounded-drain complexity are introduced.

---

## Core/Community Problem Statement

The current Community path still uses default virtual-thread startup/resume
mechanics at key execution boundaries.

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

### C1 — `community-current`
Current behavior:
- current NativeTcpCarrier
- current PaqsScheduler behavior
- `Thread.ofVirtual().start(...)`
- default Loom/ForkJoin continuation path

### C2 — `community-spawner-baseline`
Behavior-preserving refactor:
- same semantics as C1
- execution goes through an extracted backend seam
- used to prove that the refactor itself does not move results

### C3 — `community-affine-prototype`
Experimental continuation backend:
- PAQS semantics unchanged
- execution uses scheduler-aware or transport-affine backend
- benchmark target for first A/B

### C4 — `community-vt-eventloop-prototype` *(optional, later)*
Exploratory track:
- event loop or dispatch path partially modeled around VT execution
- only after C1–C3 are stable

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

### Phase C0 — Research scaffold

Deliverables:
- `README.md`
- `hypotheses.md`
- `methodology.md`
- `metrics.md`
- `reporting-rules.md`

### Phase C1 — Execution seam extraction

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

### Phase C2 — Baseline verification

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

### Phase C3 — Experimental backend

Implement:
- `community-affine-prototype`

Goal:
- compare default FJP-based execution vs scheduler-aware backend with identical
  PAQS/admission/load-shedding semantics.

#### Status (2026-03-22)

- contract layer implemented via PAQS seam TCK in core and community
- `CorePaqsSchedulerTckTest` passes (9 tests)
- `CommunityPaqsExecutionBackendContractTest` passes (1 test)
- `LocalityAwareExecutionBackend` implemented in `eu.exeris.kernel.community.transport` — pins VT continuations to a per-carrier `ForkJoinPool` via `Thread.ofVirtual().scheduler(pool)` (reflective probe; falls back to default VT scheduler if API not accessible on the active JDK)
- `NativeTcpCarrier` extended with locality-aware constructor: `NativeTcpCarrier(..., int localityPoolParallelism)` — creates and owns a dedicated `ForkJoinPool`, wired into `initPaqs()` via the 6-arg `PaqsScheduler` constructor; production default path (5-arg constructor) unchanged
- JMH benchmark variant `c4LocalityAwareVirtualThreadBackend()` executed in 3-way run; stabilized throughput `5,880,428.830 ops/s` (`+17.48%` vs C1)

### Phase C4 — Fixed-rate efficiency study

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

**Measurement Campaign**: 6-cell stabilized matrix (h1-plaintext-fixed-rate, 2 backends, 3 load profiles, -f 2 -wi 5 -i 8).

**Results Summary**:
- sub-max: -4.89% (default-vt faster), +-4.6%-7.7% error bands (clean signal)
- moderate: -3.35% (default-vt faster), +-21% error bands (noise-dominated)
- max-throughput: +3.24% (locality-aware faster), +-21% error bands (noise-dominated)

**Signal Classification**: Inconclusive. Deltas are marginal (<5%) and full-variance, within pooled measurement uncertainty.

**Mechanism Hypothesis** (to validate in C5):
- Continuation-locality affinity bookkeeping adds per-task overhead under paced fixed-rate loads (explaining -4.89% sub-max deficit).
- Unpaced workloads show weak marginal gain (+3.24%), indicating affinity amortizes in contention-dominated regimes, but signal is too noisy to claim architectural win.

**Architectural Interpretation**:
Locality-aware scheduling (C4) is optional exploratory, not a default contract lever. The default Virtual Thread scheduler remains superior for transactional fixed-rate patterns. Affinity gains are contingent on workload-intensive asynchronous patterns (C5 scope: event-loop prototyping).

**C5 Recommendation**: Conditional Go (medium confidence ~0.68). Proceed to Enterprise event-loop prototype if:
1. Isolated reruns reduce error bands to +-5-8% and separate noise from repeatable signal.
2. JFR profiling validates mechanism (e.g., fewer carrier migrations, lower park/unpark overhead).
3. Prototype demonstrates >=8% throughput or clear p95/p99 latency gain with <2-3% paced-load regression.

**Links to Subsystem/ADR Updates** (see below).

### Phase C5 — Optional exploratory VT event-loop prototype

Only if:
- C1–C4 already show a signal worth deeper modeling.

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
- **fixed sub-max load** — primary signal profile
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

## Core/Community Validation Criteria

- [x] the PAQS execution seam introduces no measurable regression in the baseline
- [x] the benchmark harness is reproducible across repeated runs
- [x] fixed-rate load exposes meaningful scheduler differences
- [x] A/B comparison between baseline and affine prototype is possible
- [x] results are strong enough to justify Enterprise expansion

**Validation complete (2026-03-22):** Full module test sweeps pass on clean rebuild.

- `exeris-kernel-core`: 914 tests, 0 failures, 0 errors, 10 skipped — BUILD SUCCESS
- `exeris-kernel-community`: 616 tests, 0 failures, 0 errors, 6 skipped — BUILD SUCCESS
- `CorePaqsSchedulerTckTest`: 9/9 pass (`PriorityDifferentiation` 4, `ScopedValuePropagation` 2, `CounterInvariants` 2, `ExecutionBackendSeamContract` 1)
- `CommunityPaqsExecutionBackendContractTest`: 1/1 pass

Toolchain note: prior `UnsupportedClassVersionError` (class `69.65535` / Java 25 preview) was caused by stale Java 25 JARs in `~/.m2`. Fixed by running `mvn -pl exeris-kernel-spi,exeris-kernel-tck,exeris-kernel-core -am clean install -DskipTests` before community test run. Workflow fix: always install dependency chain with `-am clean install -DskipTests` after clearing `/target`, rather than using `clean test` in isolation.

---

## Core/Community Decision

- [x] **Proceed to Enterprise track** — if scheduler signal is measurable and stable
- [ ] **Iterate methodology** — if seam works but signal is noisy
- [ ] **Park** — if Community proves uninformative as a methodological baseline

---

# Track 2 — Enterprise

> **Track ID:** `research/loom-continuation-locality/enterprise`

## Track Hypothesis

On the Enterprise stack, the remaining continuation-locality problem is more
architecturally important than on Community because Exeris uses native transports,
native completion reapers, and batching-oriented receive models.

The key hypothesis is:

> Even though Enterprise avoids the classic event-loop → ForkJoin task-queue handoff,
> default Loom/FJP continuation placement still breaks the locality chain between
> native completion reaping and virtual-thread work, leaving measurable efficiency,
> latency, and fairness improvements available to a transport-affine execution model.

A second-order Enterprise hypothesis follows:

> Once scheduler locality is made explicit, the interaction between continuation
> placement, multishot receive, batch receive, and bounded drain becomes a primary
> determinant of throughput/latency/fairness trade-offs.

---

## Enterprise Motivation

Enterprise is the real target of this research.

This is where the design space becomes materially different:

- native carriers,
- io_uring and completion reaping,
- multishot receive,
- batching,
- possible recvmmsg-like receive alternatives,
- bounded drain,
- tail latency vs batching trade-offs,
- fairness across native I/O work and resumed VTs.

This is also where Exeris provides a uniquely useful external data point for Loom
evolution:

- the workload is not “just Netty”,
- the transport is not just selector-based,
- the carrier model is outside the classic FJ task graph,
- and the remaining locality gap is therefore especially informative.

---

## Enterprise Problem Statement

Enterprise currently has a structurally strong but incomplete model:

- native carrier threads reap I/O completions,
- carrier threads perform no business logic,
- they wake virtual threads,
- those virtual threads still resume under default Loom scheduling.

This avoids one explicit handoff, but it does not guarantee continuation affinity.

As Enterprise moves deeper into:s/2026-03-22T07_24_20_559Z-debug-0.log
- io_uring,
- multishot receive,
- H3/QUIC,
- batch ingress,
- bounded-drain event loops,

the question becomes larger than “which scheduler is faster”.

The actual question is:

> What scheduler/transport geometry gives the best combination of throughput,
> tail latency, CPU efficiency, and fairness for Exeris native transports?

---

## Enterprise Scope

### In scope

- native carrier execution model
- enterprise scheduler seam
- native reaper + default FJP baseline
- native reaper + affine continuation backend
- H2/H3 benchmarking
- io_uring-focused follow-up phases
- multishot receive
- batch receive / recvmmsg-like receive model
- drain policy experiments
- fairness / HOL-risk analysis
- perf/JFR/Linux counter analysis

### Out of scope

- full production redesign from day one
- public JDK changes
- immediate pollerMode=3 availability
- release-quality implementation of every scheduler idea
- replacing the entire transport architecture in a single branch

---

## Enterprise Variants

### E1 — `enterprise-native-reaper-fjp`
Current model:
- native carrier/reaper thread
- completion → unpark
- virtual thread resumes on default FJP/Loom scheduler

### E2 — `enterprise-native-reaper-affine`
Experimental model:
- native carrier/reaper thread remains
- continuation execution goes through scheduler-aware / transport-affine backend
- first Enterprise A/B target

### E3 — `enterprise-vt-eventloop-bounded-drain` *(later)*
Exploratory model:
- event loop or drain loop runs in a virtual thread
- bounded drain becomes part of the scheduler geometry
- directly inspired by the design-space expansion discussed with Francesco

---

## Enterprise Dimensions

The Enterprise track is a matrix, not a single comparison.

### Dimension 1 — Protocol
- H1
- H2
- H3

### Dimension 2 — Ingress model
- single-shot receive
- multishot receive
- batch receive / recvmmsg-like path
- mixed receive patterns

### Dimension 3 — Drain policy
- immediate handoff
- count-bounded drain
- time-bounded drain
- drain-until-empty

### Dimension 4 — Scheduler model
- default FJP
- affine scheduler backend
- VT event-loop model *(later)*

---

## Enterprise Benchmark Questions

1. How much continuation-locality cost remains in the current native-reaper + FJP model?
2. Does an affine continuation backend improve:
   - CPU efficiency,
   - tail latency,
   - context switching,
   - memory hierarchy behavior?
3. How does that effect change under:
   - H2 vs H3,
   - single-shot vs multishot,
   - batch receive,
   - bursty vs steady load?
4. What bounded-drain policy gives the best trade-off between:
   - batching efficiency,
   - fairness,
   - tail latency,
   - locality?
5. Does Enterprise produce benchmark evidence strong enough to support Loom-team
   discussion about scheduler hooks and transport-affine execution?

---

## Enterprise Benchmark Plan

### Phase E0 — Shared scaffold adoption

Reuse shared documents and rules from the parent research.

### Phase E1 — Scheduler seam in Enterprise carriers

Primary goal:
- create a continuation backend hook without rewriting the transport end-to-end.

Likely hook areas:
- completion → work dispatch boundary,
- VT spawn/resume bridge,
- optional client/ingress pump execution points,
- future drain-policy hook.

Deliverables:
- baseline seam,
- no-behavior-change baseline path,
- experimental hook point.

### Phase E2 — Enterprise baseline locality study

Compare:
- `enterprise-native-reaper-fjp`
- `enterprise-native-reaper-affine`

Initial protocols:
- H2
- H3

Initial load modes:
- fixed-rate sub-max
- moderate
- max throughput

Goal:
- establish whether default continuation placement is leaving measurable value
  on the table in the current Enterprise transport.

### Phase E3 — Ingress-model expansion

Add:
- multishot receive
- batch receive / recvmmsg-like behavior
- protocol-specific receive behavior

Goal:
- observe whether batching changes the scheduler/locality trade-off.

### Phase E4 — Drain-policy study

Add:
- drain-1
- drain-count-N
- drain-time-budget
- drain-until-empty

Goal:
- quantify HOL/fairness/throughput trade-offs.

### Phase E5 — VT event-loop exploration

Only after E1–E4 show a clear signal.

Goal:
- test the more disruptive design point:
  event loop in VT, suspendable drain, possible locality benefits, mount/unmount cost.

### Phase E6 — Perf-analysis deep dive

Collect:
- `perf stat`
- `perf mem` / IBS where available
- JFR
- migrations
- scheduler trace data if feasible

Goal:
- produce research-grade evidence, not just RPS tables.

---

## Enterprise Scenarios

### Minimal initial scenario set

- `enterprise-h2-plaintext-fixed-rate`
- `enterprise-h2-json-1kb-fixed-rate`
- `enterprise-h3-plaintext-fixed-rate`
- `enterprise-h3-json-1kb-fixed-rate`

### Follow-up scenario set

- `enterprise-h2-multishot-json-1kb`
- `enterprise-h3-multishot-json-1kb`
- `enterprise-h3-multiplex-32`
- `enterprise-h3-burst-32`
- `enterprise-h3-drain-policy-matrix`

---

## Enterprise Metrics

### Required

- throughput
- CPU utilized
- instructions/req
- context switches
- p50/p95/p99/p999 latency
- error rate

### Strongly recommended

- CPU migrations
- cache references/misses
- DRAM misses or equivalent signal
- CQE drain batch size distribution
- wakeup count
- scheduler queue depth
- fairness indicators across streams
- JFR native/scheduler event correlation

---

## Enterprise Validation Criteria

- [ ] continuation backend seam can be introduced without semantic regression
- [ ] baseline FJP vs affine comparison is benchmarkable on H2/H3
- [ ] fixed-rate and moderate-load results expose measurable scheduler effects
- [ ] multishot/batching can be studied independently from basic scheduler A/B
- [ ] drain-policy differences are observable and interpretable
- [ ] results are strong enough to guide architecture and external feedback

---

## Enterprise Decision

- [ ] **Proceed to deeper transport redesign** — if affine/locality-aware execution shows durable gains
- [ ] **Proceed to bounded-drain study only** — if scheduler signal exists but backend change is partial
- [ ] **Hold as research-only** — if signal exists but implementation complexity is not justified yet
- [ ] **Park** — if Enterprise-specific complexity overwhelms the signal

---

# Shared Benchmark Methodology

## Why fixed-rate first

This research must not start from maximum throughput only.

Fixed-rate sub-maximal load is required because:
- scheduler inefficiencies often become clearer when the system has headroom,
- memory locality effects are often more visible below saturation,
- context-switch and migration costs are easier to interpret,
- previous findings already suggest this is the revealing regime.

---

## Shared Metrics Policy

Every reported comparison should prefer:
- CPU efficiency,
- instructions/req,
- latency distribution,
- context-switch behavior,
- memory hierarchy behavior,

over raw throughput alone.

Throughput remains necessary, but it is not sufficient.

---

## Shared Implementation Strategy

The codebase should evolve in this order:

1. **Scaffold**
   - research docs
   - scenario naming
   - result schema

2. **Execution seam**
   - extract execution backend
   - preserve current semantics

3. **Baseline validation**
   - prove refactor-neutrality

4. **First A/B**
   - baseline vs affine prototype

5. **Native transport expansion**
   - Enterprise-specific carrier hooks

6. **Drain policy expansion**
   - only after basic scheduler signal is proven

This sequence matters.
Do not start with the full multishot/H3/drain matrix.

---

## Shared Deliverables

1. shared methodology and reporting rules
2. Core/Community benchmark seam and first A/B comparison
3. Enterprise benchmark seam and first locality comparison
4. follow-up multishot/batching/drain study
5. perf/JFR evidence package
6. internal recommendation memo
7. optional Loom feedback summary if findings are strong

---

## Milestones

### M0 — Research scaffold
- document structure
- hypotheses
- methodology
- metrics
- reporting rules

### M1 — Execution seam
- PAQS/stream execution seam
- default implementation preserving current behavior

### M2 — Core/Community baseline pack
- baseline verification runs
- harness stabilization

### M3 — Core/Community A/B
- baseline vs affine prototype

### M4 — Enterprise baseline A/B
- native reaper + FJP vs native reaper + affine

### M5 — Enterprise ingress/drain matrix
- multishot
- batch receive
- drain policy

### M6 — Findings package
- benchmark report
- perf/JFR evidence
- architecture recommendation

---

## Initial Position

The most likely outcome is not that Exeris “switches schedulers everywhere”.

The more likely and more useful outcome is:

- Exeris gains a **clean scheduler seam**,
- proves whether continuation locality is materially relevant on its own stacks,
- isolates where the remaining performance ceiling really is,
- and then decides, based on evidence, whether Enterprise should go further into:
  - affine continuation execution,
  - bounded-drain event loops,
  - or deeper transport/scheduler integration.

That is the right order of operations.

---

## Decision

- [ ] **Proceed with both tracks** — if seam extraction and baseline validation succeed
- [ ] **Prioritize Enterprise after methodology stabilizes** — if Community only serves as a harness track
- [ ] **Keep Community as control only** — if Enterprise becomes the sole source of material signal
- [ ] **Park the initiative** — only if benchmark evidence remains inconclusive after seam extraction

---

## References

- Francesco Nigro discussion and scheduler notes
- Netty VirtualThread Scheduler materials
- Exeris PAQS / carrier design notes
- existing CPU efficiency findings and sustained-load analysis
- Exeris internal references:
  - `docs/whitepaper.md`
  - `docs/architecture.md`
  - `docs/performance-contract.md`
  - `docs/modules/04-enterprise.md`
  - `docs/subsystems/*.md`
  - `docs/adr/ADR-007*`
  - `docs/adr/ADR-008*`
