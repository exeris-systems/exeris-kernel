# Exeris Kernel — Support Matrix

> **Pre-1.0 / TRL-3 statement of scope.** This document declares what the **open-core (Community)**
> kernel supports today, what is **Enterprise-only** (out of open-core scope), and what is **deferred**
> to a future version. It is a forward-looking product-scope statement, **not** a semver guarantee:
> no SPI consumer is under a support contract before 1.0, and "stable" here means "the contract we
> intend to hold semver-binding from 1.0" (see the per-surface authority in
> [`stability-matrix.md`](./stability-matrix.md), and the generated per-release compatibility record
> in [`release/spi-api-history.md`](./release/spi-api-history.md)).

## Supported runtime baseline

| Component        | Supported                                  | Notes |
|:-----------------|:-------------------------------------------|:------|
| **JDK**          | Java 25 LTS or newer                       | The distributed `0.12.0` artifact is preview-clean — **`--enable-preview` is not required** (ADR-066). Uses Loom VTs, Panama FFM, `ScopedValue`. Pass `--enable-native-access=ALL-UNNAMED` for the FFM transport/crypto paths. The separate `eu.exeris.preview:*` artifact targets the newest JDK and does require the flag — see [guides/01](./guides/01-platform-and-dependencies.md). |
| **Database**     | PostgreSQL 16                              | Persistence + Flow snapshot store + outbox; validated via Testcontainers `postgres:16`. |
| **Event broker** | Kafka 3.6 wire (validated on CP 7.6.x)     | Optional — only when the Events subsystem uses the Kafka driver (`exeris-kernel-community-kafka`). |
| **TLS**          | OpenSSL 3.0 – 4.x (multi-version)          | Community fd-owner TLS engine; 3.x floor retained for FIPS provider compatibility (ADR-008, OpenSSL-4 migration). |
| **HTTP**         | HTTP/1.1, HTTP/2 (h2 + h2c)                | TLS 1.2 / 1.3 via OpenSSL; request/response plus SSE server-push since 0.10 (ADR-043), and full-duplex WebSocket since 0.12 (ADR-084). The WebSocket engine is reachable two ways since 0.12. It is `ServiceLoader`-discoverable and **embeddable** — an application constructs it, hands it a handshake handler, and needs no kernel boot — and there is now also a `websocket` **subsystem** that `KernelBootstrap` starts. The subsystem is **opt-in**: `websocket.enabled` defaults to `false`, so upgrading opens no listener (ADR-084 A2). |
| **Runtime image** | HotSpot/C2 — GraalVM native-image is **post-1.0** | The zero-allocation and per-core throughput SLOs are pinned to the HotSpot C2 JIT and are **not asserted** under native-image; the contract there is *different, not broken*. Enablement is a post-1.0 gated track. Stated here rather than only in [`performance-contract.md`](./performance-contract.md) §2.2.1, because silence in this document reads as "unknown". |
| **Topology**     | Single-node Community                      | The validated baseline is one node — see [reference deployment](./operations/reference-deployment.md). Scaling out is not Enterprise-gated but it is not turnkey either: what the kernel does and does not carry for N instances is inventoried in [`subsystems/events.md`](./subsystems/events.md) → *Multi-node substrate inventory*. |

## SPI surface status

Mirrors [`stability-matrix.md`](./stability-matrix.md) — that document is the authority (with the
`Abstract*Tck` evidence per surface); this is the consumer-facing summary.

