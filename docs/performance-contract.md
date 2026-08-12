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
| **Telemetry Overhead**  | > 10M ops/s     | < 5M ops/s       | `BinaryGlassBox` JMH validation         |
| **Kernel Overhead**     | < 500 µs        | > 1 ms           | Read to Virtual Thread Mount latency    |
| **GC Pause (P99)**      | < 1 ms          | > 5 ms           | ZGC Generational validation             |
| **Allocation Rate**     | **0 B / req**   | > 65 B / req     | Strict zero-allocation on hot path      |

---

### 2.2.1 Execution-Tier Scope — HotSpot/C2 (the zero-allocation contract is profile-driven)

The **0 B/req** allocation target and the broader **"No Waste Compute"** guarantees are **pinned to the HotSpot C2 JIT**. They depend on peak-tier, profile-driven optimizations — most importantly **Escape Analysis scalarization** of carriers, and the **intrinsification / runtime optimization of Panama FFM downcall stubs and `MemorySegment` / `VarHandle` access**. These are C2 runtime properties, not language-level guarantees.

Under **GraalVM native-image (SubstrateVM)** the contract is **different, not broken**: AOT compilation without profile-guided optimization makes scalarization and FFM-path decisions more conservative, so the zero-allocation and per-core throughput SLOs above are **not asserted** under native-image. PGO (instrumented build → profile → optimized build) narrows the gap but is operationally heavy and out of scope for the default contract.

**Consequences for benchmarking and claims:**

- The targets in §2.1–§2.2 apply on **HotSpot/C2** (the *throughput tier*). Any benchmark asserting the zero-allocation or RPS-per-core claims **MUST** run on HotSpot/C2 and record the JIT tier. A native-image measurement of these specific claims is **out of contract** and must not be presented as a refutation of them.
- **native-image is the *edge/lightweight tier*** — fast startup without warmup, small footprint, small image — a separately-scoped performance profile, not a regression of this one. Its enablement is a post-1.0 gated track (see ROADMAP "Road to 1.0" §"Scope Discipline & Declared Stances" — the Native-image / GraalVM stance).

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
2. If false (no capacity), HTTP responds **503 Service Unavailable**
3. Client retries after advertised Retry-After header (1–10 seconds)

This prevents unchecked thread proliferation and pool queuing:
- **Before:** Threads park on exhausted pool → fairness inversion → cascading latency spike
- **After:** Early rejection at HTTP level → client-side retry backoff → fairness restored

### Tier Differences

**Community:** Conservative HikariCP-based gating
- Rejects if: `idle == 0 AND queue forming`
- Rejects if: `active >= (maxPoolSize * 0.9)`
- Latency: <1ms per check
- Implementation: Direct HikariCP `getNumIdle()` / `getNumActive()` query

**Enterprise:** Predictive native gating
- May reject before absolute saturation using io_uring metric telemetry
- Adapts admission curve to workload pattern (exponential backoff)
- Latency: <500µs per check (measured via native probes)
- Implementation: Native driver poll of SQE availability + queue depth heuristic

### Observability

JFR events (when enabled via profile):
- `PersistenceAdmissionGate` (custom event, if profiling enabled): admitted yes/no, queue depth, active connections
- Standard `ThreadPark` events: significantly reduced (proof of starvation elimination)
- Standard `HttpResponse` events: occasional 503 responses visible (expected under saturation)

### Compliance Proof

**Benchmark Run:** 2026-03-28T15:20:50Z, entity-read-by-id scenario, 50 concurrent virtual threads
- Pre-fix: p50 52.57ms, fairness 0.1268, ThreadPark 40,793, 428k box instances per 52k requests
- Post-fix: p50 ≤5ms (target), fairness ≥0.95 (target), ThreadPark <5k (target), admission rejection <2%
- See [Performance Analysis Report](docs/performance-analysis-report.md) for full JFR analysis

---

## 3. Resilience (Load Shedding)

The Kernel must protect itself. Memory bounds are non-negotiable.

- **Warning (L0):** Queue > 2,000 requests.
- **Saturated (L1):** Queue > 5,000 requests → Trigger Backpressure (`H3_EXCESSIVE_LOAD` or HTTP `503`).
- **Starved (L2):** Carrier Thread blocked > 50 ms → Kill Task (`EX-RUN-3002`).
