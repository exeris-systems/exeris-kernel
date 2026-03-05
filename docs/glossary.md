# Glossary of Terms

This document defines the core terminology used within the **Exeris Kernel** ecosystem. A precise
understanding of these terms is mandatory to maintain the "No Waste Compute" performance standards
and architectural integrity.

---

## 🏛️ Architecture & Philosophy

### Software Inflation
The phenomenon where modern runtimes waste hardware resources through excessive layers of abstraction.
In standard Java, this manifests as generating gigabytes of temporary objects (POJOs/DTOs) to process
small binary payloads. Exeris explicitly eliminates this "Abstraction Tax."

### The Wall (Architectural Separation)
A strict boundary between the **SPI layer** (The Constitution) and the **Implementations** (The Muscle).
It prevents implementation-specific details (e.g., OpenSSL pointers, `io_uring` flags, JDBC driver types)
from leaking into business logic or system contracts.

### Glass-Box Auditability
A design philosophy centered on total transparency. Exeris exposes every internal state, memory
allocation, and lifecycle phase via high-precision **JFR (Java Flight Recorder)** events with
sub-1% CPU overhead when enabled and zero overhead when disabled.

> **Glass-Box vs. Black-Box are complementary, not contradictory:** Glass-Box (JFR) is the
> *real-time observability* strategy for healthy systems. Black-Box (binary crash dumps) is the
> *forensic* strategy for failures. Together they cover every operational scenario without
> allocating `String` objects on any path.

### Hyper-Density
The system's capability to handle an extreme number of concurrent tasks (> 8,500 RPS/vCPU) with
minimal RAM and CPU overhead, achieved by eliminating object headers, GC churn, and OS thread
context-switching via Virtual Threads and Project Valhalla.

### Sovereign Infrastructure
A "Cloud-Agnostic" approach that eliminates dependency on proprietary vendor stacks. Exeris builds
its own native transport and memory layers to ensure data residency, performance predictability, and
freedom from per-seat or per-core licensing models.

### TRL-3
**Technology Readiness Level 3.** Indicates that the Exeris Kernel architecture is not merely
theoretical — it is a **validated prototype** demonstrating proof-of-concept in extreme load
scenarios including JFR-verified zero-allocation hot-paths.

### Failure Sovereignty
The bootstrap policy that governs how the Kernel responds to a subsystem initialization failure.
Two modes are supported:
- **`FAIL_FAST`** — any initialization error halts the JVM immediately. Recommended for production.
- **`DEGRADE`** — optional subsystems may fail without blocking startup. Intended for local
  development or emergency maintenance windows.

---

## 💾 Memory & Concurrency (L0 Foundation)

### Panama FFM (JEP 454)
**Foreign Function & Memory API.** The foundation of Exeris's off-heap strategy. Provides fast,
safe, and deterministic access to native memory and C-libraries (like OpenSSL) without JNI overhead,
without `jbyteArray` heap copies, and with Arena-bound lifetime safety enforced by the JVM.

### Slab
A contiguous chunk of **off-heap** memory of a fixed size (typically MTU-aligned, ~1.5 KB). It is
the atomic unit of allocation within the Kernel's `PartitionedPool`. Traversal between slabs is
pointer arithmetic — no object instantiation per record.

### LoanedBuffer
A thread-safe, reference-counted off-heap data carrier implementing the **RAII** (Resource
Acquisition Is Initialization) pattern. It allows native `MemorySegment` slabs to be "loaned"
across Virtual Threads and subsystems without copying data to the JVM Heap. When the reference count
reaches zero, the slab is returned to the `MemoryAllocator` pool deterministically.

### Zero-Copy
A data flow model where bytes read from the **NIC (Network Interface Card)** move directly into
off-heap application memory and flow through Crypto, Transport, and Graph subsystems without ever
being copied to the JVM Heap. In the Enterprise tier: `NIC → io_uring CQ ring → LoanedBuffer → DB`.

### ScopedValue (JEP 506)
An immutable, Virtual Thread-safe context propagation mechanism. Used in Exeris for passing
`PrincipalContext`, `StorageContext`, and `TelemetryRouter` across subsystem boundaries.
**`ThreadLocal` is strictly BANNED** across the entire Kernel — it causes GC churn and memory leaks
in environments with millions of Virtual Threads.

