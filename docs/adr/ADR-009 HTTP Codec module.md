# ADR-009: HTTP Codec Module (`exeris-kernel-http`)

| Attribute      | Value                                                          |
|:---------------|:---------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                   |
| **Deciders**   | Arkadiusz Przychocki                                           |
| **Date**       | 2026-03-10                                                     |
| **Driven By**  | RFC-2026-03-10: HTTP Codec Placement & Open-Core Boundary      |
| **Compliance** | [Strategic Pillar: Open-Core Adoption & Glass-Box](../whitepaper.md) |

## Context and Problem Statement

The v0.5 roadmap requires a functional Community transport tier serving
real HTTP/1.1 and HTTP/2 traffic. This creates an immediate placement question for the HTTP
codec layer: HPACK (RFC 7541), HTTP/2 framing (RFC 7540), and HTTP/1.1 parsing (RFC 9112).

Three placements were considered:

**Option A — `exeris-kernel-core`:** Core is currently defined as protocol-agnostic
infrastructure: PAQS scheduler, LoadShedder, PinningMonitor. HPACK is a Layer 7 codec.
Placing application-layer protocol logic in Core violates the layer contract and begins
a semantic drift that, over time, makes Core's responsibility impossible to describe in
a single sentence.

**Option B — `exeris-kernel-community`:** HPACK is needed by Enterprise too — specifically
for HTTP/2 fallback when a client does not support QUIC.
Placing the codec in Community would force Enterprise to depend on Community, inverting the
dependency hierarchy and breaching The Wall.

None of the existing modules can absorb the HTTP codec without violating their stated contracts.
A dedicated module is required.

---

## 🏁 The Decision

We introduce **`exeris-kernel-http`** as a new, standalone, open-source module (Apache 2.0 +
Commons Clause) containing all HTTP codec infrastructure shared between Community and Enterprise.

### Module Responsibility

`exeris-kernel-http` is the single implementation of the HTTP wire format. It owns:

- Encoding and decoding of HTTP header blocks (HPACK, RFC 7541)
- HTTP/2 frame serialisation and deserialisation (RFC 7540)
- HTTP/2 connection and stream-level flow control (RFC 7540 §5.2)
- HTTP/1.1 request parsing and response encoding (RFC 9112)
- HTTP/1.1 chunked transfer encoding (RFC 9112 §7.1)

It does **not** own:

- Connection lifecycle or stream multiplexing (owned by transport layer)
- TLS integration (owned by `exeris-kernel-core` / `CommunityTlsEngine` per ADR-008)
- QUIC stream binding or QPACK (owned exclusively by `exeris-kernel-enterprise`)
- I/O scheduling or carrier thread management (owned by `exeris-kernel-core`)

### Package Layout

```
eu.exeris.kernel.http
  hpack/
    huffman/
      Huffman.java                ← RFC 7541 Appendix B encoder/decoder
      HuffmanTable.java           ← Pre-computed nibble FSM, 32 KB static int[]
    HpackDecoder.java             ← RFC 7541 §3 header block decoder
    HpackEncoder.java             ← RFC 7541 §6 header block encoder
    HpackDynamicTable.java        ← RFC 7541 §4 FIFO dynamic table
    HpackStaticTable.java         ← RFC 7541 Appendix A, 61 entries
  http2/
    Http2FrameParser.java         ← Wire → FrameHeader (zero-copy)
    Http2FrameEncoder.java        ← FrameHeader → wire
    Http2FrameCodec.java          ← Façade: parse + validate + write helpers
    Http2FrameType.java           ← RFC 7540 §4 frame type enum
    Http2ErrorCode.java           ← RFC 7540 §7 error code enum
    Http2StreamState.java         ← RFC 7540 §5.1 stream state machine
    Http2Settings.java            ← RFC 7540 §6.5 settings record
    Http2FlowController.java      ← RFC 7540 §5.2 window management
  http1/
    Http1RequestParser.java       ← RFC 9112 request-line + header parser
    Http1ResponseEncoder.java     ← RFC 9112 status-line + header encoder
    Http1ChunkedEncoder.java      ← RFC 9112 §7.1 chunked transfer encoder
    Http1Codec.java               ← Connection-level coordinator (planned)
```

