# Kernel Subsystem: Crypto (L1 Security)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.crypto.*` (`KernelCryptoProvider`, `TlsEngineFactory`, `TlsEngine`, `TlsSession`)
- Core: `eu.exeris.kernel.core.crypto.*` (`OpenSslLinker`, `NativeCipherContext`, `TlsHandshakeOrchestrator`)
- Community: JSSE-backed TLS engine (no native dependency)
- Enterprise: OpenSSL 3.x via Panama FFM — zero object allocation per encrypt/decrypt cycle

**Layer:** L1 (Security)  
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Crypto subsystem** provides **zero-allocation TLS and symmetric cipher operations** for all
transport pipelines. The central design constraint is:

> **Every packet encryption/decryption cycle must produce zero heap objects.**

Legacy approaches (JSSE `SSLEngine`, Bouncy Castle `Cipher`) allocate wrapper objects, `ByteBuffer`
views, and intermediate `byte[]` arrays on every call. Under 100k packets/second this creates
sustained GC pressure that conflicts with the Exeris "No Waste Compute" philosophy.

The Enterprise implementation solves this by calling **OpenSSL 3.x directly via Panama FFM**,
writing plaintext and reading ciphertext through pre-allocated `MemorySegment` slabs managed by
`MemoryAllocator`. No Java wrapper object is created between the `LoanedBuffer` and the native call.

---

## Design Principles

| Principle                  | Implementation                                                                      |
|:---------------------------|:------------------------------------------------------------------------------------|
| Zero objects per cipher op | `MemorySegment` passed directly to OpenSSL FFM call handle                          |
| Zero-Copy                  | Ciphertext written into the transport's `LoanedBuffer` in-place                     |
| SPI Isolation (The Wall)   | `KernelCryptoProvider` SPI has zero knowledge of OpenSSL, JSSE, or Panama internals |
| Context pre-allocation     | `SSL_CTX` and `SSL` structs allocated once at session start; reused per record      |
| JFR-First                  | TLS handshake start/end and cipher errors emit typed JFR events                     |

---

## OpenSSL FFM Integration Architecture

### Why FFM instead of JNI?

| Aspect              | JNI                               | Panama FFM                                           |
|:--------------------|:----------------------------------|:-----------------------------------------------------|
| Allocation per call | `jbyteArray` copy into JVM heap   | Zero — `MemorySegment` is a direct pointer           |
| Safety              | Unchecked C pointer, SIGSEGV risk | Arena-bound; JVM validates access                    |
| Overhead            | `GetPrimitiveArrayCritical` pin   | Zero pin — off-heap segment is already native memory |
| Valhalla-readiness  | No                                | `MemorySegment` value-typed layout compatible        |

### Linker Bootstrap (Enterprise — `OpenSslLinker`)

```java
// One-time setup during KernelBootstrap — NOT per-call:
Linker linker = Linker.nativeLinker();
SymbolLookup ssl = SymbolLookup.libraryLookup("libssl.so.3", Arena.global());

// Pre-resolve function handles at bootstrap time → JIT inlines them as constants:
static final MethodHandle SSL_CTX_NEW =
        linker.downcallHandle(ssl.find("SSL_CTX_new").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));

static final MethodHandle SSL_write =
        linker.downcallHandle(ssl.find("SSL_write").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

static final MethodHandle SSL_read =
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
// Enterprise implementation sketch (simplified):
public void wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) throws CryptoException {
    // Both segments are off-heap — addresses are stable native pointers:
    long plaintextAddr = plaintext.segment().address();
    long ciphertextAddr = ciphertext.segment().address();
    int plaintextLen = (int) plaintext.size();

    // Direct FFM call — no ByteBuffer, no byte[], no object allocation:
    int written;
    try {
        written = (int) SSL_write.invokeExact(sslPtr, plaintextAddr, plaintextLen);
    } catch (Throwable t) {
        throw new CryptoException(KernelErrorCodes.EX_SEC_2001, "SSL_write failed", t);
    }
    if (written <= 0) {
        throw new CryptoException(KernelErrorCodes.EX_SEC_2001, "SSL_write returned <= 0");
    }
    ciphertext.setSize(written);
}
```

### Per-Packet Decrypt Path (Zero Allocation)

```java
public void unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) throws CryptoException {
    int read;
    try {
        read = (int) SSL_read.invokeExact(
                sslPtr,
                plaintext.segment().address(),
                (int) plaintext.capacity());
    } catch (Throwable t) {
        throw new CryptoException(KernelErrorCodes.EX_SEC_2001, "SSL_read failed", t);
    }
    if (read <= 0) {
        throw new CryptoException(KernelErrorCodes.EX_SEC_2001, "SSL_read returned <= 0");
    }
    plaintext.setSize(read);
}
```

---

## NativeCipherContext Lifecycle

The `NativeCipherContext` wraps a single `SSL*` struct and its associated `BIO` pair.
It is allocated **once per TLS session** — not per record.

```
Session Start:
  SSL_CTX_new()   → ssl_ctx_ptr  (shared per provider, Arena.global())
  SSL_new()       → ssl_ptr      (per-session, Arena.ofShared())
  BIO_new_pair()  → rbio, wbio   (per-session, same Arena as ssl_ptr)
  SSL_set_bio()   → attach BIOs

Per-Record: wrap() / unwrap() calls above — ZERO allocation

Session End:
  SSL_free(ssl_ptr)  → releases ssl + BIOs (OpenSSL owns BIOs after SSL_set_bio)
  Arena.close()      → releases MemorySegment backing ssl_ptr
```

### Arena Discipline (The Wall)

