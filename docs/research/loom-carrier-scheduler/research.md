---
title: "Research: Loom Carrier Scheduler"
type: research
visibility: public
owning-repo: exeris-kernel
status: draft
last-verified: 2026-10-02
---

# Research: Loom Carrier Scheduler

> **Branch:** `research/loom-carrier-scheduler`
> **Author:** @arkstack-dev
> **Started:** 2026-10-02
> **Milestone:** unbounded — findings feed ADR-051's locality-affine backend question and a
> loom-dev experience report
> **Status:** `active`

---

## Hypothesis

The hypotheses are named `HYP-*` rather than numbered, because `H1` / `H2` / `H3` name the HTTP
protocol versions everywhere else in Exeris. This track measures **HTTP/1.1 cleartext only**.

On the Community transport (FFM POSIX sockets, platform `Selector` reactors that wake worker
virtual threads with `LockSupport.unpark`), replacing the stock `ForkJoinPool` scheduler with a
custom `VirtualThreadScheduler` that keeps one MPSC queue per carrier and pins each carrier to one
CPU bounds the open-loop tail:

> **HYP-TAIL (scheduling).** At 50–95 % of the stock scheduler's saturation rate, with carriers, JVM
> auxiliary threads and the load generator on disjoint CPUs, the pinned custom scheduler (arm `D`)
> has a lower worst-trial p99 than the isolated stock scheduler (arm `A_iso`) at every rate, and
> the difference is explained by carrier run-queue wait: floating carriers stack on one CPU, pinned
> carriers cannot.
>
> *Falsified if* `D`'s worst-trial p99 is not below `A_iso`'s at three or more of the five rates
> over n ≥ 5 fresh JVMs per cell, or if `perf sched` does not show floating carriers sharing a CPU
> in the trials where their tail breaks down.

> **HYP-CACHE (cache locality).** When the state of concurrently parked requests exceeds the 32 MiB L3,
> resuming a virtual thread on the carrier that last ran it costs fewer L2/L3 misses and fewer
> cycles per request than resuming it on any carrier.
>
> *Falsified if* misses per request and cycles per request between `D` and `A_iso` do not differ
> outside their run-to-run spread at in-flight state ≥ 2× L3.

> **HYP-CPU (cost).** The custom scheduler itself costs no more carrier CPU per request than `A_iso`;
> any CPU premium measured through the transport comes from the transport integration, not from
> the carrier loop.
>
> *Falsified if* with the transport in the loop `D` spends more than 10 % more CPU per request
> than `A_iso` at 50 % load and a profile of the carrier threads attributes the excess to
> `ExerisCarrierThread` / `ExerisCarrierScheduler` frames.

HYP-TAIL and HYP-CPU are independent of HYP-CACHE. A track that confirms HYP-TAIL and refutes
HYP-CACHE is a scheduling result,
not a locality result, and is reported as one.

---

## Motivation

- **ADR-051** ported the PAQS execution seam (`StreamExecutionBackend`) to the product line as an
  agnostic seam and kept every locality-affine backend off it. The question it left open is
  whether such a backend pays on any Exeris transport.
- **`research/loom-continuation-locality` (v0.6) concluded NO_GO for its configuration only.** Its
  affine arm could not pin carriers: it relied on a custom-scheduler hook the stock JDK does not
  have. This track is the test it could not run, now that the Loom `fibers` branch exposes
  `Thread.VirtualThreadScheduler` (`jdk.virtualThreadScheduler.implClass`).
  The transport also differs: the v0.6 Community track ran on NIO (`SocketChannel` reads and
  writes), while the Community transport now performs socket I/O through FFM syscalls on raw
  POSIX descriptors (`NativeTcpSocketBackend`), with `Selector` reactors used only for readiness.
  Results from the two tracks are not comparable, and neither speaks for the Enterprise
  `io_uring` transport.
- **External work.** Francesco Nigro's Netty virtual-thread scheduler measurements
  (<https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md>) cover
  an event-loop transport. A transport whose readiness detection and continuation execution live
  on different threads is a different data point, and the Loom maintainers have asked for
  experience reports against the SPI.
- **Pilot.** A pilot on 2026-09-27 (archived as non-evidence in `exeris-benchmarks`,
  `results/history/loom-carrier-scheduler-pilot-2026-09/`) showed 12–25 % carrier run-queue wait
  for floating carriers and 0.3–0.5 % for pinned ones, and tail breakdowns in every arm except
  `D`. It could not identify the kernel code it measured, so it motivates HYP-TAIL without supporting
  it.

---

## Methodology

### What will be measured

