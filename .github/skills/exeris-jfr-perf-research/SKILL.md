---
# DO NOT EDIT — generated from .agents/skills/exeris-jfr-perf-research/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-jfr-perf-research
description: 'Disciplined JMH/JFR performance-research loop for Exeris Kernel — guards against single-run flukes, driver confounds, and wrong conclusions from hot-method noise. Use when investigating a throughput/latency/allocation regression or claim, reading a JFR recording, or running a benchmark on a research/<slug> branch.'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-jfr-perf-research/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris JFR Perf Research

## Purpose
Make perf conclusions reproducible and falsifiable. Past investigations burned time on a single broken run and on driver confounds; this skill encodes the discipline that prevents both. Pairs with the Research doc shape (`RESEARCH-TEMPLATE.md`, branch-scoped, no central registry).

## When to Use
- Investigating a throughput/latency/allocation/CPU regression or a perf claim.
- Reading or summarizing a JFR recording (`jfr summary`, hot methods, alloc, GC).
- Running JMH or HTTP load (wrk/wrk2/h2load/k6) to support a decision.

## Where work belongs
- Perf claims, JMH harnesses, HTTP load → `exeris-benchmarks` (or `exeris-benchmarks-enterprise`). **Never** add merge gates there — benchmarks are environment-sensitive (CPU, driver, NUMA, JIT warmup) and would produce flaky gates; benchmarks are not a guard-test repo.
- The kernel change that the benchmark justifies → `exeris-kernel`, with a `research/<slug>` branch and a Research note.

## Method — non-negotiable discipline

1. **Repeat before concluding (2–3 runs minimum).**
   - A single run is not evidence. A re-run of the *same* config has flipped a "1522 rps catastrophe" back to ~3742 rps. If runs disagree by >~10–15%, treat the run as unstable and find out why before drawing any conclusion.
   - Report the spread (min/median/max), not a single number.

2. **Control the driver — rule out confounds.**
   - `wrk` vs `h2load` can differ materially on the same server (connect cost, HTTP/2 framing, keep-alive). Hold the driver fixed across compared runs, or run both and report both. Do not compare a `wrk` baseline to an `h2load` candidate.
   - Hold fixed: CPU pinning / `ActiveProcessorCount`, connection count, warmup, TLS vs plaintext, HTTP/1 vs h2 vs h2c.

3. **Read JFR for *where*, then verify the mechanism.**
   - Hot methods tell you where CPU goes (e.g. runLoop %, CLQ poll/offer, CHM.get, LoanedBuffer alloc → GC rate), not *why*. Confirm the causal mechanism with a targeted change, not by correlation alone.
   - Distinguish "more expensive" from "slower" — higher CPU per request is not automatically lower throughput. State the actual bottleneck.
   - Watch for absence as signal (e.g. OpenSSL absent from hot methods ⇒ deficit is reactor plumbing, not crypto).

4. **Calibrate against a known-good baseline.**
   - Compare to a healthy reference (e.g. plaintext core, or a prior commit), not just an absolute target. A regression is a delta vs a trusted point.
   - Beware constrained-config artifacts: a `pool=2` under `ActiveProcessorCount=1` can shed load via the v0.6.0 503 admission gate and *look* like a throughput collapse (it is 92% errors, not slowness). Check error rate, not just rps.

5. **JFR-event safety on the runtime side.**
   - If the change adds JFR instrumentation: never `begin() → blocking-op → commit()` an event on a virtual thread — a carrier-bound `EventWriter` straddling a VT mount/unmount has caused SIGSEGV (JfrStorage::flush) on JDK 26. Use single-phase `commit()`. (ConnectionAcquireEvent was the cautionary tale.)

6. **Environment caveat.**
   - JDK 26 has sporadic SIGSEGV in G1/PMD during Maven — a crashed run is not a perf result; just retry. Keep `hs_err_pid*.log` only to confirm it was a JVM crash, not your change.

## Output
1. Hypothesis (falsifiable) + config held fixed.
2. Runs table: driver, N repeats, min/median/max, error rate.
3. JFR finding: where (hot methods) → claimed mechanism → how it was verified.
4. Baseline delta + verdict (regression confirmed / fluke / confound).
5. Next action (kernel change, or more measurement).

## Non-Negotiable Rules
- No conclusion from a single run.
- No cross-driver comparison.
- No "slower" claim without separating CPU-per-req from throughput and checking error rate.
- No two-phase JFR event on a virtual thread.