| Object                    | Arena                            | Owner                                             |
|:--------------------------|:---------------------------------|:--------------------------------------------------|
| `SSL_CTX`                 | `Arena.global()`                 | `OpenSslLinker` (bootstrap, lives until JVM exit) |
| `SSL*` per session        | `Arena.ofShared()`               | `NativeCipherContext` (closed on session end)     |
| Plaintext `LoanedBuffer`  | `MemoryAllocator` (carrier slab) | Transport pipeline (RAII)                         |
| Ciphertext `LoanedBuffer` | `MemoryAllocator` (network slab) | Transport pipeline (RAII)                         |

**Rule:** Business logic code (handlers, repositories) MUST NEVER hold a reference to a
`NativeCipherContext` or any `MemorySegment` beyond the scope of a single `wrap()`/`unwrap()` call.

---

## SPI Contract (The Wall)

The `KernelCryptoProvider` SPI and all interfaces in `eu.exeris.kernel.spi.crypto.*`
are **completely blind** to OpenSSL, JSSE, BouncyCastle, or Panama internals.

```java
// SPI — knows nothing about SSL_write, MethodHandle, or Arena:
public interface TlsEngine extends AutoCloseable {
    void wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) throws CryptoException;

    void unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) throws CryptoException;

    TlsHandshakeResult handshake() throws CryptoException;

    TlsSession session();

    @Override
    void close();
}
```

The Enterprise `OffHeapTlsEngine` implementing this interface lives exclusively in
`exeris-kernel-enterprise`. It is discovered via `ServiceLoader` — the SPI module never
imports it.

---

## Community vs Enterprise Implementations

### Community (Free Tier)

- `JsseTlsEngine` — wraps `javax.net.ssl.SSLEngine`.
- Allocates `ByteBuffer` wrappers per record (acceptable for low-throughput deployments).
- **Does not** require native libraries; works on all JDK 26+ distributions out of the box.

### Enterprise (Secret Sauce — lives in `exeris-kernel-enterprise`)

- `OffHeapTlsEngine` — OpenSSL 3.x via Panama FFM as described above.
- Pre-allocates `SSL*` context via `NativeCipherContext` at session start.
- `wrap()`/`unwrap()` produce **zero heap objects**.
- TLS 1.3 session resumption via `SSL_SESSION` caching in a dedicated `MemoryAllocator` partition
  (partition name `"crypto"` in `GlobalMemoryArbiter`).
- **SPI isolation enforced:** `OffHeapTlsEngine` imports only `eu.exeris.kernel.spi.*` types.
  Zero imports from `kernel-legacy`, `sun.*`, or `javax.net.ssl.*`.

---

## JFR Events

| Event Class                | When Emitted                          | Key Fields                                         |
|:---------------------------|:--------------------------------------|:---------------------------------------------------|
| `TlsHandshakeEvent`        | Start and completion of TLS handshake | `sessionId`, `protocol`, `cipher`, `durationNanos` |
| `TlsHandshakeFailureEvent` | Handshake exception                   | `errorCode`, `peerAddress`, `failureReason`        |
| `CryptoContextAllocEvent`  | `NativeCipherContext` creation        | `arenaName`, `sizeBytes`, `providerName`           |

**Rule:** `wrap()` and `unwrap()` on the cipher hot-path MUST NOT emit JFR events per-call.
Use JFR's built-in `MethodProfiling` instead for cipher throughput analysis.

---

## Error Handling

Crypto errors follow the Black-Box pattern (see `03-telemetry.md`).
No `String.formatted()`, no `e.getMessage()` concatenation at throw sites.

```java
// ✅ CORRECT — static message, raw args:
public CryptoException(String errorCode, String staticMessage, Throwable cause) {
    super(errorCode, staticMessage, cause);
}

// Called as:
throw new

CryptoException(KernelErrorCodes.EX_SEC_2001, "SSL_write failed",cause);
```

A future `EX-SEC-2003` code will be registered for OpenSSL-specific errors with
`rawArgs[0] = int opensslErrorCode` (the raw `ERR_get_error()` return value — no string formatting).

---

## Banned Patterns

| Pattern                                                                   | Reason                            | Replacement                                                   |
|:--------------------------------------------------------------------------|:----------------------------------|:--------------------------------------------------------------|
| `javax.net.ssl.SSLEngine` in Enterprise path                              | Allocates `ByteBuffer` per record | `OffHeapTlsEngine` via Panama FFM                             |
| `new byte[n]` for encrypt/decrypt buffer                                  | GC pressure on cipher hot-path    | Pre-allocated `LoanedBuffer` from `MemoryAllocator`           |
| `ByteBuffer.wrap(segment.toArray())`                                      | Copies off-heap → heap            | `MemorySegment` address passed directly to `SSL_write`        |
| `Arena.ofConfined()` inside `wrap()`/`unwrap()`                           | Creates/destroys arena per record | Session-level `Arena.ofShared()` in `NativeCipherContext`     |
| Catching `Throwable` from `MethodHandle.invokeExact()` without rethrowing | Swallows `VirtualMachineError`    | Always rethrow `Error` and `StructuredTaskScope` cancellation |

---

## Testing Strategy

### Unit Tests

- `JsseTlsEngine` handshake over loopback (Community path, no native libs).
- `wrap()`/`unwrap()` round-trip with known plaintext/ciphertext vectors.

### Integration Tests (TCK)

- `OffHeapTlsEngine` (Enterprise): verify zero heap allocations during 1000 `wrap()` calls
  (JFR GC allocation profiler baseline must show 0 B/op).
- `NativeCipherContext` lifecycle: `SSL*` pointer freed when session arena is closed
  (verified via `MemorySegment.isNative()` check after `close()`).

### Load Tests

- 100k TLS records/second with < 5 µs P99 `wrap()` latency.
- GC pause frequency: < 1 minor GC per 10 seconds under sustained cipher load.

