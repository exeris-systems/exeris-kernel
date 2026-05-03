# Kernel Subsystem: Events (L3 Logic Engines)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.events.*` (`EventEngine`, `EventBus`, `EventDescriptor`, `EventPayload`)
- Core: `eu.exeris.kernel.core.events.*` (Outbox Orchestrator, Projections)
- Drivers:
    - **`community`**: Standard Heap/NIO (PostgreSQL Event Store, JVM-heap Pub/Sub)

**Layer:** L3 (Logic Engines)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Events subsystem** is the nervous system of the Exeris Kernel. It acts as the **"Invisible Wall"** for
event-driven communication — the engine is completely **implementation-blind**: it does not know whether it
operates on local memory, a PostgreSQL partition, or a Kafka cluster. (The SPI is designed to be implementation-blind; the current Community implementation provides in-memory bus and PostgreSQL-backed Outbox only — see Current Repo Reality below.)

- **Unified Stream SPI:** Business logic appends events to a `Stream` oblivious to whether it maps to a
  Postgres partition, a Kafka topic, or an off-heap ring buffer. *(Current Community: in-memory bus + PostgreSQL outbox only.)*
- **Transactional Outbox:** Guarantees at-least-once delivery by atomically binding event publication to
  database transactions (SQL-based persistence).
- **Ordered Aggregates:** Enforces strict ordering and versioning per Aggregate Root, preventing race
  conditions in high-concurrency Virtual Thread environments.

---

## Current Repo Reality

**Implemented in this repository:**
- **`CommunityEventEngine`** (`eu.exeris.kernel.community.events.CommunityEventEngine`) — wires all components
- **`CommunityEventBus`** — in-memory JVM-heap pub/sub (not persistent)
- **`CommunityEventQueue`** + **`CommunityEventLoop`** — in-process queue and drain loop
- **`CommunityEventRegistry`** — ordinal registry backed by `ConcurrentHashMap`
- **`CommunityHeapEventPayload`** — heap-backed `EventPayload` (not off-heap)
- **`CommunityJdbcEventStore`** — PostgreSQL-backed `EventStore` (JDBC, via `PersistenceEngine`)
- **`CommunityJdbcOutboxEventStoreAdapter`** — adapts `CommunityJdbcEventStore` for the Outbox pattern
- **`CommunityEventBusOutboxBrokerPort`** — Outbox delivery port that publishes to the local in-memory `CommunityEventBus` (NOT to Kafka/Redpanda)
- **`CommunityEventProvider`** — `ServiceLoader` entry point

**Implemented in 0.7 (Sprint 5a — SPI groundwork):**
- `EventStreamReader` / `EventStreamAppender` / `EventStream` / `StreamId` SPI contracts (`exeris-kernel-spi`) — implementation-blind replay/append surface targeted at the upcoming Kafka driver and the existing PostgreSQL outbox path. `KernelProviders.EVENT_STREAM_READER` / `KernelProviders.EVENT_STREAM_APPENDER` ScopedValue slots are wired for bootstrapper hand-off.
- `AbstractEventRegistryTck` (EVENT-205a) — binding-agnostic contract for `EX-EVENT-6003` ordinal conflict with `rawArgs == [eventType, ordinal]`. Community binding green.

**Not yet implemented:**
- Kafka or Redpanda driver — **no binding exists in the repository** (planned: Sprint 5b — `exeris-kernel-community-kafka` module + Core `KafkaSessionOrchestrator` + `KafkaEventEngine` + `AbstractKafkaEventEngineTck`).
- `AbstractEventBackpressureTck` for `EX-EVENT-6002` — deferred to Sprint 5b. The current Community `EventQueue` blocks on full via `LinkedBlockingDeque.putLast()`; aligning with the doc'd throw-on-full contract requires a `EventEngineConfig.busPublishFailFast` knob to preserve backward compatibility.
- Per-subscription retry configuration on `EventBus`.
- Operator-triggered DLQ replay API (`EventEngine.replayFromDlq(dlqId)`).

---

## Core Philosophy: "Routing vs. Payload Separation"

