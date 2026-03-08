# Exeris Kernel

> **Planned for v0.5:** Drop-in Spring Boot starter that replaces Tomcat/Netty with a native transport layer — same annotations, measurably less memory, higher throughput. Numbers available at v0.5 release.
>
> The current repository delivers the **standalone Java 26 runtime kernel and transport SPI**. Spring Boot auto-configuration will be published in a future milestone.

**Licence in one sentence:** SPI, Core, Community, and TCK are free for any use (including production); only the Enterprise acceleration layer (io_uring, QUIC, native DB drivers) requires a commercial licence — the same Open-Core boundary used by MongoDB and HashiCorp.

Exeris Kernel is a **Java 26 native runtime** that sits beneath your application stack. Instead of blocking a thread per request (Tomcat) or allocating event-loop objects (Netty), Exeris uses **Panama FFM** and off-heap memory to move bytes between the kernel and your business logic with zero heap copies and zero context-switch overhead. The Enterprise edition additionally accelerates I/O with **io_uring** and QUIC.

```
Spring Boot Application
        │
 ┌──────▼───────────────────────────────────────────┐
 │          Exeris Kernel (this repo)               │
 │  Virtual Threads · Panama FFM · Off-Heap Memory  │  ← Community (free)
 ├──────────────────────────────────────────────────┤
 │    io_uring · QUIC/HTTP3 · Off-Heap TLS accel    │  ← Enterprise (commercial)
 └──────────────────────────────────────────────────┘
        │
   Linux Kernel
```

## Why Exeris instead of Tomcat / Netty?

| Concern | Tomcat / Netty | Exeris Community | Exeris Enterprise |
|---|---|---|---|
| Memory model | JVM heap, GC pressure | Off-heap `MemorySegment`, zero GC | Off-heap `MemorySegment`, zero GC |
| Concurrency | Thread-per-request / event loops | Virtual Threads + `StructuredTaskScope` | Virtual Threads + `StructuredTaskScope` |
| I/O | Blocking NIO / `ByteBuffer` copies | Zero-copy `LoanedBuffer`, standard NIO | **io_uring** kernel-bypass, zero-copy |
| TLS | JSSE (heap allocations) | Off-heap OpenSSL via Panama FFM | **Off-heap TLS acceleration** (QUIC/HTTP3) |
| Observability | JMX / Micrometer | Built-in JFR events, flamegraph-ready | Built-in JFR events, flamegraph-ready |
| Context propagation | `ThreadLocal` | `ScopedValue` (JEP 506), VT-safe | `ScopedValue` (JEP 506), VT-safe |

## Open-Core Model

Exeris follows the **Open-Core model** (as used by MongoDB and HashiCorp): the SPI, core orchestration, and community drivers are free and open for use and contribution; enterprise accelerators (io_uring, QUIC/HTTP3, kernel-bypass TLS) require a commercial licence. This protects the project from cloud-vendor repackaging while keeping the community fully productive without paying anything.

| Module | Licence | What's included |
|---|---|---|
| `exeris-kernel-spi` | Free | Immutable contracts, Value Records |
| `exeris-kernel-core` | Free | Orchestration, bootstrap, context |
| `exeris-kernel-community` | Free | Java 26 FFM adapters, standard TCP/TLS |
| `exeris-kernel-tck` | Free | Compliance test kit for third-party drivers |
| `exeris-kernel-enterprise` | **Commercial** | io_uring, QUIC/HTTP3, off-heap TLS acceleration |

## 🏗️ Architecture ("The Wall")

Exeris enforces **strict vertical separation** at the Maven module level — not by domain, but by **trust and execution tier**:

```
exeris-kernel-parent
├── exeris-kernel-spi        (The Constitution — pure contracts, no impl)
├── exeris-kernel-core       (The Brain — orchestration, bootstrap, context)
├── exeris-kernel-community  (The Engine — standard Java 26 FFM adapters)
├── exeris-kernel-enterprise (The Accelerator — io_uring, QUIC, off-heap TLS)
└── exeris-kernel-tck        (The Judge — Technology Compatibility Kit)
```

The **À la carte** rule: you can mix providers across tiers. Use the free Community Transport while plugging in the Enterprise Persistence driver — or disable higher-level features entirely.

Key principles (see [`docs/architecture.md`](docs/architecture.md)):
- **spi** has zero Exeris dependencies — it is the immutable foundation.
- **core** depends only on **spi**.
- **community** and **enterprise** never depend on each other.
- **tck** depends only on **spi** — it tests contracts, never implementations.

## 🧩 Key Components

- **`exeris-kernel-spi`** — Identity-free data carriers (**Value Records**) and lifecycle contracts.
- **`exeris-kernel-core`** — Core runtime: `SubsystemOrchestrator`, `WatermarkManager`, `ResourceArbiter`, off-heap TLS (`OffHeapTlsEngine`), PAQS transport scheduler. `CoreOrchestrator` and `KernelBootstrap` are planned v0.5+ entrypoints for full `ServiceLoader`-backed multi-provider boot.
- **`exeris-kernel-community`** — Standard Java 26 FFM TCP/TLS adapters, accessible to all.
- **`exeris-kernel-enterprise`** — Native C drivers: `io_uring` ring management, `QuicBioMultiplexer`, off-heap OpenSSL.
- **`exeris-kernel-tck`** — Inquisition suite; any third-party driver must pass all TCK tests before integration.

## 🚀 Quick Start (Spring Boot) — Planned

> Spring Boot auto-configuration is **planned for v0.5.0**. The coordinates below reflect the target
> module structure; the Spring Boot starter artifact will be published alongside the v0.5 release notes.
> The current repository exposes the Exeris Kernel as a standalone Java 26 runtime and low-level
> transport SPI.

```xml
<!-- pom.xml — planned Spring Boot integration (not yet available) -->
<!-- A dedicated Spring Boot starter / auto-configuration module will be added in a future release. -->
<dependency>
    <groupId>io.exeris</groupId>
    <artifactId>exeris-kernel-core</artifactId>
    <version>0.5.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <version>0.5.0-SNAPSHOT</version>
    <scope>runtime</scope>
</dependency>
```

Full integration guide → [`docs/architecture.md`](docs/architecture.md)  
Performance guarantees → [`docs/performance-contract.md`](docs/performance-contract.md)

## 📄 Licence

The **SPI, Core, Community, and TCK** modules are distributed under **Apache License 2.0 + Commons Clause** ([LICENSE-COMMUNITY](LICENSE-COMMUNITY)) (free for development and production use — see individual module READMEs).  
The **Enterprise** module is distributed under the **Exeris Commercial Licence** (contact [licensing@exeris.io](mailto:licensing@exeris.io)).

Copyright © 2025–2026 Exeris. All rights reserved.
