# Kernel Subsystem: Transport (L2 Native I/O)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.transport.*` (Stream, Connection, TransportProvider)
- Core: `eu.exeris.kernel.core.transport.*` (PAQS Scheduler, Load Shedding, Stream Lifecycle)
- Drivers:
    - **`community`**: **Exeris Native TCP Carrier** – Custom off-heap TCP/TLS engine built for Virtual Thread density.
    - **`enterprise`**: **Exeris Native QUIC Carrier** – Flagship **`io_uring`** implementation (Linux) and
      high-performance native stack (Win/Mac).
      **Layer:** L2 (Native I/O)  
      **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Transport subsystem** is the Kernel’s gateway, rejecting legacy reactive event-loops in favor of a **Virtual
Thread-per-Stream** architecture. It utilizes Panama FFM to map data from the wire directly into Kernel-managed off-heap
memory, bypassing the standard JVM heap entirely.

- **High-Performance Flagship (Enterprise):** Leverages **`io_uring`** on Linux to eliminate syscall overhead (
  Kernel-Bypass), providing maximum connection density and minimal latency.
- **Native Everywhere:** Even the Community tier rejects standard libraries (Netty/Tomcat). The entire stack, from TCP
  to HTTP/2, is a custom off-heap implementation designed for zero "Object Churn."
- **Protocol Blindness:** Business logic operates on abstract `Stream` objects. It remains oblivious to whether data
  flows via TCP on Windows or via QUIC/io_uring on Linux.
- **PAQS (Priority-Aware Queue Scheduler):** Injects business context into the network edge, allowing the Transport
  layer to shed low-priority telemetry traffic during spikes to prioritize critical payment streams.

---

## Core Philosophy

### 1. Kernel-Bypass & Zero-Copy

In the Enterprise tier, the system aims to bypass traditional OS I/O overhead wherever possible. Data is read into a
`LoanedBuffer` and moves through the entire Kernel stack without a single copy operation.

### 2. Carrier Loop Architecture

Transport logic centers around dedicated **Carrier Loops** (e.g., `IoUringQuicCarrier`). These loops manage native
sockets, task queues (`MpscArrayQueue`), and local memory pools (`SlabPool`) in a tight, non-blocking execution cycle.

### 3. Imperative Simplicity (Loom)

Thanks to Java 26 Virtual Threads, developers write simple, blocking code. When the transport waits for I/O, the Virtual
Thread is parked, while the Carrier Loop continues to multiplex thousands of other active streams.

---

## Performance Tiering

| Feature              | Community (Native TCP)          | Enterprise (Native QUIC)                      |
|:---------------------|:--------------------------------|:----------------------------------------------|
| **I/O Engine**       | Java NIO.2 (Off-Heap Optimized) | **Native `io_uring` (Linux) / Native Socket** |
| **Protocol**         | TCP / TLS (HTTP 1.1/2)          | **UDP / QUIC (HTTP/3)**                       |
| **Syscall Strategy** | Optimized Standard I/O          | **SQ/CQ Ring Submission (Kernel-Bypass)**     |
| **Target Platform**  | Cross-platform                  | **Linux (Max Performance) / Multi-platform**  |

---

## Responsibilities

**What Transport SPI DOES:**

1. Define the `Stream` and `Connection` lifecycle interfaces.
2. Provide `TransportProvider` for native driver registration.
3. Define abstract `StreamHeaders` for protocol-blind header access.

**What Transport Core DOES:**

1. Implement the **PAQS Scheduler** and global Load Shedding logic.
2. Coordinate with `ResourceArbiter` to monitor `SlabPool` exhaustion.
3. Manage Virtual Thread allocation (one per incoming stream).

---

## Error Codes (Black Box Telemetry)

| Code          | Meaning               | Action                                                       |
|:--------------|:----------------------|:-------------------------------------------------------------|
| `EX-NET-4001` | Connection Terminated | Release `LoanedBuffer`, clean up Virtual Thread context.     |
| `EX-NET-4002` | Protocol Violation    | Malformed framing or security violation - drop connection.   |
| `EX-NET-4003` | PAQS Load Shedding    | Stream rejected at the network edge to preserve resources.   |
| `EX-NET-4004` | Buffer Exhaustion     | No available segments in `SlabPool` - initiate backpressure. |

---

## Code Examples

### 1. Native `io_uring` Submission (Enterprise Concept)

Example of direct interaction with `io_uring` rings via Panama FFM within the Carrier Loop:

```java
// Inside IoUringQuicCarrier (Simplified logic)
public void processIngress() {
    // Peek at the CQ ring for completed events without heap allocation
    long cqEntry = ioUring.peekCq();
    if (cqEntry != 0) {
        // Map the completion directly to a Slab-allocated segment
        MemorySegment slab = ingressPool.allocate();
        ioUring.prepareRead(fd, slab.address(), slab.byteSize());
        ioUring.submit(); // Batch submission to the SQ ring
    }
}
```

## Testing Strategy

### Integration Tests

io_uring Validation: Linux-specific tests verifying the integrity of SQ/CQ ring submissions and completions.

Zero-Allocation Hot Path: JFR-based verification ensuring zero bytes are allocated on the heap during the ingress read
path.

Cross-Platform Parity: Verification that business logic behaves identically when running over the TCP Carrier vs. the
QUIC Carrier.

### Lab & Load Tests

Saturation Point Analysis: Identifying the crossover point where io_uring outperforms the standard stack in P99 latency.

Carrier Stress: Stress-testing the MpscArrayQueue throughput under millions of network events per second.

## Summary

The Transport subsystem is the high-performance foundation of the Exeris Kernel. By focusing on io_uring for Enterprise
and custom Carrier Loops for Community, it provides an I/O infrastructure capable of handling millions of streams with
near-zero CPU overhead and zero heap-based object churn.