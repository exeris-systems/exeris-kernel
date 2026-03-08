# Kernel Subsystem: Crypto (L1 Citadel Extension)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.crypto.*` (`KernelCryptoProvider`, `TlsEngine`, `TlsStatus`)
- Core: `eu.exeris.kernel.core.crypto.*` (`CoreOpenSslLoader`, `NativeCipherContext`, `TlsStateMachine`)
- Community: Portable Off-Heap TLS (OpenSSL 3.x via Panama FFM on standard TCP)
- Enterprise: Hyper-Density TLS (OpenSSL 3.x via Panama FFM + `io_uring` + QUIC)

**Layer:** L1 (Data & Integrity)  
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Crypto subsystem** delivers **zero-allocation TLS and symmetric cipher operations** for all
transport pipelines. The central design constraint is:

> **Every packet encryption/decryption cycle must produce zero heap objects.**

Standard approaches (`javax.net.ssl.SSLEngine`) generate enormous GC pressure through continuous
`ByteBuffer` wrapper creation and `byte[]` array allocation per record. Under 100k packets/second
this sustained churn conflicts with the "No Waste Compute" philosophy.

Exeris eliminates this overhead by sending **raw `long` memory addresses** from `LoanedBuffer`
directly to native OpenSSL functions via Panama FFM. No Java wrapper object is created between the
`LoanedBuffer` and the native call — in both Community and Enterprise tiers.

---

## Design Principles

| Principle                  | Implementation                                                                       |
|:---------------------------|:-------------------------------------------------------------------------------------|
| Zero objects per cipher op | `MemorySegment` address passed directly to OpenSSL FFM call handle                   |
| Zero-Copy Handover         | Ciphertext written directly into transport's `LoanedBuffer` — no intermediate copies |
| SPI Isolation (The Wall)   | `KernelCryptoProvider` SPI has zero knowledge of OpenSSL, Panama, or `io_uring`      |
| Static Handle Inlining     | All `MethodHandle` instances are `static final` — JIT constant-folds them on hot-path|
| Session-Level Contexts     | `SSL*` and BIO structs allocated once per session via `NativeCipherContext`           |
| JFR-First                  | Handshake start/end and cipher errors emit typed JFR events                          |

---

## Zero-Arena Policy (Architectural Constraint)

Crypto (L1) has a **total ban on direct native Arena management**. This is not a style guideline —
it is an enforced architectural constraint.

| Risk                       | Why raw `Arena` is banned                                                             |
|:---------------------------|:--------------------------------------------------------------------------------------|
| **Invisible Leaks**        | A raw `Arena` does not report to Telemetry (L1). Leaks are invisible to JFR until OOM |
| **Watermark Bypass**       | Direct allocation bypasses `WatermarkManager` and `ResourceArbiter` — the Kernel      |
|                            | cannot detect that Crypto is exhausting memory and cannot shed load (`EX-MEM-1001`)   |
| **Thread-Local Penalty**   | `Arena.ofShared().close()` forces a global JVM thread-local handshake. `NativeCipherContext` |
|                            | avoids this entirely via `VarHandle`-based reference counting                         |

**The contract:** `OffHeapTlsEngine` obtains all native memory from the injected `MemoryAllocator`.
`NativeCipherContext` owns the session segment via `LoanedBuffer.retain()` / `LoanedBuffer.close()`.

| Feature          | Standard Panama (JSSE/Netty)  | Exeris Off-Heap TLS                            |
|:-----------------|:------------------------------|:-----------------------------------------------|
| Lifecycle        | Manual / Cleaner-based        | RAII (`LoanedBuffer` ref-count)                |
| Leak Tracking    | Glass-Box (OS level)          | Glass-Box (JFR + `LeakTracker`)                |
| Memory Pressure  | Unbounded                     | Arbiter-aware (backpressure at L0)             |

---

## OpenSSL FFM Integration Architecture

### Why FFM instead of JNI?

| Aspect              | JNI                               | Panama FFM                                           |
|:--------------------|:----------------------------------|:-----------------------------------------------------|
| Allocation per call | `jbyteArray` copy into JVM heap   | Zero — `MemorySegment` is a direct native pointer    |
| Safety              | Unchecked C pointer, SIGSEGV risk | Arena-bound; JVM validates access                    |
| Overhead            | `GetPrimitiveArrayCritical` pin   | Zero pin — off-heap segment is already native memory |
| Valhalla-readiness  | No                                | `MemorySegment` value-typed layout compatible        |

