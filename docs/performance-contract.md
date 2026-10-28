---
title: The Performance Contract (Hyper-Density Spec)
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# The Performance Contract (Hyper-Density Spec)

This document defines the efficiency targets for Exeris Kernel. Targets are **strictly divided**
into the current development baseline (TRL-3) and the final Enterprise goals.

## 1. Reference Hardware Profiles

| Profile             | Hardware Context                          | Purpose                     |
|:--------------------|:------------------------------------------|:----------------------------|
| **L0 (Developer)**  | Ryzen 5600 (or equiv) / 1 vCPU / 4GB RAM | Daily PR validation         |
| **L1 (Enterprise)** | `perf-box-amd64` — EU dedicated bare metal, Hetzner AX-class (AMD x86-64, 16 HW threads, 64 GB RAM, NVMe; Falkenstein DE / Helsinki FI) | Official Tier certification |

---

## 2. Efficiency Targets (The "Speed" Layer)

### 2.1 Network Throughput per Core

Defined against a "Hello World" **1 KB payload** scenario; the scenario id and build tag are pinned per published run in `exeris-benchmarks`.

> **Note:** For 8 KB entity payloads, we expect ~50% of the targets below due to MTU network
> frame pressure.

| Stage               | Target (SLO)         | Breach Threshold | Context                              |
|:--------------------|:---------------------|:-----------------|:-------------------------------------|
| **Current (TRL-3)** | **> 1,500 RPS/vCPU** | < 1,000 RPS/vCPU | Standard TCP Sockets / Off-Heap      |
| **Enterprise**      | **> 8,500 RPS/vCPU** (target — not yet measured end-to-end; pending Enterprise bootstrap wiring) | < 6,000 RPS/vCPU | `io_uring` / Native Kernel Bypass    |

---

### 2.2 Latency & Allocation (Strict Zero-GC)

This is where Exeris implements the **"No Waste Compute"** philosophy.

| Metric (SLI)            | Target (SLO)    | Breach Threshold | Rationale                               |
|:------------------------|:----------------|:-----------------|:----------------------------------------|
| **Telemetry Overhead**  | > 10M ops/s     | < 5M ops/s       | Enterprise binary Glass-Box sink (not in this repository); the in-repository JMH `AbstractTelemetrySinkBenchmark` states ≥ 2M ops/s for the JFR sink and ≥ 5M ops/s for the Enterprise ring-buffer sink |
| **Kernel Overhead**     | < 500 µs        | > 1 ms           | Read to Virtual Thread Mount latency    |
| **GC Pause (P99)**      | < 1 ms          | > 5 ms           | ZGC Generational validation             |
| **Allocation Rate**     | **0 B / req**   | > 65 B / req     | Strict zero-allocation on hot path      |

Allocation is asserted per subsystem by the suites built on `AbstractSubsystemZeroAllocTck`: zero
`eu.exeris.*` allocations per iteration when a binding sets `supportsZeroGcHotPath()`, otherwise a
per-iteration budget the suite sets (default 5; `CryptoZeroAllocTck` sets 8, though its only binding in
this repository asserts zero on the `OffHeapTlsEngine` guard path). No merge gate holds the latency, pause
or throughput targets; the JMH benchmarks in this repository assert nothing, and none measures GC pause.

---

### 2.2.1 Execution-Tier Scope — HotSpot/C2 (the zero-allocation contract is profile-driven)

The **0 B/req** allocation target and the broader **"No Waste Compute"** guarantees are **pinned to the HotSpot C2 JIT**. They depend on peak-tier, profile-driven optimizations — most importantly **Escape Analysis scalarization** of carriers, and the **intrinsification / runtime optimization of Panama FFM downcall stubs and `MemorySegment` / `VarHandle` access**. These are C2 runtime properties, not language-level guarantees.

Under **GraalVM native-image (SubstrateVM)** the contract is **different, not broken**: AOT compilation without profile-guided optimization makes scalarization and FFM-path decisions more conservative, so the zero-allocation and per-core throughput SLOs above are **not asserted** under native-image. PGO (instrumented build → profile → optimized build) narrows the gap but is operationally heavy and out of scope for the default contract.

**Consequences for benchmarking and claims:**

- The targets in §2.1–§2.2 apply on **HotSpot/C2** (the *throughput tier*). Any benchmark asserting the zero-allocation or RPS-per-core claims **MUST** run on HotSpot/C2 and record the JIT tier. A native-image measurement of these specific claims is **out of contract** and must not be presented as a refutation of them.
- **native-image is the *edge/lightweight tier*** — fast startup without warmup, small footprint, small image — a separately-scoped performance profile, not a regression of this one. From 0.13 it is supported as an opt-in build under [ADR-103](adr/ADR-103-native-image-opt-in-build.md), with the kernel's metadata shipped in its jars; it is not supported on 0.12 and earlier artifacts. The HotSpot/C2 pin above is unchanged by it, and native-image startup and footprint figures are published as edge-tier measurements, not as SLOs.

Scoping the contract to the execution tier it was measured on **defends the claim**: an unscoped "zero-alloc" assertion benchmarked under native-image would read as false when it is merely measured against the wrong contract.

---

## 2.3 Admission Control: ThreadPark Starvation Prevention

### Latency & Fairness Guarantees