### QPACK Boundary — Enterprise Exclusivity

QPACK (RFC 9204) is **explicitly excluded** from `exeris-kernel-http` and remains in
`exeris-kernel-enterprise`. This is not an arbitrary decision — it is a structural consequence
of how QPACK operates:

HPACK operates on a self-contained header block per request/response. It has no knowledge
of the transport beneath it. QPACK, by contrast, is deeply entangled with QUIC stream semantics:
`QpackDynamicTablePool` holds per-connection encoder/decoder state keyed on QUIC stream IDs
(RFC 9000), `StreamParkerPool` manages blocked streams awaiting header acknowledgement from the
QPACK decoder stream, and `QuicBioMultiplexer` (ADR-008) is the I/O boundary through which
all QPACK-compressed header blocks travel.

QPACK cannot be separated from the QUIC stream lifecycle without breaking the protocol. It
belongs in Enterprise because it **is** Enterprise — it only exists where QUIC exists.

```
exeris-kernel-http
  HPACK    ← transport-agnostic: runs over TCP, QUIC, or anything

exeris-kernel-enterprise
  QPACK    ← QUIC-entangled: QpackDynamicTablePool + StreamParkerPool
             operates on QUIC stream IDs, cannot be decoupled
```

### Dependency Graph

```
exeris-kernel-spi
  └── exeris-kernel-core          (PAQS, LoadShedder, PinningMonitor — protocol-agnostic)
        └── exeris-kernel-http    (HTTP/1.1 + HTTP/2 + HPACK — transport-agnostic)
              ├── exeris-kernel-community   (NIO TCP + HTTP/2 via exeris-kernel-http)
              └── exeris-kernel-enterprise  (io_uring + QUIC; imports HPACK, owns QPACK)
```

`exeris-kernel-http` depends only on `exeris-kernel-spi` — specifically `LoanedBuffer`,
`MemoryAllocator`, and `AllocationHint` for Huffman scratch allocation. It does not depend
on Core. The dependency chain is strictly linear: SPI → Core → HTTP → Community/Enterprise.

### Hot-Path Memory Contract

`exeris-kernel-http` maintains the Zero-Waste Compute covenant on all codec hot paths:

| Path                         | Allocation behaviour                                      |
|:-----------------------------|:----------------------------------------------------------|
| `HuffmanTable.getEntry()`    | Zero — packed `int` lookup in static `int[]`              |
| `Huffman.decode()`           | Zero — operates on caller-provided `MemorySegment`        |
| `Huffman.encode()`           | Zero — operates on caller-provided `MemorySegment`        |
| `HpackDecoder.decode()`      | One `LoanedBuffer` per Huffman-encoded string literal     |
| `HpackEncoder.encodeHeader()`| One `LoanedBuffer` when Huffman encoding is applied       |
| `Http2FrameParser`           | Zero — `FrameHeader` record; JIT-scalarised on hot path   |
| `Http2FrameEncoder`          | Zero — writes directly into caller `MemorySegment`        |
| `Http1RequestParser`         | `String` per header field — unavoidable for HTTP/1.1      |
| `Http1ResponseEncoder`       | `byte[]` per `String.getBytes()` — response path only     |

HTTP/1.1 allocation on the String layer is an accepted trade-off. HTTP/1.1 is a text protocol;
zero-allocation parsing requires a zero-copy string abstraction (`CharSequence` over `MemorySegment`)
that is out of scope for v0.5. This is a known, bounded cost — not an architectural gap.

---

## Open-Core Boundary (Summary)