### Linker Bootstrap (Core — `CoreOpenSslLoader`)

```java
Linker linker = Linker.nativeLinker();
SymbolLookup ssl = SymbolLookup.libraryLookup("libssl.so.3", Arena.global());

static final MethodHandle sslCtxNew =
        linker.downcallHandle(ssl.find("SSL_CTX_new").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));

static final MethodHandle sslWrite =
        linker.downcallHandle(ssl.find("SSL_write").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

static final MethodHandle sslRead =
        linker.downcallHandle(ssl.find("SSL_read").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
```

All `MethodHandle` instances are `static final` — the JIT constant-folds them, eliminating
virtual dispatch on the cipher hot-path.

### Per-Packet Encrypt Path (Zero Allocation)

```
Transport layer calls:
  tlsEngine.wrap(plaintext: LoanedBuffer, ciphertext: LoanedBuffer)

  1. plaintext.segment()  → MemorySegment (off-heap, already native pointer)
  2. ciphertext.segment() → MemorySegment (pre-allocated from MemoryAllocator)
  3. SSL_write(ssl_ptr, plaintext.segment().address(), plaintext.size())
       → writes ciphertext directly into ciphertext.segment() via BIO
  4. ciphertext.setSize(bytesWritten)
  5. Return — zero heap objects created
```

```java
public void wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
    long srcAddr = plaintext.segment().address();
    long dstAddr = ciphertext.segment().address();

    int written;
    try {
        written = (int) SSL_write.invokeExact(sslPtr, srcAddr, (int) plaintext.size());
    } catch (Throwable t) {
        throw new CryptoException(KernelErrorCodes.EX_NET_2001, "SSL_write failed", t);
    }
    if (written <= 0) {
        throw new CryptoException(KernelErrorCodes.EX_NET_2001, "SSL_write returned <= 0");
    }
    ciphertext.setSize(written);
}
```

### Per-Packet Decrypt Path (Zero Allocation)

```java
public void unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
    int read;
    try {
        read = (int) SSL_read.invokeExact(
                sslPtr,
                plaintext.segment().address(),
                (int) plaintext.capacity());
    } catch (Throwable t) {
        throw new CryptoException(KernelErrorCodes.EX_NET_2003, "SSL_read failed", t);
    }
    if (read <= 0) {
        throw new CryptoException(KernelErrorCodes.EX_NET_2003, "SSL_read returned <= 0");
    }
    plaintext.setSize(read);
}
```

---

## NativeCipherContext Lifecycle

The `NativeCipherContext` wraps a single `SSL*` struct and its associated `BIO` pair.
It is allocated **once per TLS session** — not per record. Memory is obtained from the injected
`MemoryAllocator`, never from a directly opened `Arena`.

The diagram below illustrates the full RAII + ref-count lifecycle from session start to pool reclaim:

```mermaid
sequenceDiagram
    participant Alloc as MemoryAllocator
    participant NCC as NativeCipherContext
    participant Trans as Transport (Hot Path)

    Note over Alloc,NCC: Session Start
    Alloc->>NCC: allocate(SESSION)<br/>returns LoanedBuffer (ref=1)
    NCC->>NCC: SSL_CTX_new() · SSL_new() · BIO_new_pair()<br/>structs written into sessionBuffer.segment()
    NCC->>NCC: retain()<br/>(ref=2, NativeCipherContext holds one count)

    Note over NCC,Trans: Per-Packet Loop (Zero Allocation)
    loop N times — wrap() / unwrap()
        Trans->>NCC: wrap(plaintext, ciphertext)<br/>SSL_write() downcall → EX-NET-2001 on failure
        Trans->>NCC: unwrap(ciphertext, plaintext)<br/>SSL_read() downcall → EX-NET-2003 on failure
    end

    Note over Alloc,NCC: Session End
    Trans->>NCC: close() — session teardown
    NCC->>NCC: SSL_free() · BIO_free_all()<br/>release() → ref-count: 1
    NCC->>Alloc: LoanedBuffer.close()<br/>ref-count: 0 → slab returned to pool
```