| Metric | Tool | Arms | Used for |
|:-------|:-----|:-----|:---------|
| Saturation throughput | `wrk`, closed loop | A, A_iso, C_iso, D | Rate ladder only — never a headline |
| p50 / p90 / p99 / p99.9, per trial | `wrk2`, open loop | A, A_iso, C_iso, D | HYP-TAIL |
| Achieved rate vs target, per trial | `wrk2` | all | Trial validity gate |
| Carrier / reactor `%usr`, `%sys`, `%wait`; cswch | `pidstat -u -w -t` | all | HYP-TAIL, HYP-CPU |
| Carrier CPU placement over time | `perf sched record` / `timehist` | A_iso, C_iso, D | HYP-TAIL mechanism |
| Thread → CPU mask, per trial | `/proc/<pid>/task/*/status` | all | Isolation gate |
| cycles, instructions, L2 / L3 misses per request | `perf stat` (Zen 3 `amd_l3`) | A_iso, D | HYP-CACHE |
| CPU-seconds per request | `pidstat` / `perf stat` | all | HYP-CPU |
| JFR (scheduler, park/unpark, GC) | JFR, single-phase events only | D, A_iso | Diagnosis, not headline |

### How it will be measured

- **Harness in `exeris-benchmarks`**, on branch `research/loom-carrier-scheduler`, rewritten
  rather than carried over from the pilot. Every trial records the kernel commit SHA, refuses to
  run from a dirty kernel tree, and records the JDK build string, the full JVM command line and
  the CPU topology. Kernel artifacts are installed from this branch's commit, not read from
  `target/classes`.
- **Hardware.** AMD Ryzen 5 5600 (Zen 3, 6 cores / 12 threads, single CCX, 32 MiB L3), Linux 7.0.
  CPU partitioning: reactors and JVM auxiliary threads on cores 0–1 (CPUs 0, 1, 6, 7); carriers
  on cores 2–3 (CPUs 2, 3; SMT siblings 8, 9 left idle); load generator on cores 4–5. The pilot's
  reactor run-queue wait came from outside the JVM, so the campaign runs with `isolcpus` /
  `nohz_full` on the carrier CPUs, or records why it could not.
- **JDK.** Loom EA, `28-testing`, branch `fibers`; the exact build string is recorded per trial.
- **Trials.** One fresh JVM per trial, n ≥ 5 per cell, arm order randomised per repetition. 10 s
  warmup, 30 s measurement. A trial whose achieved rate is below 99 % of target is marked invalid
  and re-run; it is never folded into a percentile.
- **Rates.** 50 / 70 / 85 / 90 / 95 % of `A_iso`'s median saturation rate, measured in the same
  campaign.
