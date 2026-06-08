# Physical Tier: SPI (The Constitution)

**Module:** `exeris-kernel-spi`
**Dependencies:** None (Only standard Java 26+ library)

## 🗺️ Dependency Graph: SPI as the Immutable Foundation

`exeris-kernel-spi` is a **leaf node** in the dependency tree. It has no upstream Exeris dependencies.
Every other module in the kernel depends on it, and it depends on nothing.

```mermaid
graph TD
    SPI["<b>exeris-kernel-spi</b><br/><i>The Constitution</i><br/>─────────────────<br/>Pure Contracts · Value Records"]

    CORE["<b>exeris-kernel-core</b><br/><i>The Brain & Shared Infra</i><br/>─────────────────<br/>Orchestration · Core TLS Engine"]

    COMM["exeris-kernel-community<br/><i>The Muscle · OSS</i>"]
    ENT["exeris-kernel-enterprise<br/><i>The Accelerator · Proprietary</i>"]

    TCK["<b>exeris-kernel-tck</b><br/><i>The Judge</i>"]

    %% Compile-time dependencies
    CORE -->|"compile"| SPI
    %% NOTE: exeris-kernel-community is currently a placeholder module with no declared Maven dependencies.
    COMM -->|"compile"| CORE
    %% NOTE: exeris-kernel-enterprise is not present in this repository (closed-source, distributed separately).
    ENT  -->|"compile"| CORE

    %% TCK dependencies
    TCK -->|"compile"| SPI

    %% Test-time execution: Core/Comm/Ent consume TCK as a test-jar
    CORE -.->|"test (test-jar)"| TCK
    COMM -.->|"test (test-jar)"| TCK
    ENT  -.->|"test (test-jar)"| TCK

    style SPI fill:#1a1a2e,color:#e0e0ff,stroke:#7c7cff,stroke-width:2px
    style CORE fill:#16213e,color:#e0e0ff,stroke:#4a90d9,stroke-width:2px
    style COMM fill:#0f3460,color:#e0e0ff,stroke:#4a90d9,stroke-width:1px
    style ENT  fill:#533483,color:#e0e0ff,stroke:#9b59b6,stroke-width:1px
    style TCK  fill:#1a3a2a,color:#e0e0ff,stroke:#2ecc71,stroke-width:1px
```

> **The Wall Rule:** No module may depend on `exeris-kernel-core`, `community`, or `enterprise` from within `SPI`.
> Any class in `exeris-kernel-spi` that imports a non-JDK symbol is a **Wall Breach** and must be rejected in code review.

## 📦 Canonical Module Contents

| Package Suffix             | Allowed Types                                      | Forbidden                                   |
|:---------------------------|:---------------------------------------------------|:--------------------------------------------|
| `*.spi.transport`          | `TransportBinding`, `TlsEngine`, `LoanedBuffer`    | `CommunityTlsEngine`, `QuicBioMultiplexer`  |
| `*.spi.memory`             | `MemoryAllocator`, `LoanedBuffer`, `MemorySlab`    | `PanamaArenaAllocator`, `SlabPool`          |
| `*.spi.persistence`        | `CitadelRepository`, `StorageContext`              | `JdbcTemplate`, `HikariCP`, FFM handles     |
| `*.spi.crypto`             | `TlsEngine`, `TlsHandshakeResult`, `TlsPhase`      | `SSL_CTX*` pointers, OpenSSL symbol names   |
| `*.spi.http`               | `HttpRequest`, `HttpResponse`, `HttpProvider`, `HttpServerEngine`, `HttpClientEngine` | HTTP wire codec implementations (`Hpack*`, `Http2*`, `Http1*`) |
| `*.spi.exceptions`         | `ExerisKernelException` hierarchy                  | Checked exceptions on hot paths             |

## 🛡️ Architectural Rules (L0 Enforcement)

1. **No Implementation Details:** Interfaces must never leak implementation specifics (e.g., no `io_uring` flags, no
   Netty references).
2. **Immutable Carriers:** All data transfer objects must be Java `record` or deeply immutable `final class`, designed
   for Valhalla readiness (JEP 401) — no `synchronized`, no `System.identityHashCode()`, no identity comparisons (`==`)
   on these objects.
3. **No Logic:** SPI contains only Contracts (Interfaces), Exceptions, Enums, and Constants.
4. **Loaned Memory:** All buffer passing must use `LoanedBuffer` to enforce reference counting and zero-copy semantics.

## HTTP in SPI (Current Repository State)

- HTTP contracts are currently part of `exeris-kernel-spi` under `eu.exeris.kernel.spi.http`.
- There is no separate `exeris-kernel-spi-http` module in the root reactor.
- SPI HTTP remains a pure contract layer: no wire-codec logic and no transport-specific implementation details.

## Stability

Every SPI surface in this module is classified in the [SPI Stability Matrix](../stability-matrix.md)
as `stable`, `preview`, or `experimental`. That matrix is the authoritative declaration; this module
doc does not restate per-package levels.