# Glossary of Terms

This document defines the core terminology used within the **Exeris Kernel** ecosystem. A precise understanding of these terms is mandatory to maintain the "No Waste Compute" performance standards and architectural integrity.

## Architecture & Philosophy

### Software Inflation
The phenomenon where modern runtimes waste hardware resources through excessive layers of abstraction. In standard Java, this manifests as generating gigabytes of temporary objects (POJOs/DTOs) to process small binary payloads. Exeris aims to eliminate this "Abstraction Tax."

### The Wall (Architectural Separation)
A strict barrier between the **SPI layer** (The Constitution) and the **Implementations** (The Muscle). It prevents implementation-specific details (e.g., io_uring flags or OpenSSL handles) from leaking into the system contracts.

### Glass Box (Auditability)
A design philosophy centered on total transparency. Unlike "Black Box" runtimes, Exeris exposes every internal state, allocation, and bootstrap phase via high-precision **JFR (Java Flight Recorder)** events.

### Hyper-Density
The system's capability to handle an extreme number of concurrent tasks with minimal RAM and CPU overhead, achieved by eliminating object headers and identity-based memory management.

### Sovereign Infrastructure
A "Cloud-Agnostic" approach that eliminates dependency on proprietary vendor stacks. Exeris builds its own native transport and memory layers to ensure data residency and performance predictability.

## Memory Management (Layer 0)

### Slab
A contiguous chunk of **Off-Heap** memory of a fixed size (typically 1.5 KB to align with Network MTU). It is the atomic unit of allocation within the `PartitionedPool`.

### Loaned Buffer
A thread-safe, reference-counted data carrier (implemented as a **Value Record**). It allows native memory segments to be "loaned" across different subsystems without data copying.

### Zero-Copy
A data flow model where bytes read from the **NIC (Network Interface Card)** move directly into application memory (Off-Heap) and are processed there without ever being copied to the JVM Heap.

### Identity-free Data
Data structures that lack an object header (identity). Leveraging **Project Valhalla**, these structures are flattened in memory, drastically improving L1/L2 cache hit rates.

## ⚡ Execution Model (Layer 2)

### Carrier Thread
A platform (OS) thread used as the physical executor for Virtual Threads. In Exeris, Carrier Threads must **never** be blocked by I/O or synchronization.

### Virtual Thread
A lightweight thread (**Project Loom**) that is mounted onto a Carrier Thread only during computation. Exeris uses a "Thread-per-Task" model to scale to millions of concurrent operations.

### Thread Pinning
A critical failure state where a Virtual Thread blocks its Carrier Thread (e.g., via `synchronized` or native calls), preventing other tasks from utilizing the CPU core.

### Watermark (Resource Management)
A monitoring mechanism for resource utilization. When usage crosses the **High Watermark**, the `ResourceArbiter` triggers load-shedding to protect kernel stability.

## Transport & Connectivity

### io_uring
A high-performance asynchronous I/O interface for the Linux kernel, utilized in the **Enterprise Tier** to achieve maximum throughput with minimal context switching.

### Happy Eyeballs (RFC 8305)
An algorithm that reduces connection latency by attempting to connect via IPv4 and IPv6 (or QUIC and TCP) simultaneously, picking the fastest successful path.

### ALPN (Application-Layer Protocol Negotiation)
A TLS extension used during the handshake to negotiate the protocol (e.g., `h3` for HTTP/3) without requiring additional network round-trips.