### 1. Valhalla-Ready Routing (`EventDescriptor`)

Event routing metadata is encapsulated in `EventDescriptor` — a structure composed exclusively of primitive
types (`long`, `int`). It avoids `synchronized`, `System.identityHashCode()`, and identity `==`, allowing the
C2 JIT to scalarise it via Escape Analysis today. It is designed for future migration to `value record` once
JEP 401 (Value Classes and Objects) reaches mainline GA, which will further eliminate object headers.
This guarantees **zero object allocation per routing decision** at current TRL.

### 2. RAII Payload Lifecycle (`EventPayload`)

The actual event bytes live off-heap. When the `EventBus` broadcasts to N subscribers, it increments the
`EventPayload` reference count to N before dispatch. Each handler receives a live slice; the last handler to
`close()` returns the slab to the `MemoryAllocator` pool. If N == 0 (no subscribers), the bus releases
the payload immediately — eliminating silent leaks from dead events.

### 3. Zero-Copy Native Flow

`EventPayload` bytes travel from the producer's `LoanedBuffer` directly through the `EventBus` to subscribers with RAII ref-count lifecycle — no heap serialization on the dispatch path. When the Outbox is enabled, the Community implementation delivers events to the in-memory `EventBus` after the database commit. A future distributed broker driver (Kafka/Redpanda) would preserve this zero-copy contract end-to-end; that driver is not yet implemented.

### 4. Backpressure by Design

`EventQueue` enforces Backpressure semantics. When the queue is full, publishers receive `EX-EVENT-6002`
instead of silently blocking a Carrier Thread or triggering unbounded heap growth.

---

## SPI Architecture (The Composite Façade)

`EventEngine` is the single entry point. It integrates four orthogonal components:

| Component         | Responsibility                                                                          |
|:------------------|:----------------------------------------------------------------------------------------|
| **`EventBus`**    | Pub/Sub. Manages subscriptions (returns `SubscriptionToken`), publishes fire-and-forget |
| **`EventQueue`**  | Durable backpressure buffer                                                             |
| **`EventLoop`**   | Drains the queue. Community: `StructuredTaskScope` (Virtual Threads)                    |
| **`EventRegistry`** | Type system. Maps event names → `int` ordinals for O(1) hot-path routing            |

`EventRegistry` is the critical performance gate: ordinal-based routing eliminates `String` comparison on
the hot-path entirely. `registry.ordinalOf("OrderConfirmed")` is an O(1) lookup; the returned `int` fits
in the `EventDescriptor` primitive layout.

---

## Multi-Provider Strategy

| Backend              | Best For             | Technical Advantage                                           |
|:---------------------|:---------------------|:--------------------------------------------------------------|
| **PostgreSQL**       | Local Event Sourcing | ACID-compliant atomic commits with entities                   |
| **Kafka / Redpanda** | Distributed Systems *(Target State — driver not yet implemented)* | Native Off-Heap Page Cache, Zero-Copy streaming — planned, no Community binding exists |
| **In-Memory**        | Testing / Ephemeral  | Zero latency, non-persistent                                  |

---

## Responsibilities

**What Events SPI DOES:**

1. Define `EventDescriptor` (primitive-only routing metadata) and `EventPayload` (ref-counted off-heap bytes).
2. Provide `EventStreamReader` and `EventStreamAppender` interfaces (since 0.7.0, EVENT-203) plus the `StreamId` / `EventStream` carriers used to query and stream events. Implementation-blind — bindings (PostgreSQL outbox, Kafka driver, Enterprise off-heap log) provide the cursor.
3. Define `EventRegistry` ordinal contract for O(1) type routing.
4. Define conflict-resolution routing via `EventDescriptor.flags` (PERSISTENT, ORDERED, ASYNC, BROADCAST). Note: optimistic concurrency version enforcement is a Persistence SPI concern (`PersistenceEngine.append(streamId, expectedVersion, …)`), not an Events routing concern.

**What Events Core DOES:**

