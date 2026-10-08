---
title: "Kernel Subsystem: Crypto (L1 Citadel Extension)"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Kernel Subsystem: Crypto (L1 Citadel Extension)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.crypto.*` (`KernelCryptoProvider`, `TlsEngine`, `TlsStatus`, `CryptoProviderConfig`, `TlsHandshakeResult`, `TlsPhase`, `TlsSessionState`, `TlsShutdownResult`)
- Core: `eu.exeris.kernel.core.crypto.*` (`CoreOpenSslLoader`, `NativeCipherContext`, `TlsStateMachine`, `OffHeapTlsEngine`, `CoreSslHandles`, `CoreOpenSslRuntime`, and for client peer verification `TlsPeerIdentity`, `TlsFailureDetail`, `TlsHandshakeFailureCodes`; plus internal helpers: `AlpnReader`, `CipherNameReader`, `FfmErrors`)
- Community: Portable Off-Heap TLS (OpenSSL 3.x/4.x via Panama FFM on standard TCP); `CommunityTlsClientTrust` holds a client's trust store

**Layer:** L1 (Data & Integrity)  
**Status:** Integration-Tested Prototype (TRL-4)

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
`LoanedBuffer` and the native call.

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
`NativeCipherContext` draws exactly one `SESSION`-hint `LoanedBuffer` from that allocator at
construction and closes it exactly once — not via `LoanedBuffer.retain()`, but when its own
`VarHandle`-CAS reference count (a separate counter from the `LoanedBuffer`'s own) drops from 1 to
0. See NativeCipherContext Lifecycle below for the exact sequence.

| Feature          | Standard Panama (JSSE/Netty)  | Exeris Off-Heap TLS                            |
|:-----------------|:------------------------------|:-----------------------------------------------|
| Lifecycle        | Manual / Cleaner-based        | RAII (`NativeCipherContext` ref-count)         |
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

`CoreOpenSslLoader.load(Arena)` does not bind a single hardcoded library path: it walks
newest-major-first OS candidate lists for `libssl`/`libcrypto` (`.so.4` before `.so.3` on Linux,
equivalent DLL names on Windows), honours the `EXERIS_OPENSSL_SSL_PATH` /
`EXERIS_OPENSSL_CRYPTO_PATH` / `EXERIS_OPENSSL_PATH` environment overrides first, and version-gates
the result to `3 <= OPENSSL_version_major() <= 4` with a packed-version floor of `0x30000000`
(OpenSSL 3.0.0). The resolved handles use the provider-aware `SSL_CTX_new_ex` constructor, not the
legacy `SSL_CTX_new`, and every pointer-typed parameter is bound as a raw `JAVA_LONG` — not
`ADDRESS` — so a downcall exchanges a bare `long`, never a `MemorySegment` wrapper:

```java
Linker linker = Linker.nativeLinker();
SymbolLookup ssl = /* resolved candidate, e.g. libssl.so.4 or libssl.so.3 */;

static final MethodHandle sslCtxNewEx =
        linker.downcallHandle(ssl.find("SSL_CTX_new_ex").orElseThrow(),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

static final MethodHandle sslWrite =
        linker.downcallHandle(ssl.find("SSL_write").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));