- **HYP-CACHE workload.** In-flight state is set by rate × delay × state per request (Little's law), not
  by connection count. The sweep targets 0.5×, 1×, 2× and 4× L3. Connection count is set to cover
  the in-flight count, with enough generator threads that the `wrk2` connection ramp (5 ms per
  connection per thread) finishes inside warmup.
- **Reporting.** Per-cell medians with all trial values, and worst-trial p99 for HYP-TAIL as a stated
  statistic with its n. The report follows `exeris-benchmarks`
  `docs/status-and-claim-eligibility.md`; nothing below `comparison_eligible` is quoted as a
  result.

### What will NOT be measured (scope boundary)

- **HTTP/2 and HTTP/3.** The workload is HTTP/1.1 cleartext; protocol is not an axis.
- **Enterprise transports** (`io_uring`, QUIC / HTTP/3). The `io_uring` + custom scheduler combination
  is a separate track with its own visibility rules.
- **The JDK poller.** The Community transport never starts `sun.nio.ch.Poller`, so
  `jdk.pollerMode` has no effect here and is not an axis.
- **Product-line placement.** Whether any of this code belongs on `preview` or `main` is decided
  after the Decision section, through ADR-051's successor, not on this branch.
- **Saturation throughput as a claim.** On a 6-core desktop the closed-loop spread between arms is
  inside run-to-run variance; saturation only sets the rate ladder.

---

## Implementation Notes

The branch is cut from `main`, not `preview`: the scheduler is measured on the GA-clean kernel,
so neither JEP 401 value classes nor `StructuredTaskScope` enter the arms. Two build deltas from
`main` exist only on this branch:

- `maven.compiler.release` is 28, without `--enable-preview`. The runtime is the Loom EA build,
  and patching `java.base` with the SPI stub requires `--release` to match the compiling JDK.
- PMD cannot parse release-28 class stubs, so `mvn install` / `verify` fails at the PMD step;
  build and test through the reactor (`mvn test -pl <module> -am`), and never install this
  branch's artifacts into the local Maven repository.

It carries only the scheduler part of the pilot implementation, so that a later diff shows every
change the measurements depend on:

- Core, `eu.exeris.kernel.core.transport.scheduler.locality`: `ExerisCarrierScheduler`,
  `ExerisCarrierGroup`, `ExerisCarrierThread`, `CarrierSchedulingContext`,
  `RoundRobinCarrierExecutionBackend` (the ADR-051 seam).
- `exeris-kernel-core/pom.xml` compiles a `java.lang.Thread` stub from `src/compile-stub` and
  patches `java.base` with it, so the SPI types resolve on a JDK without the `fibers` branch.
- Community: locality backend selection in `NativeTcpCarrier`, reactor and acceptor CPU affinity,
  reactor-count override.

Open items, in the order they block measurement:

1. **Where the pilot's carrier CPU went.** The carrier loop parks correctly when its queue is
   empty, measured without the transport: `probes/CarrierIdleProbe.java` (virtual threads started directly,
   all routed to 2 carriers with `exeris.locality.allVthreads=true`, each touching 8 KB and
   sleeping 20 ms at 3,000/s, 3 s phases, carrier CPU from `ThreadMXBean`, 2 fresh JVMs per arm,
   Loom EA b18-20260917) read 0.0 % carrier CPU idle before and after load and 4.5–4.7 % under
   load, against 12.5 % for 2 `ForkJoinPool` workers plus their delay scheduler. The pilot's
   60–70 % `%usr` per carrier on the same handler shape therefore comes from the transport path,
   not from the carrier loop. The poller hand-off methods (`tryParkPoller`, `canParkPoller`,
   `unparkPoller`, `registerPinnedPoller`) have no callers, so the shared `carrierState` they
   would race on cannot be the cause. Next: profile the carrier threads (async-profiler, `cpu`
   and `wall`) under `/delayed` with the HTTP/1.1 target. Blocks HYP-CPU, and HYP-TAIL's CPU column.
2. **Harness identity.** Kernel SHA, clean-tree check, JDK build string, per-trial isolation
   check, achieved-rate gate. Blocks every hypothesis.
3. **Tests that exercise the scheduler.** `ExerisCarrierSchedulerTest` runs on the stock JDK,
   where `newThread` returns `null` and tasks fall back to `Thread.ofVirtual()`, so the locality
   path never executes. A test profile on the Loom EA JDK has to assert carrier placement,
   `onStart` / `onContinue` routing and shutdown.
4. **Placement.** `sched_setaffinity` is an FFM downcall in Core, and Core bytecode references
   `Thread$VirtualThreadScheduler`, which exists in no GA JDK. Acceptable on a research branch;
   it is the first thing any promotion has to move.
5. **Configuration** is read with `System.getProperty` (`exeris.carrier.*`, `exeris.locality.*`,
   `exeris.transport.locality`, reactor affinity keys), bypassing the kernel configuration.
6. **Pilot changes left out.** The pilot also set `SO_REUSEPORT` unconditionally on server
   channels, moved the MinIO test image to `:latest`, and changed the ephemeral-port probe in 17
   test files. None is part of the scheduler and none is on this branch; the port probe can be
   proposed to the product line on its own.
7. **Timed parks.** The scheduler does not override `schedule(Runnable, long, TimeUnit)`, so timed
   parks are serviced by the JDK's `VirtualThread-unparker` platform thread and resume carriers
   with a cross-CPU unpark. Not a HYP-TAIL axis; a question for the loom-dev report.

---

## Results

*Not started. The pilot is archived as non-evidence and is not a result of this track.*

### Summary

### Data

| Config | Metric | Value | vs Baseline |
|:-------|:-------|:------|:------------|
| | | | |

Campaign data lives in `exeris-benchmarks` on the matching branch; this section cites it by
campaign directory and commit.

---

## Decision

*Open.*

- [ ] **Promote to ADR** — a successor to ADR-051 deciding whether a locality-affine backend has a
  product-line home
- [ ] **Promote to Feature**
- [ ] **Park**
- [ ] **Abandon**

**Rationale:**

**Follow-up issue:**

---

## References

- ADR-051 — PAQS execution seam
- `research/loom-continuation-locality` — v0.6 track, concluded NO_GO for its configuration
- Loom `fibers` branch: `java.lang.Thread.VirtualThreadScheduler`,
  `jdk.virtualThreadScheduler.implClass`
- F. Nigro, Netty virtual-thread scheduler performance notes:
  <https://github.com/franz1981/Netty-VirtualThread-Scheduler/blob/master/PERFORMANCE.md>
- `exeris-benchmarks`: `results/history/loom-carrier-scheduler-pilot-2026-09/README.md`
