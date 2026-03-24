# Methodology - Loom Continuation Locality Benchmark

## Benchmark Type

Fixed-rate request-response throughput and CPU efficiency measurement on NativeTcpCarrier
with two execution backends (default VT and scheduler-aware alternative).

Implemented micro-harness includes:
- Preflight fail-fast validation of backend availability.
- Per-trial artifact manifest (JSON) with benchmark metadata.
- Optional JFR recording per trial (gated by `exeris.benchmark.jfr.enabled`, default true).
- Explicit concurrency matrix parameter (16, 32, 64 concurrent streams).
- Round-robin stream pool writes to exercise concurrent workload.
- perf-stat and CPU cycle counting remain external-runner responsibility (not in-process).

## Load Model

Sustained fixed-rate request-response loop.

- **Fixed Rate:** Target throughput set by target requests-per-second (RPS).
- **Load Generator:** Coordinated thread pool issuing timed requests on fixed cadence.
- **Transport:** NativeTcpCarrier (TCP, loopback).
- **Message Size:** Echo protocol (fixed request/response size, ~100 bytes each).

## Experiment Matrix: Backend Variant x Concurrency

- **Backend variants:** 2
  - C: baseline (default VT execution)
  - E: experimental (scheduler-aware continuation backend)

- **Concurrency levels:** 3 (JMH @Param matrix, 16, 32, 64 concurrent streams per trial)
  - Implemented via stream pool with round-robin indexing per write operation.
  - Each write samples next stream from pool deterministically.

- **Target RPS:** determined by max-throughput preflight; then fix at 80% of max for stable measurement.
- **Warm-up:** 5 seconds (JIT, JFR buffer, scheduler stabilization).
- **Measurement Window:** 30 seconds.
- **Cool-down:** 2 seconds.
- **Repetitions:** 5 independent runs per (variant, concurrency) pair.

Total runs: 2 variants x 3 levels x 5 reps = 30 benchmark runs per harness invocation.

## Observability Harness

**Artifact Manifest:**
- Per-trial JSON metadata stored at `target/benchmark-artifacts/trial-<timestamp>/manifest.json`.
- Includes: benchmarkName, backendMode/strictLocality flag, scenario, loadProfile, concurrency, timestamp, jfrEnabled, jfrFile path, perfStatMode, perfStatIntegrated.

**JFR Capture:**
- Optional, controlled by `exeris.benchmark.jfr.enabled` system property (default true).
- Enabled for full measurement window; starts at @Setup(Level.Trial), stops at @TearDown(Level.Trial).
- Target events: ThreadCPULoad, ThreadContextSwitch, VirtualThreadStart, VirtualThreadEnd, JDKContinuationFork, JdkThreadAllocationStatistics, GarbageCollection.
- File: one per trial with variant+concurrency+timestamp name, relative to artifact directory.

**Perf-stat Collection:**
- Option: no-multiplex (strict CPU cycle counting, no scaling).
- Counters: cycles, instructions, context-switches, cpu-migrations.
- Pin to isolated CPU (if available) or document affinity constraints.
- **External runner responsibility:** in-process perf-stat integration not implemented; external harness must invoke perf-stat and collect counters.

**Post-processing:**
- Compute allocation_rate and bytes/request from JFR, then compare E vs C.

**API Preflight Validation:**
- Fail-fast IllegalStateException if scheduler SPI or backend unavailable.
- Runs before setup of infrastructure.

## Harness Discipline

- Single JVM process per run (no cross-run interference).
- Fixed seed for request distribution (reproducibility).
- Keep GC events recording enabled for outlier filtering and allocation analysis.
- Log elapsed wall-clock time; derive rate from request count / elapsed.
- Per-trial artifact directory (manifest, JFR file) enables run-by-run traceability.
- Artifact base: `target/benchmark-artifacts` (configurable via `exeris.benchmark.artifacts.dir` system property).
- JFR file path captured in manifest for post-run analysis tooling.

## Risk Mitigations

- Test harness stability on baseline (C) first; green-light before moving to experimental (E).
- If CV > 3%, increase measurement window to 60s or run in isolated CI job.
- If latency regresses > 5%, capture a targeted JFR trace with full event verbosity.

## Current Scope Limitations (Decision Boundary)

- Current scope is Phase 1 microbench only.
- This scope answers the CPU-efficiency mechanism question only.
- This does not justify C5 architectural GO by itself.
- Phase 2 E2E, per handoff, is required before C5 GO.
- Until then, status is descriptive_partial (not C5 comparison-eligible).