| Subsystem (`eu.exeris.kernel.spi.*`) | Status | Since |
|:-------------------------------------|:-------|:------|
| `memory`, `transport`, `bootstrap`, `context`, `persistence`, `flow`, `exceptions`, `telemetry`, `config`¹ | **stable** | 0.5.0 |
| `diagnostics` | **stable** | 0.9.0 (ADR-033) |
| `http` — `HttpServerEngine`/`HttpClientEngine`/`HttpExchange`/`HttpHandler`/`HttpProvider`, `HttpClientRequestEnricher` | **stable** | 0.5.0 / 0.8.0 |
| `http` — `HttpRequestBodyEncoder`/`Decoder`, `HttpResponseBodyDecoder` (ADR-034) | **preview** | 0.8.0 |
| `http` — `HttpStreamExchange`/`HttpStreamHandler`/`StreamEvent`, SSE server-push (ADR-043) | **preview** | 0.10.0 |
| `security.identity` — `IdentityProvider`/`TokenValidator`/`VerifiedClaims` (ADR-040) | **preview** | 0.10.0 |
| `events`, `graph`, `security`, `crypto` | **preview** | 0.5.0 |
| `scheduling` — `JobScheduler` (ADR-057) | **preview** | 0.11.0 |
| `storage.blob` — `BlobStorageProvider` (ADR-056) | **preview** | 0.11.0 |
| `time` — `TimeSource`/`Clock` seam (ADR-082) | **preview** | 0.12.0 |
| `websocket` — `WebSocketProvider`/`WebSocketExchange`/`WebSocketHandshakeHandler` (ADR-084) | **preview** | 0.12.0 |
| `util` | _internal_ | — |

¹ `config` — the core provider/registry contract is **stable**; the v0.9 `@Immutable` annotation + watcher-refusal semantics (Sprint 5) are **preview** (see [`stability-matrix.md`](./stability-matrix.md)).

## Community-tier limits

The open-core Community runtime is **single-node and NIO-based** by design (ADR-008 places high-perf
transport in Enterprise):

- **Single-node only** — no built-in clustering/discovery; horizontal scale is the host application's concern.
- **NIO transport carrier** — `java.nio` selector reactors, not `io_uring`; OpenSSL fd-owner TLS.
- **No in-process cache ships at all** — no cache dependency is declared anywhere in the reactor, and there is no `CacheProvider` SPI. An application that needs one supplies its own.
- **Full duplex ships, one-directional push still does too** — `HttpStreamExchange` (SSE) since 0.10 (ADR-043) and `WebSocketExchange` since 0.12 (ADR-084). The WebSocket surface is text-frame-only on the application side and carries **no server-initiated keepalive**: the read path parks without a timeout, so a ping the server originates has nothing to ride. A deployment that needs liveness detection drives it from the client.
- **No per-tenant rate limit or quota** — `isolationKey` isolates tenant *data*, not tenant *throughput*. Admission is global; one tenant's burst is shed against the same counters as every other tenant's. Post-1.0.
- **Events are single-node by default** — the in-heap bus does not cross the node boundary and the Outbox is durable *emission*, not cross-node delivery; that needs the Kafka driver. See [`subsystems/events.md`](./subsystems/events.md) → *Delivery Boundary*.
- Best-effort performance contract (No Waste Compute on hot paths, but not the Enterprise zero-copy native tier).

## Enterprise-only (out of open-core scope)

These are deliberately **not** in the Community kernel and live behind the Enterprise overlay:

- `io_uring` transport carrier and QUIC / HTTP/3 path.
- Off-heap **slab pools** and Panama-FFM **native crypto** bypass.
- **Glass-Box** binary telemetry stream / crash-file forensics (the open-core wire contract + file decoder split is ADR-039; the live binary stream is Enterprise).
- FIPS-validated crypto provider.

## Deferred (future versions / post-1.0)

| Capability | Target | Driver |
|:-----------|:-------|:-------|
| `CacheProvider` SPI | RFC-stage | new-SPI roadmap |
| OTLP metrics export + distributed tracing | post-1.0 | ADR-031 (kernel-gated) — still no tracing logic in the kernel: no OTLP exporter, no `traceparent` propagation, Prometheus pull only. The `~v0.12` target this row carried has passed unmet, which is why it now names a phase rather than a version. |
| Per-tenant rate limiting / quota | post-1.0 | no token bucket and no per-tenant counter; see *Community-tier limits* above |

## See also
- [`stability-matrix.md`](./stability-matrix.md) — authoritative per-SPI-surface status + TCK evidence.
- [`operations/reference-deployment.md`](./operations/reference-deployment.md) — the validated single-node Community deployment.