> **RAII Invariant:** `NativeCipherContext` is the **sole** ref-count authority for the session slab.
> Transport code calls `wrap()`/`unwrap()` without ever retaining the buffer — it borrows, not owns.

```
Session Start:
  allocator.allocate(AllocationHint.SESSION) → sessionBuffer (LoanedBuffer, tracked by MemoryAllocator)
  SSL_CTX_new()  → ssl_ctx_ptr  (shared per provider, Arena.global() in CoreOpenSslLoader)
  SSL_new()      → ssl_ptr      (per-session, backed by sessionBuffer.segment())
  BIO_new_pair() → rbio, wbio   (per-session, same segment)
  SSL_set_bio()  → attach BIOs
  sessionBuffer.retain()        → ref-count: 2 (held by NativeCipherContext)

Per-Record: wrap() / unwrap() calls above — ZERO allocation

Session End:
  NativeCipherContext.close()   → ref-count: 1
  sessionBuffer.close()         → ref-count: 0 → returned to MemoryAllocator pool
```

### Arena Discipline

| Object                    | Memory Owner                         | Lifecycle Authority                                    |
|:--------------------------|:-------------------------------------|:-------------------------------------------------------|
| `SSL_CTX`                 | `Arena.global()`                     | `CoreOpenSslLoader` (bootstrap, lives until JVM exit)  |
| `SSL*` per session        | `MemoryAllocator` (SESSION hint)     | `NativeCipherContext` via `LoanedBuffer` ref-count     |
| Plaintext `LoanedBuffer`  | `MemoryAllocator` (carrier slab)     | Transport pipeline (RAII)                              |
| Ciphertext `LoanedBuffer` | `MemoryAllocator` (network slab)     | Transport pipeline (RAII)                              |

**Rule:** Business logic code MUST NEVER hold a reference to a `NativeCipherContext` or any
`MemorySegment` beyond the scope of a single `wrap()`/`unwrap()` call.

---

## SPI Contract (The Wall)

```java
public interface TlsEngine extends AutoCloseable {
    void wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) throws CryptoException;
    void unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) throws CryptoException;
    TlsHandshakeResult handshake() throws CryptoException;
    TlsSession session();

    @Override
    void close();
}
```

The SPI has zero knowledge of OpenSSL, JSSE, BouncyCastle, or Panama internals.
Both `CommunityTlsEngine` and `OffHeapTlsEngine` implementing this interface are discovered
via `ServiceLoader` — the SPI module never imports either.

---

## Community vs Enterprise Implementations

### Community (Free Tier — Portable Off-Heap TLS)

- `CommunityTlsEngine` — OpenSSL 3.x via Panama FFM on standard TCP/POSIX networking
  (`epoll`/`kqueue`).
- Same `CoreOpenSslLoader` and `NativeCipherContext` as Enterprise — zero `ByteBuffer` wrappers,
  zero `byte[]` arrays per record.
- Memory BIOs (`BIO_s_mem()`) with standard TCP `FileDescriptor` handoff.
- **Does not** use `io_uring` or QUIC — those are reserved for the Enterprise tier.
- Heap boundary: JDBC/persistence interactions may still generate bounded allocations.

### Enterprise (Secret Sauce — lives in `exeris-kernel-enterprise`)

- `OffHeapTlsEngine` — OpenSSL 3.x via Panama FFM + `io_uring` ring + QUIC Memory BIOs.
- Eliminates Head-of-Line Blocking via QUIC (UDP-based multiplexing).
- Zero JVM thread-local handshakes: `io_uring` batches syscalls, bypassing `epoll` overhead.
- TLS 1.3 session resumption via `SSL_SESSION` caching in a dedicated `MemoryAllocator`
  partition (`"crypto"` in `GlobalMemoryArbiter`).
- `wrap()`/`unwrap()` produce **zero heap objects**.
- **SPI isolation enforced:** zero imports from `sun.*` or `javax.net.ssl.*`.

### Code Example: Session Context via MemoryAllocator

```java
public OffHeapTlsEngine(MemoryAllocator allocator, CoreSslHandles handles) {
    LoanedBuffer sessionCtx = allocator.allocate(AllocationHint.SESSION);
    this.context = new NativeCipherContext(handles, sessionCtx.segment().address());
    this.context.retain();
    sessionCtx.close();
}
```