1. Orchestrate the **Event Bus** for in-memory distribution with RAII ref-count lifecycle.
2. Manage the **Transactional Outbox** state machine to prevent Dual-Write problems.
3. Handle event serialization/deserialization using the Kernel's binary formats (no JSON on hot-path).
4. Manage local **Projections** via `ProjectionEngine` — subscribes typed `ProjectionHandler<S>` instances to the bus and maintains their immutable state via lock-free `AtomicReference.updateAndGet`.
5. Provide binary `EventDescriptorCodec` for off-heap serialisation of `EventDescriptor` structs (Panama FFM `StructLayout`, little-endian).

---

## Error Codes 

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code            | Meaning               | Glass-Box Payload (`rawArgs`)                                          |
|:----------------|:----------------------|:-----------------------------------------------------------------------|
| `EX-EVENT-6001` | Generic Engine Failure| `[0] String message`                                                   |
| `EX-EVENT-6002` | Bus Publish Failure   | `[0] String eventType, [1] long queueDepth, [2] long queueCapacity`    |
| `EX-EVENT-6003` | Registry Conflict     | `[0] String eventType, [1] int ordinal`                                |
| `EX-EVENT-6004` | Provider Boot Failure | `[0] String providerName, [1] String reason`                           |

**Backpressure note for `EX-EVENT-6002`:** When thrown, the publisher MUST NOT retry inline. The
`EventBus` must propagate this exception to the caller's `StructuredTaskScope` boundary, allowing
the Joiner policy to decide whether to fail-fast or shed the event.

---

## Code Examples

### 1. Zero-Allocation Event Handler (SPI — RAII Contract)

Every handler that receives an `EventPayload` MUST close it. `try-with-resources` is the canonical pattern.

```java
public class PaymentProcessor implements EventHandler {

    @Override
    public void handle(EventDescriptor descriptor, EventPayload payload) {
        try (payload) {
            MemorySegment bytes = payload.segment();
            // process zero-copy bytes directly from off-heap slab
        }
        // auto-close decrements refCount; last handler returns slab to pool
    }
}
```

### 2. O(1) Routing via `EventDescriptor` (SPI)

`EventDescriptor` primitives are passed by value — no object headers, no GC pressure on the routing path.

```java
EventDescriptor descriptor = EventDescriptor.of(
        eventUuidHigh, eventUuidLow,
        streamUuidHigh, streamUuidLow,
        registry.ordinalOf("OrderConfirmed"),
        EventDescriptor.FLAG_PERSISTENT | EventDescriptor.FLAG_ASYNC,
        System.currentTimeMillis()
);

engine.bus().publish(descriptor, payload);
```

> Ownership of `payload` is strictly transferred to the `EventBus` on `publish()`. The caller MUST NOT
> call `payload.close()` after this point — the bus manages the ref-count lifecycle.

### 3. Transactional Outbox Bridge (Core)

```java
@Transactional
public void saveAndPublish(Entity entity, Event event) {
    persistence.save(entity);
    events.append(event);
}
// After commit, Outbox Poller delivers to the physical broker.
```

---

---

## Event Schema Evolution Strategy

`EventPayload` bytes are opaque to the Kernel. The schema (serialisation format) is the
responsibility of the application layer. The Kernel provides **schema version routing** via
`EventDescriptor.flags`.

| Approach                             | Mechanism                                                                                       |
|:-------------------------------------|:------------------------------------------------------------------------------------------------|
| **Additive changes** (new fields)    | Use a schema registry (Avro/Protobuf) with backward-compatible evolution. Old consumers ignore unknown fields. |
| **Breaking changes** (renamed/removed fields) | Introduce a new event type (e.g., `OrderConfirmedV2`) and register it separately in `EventRegistry`. Route both versions simultaneously during the migration window. |
| **Version field in descriptor**      | `EventDescriptor.flags` can carry a 4-bit schema version (`flags & 0xF`). Consumers check version before deserialising. |
| **Kernel guarantee**                 | The Event Store is append-only. Old events are never mutated. Consumer projection rebuilds (see Replay API below) can re-read all historical versions. |

---

## Dead Letter Queue (DLQ)

