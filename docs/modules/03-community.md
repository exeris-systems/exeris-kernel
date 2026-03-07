# Physical Tier: Community (The Muscle)

**Module:** `exeris-kernel-community`
**Dependencies (Planned — not yet declared in `exeris-kernel-community/pom.xml`):**
- `compile`: `exeris-kernel-spi` (SPI contracts — The Wall)
- `compile`: `exeris-kernel-core` (shared infrastructure: `CoreOpenSslLoader`, `TlsStateMachine`, `AbstractLoanedBuffer`)
- `test`: `exeris-kernel-tck`

> **Note:** The current `exeris-kernel-community/pom.xml` declares **no** Maven dependencies and the module has **no** `src/` tree in this repository. The dependency list above reflects the **intended target architecture**. This module is an **active placeholder** — implementations will be added in future pull requests.

> **⚠️ Implementation Status (TRL-3):** `exeris-kernel-community` is currently a **placeholder module** in this
> repository with **no sources** and **no Maven build output**. When complete, it will ship:
> `CommunityTelemetryProvider` *(planned)* (Console/JFR/File sinks),
> `CommunityKernelCryptoProvider` *(planned)* (OpenSSL 3.x via Panama FFM, TCP-only — no QUIC),
> `CommunityMemoryProvider` *(planned)* (Arena-based off-heap allocator + `CommunityAllocationEvent` JFR),
> `CommunityPersistenceProvider` *(planned)* (JDBC-based), and `CommunityGraphProvider` *(planned)* (SQL/Bolt-based).
> The **Network transport driver** (Off-Heap TCP carrier + PAQS scheduler) is **planned (TRL-4)**.
> See the request-flow diagram below for the target architecture.

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

> **Key Insight:** In the target architecture (ADR-008, implementation pending), the GC will only
> fire in steps ⑥ and ⑦ — TLS and network entirely off-heap via Panama FFM / OpenSSL.
> In the **current in-repo state** (`exeris-kernel-community` has no sources; TRL-3), the
> `CryptoZeroAllocTck` documents the Community TLS contract as JSSE-based with **bounded
> allocations** (≤ 8 per record). Enterprise is the zero-alloc hard guarantee today.
> The JDBC Tax (steps ⑥–⑦) is heap-bound in both tiers.

## 💪 Architectural Rules (L0 Enforcement)

1. **Panama-Powered TCP *(Target — ADR-008, implementation pending)*:** Community will own the
   TCP File Descriptor, driving TLS 1.3 via `CoreOpenSslLoader` handles from `exeris-kernel-core`.
   No QUIC, no `io_uring`. `exeris-kernel-community` currently has no sources — this is the
   accepted ADR-008 target architecture.
2. **TLS Allocation Contract *(current in-repo vs. target)*:** The `CryptoZeroAllocTck` documents
   the **current** Community TLS contract as JSSE/`SSLEngine` with bounded allocations
   (≤ 8 per record). The **target** (ADR-008) is zero-alloc Panama FFM / OpenSSL, identical
   to Enterprise. Until the ADR-008 implementation lands, `CryptoZeroAllocTck` is the
   authoritative contract.
3. **The Heap Boundary (JDBC):** The heap boundary begins at the business logic layer (step ⑥).
   JDBC persistence (step ⑦) allocates `ResultSet`, DTO, and `String` objects on the JVM heap.
   The Garbage Collector may trigger here. In the ADR-008 target architecture, the network and
   TLS layers will be exempt from GC; in the current TRL-3 state they incur bounded allocations
   per the `CryptoZeroAllocTck` contract.
4. **No Kernel-Bypass:** Uses standard POSIX networking (`epoll`/`kqueue`/`WSAPoll`). Advanced kernel-bypass
   techniques (`io_uring`, strictly off-heap custom DB drivers) are reserved for the Enterprise module.
5. **Controlled Core Access (ADR-008):** Community drivers depend on `exeris-kernel-core` to utilize shared
   infrastructure (e.g., `CoreOpenSslLoader`, `TlsStateMachine`). However, drivers must never bypass standard
   SPI orchestration or attempt to manipulate Core's internal `WatermarkManager` directly.

## 📊 Allocation Contract (Community vs. Enterprise)

| Feature               | Community (Zero-GC Network, Heap Persistence) | Enterprise (Zero-GC End-to-End)               |
|-----------------------|-----------------------------------------------|-----------------------------------------------|
| **Network I/O**       | **Zero-Alloc** *(Target — ADR-008, impl pending)* Off-Heap TCP + OpenSSL/FFM | Zero-Alloc + Kernel-Bypass (`io_uring`) |
| **TLS Hot Path**      | **Bounded** (JSSE/SSLEngine, ≤ 8 alloc/record per `CryptoZeroAllocTck`) → Zero-Alloc once ADR-008 impl lands | Zero-Alloc hard guarantee (Panama FFM / OpenSSL) |
| **Telemetry**         | Bounded (~65 bytes / JFR event)               | Strict Zero (Binary Ring-Buffer off-heap)     |
| **Persistence**       | JDBC overhead (heap-bound) ⚠️                  | Strict Zero (Native DB driver)                |
| **Context**           | `ScopedValue` (low overhead)                  | `ScopedValue` + Off-Heap Arena                |

## 🎯 Open-Core Strategy

The Community tier is an engineering-grade, production-ready engine — not a crippled fallback. It eliminates the
Garbage Collector from the network hot-path entirely (the bottleneck for 95% of applications). The upgrade path to
Enterprise targets the remaining bottlenecks: OS-level syscall overhead (solved by `io_uring`) and heap-bound DB
drivers (solved by the native persistence driver).