> `allocator.allocate()` is tracked by `WatermarkManager`. If the off-heap budget is exhausted,
> it throws `MemoryExhaustedException(EX-MEM-1001)` before any native memory is touched —
> this is the backpressure integration point between Crypto (L1) and Memory (L0).

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Path          | Meaning                            | Glass-Box Payload (`rawArgs`)                        |
|:--------------|:--------------|:-----------------------------------|:-----------------------------------------------------|
| `EX-NET-2001` | **wrap** (encrypt) | `SSL_write` failure / BIO error | `[0] int nativeErrorCode, [1] String detail`    |
| `EX-NET-2002` | Bootstrap     | Crypto Provider init failure       | `[0] String providerName, [1] String reason`         |
| `EX-NET-2003` | **unwrap** (decrypt) | `SSL_read` failure / alert received | `[0] int nativeErrorCode, [1] String detail` |

The split between `EX-NET-2001` and `EX-NET-2003` preserves the **one-code-one-schema invariant**
required by the binary Glass-Box telemetry contract: decoders can distinguish encrypt-side from
decrypt-side failures without parsing the `detail` string.

---

## JFR Events

| Event Class                | When Emitted                          | Key Fields                                         |
|:---------------------------|:--------------------------------------|:---------------------------------------------------|
| `TlsHandshakeEvent`        | Start and completion of TLS handshake | `sessionId`, `protocol`, `cipher`, `durationNanos` |
| `TlsHandshakeFailureEvent` | Handshake exception                   | `errorCode`, `peerAddress`, `failureReason`        |
| `CryptoContextAllocEvent`  | `NativeCipherContext` creation        | `allocatorName`, `sizeBytes`, `providerName`       |

**Rule:** `wrap()` and `unwrap()` on the cipher hot-path MUST NOT emit JFR events per-call.
Use JFR's built-in `MethodProfiling` for cipher throughput analysis.

---

## Banned Patterns

| Pattern                                                          | Reason                               | Replacement                                              |
|:-----------------------------------------------------------------|:-------------------------------------|:---------------------------------------------------------|
| `javax.net.ssl.SSLEngine` in any tier                            | Allocates `ByteBuffer` per record    | `CommunityTlsEngine` or `OffHeapTlsEngine` via FFM      |
| `new byte[n]` for encrypt/decrypt buffer                         | Sustained GC pressure on hot-path    | Pre-allocated `LoanedBuffer` from `MemoryAllocator`      |
| `ByteBuffer.wrap(segment.toArray())`                             | Copies off-heap → heap               | `MemorySegment` address passed directly to `SSL_write`   |
| `Arena.ofConfined()` or `Arena.ofShared()` in Crypto logic       | Bypasses `WatermarkManager`          | `MemoryAllocator.allocate(AllocationHint.SESSION)`       |
| Catching `Throwable` from `invokeExact()` without rethrowing     | Swallows `VirtualMachineError`       | Always rethrow `Error` and `StructuredTaskScope` signals |

---

## Testing Strategy

### Unit Tests

- `CommunityTlsEngine` handshake over loopback — no `io_uring`, no QUIC, pure TCP.
- `wrap()`/`unwrap()` round-trip with known plaintext/ciphertext vectors.
- `NativeCipherContext` ref-count: `retain()` increments, `close()` decrements, segment freed at zero.

### Integration Tests (TCK)

- Both Community and Enterprise: verify zero heap allocations during 1000 `wrap()` calls
  (JFR GC allocation profiler baseline must show 0 B/op).
- `MemoryExhaustedException(EX-MEM-1001)` is thrown before native allocation when
  `WatermarkManager` reports high watermark breach.
- `NativeCipherContext` lifecycle: `SSL*` pointer freed when `LoanedBuffer` ref-count reaches zero.

### Load Tests

- 100k TLS records/second with < 5 µs P99 `wrap()` latency *(target, not yet measured — baseline
  JMH benchmark pending TRL-4 milestone; current manual profiling shows < 12 µs P99 on loopback)*.
- GC pause frequency: < 1 minor GC per 10 seconds under sustained cipher load.
