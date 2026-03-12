# Exeris Kernel :: HTTP

**Module:** `eu.exeris:exeris-kernel-http`  
**Role:** L7 HTTP Wire-Format Codec

## Overview

HTTP codec infrastructure shared between the Community and Enterprise tiers.
Owns the complete HTTP/1.1 and HTTP/2 wire format — HPACK header compression,
HTTP/2 frame serialisation, flow control, and HTTP/1.1 request/response parsing.
Does **not** own connection lifecycle, TLS, or QUIC stream binding.

For the full architectural rationale see [`docs/adr/ADR-009`](../docs/adr/ADR-009%20HTTP%20Codec%20module.md)
and the module contract in [`docs/modules/06-http.md`](../docs/modules/06-http.md).

## 🧩 Components

**HPACK (RFC 7541)**
- **`HpackEncoder`**: Stateful per-connection encoder. Indexed §6.1 → Literal Incremental §6.2.1 → Never Indexed §6.2.3 strategy. Prefers dynamic table name-only match over static for compression efficiency.
- **`HpackDecoder`**: Stateful per-connection decoder. Enforces `maxHeaderListSize`. Huffman scratch via `allocateNetwork(strLen * 2)` — safe for full 64 KB string literals. Allocates a new `byte[]` for non-Huffman string literals and when materialising decoded bytes into Java `String` instances (not fully zero-allocation for header values).
- **`HpackDynamicTable`**: FIFO ring-buffer. RFC §4.1 entry cost, LIFO index address, eviction on size overflow.
- **`HpackStaticTable`**: 61 interned entries (RFC Appendix A). O(61) lookup; read-only, shared across connections.
- **`Huffman`**: Stateless encode/decode over `MemorySegment`. Zero allocation. RFC Appendix B nibble-FSM via `HuffmanTable` (32 KB `static final int[]`).

**HTTP/2 Frame Codec (RFC 7540)**
- **`Http2FrameCodec`**: Per-connection façade. Owns negotiated `maxFrameSize`. `parseAndValidate()` enforces `FRAME_SIZE_ERROR`. Write helpers for DATA and HEADERS frames.
- **`Http2FrameParser`**: Wire → `FrameHeader` record. Big-endian, zero allocation. `parseHeader()` delegates to `parseHeaderBigEndian()`.
- **`Http2FrameEncoder`**: `MemorySegment` writes for DATA, HEADERS, SETTINGS, WINDOW\_UPDATE, RST\_STREAM (§6.4), GOAWAY (§6.8). Zero allocation.
- **`Http2Settings`**: Immutable record. `withSetting()` update pattern. `-1` sentinel = unlimited (parameter omitted from SETTINGS frame per §6.5.2).
- **`Http2FlowController`**: Per-direction window arithmetic. Supports negative window after `SETTINGS_INITIAL_WINDOW_SIZE` reduction (§6.9.2).
- **`Http2FrameType`** / **`Http2ErrorCode`** / **`Http2StreamState`**: Enums covering RFC §4, §7, §5.1 respectively.

**HTTP/1.1 Codec (RFC 9112)**
- **`Http1Codec`**: Stateful per-connection coordinator. Tracks keep-alive state and `Content-Length` across the request/response cycle. `writeStatusAndConnection()` emits status-line + `Connection` header atomically.
- **`Http1RequestParser`**: Static utility. Bounded overload enforces `maxHeaders` (default 100) and `maxHeaderSize` (default 8 192 B) — DoS protection. Preserves colons in header values.
- **`Http1ResponseEncoder`**: Status-line + header encoder. Zero-copy `MemorySegment` writes.
- **`Http1ChunkedEncoder`**: RFC §7.1 chunked transfer. `writeChunk()`, `writeLastChunk()`, hex-correct `writeChunkHeader()`.

## 📐 Dependency

```
exeris-kernel-spi  ←  exeris-kernel-http  ←  exeris-kernel-community
                                          ←  exeris-kernel-enterprise
```

Depends only on `exeris-kernel-spi` (`LoanedBuffer`, `MemoryAllocator`, `AllocationHint`).
Must never import Core, Community, or Enterprise classes.

## 🧪 Tests

169 tests across three levels:
- **L0 Unit** — hot-path contract per class, PMD-clean
- **L1 Integration** — encoder → decoder over shared dynamic table
- **L2 RFC Conformance** — `HpackRfc7541AppendixCTest`: 9 byte-exact vectors from RFC 7541 Appendix C.2–C.4, verified against nghttp2/Netty interoperability expectations

## ⚖️ Licence

Licensed under the **Apache License 2.0 with Commons Clause**.
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris HTTP codec as a standalone competing product.

For questions: legal@exeris.eu