static final MethodHandle sslRead =
        linker.downcallHandle(ssl.find("SSL_read").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
```

All `MethodHandle` instances are `static final` — the JIT constant-folds them, eliminating
virtual dispatch on the cipher hot-path. The `JAVA_LONG` layout choice (rather than `ADDRESS`) is
deliberate: it is what lets `wrap()`/`unwrap()` pass a raw address without the JVM constructing a
`MemorySegment` for the call, matching the zero-wrapper-object claim in Overview above.

### Protocol Floor

Every Community `SSL_CTX`, server and client, carries a minimum protocol version taken from
`CryptoProviderConfig#minimumTlsVersion`: `TLSv1.3` (the default and what the transport passes) or
`TLSv1.2`, applied through `SSL_CTX_ctrl(SSL_CTRL_SET_MIN_PROTO_VERSION)` before anything else is
set on the context. A peer that negotiates below the floor fails the handshake. Any other value, such
as `TLSv1.1`, `TLSv1.4`, `1.3` or a differently cased name, makes `createTlsEngine` and
`createClientTlsEngine` throw `CryptoBootstrapException` (`EX-NET-2002`) naming the value; nothing falls
back to OpenSSL's own floor. A booted kernel has no setting that lowers the floor: `crypto.tls.minVersion`
is not read (see [config.md](config.md)), so a Community listener refuses a client that speaks only
TLS 1.2 and a Community client refuses such a server.

### Client Peer Verification

A Community TLS client verifies the server it dials (ADR-074 §4, Amendment A1). The context is
`SSL_VERIFY_PEER`; the carrier opens one `X509_STORE` and hands it to each connection's context; the
engine expects the host of the authority it dialled. `CommunityKernelCryptoProvider#createClientTlsEngine`
builds that engine from a `CommunityTlsClientTrust` (opened by `openClientTrust`) and a
`TlsPeerIdentity`. An engine from `createTlsEngine` with a client configuration expects no peer, and
its `beginHandshake` refuses once bound (`EX-NET-2001`, detail `client engine has no expected peer
identity`).

**Identity.** `TlsPeerIdentity.of(host)` classifies without a DNS lookup. A bracketed host, or one
`InetAddress#ofLiteral` accepts, is an IP address — the bytes the dial connects to, IPv6 scope
dropped. Anything else is a DNS name: one trailing dot removed, lower-cased, ASCII, 1–253 characters
in labels of 1–63 letters, digits, hyphens or underscores (an internationalised name in its A-label
form). `OffHeapTlsEngine#expectPeer` applies it once, to an unbound client engine:

| Identity | Calls | Server name indication |
| :-- | :-- | :-- |
| DNS name | `X509_VERIFY_PARAM_set_hostflags(NO_PARTIAL_WILDCARDS \| NEVER_CHECK_SUBJECT)`, then `X509_VERIFY_PARAM_set1_host` with an explicit length | `SSL_ctrl(SSL_CTRL_SET_TLSEXT_HOSTNAME)` |
| IP address | `X509_VERIFY_PARAM_set1_ip` with the 4 or 16 address bytes | none |

The subject common name is never consulted, `f*.example.test` matches nothing, and an identity of
one kind never matches a subject alternative name of the other. A handshake that OpenSSL completes
with an `SSL_get_verify_result` other than `X509_V_OK` is refused anyway, so the engine never becomes
`ACTIVE` unverified, whatever the context's verify mode.

**Trust.** `crypto.tls.client.trustFile` names a PEM file that **replaces** OpenSSL's default trust
(`X509_STORE_load_file`). Unset, the store takes OpenSSL's default locations
(`X509_STORE_set_default_paths`), which `SSL_CERT_FILE` and `SSL_CERT_DIR` override; the carrier
reports the effective file and directory, and whether either exists. `X509_V_FLAG_PARTIAL_CHAIN` is
off: a chain must end at an anchor the trust holds, so a trust file holding only an intermediate does
not anchor a chain. No CRL or OCSP is consulted. The trust is read once per carrier, when the carrier
is built, and never reloaded: a changed file reaches only the carriers built after the change.

**Where it holds.** The transport arms a verifying client only where a crypto provider is bound when
the carrier is built and `exeris.transport.tls` is not `false` (for a `DUAL` carrier, only when its
listener holds certificate material); anywhere else the carrier dials plaintext, and its
`TransportTlsClientPosture` event says so ([transport.md](transport.md#client-tls)). A carrier's
owner may instead require plaintext, or require verified TLS, which refuses the carrier where TLS
cannot be armed rather than building it plaintext; the S3 blob client requires what its endpoint's
scheme names. No setting keeps TLS and skips verification.

**Server chains.** The Community server loads its certificate with `SSL_CTX_use_certificate_file`,
which takes the first certificate of the file, so it presents its leaf and no intermediates. A client
of a server whose certificate is chained holds those intermediates in its trust file, next to the
root.

**Symbols.** Bound by `CoreOpenSslLoader` into `CoreSslHandles.PeerVerificationHandles` and
`TrustStoreHandles`; every one is present, outside any deprecation guard, from OpenSSL 3.0 through
4.0. `size_t` is bound as `JAVA_LONG` and given an explicit length; C `long` uses the linker's
canonical layout, cast with `MethodHandles.explicitCastArguments` to a fixed `long` signature, so a
call site has one shape on LP64 and LLP64 (the LLP64 case has no CI).

| Group | Symbols |
| :-- | :-- |
| Peer verification | `SSL_CTX_set1_cert_store`, `SSL_get0_param`, `X509_VERIFY_PARAM_set1_host`, `X509_VERIFY_PARAM_set_hostflags`, `X509_VERIFY_PARAM_set1_ip`, `SSL_ctrl`, `SSL_get_verify_result`, `X509_verify_cert_error_string` |
| Trust store | `X509_STORE_new`, `X509_STORE_free`, `X509_STORE_set_default_paths`, `X509_STORE_load_file`, `X509_get_default_cert_file`, `X509_get_default_cert_dir`, `X509_get_default_cert_file_env`, `X509_get_default_cert_dir_env` |
| Error queue | `ERR_clear_error` |

`SSL_set1_host` (deprecated in 4.0), `SSL_set1_dnsname`/`SSL_set1_ipaddr` (4.0 only) and
`X509_VERIFY_PARAM_set1_ip_asc` (its address parser differs from the JDK's) are deliberately not bound.

**Ownership.**

- The `X509_STORE` belongs to `CommunityTlsClientTrust`: its creator holds one reference, and each
  context takes its own through `SSL_CTX_set1_cert_store`, so a context outlives the trust's
  `close()`. The trust is handed over under a lease (`retainStore`/`release`, CAS-counted like
  `NativeCipherContext`); a `close()` while a lease is out defers `X509_STORE_free` to the last
  release, and a lease taken after `close()` is refused. The carrier closes its trust in `close()`.
- The `X509_VERIFY_PARAM` from `SSL_get0_param` is the session's own: borrowed, never freed.
- The host name and address bytes are staged in an `allocateInfrastructure` buffer released before
  `expectPeer` returns; OpenSSL copies them.
- The store's certificates live on OpenSSL's heap, which `WatermarkManager` does not track. A
  default-trust store parses the whole system bundle, once per verifying carrier.

**Error queue.** OpenSSL keeps its error queue per OS thread, and `SSL_get_error` reports
`SSL_ERROR_SSL` whenever that queue holds an entry, whichever connection left it. A completed
handshake, and every outcome of `beginHandshake`, `unwrap`, `wrap`, `initiateShutdown` and
`bindTransportFd` that fails or ends the session, empties the calling thread's queue: after
`SSL_get_error` where the step reads it and, for a client with an expected peer,
`SSL_get_verify_result`, and before any event or exception. No park point lies between those reads
and the clear: a virtual thread that unmounted there would clear one carrier's queue and leave
another's entry behind. A failed context or trust load clears the queue too. A
`WANT_READ`/`WANT_WRITE` retry and every other success make no extra downcall.

**Failure.** A failed handshake leaves its `SSL_get_error` code and `X509_V_*` result on
`TlsHandshakeFailureCodes`; the Community stream turns them into `TlsHandshakeException` for the
caller (see [Error Codes](#error-codes)).

### Per-Packet Encrypt Path (Zero Allocation)

`OffHeapTlsEngine.wrap()` below is the actual Core implementation. Note two things that a
generic sketch would get wrong: it returns a `TlsStatus`, not `void`, and — in the Community
fd-owner BIO mode described under "Operator Notes" below — `SSL_write` pushes bytes straight into
the kernel socket buffer, so `ciphertext` is never written and `ciphertext.setSize(...)` is never
called; only the Enterprise Memory-BIO mode drains ciphertext into the caller's buffer, and it does
so itself, outside this method. See the SPI Contract section for the full buffer-ownership split.

```
Transport layer calls:
  tlsEngine.wrap(plaintext: LoanedBuffer, ciphertext: LoanedBuffer)

  1. plaintext.segment().address() → raw long (no MemorySegment wrapper on this call)
  2. SSL_write(ssl_ptr, srcAddr, plaintext.size())
       fd-owner BIO (Community): writes straight to the kernel socket buffer; ciphertext untouched
       Memory-BIO (Enterprise):  writes into the write-BIO; caller drains it into ciphertext after return
  3. Return TlsStatus.OK on success — zero heap objects created
```

```java
@Override
public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
    checkNotClosed();
    checkActive();

    long sslPtr = cipherCtx.retainSslPointer();
    try {
        long srcAddr = plaintext.segment().address();
        int  len     = (int) Math.min(plaintext.size(), Integer.MAX_VALUE);

        int bytesWritten = handles.ioHandles().invokeWrite(sslPtr, srcAddr, len);
        if (bytesWritten > 0) {
            return TlsStatus.OK;
        }

        int sslErr = handles.ioHandles().invokeGetError(sslPtr, bytesWritten);
        return mapSslError(sslErr); // NEED_WRAP / NEED_UNWRAP, or CLOSED — see mapSslError()
    } finally {
        cipherCtx.release();
    }
}
```

The guard checks above (`checkNotClosed()`, `checkActive()`) are what throw
`TlsHandshakeException` (`EX-NET-2001`) — via a pre-allocated, stack-trace-free sentinel instance,
not a freshly constructed exception — when `wrap()` is called on a closed engine or one that has
not completed its handshake.

### Per-Packet Decrypt Path (Zero Allocation)

`unwrap()` mirrors `wrap()`, with the sentinel exception type swapped to `TlsDecryptException`
(`EX-NET-2003`) so a Glass-Box decoder can tell the two directions apart without parsing the
message string:

```java
@Override
public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
    checkNotClosedForDecrypt();
    checkActiveForDecrypt();

    long sslPtr = cipherCtx.retainSslPointer();
    try {
        long dstAddr = plaintext.segment().address();
        int  maxLen  = (int) Math.min(plaintext.segment().byteSize(), Integer.MAX_VALUE);

        int bytesRead = handles.ioHandles().invokeRead(sslPtr, dstAddr, maxLen);
        if (bytesRead > 0) {
            plaintext.setSize(bytesRead);
            return TlsStatus.OK;
        }

        int sslErr = handles.ioHandles().invokeGetError(sslPtr, bytesRead);
        if (sslErr == CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN) {
            return TlsStatus.CLOSED; // clean peer close_notify
        }
        return mapSslError(sslErr);
    } finally {
        cipherCtx.release();
    }
}
```

Both methods bracket the native downcall with `cipherCtx.retainSslPointer()` /
`cipherCtx.release()` — see NativeCipherContext Lifecycle below — so the `SSL*` pointer cannot be
freed by a concurrent `close()` while a downcall is in flight.

---

## NativeCipherContext Lifecycle

The `NativeCipherContext` wraps a single `SSL*` pointer returned by `SSL_new()`.
It is allocated **once per TLS session** — not per record. The `SSL*` struct itself lives on
OpenSSL's own native heap, outside the Kernel's accounting; what `NativeCipherContext` obtains from
the injected `MemoryAllocator` is a separate `AllocationHint.SESSION` `LoanedBuffer` used purely to
make that session's off-heap footprint visible to `WatermarkManager` for backpressure — it is not
the memory `SSL_new` writes into. Never a directly opened `Arena` either way.

`NativeCipherContext` runs its **own** `VarHandle`-CAS reference count (a plain `int` field, base
value 1 at construction) — distinct from the `LoanedBuffer`'s own ref-count, which is never
`retain()`-ed here. Every call that touches the raw `sslPtr` — `wrap()`, `unwrap()`,
`beginHandshake()`, `initiateShutdown()`, `bindTransportFd()` — brackets its downcall with
`retainSslPointer()` (count+1) and `release()` (count-1) in a `finally` block, so the pointer
cannot be freed by a concurrent `close()` mid-downcall. The diagram below shows that per-call
retain/release cycle repeating across the hot loop — not a single retain held for the session.

```mermaid
sequenceDiagram
    participant Alloc as MemoryAllocator
    participant NCC as NativeCipherContext
    participant Trans as Transport (Hot Path)

    Note over Alloc,NCC: Session Start
    Alloc->>NCC: allocate(SESSION)<br/>sessionSlab (accounting only, tracked by WatermarkManager)
    NCC->>NCC: SSL_new(sslCtxPtr) → sslPtr<br/>refCount = 1 (base reference)

    Note over NCC,Trans: Per-Packet Loop (Zero Allocation)
    loop N times — wrap() / unwrap()
        Trans->>NCC: retainSslPointer() → refCount+1
        Trans->>NCC: SSL_write()/SSL_read() downcall<br/>EX-NET-2001 (wrap) / EX-NET-2003 (unwrap) on a closed/inactive engine
        Trans->>NCC: release() → refCount-1
    end

    Note over Alloc,NCC: Session End
    Trans->>NCC: close() → release() → refCount: 1 → 0
    NCC->>NCC: SSL_free(sslPtr)<br/>failure recorded via NativeCipherContextFreeFailureEvent, never thrown
    NCC->>Alloc: sessionSlab.close()<br/>slab returned to MemoryAllocator pool
```

> **RAII Invariant:** `NativeCipherContext` is the **sole** ref-count authority for both the
> `sslPtr` lifetime and the session slab's return to the pool. Transport code calls
> `wrap()`/`unwrap()`, which retain/release around each downcall — it borrows, not owns.

```
Session Start:
  allocator.allocate(AllocationHint.SESSION) → sessionSlab (LoanedBuffer, accounting-only, tracked by WatermarkManager)
  SSL_new(sslCtxPtr) → sslPtr   (opaque native pointer; struct itself lives on OpenSSL's own heap)
  refCount = 1 (BASE_REF_COUNT, set at construction — no explicit retain() call here)

Per-Record (each wrap()/unwrap() call):
  retainSslPointer() → refCount+1 → SSL_write()/SSL_read() → release() → refCount-1
  Net allocation: ZERO — no MemorySegment wrapper, no heap object

Session End:
  close() → release() → refCount: 1 → 0
    → SSL_free(sslPtr) invoked; a failure is recorded via NativeCipherContextFreeFailureEvent, never thrown
    → sessionSlab.close() → returned to MemoryAllocator pool
  If another virtual thread still holds a retain when close() runs, the actual SSL_free/slab-close
  is deferred until that thread's release() brings the count to zero — close() from a
  try-with-resources block is safe even while a downcall is in flight.
```

### Arena Discipline

| Object                    | Memory Owner                         | Lifecycle Authority                                    |
|:--------------------------|:--------------------------------------|:--------------------------------------------------------|
| `SSL_CTX` per engine      | OpenSSL's own native heap            | The Community engine built with it — `CommunityKernelCryptoProvider` creates one per engine, so one per connection, and `CommunityTlsEngine#close` frees it |
| `X509_STORE` per verifying carrier | OpenSSL's own native heap, not tracked by `WatermarkManager` | `CommunityTlsClientTrust`'s reference plus one per `SSL_CTX` it was handed to (see Client Peer Verification) |
| `SSL*` per session        | OpenSSL's own native heap; accounted via a `MemoryAllocator` (SESSION hint) slab | `NativeCipherContext`'s own `VarHandle`-CAS ref-count |
| Plaintext `LoanedBuffer`  | `MemoryAllocator` (carrier slab)     | Transport pipeline (RAII)                              |
| Ciphertext `LoanedBuffer` | `MemoryAllocator` (network slab)     | Transport pipeline (RAII)                              |

**Rule:** Business logic code MUST NEVER hold a reference to a `NativeCipherContext` or any
`MemorySegment` beyond the scope of a single `wrap()`/`unwrap()` call.

---

## SPI Contract (The Wall)

```java
public interface TlsEngine extends AutoCloseable {
    default void notifyBound() {}
    TlsStatus beginHandshake(LoanedBuffer outbound);
    TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext);
    TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext);
    boolean isHandshakeComplete();
    String negotiatedProtocol();
    void initiateShutdown(LoanedBuffer outbound);

    @Override
    void close();
}
```

The SPI has zero knowledge of OpenSSL, JSSE, BouncyCastle, or Panama internals.
`notifyBound()` is a default no-op; fd-owner or Memory-BIO pipelines override. Triggers `TlsEngineBindEvent` emission and state-machine transition.
`TlsEngine` implementations are not themselves discovered via `ServiceLoader`: the
`KernelCryptoProvider` that constructs them is (`META-INF/services/eu.exeris.kernel.spi.crypto.KernelCryptoProvider`
lists `CommunityKernelCryptoProvider`). Its `createTlsEngine()` builds a `CommunityTlsEngine`
(which wraps `OffHeapTlsEngine`) directly with `new` — the SPI module never imports either
concrete class.

---

### Code Example: Session Context via MemoryAllocator

```java
public OffHeapTlsEngine(CoreSslHandles handles, long ctxPointer,
                        boolean serverMode, MemoryAllocator allocator) {
    // MemoryExhaustedException (EX-MEM-1001) propagates to caller if budget exceeded
    this.cipherCtx = new NativeCipherContext(handles, ctxPointer, allocator);
    this.stateMachine = new TlsStateMachine();
    this.serverMode = serverMode;
}
```

> `allocator.allocate()` is tracked by `WatermarkManager`. If the off-heap budget is exhausted,
> it throws `MemoryExhaustedException(EX-MEM-1001)` before any native memory is touched —
> this is the backpressure integration point between Crypto (L1) and Memory (L0).

---

## Operator Notes — FD Resolution on Restricted JDKs

The Community TLS pipeline binds an OpenSSL BIO to the underlying socket file descriptor through
`SocketChannelFdAccess.requireFd(channel)` at `CommunityTlsEngine.bindFileDescriptor(...)` time.
On open JDK builds, FD resolution uses reflective access to `sun.nio.ch.SocketChannelImpl` and
`java.io.FileDescriptor`. On JDKs that close those internals (e.g., distributions with strict
`--illegal-access=deny`, modular runtime images that omit the relevant exports), reflective FD
extraction fails with a clear diagnostic.

Two operator-side resolutions exist:

1. **Add JVM flags at startup** — pass:

   ```text
   --add-opens java.base/sun.nio.ch=ALL-UNNAMED
   --add-opens java.base/java.io=ALL-UNNAMED
   ```

   This restores reflective FD access without code changes and is the recommended path for hosts
   the operator controls.

   **These flags have a transport-side consequence.** The same
   `SocketChannelFdAccess.isRuntimeFdAccessAvailable()` probe that gates TLS FD binding is one of
   the three conditions `NativeTcpSocketBackend` requires before it arms the POSIX socket seam, so
   passing them also moves plain-TCP data I/O off NIO and onto `recv`/`send` through Panama FFM.
   The selector and accept path stay on NIO either way. See `docs/subsystems/transport.md`.
2. **Use the explicit FD entry point** — call `CommunityTlsEngine.bindFileDescriptor(int)` directly
   from your transport carrier with an FD obtained outside the closed reflection path (e.g., from
   a native socket library or from a pre-opened descriptor). The reference Community carrier
   already exposes this fallback via `NativeTcpCarrier` / `NativeTcpStream`, and embedders that
   build their own carrier should mirror the contract.

Either path keeps the SPI surface unchanged — `TlsEngine` does not expose JVM internals, and the
fallback is a Community implementation detail.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code          | Path          | Meaning                            | Glass-Box Payload (`rawArgs`)                        |
|:--------------|:--------------|:-----------------------------------|:-----------------------------------------------------|
| `EX-NET-2001` | **wrap** (encrypt), handshake | `SSL_write` failure / BIO error; a failed or refused handshake | `[0] int nativeErrorCode, [1] String detail`    |
| `EX-NET-2002` | Bootstrap     | Crypto Provider init failure       | `[0] String providerName, [1] String reason`         |
| `EX-NET-2003` | **unwrap** (decrypt) | `SSL_read` failure / alert received | `[0] int nativeErrorCode, [1] String detail` |

The split between `EX-NET-2001` and `EX-NET-2003` preserves the **one-code-one-schema invariant**
required by the binary Glass-Box telemetry contract: decoders can distinguish encrypt-side from
decrypt-side failures without parsing the `detail` string.

A client handshake refusal carries one of the fixed `detail` strings in `TlsFailureDetail`, and
`[0]` holds what that detail names:

| `detail` (`TlsFailureDetail`) | `rawArgs[0]` |
| :-- | :-- |
| `peer certificate verification failed` (`PEER_VERIFICATION_FAILED`) | the `X509_V_*` code, e.g. `18` self-signed, `20` untrusted issuer, `62` host mismatch, `64` IP mismatch |
| `handshake failed` (`HANDSHAKE_FAILED`) | the `SSL_get_error` code |
| `peer identity rejected` (`PEER_IDENTITY_REJECTED`) | `-1` |
| `authority host is neither a DNS name nor an IP literal` (`INVALID_PEER_NAME`) | `-1` |
| `client engine has no expected peer identity` (`NO_PEER_IDENTITY`) | `-1` |
| `bound crypto provider cannot verify an outbound peer` (`NO_PEER_VERIFIER`) | `-1` |

Where each surfaces:

- `INVALID_PEER_NAME` and `NO_PEER_VERIFIER` reach a caller of `TransportEngine#connect` as the
  cause of `TransportException` `EX-NET-4001`, before any socket opens.
- `PEER_IDENTITY_REJECTED` is thrown by `OffHeapTlsEngine#expectPeer`, and so by
  `createClientTlsEngine`, when OpenSSL refuses an identity that `TlsPeerIdentity` accepted. On a
  carrier, `NativeTcpCarrier#connect` builds the engine after the socket connects; it closes the
  socket and throws that `TlsHandshakeException` unwrapped, not as the cause of `EX-NET-4001`.
- `PEER_VERIFICATION_FAILED` and `HANDSHAKE_FAILED` reach the stream's first read or write, and
  every one after it.
- `NO_PEER_IDENTITY` never reaches a carrier stream: it is thrown by `beginHandshake` of a client
  engine from `CommunityKernelCryptoProvider#createTlsEngine`, which names no peer, while a carrier
  builds its outbound engines through `createClientTlsEngine`.

---

## JFR Events

> **Note:** The previous table in this section contained incorrect field names (e.g., `sessionId` does not exist; the actual field is `sslPtr`). The table below reflects actual implementation.

The headings below name each group's JFR event-name prefix (the `@Name` annotation's leading
segments), not the Java package the class file lives in — every Core class in both tables actually
lives under `eu.exeris.kernel.core.crypto.*` (`.openssl` / `.tls` subpackages), and the Community
pair lives under `eu.exeris.kernel.community.crypto`.

**Core — `eu.exeris.kernel.core.crypto` JFR namespace (`eu.exeris.kernel.core.crypto.openssl`):**

| Event Class                           | JFR Category                                              | When Emitted                              | Key Fields                        |
|:--------------------------------------|:----------------------------------------------------------|:------------------------------------------|:----------------------------------|
| `CryptoContextAllocEvent`             | `eu.exeris.kernel.core.crypto.ContextAlloc`                | `NativeCipherContext` creation            | `sslPtr`, `sslCtxPtr`             |
| `NativeCipherContextFreeFailureEvent` | `eu.exeris.kernel.core.crypto.NativeCipherContextFreeFailure` | `SSL_free` failure                    | `sslPtr`, `exceptionClass`        |
| `OpenSslLoadEvent`                    | `eu.exeris.kernel.core.crypto.OpenSslLoad`                 | `CoreOpenSslLoader.load()` success (once, cold bootstrap path) | `versionMajor`, `versionMinor`, `sslPath`, `cryptoPath` |

**Core — `eu.exeris.kernel.tls` JFR namespace (`eu.exeris.kernel.core.crypto.tls`):**

| Event Class                | JFR Category                                         | When Emitted                     | Key Fields                                  |
|:---------------------------|:-----------------------------------------------------|:---------------------------------|:--------------------------------------------|
| `TlsHandshakeEvent`        | `eu.exeris.kernel.tls.Handshake`                     | Handshake start and completion   | `sslPtr`, `mode`, `protocol`, `cipher`, `negotiatedAlpn`, `durationNanos` |
| `TlsHandshakeFailureEvent` | `eu.exeris.kernel.tls.HandshakeFailure`              | Handshake step failed, or a completed handshake refused because verification failed | `sslPtr`, `mode`, `errorCode` (`EX-NET-2001`), `failureReason`, `sslErrorCode`, `verifyResult` (`X509_V_*` for a client that expected a peer, else `-1`) |
| `TlsEngineBindEvent`       | `eu.exeris.kernel.tls.EngineBind`                    | `notifyBound()` call             | `sslPtr`, `mode`                            |
| `TlsEngineCloseEvent`      | `eu.exeris.kernel.tls.EngineClose`                   | `close()` call                   | `sslPtr`, `graceful`, `finalPhase`          |
| `TlsPhaseTransitionEvent`  | `eu.exeris.kernel.tls.PhaseTransition`               | Per state-machine transition     | `fromPhase`, `toPhase`                      |

**Community — `eu.exeris.kernel.crypto` JFR namespace (`eu.exeris.kernel.community.crypto`):**

| Event Class                       | JFR Category                                                   | When Emitted                            | Key Fields |
|:-----------------------------------|:---------------------------------------------------------------|:----------------------------------------|:-----------|
| `CommunityProviderBootstrapEvent` | `eu.exeris.kernel.crypto.CommunityProviderBootstrap`           | Provider creates engine                 | `providerName`, `durationNs` |
| `CommunityTlsHandshakeEvent`      | `eu.exeris.kernel.crypto.CommunityTlsHandshake`                | Per `beginHandshake()` in Community     | `complete`, `opensslError` |

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

- `OffHeapTlsEngineTest` — lifecycle, guard checks, state machine integration, idempotent close.
  - `notifyBound()` transitions `UNINITIALIZED → HANDSHAKE_IN_PROGRESS`; double-call throws.
  - `beginHandshake()` before `notifyBound()` MUST throw `TlsHandshakeException`.
  - `unwrap()` before handshake / after close MUST throw `TlsDecryptException` (`EX-NET-2003`).
  - `wrap()` before handshake / after close MUST throw `TlsHandshakeException` (`EX-NET-2001`).
- `NativeCipherContextTest` — `retainSslPointer()` increments the context's own ref-count,
  `release()`/`close()` decrement it, `SSL_free` fires exactly once when the count reaches zero, and
  a retain on an already-closed context throws `IllegalStateException`.
- `wrap()`/`unwrap()` round-trip with known plaintext/ciphertext vectors.

### Integration Tests

`*IT` classes are executed by `maven-failsafe-plugin` (bound to `integration-test` + `verify` phases
in the respective module's `pom.xml`) — they are NOT picked up by Surefire. Run with `mvn verify`
or `mvn install`. OpenSSL 3.x or 4.x must be present on the CI host for Linux targets.

- `OffHeapTlsEngineLoopbackIT` (`exeris-kernel-core/src/test/java/eu/exeris/kernel/core/crypto/tls/`):
  full TLS 1.3 handshake and round-trip against `OffHeapTlsEngine` directly, over a real
  `ServerSocketChannel`/`SocketChannel` loopback pair with a self-signed cert generated by the
  `openssl` CLI. Includes `sessionSlabAllocatedViaMemoryAllocator`, which proves
  `NativeCipherContext` calls `allocator.allocate(AllocationHint.SESSION)`, making session memory
  visible to `WatermarkManager` for `EX-MEM-1001` backpressure — and a fatal-handshake case (non-TLS
  bytes on the socket) asserting `beginHandshake()` returns `CLOSED` and the engine forces itself
  to `ERROR` so re-entry fails fast rather than re-driving `SSL_accept`.
- `CommunityTlsEngineLoopbackIntegrationTest` (`exeris-kernel-community/src/test/java/eu/exeris/kernel/community/crypto/`, `@Tag("integration")`, `@EnabledOnOs(OS.LINUX)`):
  - Simulates Community tier: `SSL_set_fd` resolved as a separate Community-owned handle,
    called before `notifyBound()` — Core engine has zero knowledge of the fd.
  - Full TLS 1.3 handshake over a real `ServerSocketChannel`/`SocketChannel` loopback pair
    with a certificate `TlsTestCertificate` generates per run, which the client engine
    (`createClientTlsEngine`, expecting `127.0.0.1`) trusts — both engines reach `ACTIVE`.
  - Round-trip: full round-trip in both directions matches for a 512-byte payload.
  - Asserts the `CommunityProviderBootstrap` and `CommunityTlsHandshake` JFR events (see JFR Events
    above) are emitted on bootstrap and on a successful/failed handshake.

### Client Peer Verification and the Error Queue

- `OffHeapTlsEngineErrorQueueTest` (Core, stub handles): each step's failure, a read that ends the
  session and a completed handshake clear the queue once, after `SSL_get_error` where the step reads
  it; a retry and every other success make no clear.
- `OffHeapTlsEngineErrorQueueIT` and `OffHeapTlsEnginePeerVerificationIT` (Core, Failsafe; they fail
  rather than skip when OpenSSL cannot be loaded): the failing thread's queue is empty
  (`ERR_peek_error`) on every major; which certificates a client accepts, which it refuses with which
  `X509_V_*` code, and what server name it sends.
- `TlsPeerIdentityTest`: how an authority host is classified, and which hosts are refused.
- `CommunityTlsClientTrustTest`: the store lease — a `close()` with a lease out frees nothing until
  the release, and a lease after `close()` is refused.
- `CommunityTlsServerErrorQueueTest`: a failed handshake on a listener's reactor does not close the
  healthy connections that reactor serves (on the 3.x line, where the stale entry did).
- `CommunityTlsPeerVerificationTest` (real carriers): the refusal codes through the stream, a read
  that reports the refusal, a host refused before the dial, a `DUAL` carrier dialling as a client, and
  the plain client engine refusing its handshake.
- `CommunityTlsDefaultTrustTest`: OpenSSL's default trust, and a configured file replacing it. It runs
  only in the forked `default-trust` Surefire execution, which sets `SSL_CERT_FILE` and `SSL_CERT_DIR`
  (`mvn -pl exeris-kernel-community surefire:test@default-trust`).
- `AbstractHttpClientTlsPeerVerificationTck`, bound by `CommunityHttpClientTlsPeerVerificationTckTest`:
  the contract at the HTTP client ([http.md](http.md)).

Every suite above runs in the default build, against the runner's own OpenSSL:
`CommunityTlsDefaultTrustTest` in its fork, which is bound to the `test` phase beside the module's
other Surefire executions. The `tls-openssl-matrix` CI job runs the ones that load OpenSSL — the two
Core ITs, the Community carrier suites, the `default-trust` fork and the TCK binding — on each
pinned OpenSSL major, and fails an entry for a listed suite that has no report, ran no test or
skipped one.

### Integration Tests (TCK)

- `CryptoZeroAllocTck` (`hotPathIterations() = 10_000`, preceded by `1_000` warmup iterations):
  the base TCK's default allocation ceiling is ≤ 8 `eu.exeris.*` allocations/iteration; a binding
  overrides `supportsZeroGcHotPath()` to `true` to tighten that to 0 B/op. The one binding that
  exists today, `CoreOffHeapTlsEngineZeroAllocTckTest`, does the latter — but it
  measures the **guard/sentinel failure path** (`wrap()` on an engine bound to a non-existent FD,
  which fails fast through a pre-allocated exception), not a successful steady-state `SSL_write`.
  The successful-write path's allocation profile is not separately gated by this TCK; correctness of
  that path (not its allocation count) is what `OffHeapTlsEngineLoopbackIT` and the Community/
  Enterprise integration tests cover.
- `AbstractCryptoEngineTck` "Error-code:"-prefixed tests (there is no separate `ErrorCodeContract`
  nested class — these are flat `@Test` methods on the abstract TCK class):
  - `unwrap()` on a closed engine MUST throw `TlsDecryptException` (`EX-NET-2003`).
  - `wrap()` and `unwrap()` MUST throw distinct exception types (one-code-one-schema invariant).
- `MemoryExhaustedException(EX-MEM-1001)` is thrown before native allocation when
  `WatermarkManager` reports high watermark breach.
- `NativeCipherContext` lifecycle: `SSL*` pointer freed when `NativeCipherContext`'s own ref-count
  (not the session `LoanedBuffer`'s) reaches zero.
- **`CryptoCarrierPinningTck`** — verifies no carrier thread pinning during `wrap()`/`unwrap()` operations.

> **TCK gap:** No Community binding exists for `CryptoZeroAllocTck` or `CryptoCarrierPinningTck` —
> both are bound only against `OffHeapTlsEngine` directly, in `exeris-kernel-core/src/test/`
> (`CoreOffHeapTlsEngineZeroAllocTckTest`, `CoreOffHeapTlsEngineCarrierPinningTckTest`). `Community`
> is bound only to `AbstractCryptoEngineTck`, via `CommunityKernelCryptoProviderTckTest`. As of
> current state, Community-tier zero-allocation on the TLS hot path (per ADR-008) is documented but
> not TCK-enforced at the Community binding level.

---

## Owning ADRs

- [ADR-008](../adr/ADR-008-open-core-strategy-and-commoditization-of-off-heap-tls.md) — Open-Core Strategy & Commoditization of Off-Heap TLS
- [ADR-074](../adr/ADR-074-http-client-peer-addressing.md) §4, Amendment A1 — a TLS client verifies its server against the effective authority

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.crypto.*`) is classified **preview** in the
[SPI Stability Matrix](../stability-matrix.md). The OpenSSL 3.0–4.x binding migration itself already
landed (v0.9 Sprint 4b, per ADR-008's repository-state note: `SSL_CTX_new_ex`, `.so.4` candidates,
version band widened to major 3–4, floor kept at 3.0.0 for FIPS-validated 3.1.2 builds); the
preview classification instead reflects that a FIPS provider workstream remains deferred and
unscheduled, so the binding/ABI surface may still move if and when that workstream lands. See the
matrix for the semver policy and TCK coverage status.
