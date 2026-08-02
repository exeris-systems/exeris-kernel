# Exeris Kernel — Support Matrix

> **Pre-1.0 / TRL-3 statement of scope.** This document declares what the **open-core (Community)**
> kernel supports today, what is **Enterprise-only** (out of open-core scope), and what is **deferred**
> to a future version. It is a forward-looking product-scope statement, **not** a semver guarantee:
> there are no external SPI consumers before 1.0, and "stable" here means "the contract we intend to
> hold semver-binding from 1.0" (see the per-surface authority in [`stability-matrix.md`](./stability-matrix.md)).

## Supported runtime baseline

| Component        | Supported                                  | Notes |
|:-----------------|:-------------------------------------------|:------|
| **JDK**          | Java 26 (preview features enabled)         | `--enable-preview`; Loom VTs, Panama FFM, `ScopedValue`, `StructuredTaskScope`. Maven itself must run on JDK 26. |
| **Database**     | PostgreSQL 16                              | Persistence + Flow snapshot store + outbox; validated via Testcontainers `postgres:16`. |
| **Event broker** | Kafka 3.6 wire (validated on CP 7.6.x)     | Optional — only when the Events subsystem uses the Kafka driver (`exeris-kernel-community-kafka`). |
| **TLS**          | OpenSSL 3.0 – 4.x (multi-version)          | Community fd-owner TLS engine; 3.x floor retained for FIPS provider compatibility (ADR-008, OpenSSL-4 migration). |
| **HTTP**         | HTTP/1.1, HTTP/2 (h2 + h2c)                | TLS 1.2 / 1.3 via OpenSSL; request/response plus SSE server-push since 0.10 (ADR-043). WebSocket is still out — see *Deferred* below. |
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
| `util` | _internal_ | — |

¹ `config` — the core provider/registry contract is **stable**; the v0.9 `@Immutable` annotation + watcher-refusal semantics (Sprint 5) are **preview** (see [`stability-matrix.md`](./stability-matrix.md)).

## Community-tier limits

The open-core Community runtime is **single-node and NIO-based** by design (ADR-008 places high-perf
transport in Enterprise):

- **Single-node only** — no built-in clustering/discovery; horizontal scale is the host application's concern.
- **NIO transport carrier** — `java.nio` selector reactors, not `io_uring`; OpenSSL fd-owner TLS.
- **Caffeine** is the only in-process cache; no pluggable `CacheProvider` yet.
- **Server push is SSE-only** — one-directional `HttpStreamExchange` since 0.10 (ADR-043); no WebSocket, so a full-duplex client still polls or opens a second request.
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
| WebSocket (full duplex) | after SSE | follows the SSE primitive (shipped 0.10); separately justified, not milestone-pinned |
| `BlobStorageProvider`, `JobScheduler` SPI | v0.11 | new-SPI roadmap |
| `CacheProvider` SPI | RFC-stage | new-SPI roadmap |
| OTLP metrics export + distributed tracing | ~v0.12 | ADR-031 (kernel-gated) — no tracing logic in the kernel today |

## See also
- [`stability-matrix.md`](./stability-matrix.md) — authoritative per-SPI-surface status + TCK evidence.
- [`operations/reference-deployment.md`](./operations/reference-deployment.md) — the validated single-node Community deployment.