Events that consistently fail handler execution are moved to a Dead Letter Queue.

**Trigger:** Handler throws an uncaught exception on attempt `max-retries` (default: 5).
There is currently no per-subscription retry configuration on `EventBus`. Retry behavior is configured at the engine level via `EventEngineConfig`.

**DLQ table (auto-created on first boot):**

```sql
CREATE TABLE IF NOT EXISTS exeris_outbox_dlq (
    id            TEXT PRIMARY KEY,
    stream_id     TEXT NOT NULL,
    event_type    TEXT NOT NULL,
    payload       BYTEA NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    failure_reason TEXT
);
```

**JFR event:** `OutboxDlqEvent` (`eu.exeris.kernel.core.events.jfr`) emitted on DLQ transition.
fields: `eventType (String)`, `streamIdHigh (long)`, `streamIdLow (long)`, `reason (String)`, `retryCount (int)`.

> **Target State:** Operator-triggered DLQ replay (`EventEngine.replayFromDlq(dlqId)`) is planned but not
> yet implemented in the SPI. Current recovery requires direct `exeris_outbox_dlq` table access.

---

## Event Replay API

The `EventStreamReader` / `EventStreamAppender` SPI (since 0.7.0, EVENT-203) defines a binding-agnostic replay and direct-append surface that brokers (PostgreSQL outbox, Kafka, Enterprise off-heap log) implement and bootstrappers wire via `KernelProviders.EVENT_STREAM_READER` / `KernelProviders.EVENT_STREAM_APPENDER`. Application code consults `KernelProviders.eventStreamReader()` / `KernelProviders.eventStreamAppender()` and treats an empty `Optional` as "broker does not support this capability" — never as a hard error.

```java
public interface EventStreamReader extends AutoCloseable {
    // Replay all events for a specific stream from a timestamp (inclusive)
    EventStream replayFrom(StreamId streamId, Instant fromTimestamp);

    // Replay from a specific stream version (broker-defined offset semantics)
    EventStream replayFromVersion(StreamId streamId, long fromVersion);

    // Cross-stream replay of every event of a given type (projection rebuild)
    EventStream replayByType(String eventType, Instant fromTimestamp);

    @Override void close(); // releases shared driver resources; idempotent
}

@FunctionalInterface
public interface EventStreamAppender {
    // Same RAII ownership transfer as EventBus.publish(...)
    void append(StreamId streamId, EventDescriptor descriptor, EventPayload payload);
}

public record StreamId(long streamIdHigh, long streamIdLow, String streamType) { ... }
```

`StreamId` is wire-compatible with `EventDescriptor.streamIdHigh()` / `streamIdLow()` so the same routing index serves both descriptor dispatch and stream-scoped queries.

**Zero-allocation replay path:** Events are streamed directly from the PostgreSQL WAL or Kafka topic into `LoanedBuffer` slabs — no intermediate `List<Event>` materialisation. `EventStream extends Iterable<EventPayload>, AutoCloseable`; each payload arrives at refCount 1 and the consumer closes it (no broadcast retain protocol on replay). The cursor is released via `EventStream.close()`.

**Bindings (status):**

- PostgreSQL outbox replay — planned (no current binding).
- Kafka driver — planned for Sprint 5b in the new `exeris-kernel-community-kafka` module.
- Enterprise off-heap log — out-of-repo, target-state.

---

## Deduplication — Design Contract

The Kernel **does not provide built-in deduplication** at the `EventBus` level. This is an
intentional design contract, not an oversight.

| Layer           | Deduplication Mechanism                                                       |
|:----------------|:------------------------------------------------------------------------------|
| **EventBus (L3)** | None. At-least-once delivery semantics. Duplicate events may be delivered on broker reconnect or Outbox retry. |
| **Flow Engine (L4)** | `IdempotencyGuard` — per-Saga-step idempotency key prevents duplicate step execution. This covers the most critical deduplication requirement (business actions). |
| **Application layer** | Each subscriber must implement idempotency for its own state mutations. The `EventDescriptor.eventUuidHigh/Low` fields provide a stable deduplication key (UUID stable across retries). |

