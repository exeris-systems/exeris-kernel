# Physical Tier: Community (The Muscle)

**Module:** `exeris-kernel-community`
**Current Repository Reality:** this module is no longer a placeholder. The repository contains a real `src/` tree,
provider implementations, bootstrap wiring, tests, and e2e assets.

**Dependencies (Current Architecture):**

- `compile`: `exeris-kernel-spi` (SPI contracts — The Wall)
- `compile`: `exeris-kernel-core` (bootstrap/runtime infrastructure shared across tiers)
- `test`: `exeris-kernel-tck`

> **Status:** `exeris-kernel-community` currently ships working Community implementations for memory, crypto,
> persistence, transport, and HTTP in this repository state. The module also contains bootstrap integration tests,
> transport/HTTP integration tests, and TCK bindings. P0 e2e helper scripts are stored in
> the separate `exeris-benchmarks` repository.
> Resource-server security path is implemented in current repository state: Community HTTP admission integrates JWT/JWKS validation and enforces explicit `401` (authentication failure) versus `403` (insufficient scope) outcomes.

> **Reality vs target-state:** parts of the original ADR-008 target architecture remain aspirational, but the module
> is operational today. The authoritative source of truth is the current source tree and tests, not the older
> placeholder narrative.

## 🗺️ Request Flow: Standard POSIX + JDBC Stack

Every incoming request traverses this pipeline. Annotations mark the **memory domain** at each stage.
`[OFF-HEAP]` = Panama `MemorySegment`; `[ON-HEAP]` = JVM Heap; `[POOL]` = slab returned to allocator.

```mermaid
flowchart TD
    NIC(["🌐 NIC / TCP Packet"])

    A["① TCP accept()\n<b>POSIX syscall — epoll/kqueue</b>\nFile Descriptor acquired\n<i>[OS Kernel → JVM boundary]</i>"]

    B["② LoanedBuffer.allocate()\n<b>Off-Heap Arena (PanamaArenaAllocator)\n(conceptual placeholder)</b>\nRaw bytes written to MemorySegment\nref-count = 1\n<i>[OFF-HEAP]</i>\n<i>(conceptual placeholder — TRL-4)</i>"]

    C["③ TLS unwrap\n<b>OpenSSL via Panama FFM</b>\n(target architecture — ADR-008, impl pending)\nSSL_read() downcall · plaintext → LoanedBuffer slice\nNo ByteBuffer · No heap copy\n<i>[OFF-HEAP → OFF-HEAP]</i>\n<i>(conceptual placeholder — TRL-4)</i>"]

    D{"④ PAQS Scheduler\n<b>Decision Gate</b>\nPriority queue · Watermark check\nLoad-shed if WM ceiling breached"}

    E["⑤ Virtual Thread mount\n<b>Project Loom (JEP 444)</b>\nScopedValue: KernelContext injected\n~100 bytes stack · carrier thread unmounted\n<i>[OFF-HEAP context]</i>"]

    F["⑥ Business Logic / Flow Engine\n<b>Standard Java POJOs</b>\nDeserialization · Domain objects\n<i>[ON-HEAP ⚠️ Heap Boundary]</i>"]

    G["⑦ JDBC Persistence\n<b>Standard JDBC Driver</b>\nResultSet · DTO · String materialisation\n<i>[ON-HEAP — GC may trigger here]</i>"]

    H["⑧ Response serialisation\n<b>Off-Heap write-back</b>\nLoanedBuffer filled · TLS wrap (SSL_write)\n<i>[OFF-HEAP]</i>"]

    I["⑨ TCP send() + Slab release\n<b>POSIX syscall</b>\nLoanedBuffer.release() → ref-count 0\nSlab returned to PanamaArenaAllocator pool\n(conceptual placeholder)\n<i>[POOL]</i>\n<i>(conceptual placeholder — TRL-4)</i>"]

    NIC --> A --> B --> C --> D
    D -->|"ADMIT"| E --> F --> G --> H --> I
    D -->|"SHED"| SHED(["🚫 Load Shed\nHTTP 503 / EX-NET-4006\nNo Virtual Thread spawned"])

    style A fill:#0f3460,color:#e0e0ff,stroke:#4a90d9
    style B fill:#0f3460,color:#e0e0ff,stroke:#2ecc71
    style C fill:#0f3460,color:#e0e0ff,stroke:#2ecc71
    style D fill:#1a1a2e,color:#ffe066,stroke:#ffe066,stroke-width:2px
    style E fill:#0f3460,color:#e0e0ff,stroke:#4a90d9
    style F fill:#3a1a1a,color:#ffb3b3,stroke:#e74c3c,stroke-width:2px
    style G fill:#3a1a1a,color:#ffb3b3,stroke:#e74c3c,stroke-width:2px
    style H fill:#0f3460,color:#e0e0ff,stroke:#2ecc71
    style I fill:#1a3a2a,color:#e0e0ff,stroke:#2ecc71
    style SHED fill:#3a1a1a,color:#ffb3b3,stroke:#e74c3c
```

