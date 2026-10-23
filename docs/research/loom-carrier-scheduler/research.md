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

> **HYP-CAPACITY (capacity at a fixed tail).** Held to a p99 objective — 5 ms with the 1 ms
> backend, 80 ms with the 30 ms backend — the pinned custom scheduler (`D`) carries a higher rate
> than the isolated stock scheduler (`A_iso`).
>
> *Falsified*, per regime, if `D`'s capacity is not above `A_iso`'s on a ladder of 50 % and
> 70–100 % of `A_iso`'s saturation in steps of 5 %, 5 fresh JVMs per cell. A rate passes when at
> least 4 of its trials are valid, every valid trial sustained the rate, and their median p99 is
> inside the objective. An arm's capacity — the primary result — is the highest rate up to which
> every rate passes; the highest passing rate is reported beside it as supplementary.

The objectives were fixed with the first campaigns' results known, so each is placed where no
known median decides it. 5 ms is about twice the p99 every arm reaches at 50–70 % with the 1 ms
backend, and far from every median of that regime (2.5 ms at 70 %, 10.8 ms and above at 85 %).
With the 30 ms backend the objective is 80 ms rather than twice the 32.5 ms a request costs with
nothing queued (65 ms), because 65 ms lies on `A_iso`'s known median at 70 % (65.8 ms) and would
leave that rung to chance; the nearest known medians to 80 ms are 65.8 and 96.3 ms. The move does
not favour `D`: its known median at 70 % (96.3 ms) fails either objective. The 50 % rung anchors
the ladder — capacity counts only rates below which nothing failed, so the lowest rung has to be
one every arm passed in the first campaigns (2.6–2.7 ms and 39–44 ms).

The capacity ladders add arm `B_pin`, the stock `ForkJoinPool` with each worker pinned to one CPU:
the cell of the scheduling-algorithm × pinning table the first campaigns did not have. It carries
no hypothesis of its own. Where its capacity lands attributes `D`'s: at `D`'s, pinning carries
it; at `A_iso`'s, the per-carrier queues do; in between, both.

`B_pin` differs from `A_iso` in two ways at once: the binding, and being the stock scheduler
reproduced through the SPI. Arm `B_float` — the same class with the binding off — separates them.
It runs at 85 % only, 5 fresh JVMs per regime, beside the ladder. The reproduction is accepted when
`B_float`'s p99 does not separate from `A_iso`'s (exact two-sided Mann–Whitney p ≥ 0.05) and its
median p99 lies inside the range of `A_iso`'s trials. If it is not accepted, `B_pin` is read against
`B_float` instead of `A_iso`: both go through the SPI, so the binding is still the one difference.
With 5 trials per arm the check detects only large differences; it guards against a broken
reproduction, not a subtle one.

The ladders also re-measure `D` at 70 % with the 30 ms backend, where the backend stalled in 9 of
10 trials of the first campaign; whether that recurs is a result either way.

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

- **Harness in `exeris-benchmarks`**, branch `research/loom-carrier-scheduler`,
  `scripts/loom-carrier-scheduler/` and `scenarios/loom-carrier-scheduler/`. The kernel is
  exported from one commit with `git archive` and built into the harness's own directory, never
  read from a working tree and never installed into the local Maven repository. Every trial
  records the kernel commit, the JDK build string, the full JVM command lines and the CPU
  topology, and is gated on: FFM socket path active (`active=posix-hybrid, ffmArmed=true`),
  achieved rate ≥ 99 % of target, no socket errors or non-2xx, carrier and non-carrier thread
  placement at the end of the window, and no profiler or tracer attached.
