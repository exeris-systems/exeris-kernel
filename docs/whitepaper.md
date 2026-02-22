# Exeris Kernel: Technical Whitepaper
**Author:** Arkadiusz Przychocki, Founder & Lead Architect  
**Status:** TRL-3 (Experimental proof of concept)  
**Date:** January 2026

---

## 1. Executive Summary

### 1.1 The Problem: The Abstraction Tax (Software Inflation)
Modern cloud computing is suffering from "Software Inflation". For decades, industry prioritized developer convenience over hardware efficiency. This has led to a critical "Memory Tax":
- **The Inflation Factor:** Processing a 4GB payload often triggers >160GB of temporary heap allocations (POJOs, DTOs).
- **The Consequence:** Infrastructure is over-provisioned. We pay for 16GB RAM nodes to run workloads that functionally require megabytes, just to buffer GC cycles.

### 1.2 The Insight: Data-Oriented Design (DOD)
**Data is a Stream, not an Object.** Exeris rejects the dogma that every byte from the network must be wrapped in a Java Object. By treating data as binary payloads managed Off-Heap, we eliminate the translation layer entirely.

### 1.3 The Solution: Exeris Kernel
Exeris is a zero-copy runtime platform targeting modern Java features (Java 26+). It bypasses the JVM heap to deliver extreme throughput and microsecond latency.

---

## 2. Technical Pillars (The "No Waste" Stack)

### 2.1 Project Valhalla (JEP 401/501) - Density
We eliminate object headers and identity overhead using **Value Records** and **Value Classes**. This allows for:
- Array flattening (storing data inline).
- Zero memory waste for small data carriers.

### 2.2 Project Panama (FFM API) - Raw Power
Direct, safe access to native memory and foreign functions:
- **Zero-Copy I/O:** Moving data from NIC to Application memory without JVM heap intermediate steps.
- **Native Muscle:** Direct integration with OpenSSL and `io_uring` for kernel-level performance.

### 2.3 Project Loom (Virtual Threads) - Scalability
Massive concurrency without the complexity of reactive event loops.
- **Thread-per-Request:** Simplified programming model with sub-millisecond context switching.
- **Structured Concurrency:** Guaranteed resource cleanup and error propagation.

---

## 3. Architectural Philosophy

### 3.1 "The Wall" (Separation of Concerns)
Exeris is built on a strict tiered architecture:
- **SPI (The Constitution):** Immutable contracts. Tier-agnostic.
- **Core (The Brain):** Intelligent orchestration, load-shedding, and resource arbitration.
- **Drivers (The Muscle):** 
  - *Community:* Standard Java-based adapters.
  - *Enterprise:* Native, high-density drivers (io_uring, QUIC, Kernel-Bypass).

### 3.2 "Glass Box" (Auditability)
Unlike "Black Box" runtimes, Exeris provides total transparency via **Java Flight Recorder (JFR)**. Every allocation, bootstrap phase, and transport event is traceable with nanosecond precision.

---

## 4. Operational Mantras
1. **No Waste Compute:** Every CPU cycle and every byte of RAM must serve the business logic.
2. **Fail-Fast Bootstrap:** Validate memory partitions and native dependencies at T-minus 0.
3. **Hardware Awareness:** The runtime must know the machine it runs on (NUMA, Page Size, CPU Caches).