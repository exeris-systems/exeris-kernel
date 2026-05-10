# ADR-008: Open-Core Strategy & Commoditization of Off-Heap TLS

| Attribute      | Value                                                          |
|:---------------|:---------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                   |
| **Deciders**   | Arkadiusz Przychocki                                           |
| **Date**       | 2026-03-05                                                     |
| **Compliance** | [Strategic Pillar: Open-Core Adoption & Glass-Box](../whitepaper.md) |

## Context and Problem Statement

The original architecture assumed a split in which `exeris-kernel-community` relied on the standard,
heap-allocating JSSE (`SSLEngine`), while zero-allocation OpenSSL (Panama FFM) was reserved exclusively
for the Enterprise tier.

This creates two critical problems:

**Adoption (The First Impression):** Engineers evaluating Exeris on the free tier do not experience the
core promise of "Zero-Waste Compute" at the network layer, which may block their migration to the
Enterprise tier. The performance gap between JSSE and off-heap OpenSSL is so large that Community users
would benchmark a fundamentally inferior product.

**Open-Core Identity:** For the Exeris Kernel to become a market standard — replacing Netty and Tomcat —
the free core (Community) must be technologically unrivalled. A Community tier that leaks heap on every
TLS record undermines the entire "No Waste Compute" identity.

We must define a hard Open-Core boundary that protects the business interest (Enterprise) while delivering
revolutionary value in Open Source.

---

## 🏁 The Decision

We adopt an **Aggressive Open-Core** model, redefining the monetisation and technology boundary across
three physical tiers.

### 1. Core TLS Engine (Shared Infrastructure — `exeris-kernel-core`)

The off-heap TLS engine is extracted into `exeris-kernel-core` as shared infrastructure. This is the
architectural source of truth for all TLS operations, regardless of tier:

- `CoreOpenSslLoader` — Panama FFM symbol resolution for OpenSSL 3.x (`SSL_read`, `SSL_write`,
  `SSL_do_handshake`, `SSL_CTX_new`, etc.)
- `CoreSslHandles` — Valhalla-ready record carrier for all resolved `MethodHandle` instances.
- `TlsStateMachine` — lock-free `VarHandle` CAS state machine governing `TlsPhase` transitions.
- `NativeCipherContext` — off-heap lifecycle wrapper for `SSL*` and `SSL_CTX*` native pointers.

Both Community and Enterprise **depend on Core** for TLS. Neither tier reimplements the FFM layer.
This is consistent with The Wall: Core is the Brain, not a driver.

### 2. Community Tier — TCP FD Owner (`exeris-kernel-community`)

Community becomes the **TCP File Descriptor Owner**. It owns the Berkeley socket lifecycle
(`CoreSyscallLoader` handles), drives the TLS handshake over a standard TCP connection,
and exposes the result to the application layer via the SPI:

- `CommunityTlsEngine` — `TlsEngine` SPI implementation. Uses `CoreOpenSslLoader` handles.
  Supports **TLS 1.3 over TCP only**. No QUIC, no `io_uring` BIO pairs.
- `SslHandles` (package-private) — Law of Demeter wrapper: all `invokeExact` calls are
  encapsulated; `CommunityTlsEngine` calls `handles.invokeRead(...)`, never raw handles.
- Zero heap allocation on the TLS hot path — plaintext and ciphertext reside exclusively
  in `LoanedBuffer` instances backed by Panama `MemorySegment`.

**Community targets zero heap allocation on the TLS hot path** — a property not achievable by stacks
based on standard `JSSE`/`SSLEngine`, which allocate `ByteBuffer` wrappers and `TLSPlaintext` records
on the JVM heap for every TLS record processed.

### 3. Enterprise Tier — BIO QUIC & io_uring (`exeris-kernel-enterprise`)

Enterprise abandons simple TCP+OpenSSL as its differentiator. The Enterprise boundary is now defined
by **Kernel-Bypass I/O and the QUIC protocol stack**:

- `QuicBioMultiplexer` — `BIO_new_bio_dgram_pair` crossover-cable abstraction. Bridges the
  application-side UDP datagram path to the OpenSSL QUIC stack with zero copies.
  `networkBioPtr` is injected with incoming UDP packets; `internalBioPtr` is passed to
  `SSL_set_bio` on the QUIC connection object.
- `EnterpriseQuicSslLoader` — resolves QUIC-specific OpenSSL symbols (`SSL_CTX_new_ex`,
  `OSSL_QUIC_client_method`, `SSL_provide_quic_data`, `SSL_process_quic_post_handshake`).
- `QuicSslHandles` — record carrier for QUIC-specific `MethodHandle` instances. Separate from
  `CoreSslHandles` to maintain The Wall: QUIC symbols must not contaminate the Core layer.