- **Workload.** Modelled on Nigro's CPU-bound configuration: the handler's virtual thread makes
  one blocking call to a mock backend on its own CPUs (1 ms think time, 100 connections,
  2 carriers), driven open-loop below saturation. The backend client is an axis, because it
  decides which mechanism wakes the virtual thread: `jdk` (pooled blocking `java.net.Socket`,
  woken by the JDK poller) or `kernel` (`KernelWebClient`, woken by the transport's reactors).
  `jdk.pollerMode` is held equal across arms within a campaign and recorded.
- **Load vehicle.** Requests are HTTP/1.1 (`/backend`) because `wrk` / `wrk2`
  drive it with open-loop pacing and coordinated-omission correction. The hypotheses concern
  continuation scheduling below the protocol layer; the protocol is not a factor.
- **Hardware.** AMD Ryzen 5 5600 (Zen 3, 6 cores / 12 threads, single CCX, 32 MiB L3), Linux 7.0.
  CPU partitioning: server JVM auxiliary threads and reactors on core 0 (CPUs 0, 6); mock backend
  on core 1 (CPUs 1, 7); carriers on cores 2–3 (CPUs 2, 3; SMT siblings 8, 9 left idle); load
  generator on cores 4–5 (CPUs 4, 5, 10, 11). Each trial records the kernel's isolated-CPU set;
  the host isolates none, so processes outside the benchmark can still run on every CPU.
- **JDK.** Loom EA, `28-testing`, branch `fibers`; the exact build string is recorded per trial.
- **Trials.** One fresh JVM per trial, n ≥ 5 per cell, arm order randomised per repetition. 10 s
  warmup, 30 s measurement. A trial that fails a measurement gate is invalid,
  listed in the campaign summary and never used. A valid trial whose arm could not carry the target
  rate is a result about that arm: it is counted per cell ("sustained k of n") and kept out of the
  percentiles, which describe only runs that kept up.
- **Rates.** 50 / 70 / 85 % of `A_iso`'s median saturation rate, measured in the same campaign;
  90 / 95 % are added once the lower rungs are stable.
- **HYP-CACHE workload.** In-flight state is set by rate × delay × state per request (Little's law), not
  by connection count. The sweep targets 0.5×, 1×, 2× and 4× L3. Connection count is set to cover
  the in-flight count, with enough generator threads that the `wrk2` connection ramp (5 ms per
  connection per thread) finishes inside warmup.
- **Reporting.** Per-cell medians with all trial values, and worst-trial p99 for HYP-TAIL as a stated
  statistic with its n. The report follows `exeris-benchmarks`
  `docs/status-and-claim-eligibility.md`; nothing below `comparison_eligible` is quoted as a
  result.

### What will NOT be measured (scope boundary)

- **Enterprise transports** (`io_uring`). The `io_uring` + custom scheduler combination
  is a separate track with its own visibility rules.
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

Arm `B_pin` is not kernel code: it is `PinnedForkJoinScheduler` in the `exeris-benchmarks`
target, so the capacity ladders measure the same kernel commit as the first campaigns. It builds
its pool with the built-in scheduler's arguments and properties, its workers are the JDK's
`CarrierThread`s (so the carrier-only paths — lazy submit after a park, external submit on yield,
compensation around blocking calls — apply to them), and it takes the two submit paths the JDK
reserves for the built-in scheduler by identity under the same conditions. Each worker binds
itself to a free CPU when it starts and releases it when it ends, because the pool retires idle
workers after 30 s and starts new ones. With `loom.bench.pinned.bind=false` it binds nothing (arm
`B_float`).

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
   would race on cannot be the cause. Resolved for this track: with the benchmark target on a
   shielded host the premium does not appear (Results, HYP-CPU).
2. **Harness identity.** Done in `exeris-benchmarks`: kernel SHA, clean-tree check, JDK build
   string, per-trial isolation check, achieved-rate outcome.
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

Backend `jdk` only, two regimes, kernel `bc26f9a0e8fc`, Loom EA b18-20260917, on a
cpuset-shielded host: 10 fresh JVMs per (arm, rate) for HYP-TAIL and HYP-CPU, 5 per (arm, rung)
on the capacity ladders. Full reports: `exeris-benchmarks`,
`results/reports/2026-10-14-loom-carrier-scheduler-pinned-carriers-and-the-open-loop-tail.md` and
`results/reports/2026-10-16-loom-carrier-scheduler-capacity-at-a-fixed-tail.md`.
The pilot is archived as non-evidence and is not a result of this track.

### Summary

- **HYP-TAIL — supported near capacity only; mechanism confirmed for the stock scheduler.** At
  85 % (both regimes) and 90 % (CPU-bound) `D`'s p99 separates completely from `A_iso` and `C_iso`
  (exact Mann–Whitney p ≤ 2.2 × 10⁻⁵). At 50 % no arm separates. At 70 % with the 30 ms backend
  `D` is the worst arm, bimodal across JVMs, and the stall is observed on the mock backend's side (its CPU
  hits its ceiling near second 53 in 9 of 10 `D` trials, never under the floating arms; pinning, the
  read sub-poller, GC and connection churn ruled out). The capacity ladders reproduce it under the
  stock pool pinned as well (`B_pin`, 5 of 5 trials), so it follows the pinning, not the custom
  scheduler; its cause is open. The
  falsification criterion (not below `A_iso` at three or more rates) is not met; "at every rate"
  is not met either. Diagnostic scheduler traces show `A_iso`'s workers stacked on one CPU in its
  slow windows and no stacking behind `C_iso`'s tail.
- **HYP-CPU — not falsified.** At 50 % `D` uses 1.21 CPUs against `A_iso`'s 1.22 (1 ms backend)
  and 1.25 against 1.32 (30 ms backend). The pilot's CPU premium does not reproduce.
- **HYP-CAPACITY — not falsified with the 1 ms backend, falsified with the 30 ms backend.**
  Capacity at p99 ≤ 5 ms: `D` and `B_pin` 95 % of `A_iso`'s saturation, `A_iso` and `C_iso` 50 %
  (70 % without connection tracking on loopback; the campaign ran with the Docker daemon's
  conntrack and NAT rules loaded, which add about 7 % netfilter samples to every arm's carriers);
  `B_pin` lands at `D`'s, so pinning carries the gain. Capacity at p99 ≤ 80 ms: `C_iso` 80 %,
  `A_iso` 70 %, `D` and `B_pin` 50 %, both pinned arms failing the 70 % rung on the backend stall;
  highest passing rung `B_pin` 95 %, `D` 85 %. Under pinning the custom scheduler costs tail and
  saturation throughput with the 30 ms backend and changes nothing with the 1 ms backend: per-carrier queues without
  stealing, started threads kept on the starting carrier, and carriers that park as soon as their queue is empty.
  Its plain-`Thread` carriers lack the JDK's carrier-only paths, but those are not in play at
  `maxPoolSize = 2` (no `B_pin` JVM started a third worker). The
  reproduction check (`B_float` against `A_iso` at 85 %) is accepted with the 1 ms backend and not
  with the 30 ms one (range clause, by 1.54 ms), where `B_pin` is read against `B_float`: 40.7
  against 199 ms p99.
- **HYP-CACHE — not run.** In-flight state stays far inside L3 in both regimes.

### Data

p99, median of 10 sustained trials (9 where one did not sustain the rate), ms.

| Regime | Rate | `A_iso` | `C_iso` | `D` |
|:-------|:-----|--:|--:|--:|
| 1 ms backend, 100 connections | 50 % | 2.63 | 2.68 | 2.67 |
| | 70 % | 2.46 | 2.54 | 2.47 |
| | 85 % | 11.89 | 10.79 | **2.58** |
| | 90 % | 21.39 | 13.46 | **2.58** |
| 30 ms backend, 5,000 connections | 50 % | 41.23 | 43.57 | 39.49 |
| | 70 % | 65.81 | 52.91 | 96.32 |
| | 85 % | 181.25 | 240.13 | **52.61** |

Campaigns, in `exeris-benchmarks` `results/raw/loom-carrier-scheduler/`:
`20261009T040618Z-jdk-kbc26f9a0e8fc` (harness `84685a1c`, 129 of 129 trials valid) and
`20261013T191957Z-jdk-kbc26f9a0e8fc` (harness `e33e9595`, 99 of 99 valid); capacity ladders
`20261016T002844Z-jdk-kbc26f9a0e8fc` and `20261016T034438Z-jdk-kbc26f9a0e8fc` (harness
`8b915264`, 177 of 177 valid each).

Capacity (highest rung below which every rung passed) and, in brackets, highest passing rung, as a
fraction of `A_iso`'s saturation in the same campaign:

| Regime, objective | `A_iso` | `B_pin` | `C_iso` | `D` |
|:------------------|--:|--:|--:|--:|
| 1 ms backend, p99 ≤ 5 ms | 50 % (50 %) | **95 %** (95 %) | 50 % (50 %) | **95 %** (95 %) |
| 30 ms backend, p99 ≤ 80 ms | 70 % (70 %) | 50 % (**95 %**) | **80 %** (80 %) | 50 % (85 %) |

Diagnostic runs (exploratory, recorders attached): `exeris-benchmarks`
`results/raw/loom-carrier-scheduler-diag/20261015T041646Z-h71112fae47da` and
`20261015T093021Z-h596d216ecca5`; the report's §5.5 summarises them.

Next, in order: why the backend stalls under pinned carriers at I/O-bound 70 % — a 60 s warmup
(does the jump follow the mock's age or the measurement phase), a JIT, deoptimisation and pinning
record of the mock with its `ForkJoinPool` size, and the arrival pattern at the mock under `B_pin`
against `A_iso` (same algorithm, only the binding differs); what keeps `A_iso`'s carriers 6 points
of sys above a host without iptables once loopback is exempted from tracking (the floating arms'
CPU-bound drift between the campaigns is connection tracking on loopback: exempting it in one boot
takes `A_iso` at 70 % from 8.59 to 2.70 ms and leaves `D` at 2.5 ms); what drives `C_iso`'s tail at 85 %; the `kernel` backend; the HYP-CACHE sweep.

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