| Capability                            | SPI | Core | HTTP | Community | Enterprise |
|:--------------------------------------|:---:|:----:|:----:|:---------:|:----------:|
| `LoanedBuffer` / `MemoryAllocator`    | ✅  |      |      |           |            |
| PAQS scheduler / LoadShedder          |     | ✅   |      |           |            |
| HPACK encoder/decoder (RFC 7541)      |     |      | ✅   |           |            |
| HTTP/2 frame codec (RFC 7540)         |     |      | ✅   |           |            |
| HTTP/1.1 parser + encoder (RFC 9112)  |     |      | ✅   |           |            |
| HTTP/2 flow control                   |     |      | ✅   |           |            |
| NIO TCP transport + HTTP/2            |     |      |      | ✅        |            |
| TLS 1.3 over TCP (off-heap)           |     |      |      | ✅        |            |
| QPACK encoder/decoder (RFC 9204)      |     |      |      |           | ✅         |
| `io_uring` TCP / QUIC transport       |     |      |      |           | ✅         |
| `BIO_s_dgram_pair` / QUIC BIO pair    |     |      |      |           | ✅         |

---

## Consequences

### ✅ Positive Outcomes

* **[+] The Wall holds.** Core remains protocol-agnostic. The introduction of `exeris-kernel-http`
  absorbs the L7 codec surface without polluting any existing module's stated contract. Each module
  can still be described in one sentence.

* **[+] No duplication between tiers.** Enterprise uses the same `HpackDecoder`, `Http2FrameParser`,
  and `Http2FlowController` as Community for HTTP/2 fallback traffic. A security fix or RFC
  compliance patch in `exeris-kernel-http` propagates to both tiers simultaneously.

* **[+] Community transport unblocked for v0.5.** `NioTransportEngine` now has all codec
  primitives it needs: `Http1RequestParser` for HTTP/1.1, `Http2FrameCodec` for framing,
  `HpackDecoder`/`HpackEncoder` for header compression, `Http2FlowController` for backpressure.

* **[+] QPACK moat preserved.** By keeping QPACK in Enterprise, the most technically complex
  and operationally valuable part of the H3 stack remains proprietary. `exeris-kernel-http`
  is open and auditable; the QUIC/QPACK integration is not.

* **[+] Spring Boot Starter / Quarkus DX.** When the Spring Boot Starter and Quarkus extension
  land (v0.6), `exeris-kernel-http` is the natural integration point for `@ExerisMapping`,
  `ExerisHttpRequest`, and `Http3Router` SPI stubs. The codec layer is already open and
  dependency-safe for framework integration authors.

### ⚠️ Trade-offs

* **[!] HTTP/1.1 String allocation.** `Http1RequestParser` materialises `String` objects per
  header field. This is acceptable for v0.5 Community tier but is a known deviation from the
  Zero-Waste Covenant. A future `CharSequence`-over-`MemorySegment` API would eliminate this.
  Tracked as v0.6 scope; not a blocker for v0.5.

* **[!] HTTP/2 connection preface not owned by this module.** The client connection
  preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`, RFC 7540 §3.5) and server-side validation
  are transport-layer concerns owned by `exeris-kernel-community`.
  `exeris-kernel-http` provides frame codec primitives; it does not own connection
  establishment or preface exchange. This is intentional and architectural, not a gap.

* **[!] Per-connection stream multiplexing is out of scope.** Stream ID assignment,
  concurrent stream counting against `maxConcurrentStreams`, and `Http2StreamState`
  transitions are intentionally owned by the transport layer, not
  by this module. `exeris-kernel-http` provides `Http2StreamState` and `Http2FlowController`
  as primitives; the stateful stream table belongs at the transport tier.

* **[-] Additional Maven module.** The open-core repository gains one more module. Build
  time increases by ~4–5 seconds. Dependency graph becomes slightly wider. This cost is
  structurally unavoidable given the placement constraints described above.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single
Source of Truth.

Any new HTTP codec primitive (header compression, frame type, flow control extension) that
is shared between Community and Enterprise **must** land in `exeris-kernel-http`. Any codec
that is structurally inseparable from QUIC stream semantics (e.g., QPACK, QUIC SETTINGS
frames, HTTP/3 stream type negotiation) **must** land in `exeris-kernel-enterprise`.

Introducing HTTP codec logic directly into `exeris-kernel-community` or `exeris-kernel-core`
is a Wall breach and must be rejected in code review with reference to this ADR.