# Research: Loom Continuation Locality - Enterprise Track

> **Branch:** `research/loom-continuation-locality`
> **Author:** @arkstack-dev
> **Started:** 2026-03-20
> **Milestone:** v0.6 - findings feed scheduler seam design, benchmark harness, and bounded architecture guidance
> **Status:** `active`

Shared context, hypothesis background, methodology, deliverables, milestones, and initial position are in `RESEARCH-loom-continuation-locality.md`.

---

# Track 2 - Enterprise

> **Track ID:** `research/loom-continuation-locality/enterprise`

## Track Hypothesis

On the Enterprise stack, the remaining continuation-locality problem is more
architecturally important than on Community because Exeris uses native transports,
native completion reapers, and batching-oriented receive models.

The key hypothesis is:

> Even though Enterprise avoids the classic event-loop -> ForkJoin task-queue handoff,
> default Loom/FJP continuation placement still breaks the locality chain between
> native completion reaping and virtual-thread work, leaving measurable efficiency,
> latency, and fairness improvements available to a transport-affine execution model.

A second-order Enterprise hypothesis follows:

> Once scheduler locality is made explicit, the interaction between continuation
> placement, multishot receive, batch receive, and bounded drain becomes a primary
> determinant of throughput/latency/fairness trade-offs.

Gate note (2026-03-24): after Community near-parity, Enterprise proceeds as targeted validation of architecture-dependent effects, not as a pre-justified redesign track.

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

- the workload is not "just Netty",
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

As Enterprise moves deeper into:
- io_uring,
- multishot receive,
- H3/QUIC,
- batch ingress,
- bounded-drain event loops,

the question becomes larger than "which scheduler is faster".

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

### E1 - `enterprise-native-reaper-fjp`
Current model:
- native carrier/reaper thread
- completion -> unpark
- virtual thread resumes on default FJP/Loom scheduler

### E2 - `enterprise-native-reaper-affine`
Experimental model:
- native carrier/reaper thread remains
- continuation execution goes through scheduler-aware / transport-affine backend
- first Enterprise A/B target

### E3 - `enterprise-vt-eventloop-bounded-drain` *(later)*
Exploratory model:
- event loop or drain loop runs in a virtual thread
- bounded drain becomes part of the scheduler geometry
- directly inspired by the design-space expansion discussed with Francesco

---

## Enterprise Dimensions

The Enterprise track is a matrix, not a single comparison.

### Dimension 1 - Protocol
- H1
- H2
- H3

### Dimension 2 - Ingress model
- single-shot receive
- multishot receive
- batch receive / recvmmsg-like path
- mixed receive patterns

### Dimension 3 - Drain policy
- immediate handoff
- count-bounded drain
- time-bounded drain
- drain-until-empty

### Dimension 4 - Scheduler model
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

### Phase E0 - Shared scaffold adoption

Reuse shared documents and rules from the parent research.

### Phase E1 - Scheduler seam in Enterprise carriers

Primary goal:
- create a continuation backend hook without rewriting the transport end-to-end.

Likely hook areas:
- completion -> work dispatch boundary,
- VT spawn/resume bridge,
- optional client/ingress pump execution points,
- future drain-policy hook.

Deliverables:
- baseline seam,
- no-behavior-change baseline path,
- experimental hook point.

### Phase E2 - Enterprise baseline locality study

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
- establish either an operationally meaningful and reproducible locality signal,
   or confirm near-parity under Enterprise transport conditions.

### Phase E3 - Ingress-model expansion

Add:
- multishot receive
- batch receive / recvmmsg-like behavior
- protocol-specific receive behavior

Goal:
- observe whether batching changes the scheduler/locality trade-off.

### Phase E4 - Drain-policy study

Add:
- drain-1
- drain-count-N
- drain-time-budget
- drain-until-empty

Goal:
- quantify HOL/fairness/throughput trade-offs.

### Phase E5 - VT event-loop exploration

Only after E1-E4 show a clear signal.

Goal:
- test the more disruptive design point:
  event loop in VT, suspendable drain, possible locality benefits, mount/unmount cost.

### Phase E6 - Perf-analysis deep dive

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

- [ ] **Proceed to deeper transport redesign** - if affine/locality-aware execution shows durable gains
- [ ] **Proceed to bounded-drain study only** - if scheduler signal exists but backend change is partial
- [ ] **Hold as research-only** - if signal exists but implementation complexity is not justified yet
- [ ] **Park** - if Enterprise-specific complexity overwhelms the signal

---

## References

- Francesco Nigro discussion and scheduler notes
- Netty VirtualThread Scheduler materials
- Exeris PAQS / carrier design notes
- existing CPU efficiency findings and sustained-load analysis
