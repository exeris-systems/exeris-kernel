# Kernel Subsystem: HTTP (HPACK + HTTP/2 + HTTP/1.1)

**Layer:** L2 (Wire Translation)  
**Status:** Implemented in `exeris-kernel-core` (`v0.5.0-SNAPSHOT`)

---

## Overview

HTTP in the current repository is implemented as:

- **Contracts (SPI):** `eu.exeris.kernel.spi.http.*` in `exeris-kernel-spi`
- **Codec implementation (Core):** `eu.exeris.kernel.core.http.*` in `exeris-kernel-core`
- **Contract tests (TCK):** `eu.exeris.kernel.tck.contract.http.AbstractHttp*Tck` in `exeris-kernel-tck`

There is currently **no separate** `exeris-kernel-spi-http` or `exeris-kernel-http` Maven module
in the root reactor.

---

## SPI Scope (exeris-kernel-spi)

HTTP SPI package: `eu.exeris.kernel.spi.http`

Key contracts:

- `HttpProvider`
- `HttpServerEngine`, `HttpClientEngine`
- `HttpHandler`, `HttpExchange`
- `HttpRequest`, `HttpResponse`, `HttpHeader`, `HttpStatus`
- `HttpConfig`, `HttpMode`, `HttpMethod`, `HttpVersion`
- `HttpKernelProviders` (ScopedValue slots)

HTTP exceptions in SPI:

- `eu.exeris.kernel.spi.exceptions.http.HttpException`

Wall invariant:

- SPI HTTP remains transport-agnostic and does not expose `io_uring`, QUIC internals,
  NIO channel details, or codec implementation classes.

---

## Core Scope (exeris-kernel-core)

HTTP codec package: `eu.exeris.kernel.core.http`

Implemented components:

- **HPACK / Huffman:** `hpack.*`, `hpack.huffman.*`
- **HTTP/2 framing:** `http2.*`
- **HTTP/1.1 codec:** `http1.*`

Current placement reality:

- Wire codec code currently lives in Core.
- Community/Enterprise transport implementations are expected to consume these primitives
  via Core in this repository state.

---

## TCK Scope (exeris-kernel-tck)

HTTP abstract TCK suites present:

- `AbstractHttpProviderTck`
- `AbstractHttpServerEngineTck`
- `AbstractHttpClientEngineTck`
- `AbstractHttpHandlerTck`
- `AbstractHttpExchangeTck`

These verify SPI-level HTTP contract behavior and ServiceLoader/provider semantics.

---

## Current Validation Topology (Factual)

At this repository stage, HTTP contract validation is wired as:

- `exeris-kernel-tck`: abstract HTTP contract suites (`AbstractHttp*Tck`)
- `exeris-kernel-core` (test scope): concrete bindings extending those suites
- `exeris-kernel-core` (test scope): minimal fixture provider/engines/exchange used only to satisfy SPI lifecycle contracts

```mermaid
graph TD
  SPI[exeris-kernel-spi\n eu.exeris.kernel.spi.http.*]
  TCK[exeris-kernel-tck\n AbstractHttpProviderTck\n AbstractHttpServerEngineTck\n AbstractHttpClientEngineTck\n AbstractHttpHandlerTck\n AbstractHttpExchangeTck]
  CORETEST[exeris-kernel-core tests\n CoreHttpProviderTckTest\n CoreHttpServerEngineTckTest\n CoreHttpClientEngineTckTest\n CoreHttpHandlerTckTest\n CoreHttpExchangeTckTest]
  FIXTURE[CoreHttpProviderFixture\n test-only SPI fixture]

  SPI --> TCK
  TCK --> CORETEST
  CORETEST --> FIXTURE
```

What this currently guarantees:

- SPI contract semantics are executable and verified in CI.
- ServiceLoader selection semantics are verified for HTTP provider contract.

What this does not guarantee yet:

- Production wire engine behavior (socket bind/accept loop, real client transport).
- End-to-end runtime transport semantics in Community/Enterprise drivers.

---

## Core HTTP vs Engine Responsibility

Current `exeris-kernel-core` HTTP package focuses on codec/wire primitives:

- `http1.*`
- `http2.*`
- `hpack.*`
- `hpack.huffman.*`

The concrete production `HttpProvider`/`HttpServerEngine`/`HttpClientEngine` drivers are expected in runtime driver tiers
(Community/Enterprise). In current repository state, Core contains test-only contract fixtures for TCK binding,
not a production server/client engine.

---

## Architectural Notes (Current State)

- `QPACK` / HTTP/3 implementation is not present in this open repository module set.
- Root Maven modules are currently: `build-config`, `bom`, `parent`, `spi`, `tck`, `core`, `community`.
- Documentation and design discussions that mention dedicated `exeris-kernel-http` module
  should be treated as target/roadmap unless that module appears in the root reactor.
