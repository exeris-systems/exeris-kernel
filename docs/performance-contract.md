# The Performance Contract (Hyper-Density Spec)

This document defines the efficiency targets for Exeris Kernel. Targets are divided into the current development baseline (TRL-3) and the final Enterprise goals.

## 1. Reference Hardware Profiles

| Profile             | Hardware Context                         | Purpose                     |
|:--------------------|:-----------------------------------------|:----------------------------|
| **L0 (Developer)**  | Ryzen 5600 (or equiv) / 1 vCPU / 4GB RAM | Daily PR validation         |
| **L1 (Enterprise)** | AWS c7g.4xlarge (16 vCPU, 32GB RAM)      | Official Tier certification |

## 2. Efficiency Targets (The "Speed" Layer)

### 2.1 Throughput per Core (Efficiency Ratio)
Measured with "Hello World" (1KB payload).

| Stage               | Target (SLO)         | Breach Threshold | Context                          |
|:--------------------|:---------------------|:-----------------|:---------------------------------|
| **Current (TRL-3)** | **> 1,500 RPS/vCPU** | < 1,000 RPS/vCPU | Standard Sockets / Zero-Copy WIP |
| **Enterprise**      | **> 8,500 RPS/vCPU** | < 6,000 RPS/vCPU | io_uring / PBUF_RING / Native    |

*Note: For 8KB entity payloads, we expect ~50% of the above targets due to network frame pressure.*

### 2.2 Latency & Overhead
| Metric (SLI)        | Target (SLO) | Breach Threshold | Rationale                   |
|:--------------------|:-------------|:-----------------|:----------------------------|
| **Kernel Overhead** | < 500 µs     | > 1 ms           | Time from Read to VT Mount  |
| **GC Pause (P99)**  | < 1 ms       | > 5 ms           | Must use ZGC Generational   |
| **Allocation Rate** | < 1 KB/req   | > 8 KB/req       | Target: 0 B/req on hot path |

## 3. Resilience (Load Shedding)
- **Warning:** Queue > 2,000 requests.
- **Saturated:** Queue > 5,000 requests -> Trigger Backpressure (`H3_EXCESSIVE_LOAD`).
- **Starved:** Carrier Thread blocked > 50ms -> Kill Task (`EX-RUN-3002`).