Exeris runtime enforces per-request latency and fairness bounds via SPI-level admission control in the Persistence subsystem:

| Metric | Target | Measurement | Context |
|--------|--------|-------------|----------|
| **p50 latency** | ≤5ms | Entity-read-by-id benchmark, steady-state | HTTP+JDBC steady load |
| **p95 latency** | ≤30ms | Tail latency under 50+ concurrent VT load | Percentile @ saturation |
| **p99 latency** | ≤100ms | Extreme tail; may include occasional 503 backpressure | Rare edge cases |
| **Fairness index** | ≥0.95 | Latency distribution symmetry (0.1268 unsuitable → ≥0.95 suitable) | Pre-fix was inversed |
| **ThreadPark events** | <5,000/120sec | Per-test baseline; <12.5% of observed rate without admission control | Context-switch waste metric |

### Mechanism: HTTP Backpressure Gate

When Persistence pool(s) reach saturation:

1. HTTP dispatcher calls `PersistenceEngine.canServiceRequest()`
2. If false (no capacity), HTTP responds **503 Service Unavailable** with `Retry-After: 1`; if the admission check itself throws, the 503 carries `Retry-After: 10` (`CommunityHttpRequestDispatcher.dispatch`)
3. Client retries after the advertised Retry-After delay

This prevents unchecked thread proliferation and pool queuing:
- **Before:** Threads park on exhausted pool → fairness inversion → cascading latency spike
- **After:** Early rejection at HTTP level → client-side retry backoff → fairness restored

### Tier Differences

**Community:** HikariCP-based gating (ADR-035), `CommunityPersistenceAdmissionController`
- Reads the pool's active, idle and pending-acquire counts from the Hikari pool MXBean
- Sheds only once pending acquires exceed an allowance of `ceil(maxPoolSize × queueDepthAllowanceRatio)`; the ratio defaults to `8.0` (`persistence.admission.queueDepthAllowanceRatio`), and `0` restores shedding on the first queued acquire
- Past the allowance it rejects when: `active / maxPoolSize >= 0.90`; or `active / maxPoolSize >= 0.85` with little headroom left or sustained fairness stress; or no idle connection remains (the ratios are defaults of `persistence.admission.*` keys read by `CommunityAdmissionConfig`)
- Below the allowance a request is admitted and waits on the connection-acquire timeout

**Enterprise:** Predictive native gating (design target; the Enterprise tier is not in this repository)
- May reject before absolute saturation using io_uring metric telemetry
- Adapts admission curve to workload pattern (exponential backoff)
- Latency target: <500µs per check
- Implementation: Native driver poll of SQE availability + queue depth heuristic

### Observability

JFR events (when enabled via profile):
- `AdmissionDecisionEvent` (`eu.exeris.kernel.persistence.AdmissionDecision`): admitted yes/no, queue depth, saturation, fairness ratio, queue-depth and queue-wait p95, decision reason; `PersistenceAdmissionStageEvent` carries the queue-enter, queue-wait and admission stages
- Standard `ThreadPark` events: significantly reduced (proof of starvation elimination)

### Compliance Proof

**Benchmark Run:** 2026-03-28T15:20:50Z, entity-read-by-id scenario, 50 concurrent virtual threads
- Pre-fix: p50 52.57ms, fairness 0.1268, ThreadPark 40,793, 428k box instances per 52k requests
- Post-fix: p50 ≤5ms (target), fairness ≥0.95 (target), ThreadPark <5k (target), admission rejection <2%
- **No report backs these figures.** They come from a development run of 2026-03-28; the
  "Performance Analysis Report" this line used to link does not exist in this repository and
  never has, and `exeris-benchmarks` publishes nothing for that date — its earliest
  `entity-read-by-id` report is 2026-06-20. Evidence state: **unartifacted**
  (`claims-and-evidence.md`). Treat the pre-fix numbers as a development observation, not as a
  citable measurement, until a run on the reference profile replaces them.

---

## 3. Resilience (Load Shedding)

The Kernel must protect itself. Memory bounds are non-negotiable. The levels below are the contract;
the enforcement in this repository is stated beside each.

- **Warning (L0):** Queue > 2,000 requests. Carried as `ScalingContext.free().maxQueueDepth()`, a value
  a caller passes to `ResourceArbiter`; no kernel code path measures a request queue against it.
- **Saturated (L1):** Queue > 5,000 requests → Trigger Backpressure (`H3_EXCESSIVE_LOAD` or HTTP `503`).
  Concurrently admitted streams are capped per engine by `AdmissionController` at
  `transport.paqs.maxActiveStreams` (default `TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS`, 5,000; `-1`
  for none). `H3_EXCESSIVE_LOAD` is the documented response to the `SHEDDING` watermark, but no code in
  this repository sends it. The Community watermark moves only when `memory.offHeapBudgetBytes` is
  set, and the arbiter answers `ALLOW` for 30 s after each carrier start. The HTTP `503` is
  the persistence admission gate above.
- **Starved (L2):** Carrier Thread blocked > 50 ms → Kill Task (`EX-RUN-3002`). Target only: `EX-RUN-3002`
  is a registered code with a `CarrierPinnedJfrEvent`, and no kernel code measures carrier blocking or
  kills a task. Carrier pinning is asserted from JFR recordings by `AbstractCarrierPinningTck`.