- `io_uring` TCP transport (`transport/iouring/tcp/`) — SQ/CQ ring-based TCP, kernel-bypass
  I/O submission without `epoll` syscall overhead.
- `io_uring` QUIC transport (`transport/iouring/quic/`) — UDP datagram pipeline, `PBUF_RING`,
  multishot `RECVMSG`, QUIC/HTTP3 Transport SPI adapter.

---

## Open-Core Boundary (Summary)

| Capability                          | Core | Community | Enterprise |
|:------------------------------------|:----:|:---------:|:----------:|
| Panama FFM symbol resolution (TLS)  | ✅   |           |            |
| `TlsStateMachine` (lock-free CAS)   | ✅   |           |            |
| `NativeCipherContext` lifecycle     | ✅   |           |            |
| TLS 1.3 over TCP (off-heap)         |      | ✅        |            |
| TCP FD ownership (Berkeley socket)  |      | ✅        |            |
| `BIO_s_dgram_pair` / QUIC BIO       |      |           | ✅         |
| QUIC-specific OpenSSL symbols       |      |           | ✅         |
| `io_uring` TCP (kernel-bypass)      |      |           | ✅         |
| `io_uring` QUIC + multishot RECVMSG |      |           | ✅         |

---

## Consequences

### ✅ Positive Outcomes

* **[+] Massive Community Adoption:** The free Exeris Kernel becomes the fastest Java application
  server in the ecosystem, outclassing Netty, Undertow, and Vert.x on memory efficiency. Every
  benchmark published by the community becomes organic marketing for the Enterprise tier.
* **[+] Trust & Credibility:** Open-sourcing Panama FFM + OpenSSL TLS signals deep, verifiable
  engineering competence. It is proof, not marketing.
* **[+] Architectural Cohesion:** The Core TLS engine is a single, audited implementation.
  No duplication between tiers — Community and Enterprise share the same `NativeCipherContext`
  lifecycle and `TlsStateMachine`, reducing the attack surface for security bugs.
* **[+] Enterprise Upsell Clarity:** The Enterprise value proposition is now unambiguous and
  multi-dimensional. Community eliminates the GC tax at the **Ingress layer only** (TLS + transport).
  Enterprise extends the Zero-Alloc Covenant across the **entire data lifecycle**:

  | Layer             | Community                                      | Enterprise                                           |
  |:------------------|:-----------------------------------------------|:-----------------------------------------------------|
  | **TLS / Ingress** | Zero-Alloc (OpenSSL Panama FFM)                | Zero-Alloc (OpenSSL Panama FFM)                      |
  | **I/O Overhead**  | Standard syscalls (context-switch per read/write) | `io_uring` kernel-bypass (zero syscalls steady-state) |
  | **Memory Lifecycle** | Slab-per-transport (heap contact after TLS)  | Global off-heap block: NIC → DB, zero heap touch     |
  | **Persistence**   | JDBC / SQL (DTO, ResultSet, String allocations) | Native off-heap drivers (Postgres/Neo4j via FFM)     |
  | **Logic Layer**   | Standard POJOs (heap)                          | Valhalla Value Classes (flattened, off-heap)         |

  Community still pays the **JDBC Tax** and the **Syscall Tax**. Enterprise is exempt from both.

  Notably, even Community — burdened with JDBC — outperforms competitors running on their native graph
  drivers, because the Exeris L3/L4 (Flow/Events) query optimisation eliminates object churn at the
  logic layer. Enterprise removes the last remaining tax entirely: the same query runs over a native
  off-heap persistence driver, turning a competitive advantage into an insurmountable one.

### ⚠️ Trade-offs

* **[!] Enterprise Upsell Shift:** The Enterprise sales motion must be precisely targeted.
  Community eliminates GC pressure at the ingress layer — but the **JDBC Tax** (DTO allocation,
  `ResultSet` wrapping, `String` materialisation) and the **Syscall Tax** (OS context-switch on
  every `read`/`write`) remain fully in force throughout the persistence and I/O layers.
  Enterprise targets organisations that have hit the **persistence ceiling** (needing native
  off-heap DB drivers) or the **I/O ceiling** (needing `io_uring` kernel-bypass or QUIC/HTTP3
  for mobile-edge and loss-tolerant paths) — not organisations merely escaping GC pressure,
  which Community already resolves.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.

Any future capability that introduces new OpenSSL symbols for TCP must land in `exeris-kernel-core`
(`CoreOpenSslLoader` / `CoreSslHandles`). Any capability specific to QUIC BIO or `io_uring` ring
management must land in `exeris-kernel-enterprise`. Violations of this boundary are a Wall breach
and must be rejected in code review.