**Rationale:** Built-in bus-level deduplication requires shared state (bloom filter or seen-set)
across all subscribers. In a multi-subscriber scenario this state is either a heap-allocated
`ConcurrentHashMap` (GC pressure) or a distributed lock (latency). Neither is acceptable at the
performance tier targeted by the Events subsystem. Subscribers that require exactly-once semantics
must implement their own idempotency using the event UUID as the deduplication key.

---

## Redpanda vs. Kafka — Semantic Differences (Target State — Not Yet Implemented)

> **Target State — not yet implemented.** No Kafka or Redpanda driver exists in the current
> repository. The `EventEngine` SPI is designed to be broker-agnostic so that a future driver
> can implement either broker without changing application code. The table below documents the
> planned operational differences for reference when that driver is developed.

Both brokers can be supported via the same `EventEngine` SPI. The following table highlights
operational differences relevant to a future Exeris Kafka/Redpanda driver:

| Behaviour              | Apache Kafka                                       | Redpanda                                              |
|:-----------------------|:---------------------------------------------------|:------------------------------------------------------|
| **Transactions**       | Kafka Transactions API (EOS — Exactly-Once Semantics) | Redpanda supports Kafka Transactions API (v23.2+)   |
| **Log compaction**     | Background `log.cleaner` thread (asynchronous, configurable delay) | Redpanda compaction is asynchronous, similar semantics |
| **Retention semantics**| Time-based (`retention.ms`) + size-based (`retention.bytes`) | Same API — compatible                                |
| **Consumer groups**    | Standard Kafka consumer group protocol             | Compatible (Kafka protocol v2)                        |
| **`sendfile` / zero-copy** | Page cache → socket via `sendfile(2)` (no JVM copy) | Identical — same Linux kernel path                 |
| **Known difference**   | Kafka has more mature tooling (Kafka Streams, ksqlDB) | Redpanda is faster cold-start (single binary, no ZK) |

---

## Testing Strategy

### Unit Tests

- `EventDescriptor` scalarization: verify no heap objects are created during descriptor construction.
- `EventPayload` ref-count correctness: N subscribers → ref-count N; last `close()` → pool return.
- `EventRegistry` ordinal conflicts: `EX-EVENT-6003` thrown on duplicate registration.
  > **Closed in 0.7 (EVENT-205a).** Covered by `AbstractEventRegistryTck`; Community binding `CommunityEventRegistryTckTest` green. Asserts both error code and `rawArgs == [String eventType, int ordinal]` per the documented Glass-Box layout.
- Backpressure: `EX-EVENT-6002` thrown with correct `rawArgs` when queue is at capacity.
  > **(TCK gap — deferred to Sprint 5b.)** Reconciliation requires `EventEngineConfig.busPublishFailFast` because the current Community `EventQueue` blocks on full via `LinkedBlockingDeque.putLast()` (safe for virtual threads, but inconsistent with the throw-on-full contract documented above).

### Integration Tests (TCK)

- **Outbox Guarantee:** Disconnect the broker mid-flight; verify events are not lost in the DB outbox.
- **Provider Switching:** Run the same TCK suite against PostgreSQL. *(Kafka/Redpanda provider switching TCK: planned — no driver available yet.)*
- **Order Integrity:** Verify events for the same `StreamId` are processed in strict sequence.
  > **(TCK gap — not yet implemented)**
- **Zero Subscriber Fast-Free:** Publish to a bus with zero subscribers; verify `payload.refCount() == 0`
  and slab is immediately returned to the pool (no silent leak).

---

## Summary

The Events subsystem is the nervous system of the Exeris Kernel. The `EventDescriptor` / `EventPayload`
separation is the architectural core: primitive routing metadata enables O(1) dispatch and Valhalla
scalarization, while RAII `EventPayload` ref-counting guarantees that off-heap memory is reclaimed
deterministically — regardless of how many subscribers fan out or how deep the retry chain goes. Together
with the Transactional Outbox, it scales from a single-node application to a
globally distributed event-driven mesh without changing a single line of domain logic.