> **Key Insight:** The target architecture still aims to keep TLS and transport off the heap, but the current
> in-repo state is already materially implemented. Community boots real HTTP, transport, crypto, and persistence
> providers today. The remaining question is not whether the module exists, but which runtime paths are already
> zero-copy/low-allocation and which are still bounded-allocation or JDBC-bound.

## 💪 Architectural Rules (L0 Enforcement)

1. **Community is implemented now:** `exeris-kernel-community` contains concrete provider and bootstrap code in this
   repository. Reviews and design decisions must start from the live implementation, not from the older placeholder
   assumption.
2. **Transport and TLS remain constrained by current implementation:** Community supports external HTTP/TCP
   communication today, with crypto wired into transport-backed HTTP paths. Allocation behavior still needs to be
   judged per concrete runtime path and test evidence rather than by stale module prose.
3. **The Heap Boundary (JDBC):** The heap boundary begins at the business logic layer (step ⑥).
   JDBC persistence (step ⑦) allocates `ResultSet`, DTO, and `String` objects on the JVM heap.
   The Garbage Collector may trigger here. Community persistence is therefore operational but not zero-GC.
4. **No Kernel-Bypass:** Uses standard POSIX networking (`epoll`/`kqueue`/`WSAPoll`). Advanced kernel-bypass
   techniques (`io_uring`, strictly off-heap custom DB drivers) are reserved for the Enterprise module.
5. **Controlled Core Access (ADR-008):** Community drivers depend on `exeris-kernel-core` to utilize shared
   infrastructure (e.g., `CoreOpenSslLoader`, `TlsStateMachine`). However, drivers must never bypass standard
   SPI orchestration or attempt to manipulate Core's internal `WatermarkManager` directly.

## ✅ E2E / Test Readiness (Current State)

- **P0 harness:** present in-repo under `exeris-benchmarks/exeris-kernel-community/e2e/` with launcher,
   postgres helper, `k6`, `h2load`, and `wrk` scripts.
- **HTTP loopback contract:** Community binds a provider-level loopback TCK that exercises client+server
   request/response through Community runtime engines.
- **Core HTTP TCK posture:** Core keeps fixture-based HTTP engine TCK bindings for SPI lifecycle/identity contracts;
   transport-backed E2E binding remains a Community runtime concern in current repository reality.

## 📊 Allocation Contract (Community vs. Enterprise)

| Feature         | Community (Zero-GC Network, Heap Persistence)                                                   | Enterprise (Zero-GC End-to-End)               |
| :-------------- | :---------------------------------------------------------------------------------------------- | :-------------------------------------------- |
| **Network I/O** | **Zero-Alloc** *(Target — ADR-008, impl pending)* Off-Heap TCP + OpenSSL/FFM                   | Zero-Alloc + Kernel-Bypass (`io_uring`)       |
| **TLS Hot Path** | **Community OpenSSL/FFM provider path** via `CommunityKernelCryptoProvider` + `CommunityTlsEngine` | Zero-Alloc hard guarantee (Panama FFM / OpenSSL) |
| **Telemetry**   | Bounded (~65 bytes / JFR event)                                                                 | Strict Zero (Binary Ring-Buffer off-heap)     |
| **Persistence** | JDBC overhead (heap-bound) ⚠️                                                                   | Strict Zero (Native DB driver)                |
| **Context**     | `ScopedValue` (low overhead)                                                                    | `ScopedValue` + Off-Heap Arena                |

## 🎯 Open-Core Strategy

The Community tier is an engineering-grade, production-ready engine — not a crippled fallback. It eliminates the
Garbage Collector from the network hot-path entirely (the bottleneck for 95% of applications). The upgrade path to
Enterprise targets the remaining bottlenecks: OS-level syscall overhead (solved by `io_uring`) and heap-bound DB
drivers (solved by the native persistence driver).

## Stability

Community providers (priority 0) implement SPI surfaces classified in the
[SPI Stability Matrix](../stability-matrix.md).