### Value Class / Value Record (JEP 401 — Valhalla Readiness)
A future JVM primitive that eliminates object headers and identity overhead. Exeris designs all
immutable data carriers (e.g., `EventDescriptor`, `MemorySlab`) as standard `record` types today,
strictly avoiding identity operations (`==`, `synchronized`, `System.identityHashCode()`) so they
scalarize cleanly via JIT Escape Analysis and will migrate to `value record` without code changes.

### Thread Pinning
A critical performance degradation where a Virtual Thread blocks its underlying OS **Carrier Thread**
(e.g., via `synchronized` blocks or blocking native calls). This prevents the CPU core from
processing other Virtual Threads, effectively eliminating Loom's scalability advantage. Tracked via
`CarrierPinnedEvent` (JFR) with `EX-RUN-3002` in the Black-Box telemetry stream.

### Watermark (Resource Management)
A monitoring threshold for resource utilization managed by `WatermarkManager`. When consumption
crosses the **High Watermark**, the `ResourceArbiter` triggers load-shedding (via PAQS) and
backpressure to protect kernel stability. Crossing the **Low Watermark** (recovery) resumes normal
acceptance.

---

## 🚨 Telemetry & Diagnostics (L0 / L1)

### Black-Box Pattern
An error-reporting architecture where `ExerisKernelException` subclasses **never allocate `String`
messages** in their constructors. Instead, they capture raw primitives (`long`, `int`, `Enum`) in
an `Object[] rawArgs` array. The Enterprise tier serializes these directly into a binary off-heap
ring buffer as fixed-width structs — readable by the Black-Box Decoder without `toString()`,
`ObjectOutputStream`, or JSON.

> The only permitted allocation is autoboxing of primitives to `Long`/`Integer` for `rawArgs[]`,
> because exceptions are **never thrown on the normal hot-path** — they represent exceptional
> failure states.

### CWE-532 Enforcement
A security constraint where sensitive configuration values (tokens, passwords, connection URLs)
that appear in error context (e.g., `EX-CFG-1002` Type Mismatch) are **explicitly redacted by the
caller** before entering the `rawArgs[]` payload. Emitting raw credentials into the binary telemetry
dump constitutes a CWE-532 (Information Exposure Through Log Files) violation against the Exeris
Security Contract.

---

## 🚀 Native I/O & Execution (L2 Data Synthesis)

### io_uring
A high-performance asynchronous I/O interface for the Linux kernel. Utilized in the **Enterprise
Tier** to batch network reads/writes into SQ/CQ rings, bypassing standard `epoll` syscall overhead.
This achieves kernel-bypass networking with near-zero CPU jitter and enables the `NIC → LoanedBuffer`
zero-copy path.

### PAQS (Priority-Aware Queue Scheduler)
Exeris's intelligent ingress mechanism. Injects **business context** at the network edge, allowing
the system to shed low-priority traffic (e.g., analytics, telemetry) before it consumes any parsing
resources, heap state, or Virtual Thread slots. The shedding decision is O(1) — a single `int`
comparison against the `WatermarkManager` threshold. Sheds are tracked via `EX-NET-4006`.

### ALPN (Application-Layer Protocol Negotiation)
A TLS extension used during the handshake to negotiate the application protocol (e.g., `h2` for
HTTP/2, `h3` for HTTP/3) without additional network round-trips. In Exeris, ALPN negotiation uses
SIMD `mismatch()` on native memory for zero-allocation protocol detection.

### Happy Eyeballs (RFC 8305)
An algorithm that reduces connection latency by racing IPv4/IPv6 (or QUIC and TCP) connections
simultaneously, selecting the fastest successful path. Used in the Enterprise Transport tier to
minimize handshake latency on dual-stack deployments.

---

## 🔀 Orchestration (L3 / L4)

### DAG Init
**Directed Acyclic Graph initialization.** The Bootstrap subsystem resolves the exact startup order
of all Kernel subsystems at **T-minus 0** using topological sort (Kahn's algorithm). A circular
dependency in `dependsOn()` declarations is an unrecoverable defect — it immediately triggers
`EX-BOOT-0001` and halts the JVM before any subsystem is activated.

### Failure Sovereignty *(see Architecture & Philosophy)*

### Async Park/Wake
A Virtual Thread lifecycle pattern used by the Flow/Saga subsystem. A Saga waiting for an external
event (e.g., a payment gateway callback) **parks** its Virtual Thread instead of blocking a thread
pool or serializing state to a database. Loom's sub-1 KB thread cost allows millions of suspended
Sagas to coexist in memory. When the callback arrives, `SagaState.wake(event)` resumes the thread
in microseconds — no DB round-trip, no heap allocation.

