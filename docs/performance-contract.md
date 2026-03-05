# The Performance Contract (Hyper-Density Spec)

This document defines the efficiency targets for Exeris Kernel. Targets are **strictly divided**
into the current development baseline (TRL-3) and the final Enterprise goals.

## 1. Reference Hardware Profiles

| Profile             | Hardware Context                          | Purpose                     |
|:--------------------|:------------------------------------------|:----------------------------|
| **L0 (Developer)**  | Ryzen 5600 (or equiv) / 1 vCPU / 4GB RAM | Daily PR validation         |
| **L1 (Enterprise)** | AWS c7g.4xlarge (16 vCPU, 32GB RAM)       | Official Tier certification |

---

## 2. Efficiency Targets (The "Speed" Layer)

### 2.1 Network Throughput per Core

Measured with a "Hello World" **1 KB payload**.

> **Note:** For 8 KB entity payloads, we expect ~50% of the targets below due to MTU network
> frame pressure.

| Stage               | Target (SLO)         | Breach Threshold | Context                              |
|:--------------------|:---------------------|:-----------------|:-------------------------------------|
| **Current (TRL-3)** | **> 1,500 RPS/vCPU** | < 1,000 RPS/vCPU | Standard TCP Sockets / Off-Heap      |
| **Enterprise**      | **> 8,500 RPS/vCPU** | < 6,000 RPS/vCPU | `io_uring` / Native Kernel Bypass    |

---

### 2.2 Latency & Allocation (Strict Zero-GC)

This is where Exeris implements the **"No Waste Compute"** philosophy.

| Metric (SLI)            | Target (SLO)    | Breach Threshold | Rationale                               |
|:------------------------|:----------------|:-----------------|:----------------------------------------|
| **Telemetry Overhead**  | > 10M ops/s     | < 5M ops/s       | `BinaryBlackBox` JMH validation         |
| **Kernel Overhead**     | < 500 µs        | > 1 ms           | Read to Virtual Thread Mount latency    |
| **GC Pause (P99)**      | < 1 ms          | > 5 ms           | ZGC Generational validation             |
| **Allocation Rate**     | **0 B / req**   | > 65 B / req     | Strict zero-allocation on hot path      |

---

## 3. Resilience (Load Shedding)

The Kernel must protect itself. Memory bounds are non-negotiable.

- **Warning (L0):** Queue > 2,000 requests.
- **Saturated (L1):** Queue > 5,000 requests → Trigger Backpressure (`H3_EXCESSIVE_LOAD` or HTTP `503`).
- **Starved (L2):** Carrier Thread blocked > 50 ms → Kill Task (`EX-RUN-3002`).
