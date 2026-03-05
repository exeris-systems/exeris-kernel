# Kernel Subsystem: Events (L3 Logic Engines)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.events.*` (`EventEngine`, `EventBus`, `EventDescriptor`, `EventPayload`)
- Core: `eu.exeris.kernel.core.events.*` (Outbox Orchestrator, Projections)
- Drivers:
    - **`community`**: Standard Heap/NIO (PostgreSQL Event Store, JVM-heap Pub/Sub)
    - **`enterprise`**: Off-Heap Slab Pools + `io_uring` + Native Kafka / Redpanda Integration

**Layer:** L3 (Logic Engines)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Events subsystem** is the nervous system of the Exeris Kernel. It acts as the **"Invisible Wall"** for
event-driven communication — the engine is completely **implementation-blind**: it does not know whether it
operates on local memory, a PostgreSQL partition, or a Kafka cluster.

- **Unified Stream SPI:** Business logic appends events to a `Stream` oblivious to whether it maps to a
  Postgres partition, a Kafka topic, or an off-heap ring buffer.
- **Transactional Outbox:** Guarantees at-least-once delivery by atomically binding event publication to
  database transactions (SQL-based persistence).
- **Zero-Copy Native Flow:** In the Enterprise tier, the engine exchanges data directly with Kafka/Redpanda,
  bypassing the JVM heap entirely via Panama FFM and `LoanedBuffer` handover to the broker socket.
- **Ordered Aggregates:** Enforces strict ordering and versioning per Aggregate Root, preventing race
  conditions in high-concurrency Virtual Thread environments.

---

## Core Philosophy: "Routing vs. Payload Separation"

### 1. Valhalla-Ready Routing (`EventDescriptor`)

Event routing metadata is encapsulated in `EventDescriptor` — a structure composed exclusively of primitive
types (`long`, `int`). This allows the JIT to fully scalarize it, prepares it for `value record` migration
(JEP 401), and guarantees **zero object allocation per routing decision**.

### 2. RAII Payload Lifecycle (`EventPayload`)

The actual event bytes live off-heap. When the `EventBus` broadcasts to N subscribers, it increments the
`EventPayload` reference count to N before dispatch. Each handler receives a live slice; the last handler to
`close()` returns the slab to the `MemoryAllocator` pool. If N == 0 (no subscribers), the bus releases
the payload immediately — eliminating silent leaks from dead events.

### 3. Zero-Copy Native Flow

In the Enterprise tier, bytes travel:
```
Producer LoanedBuffer → EventBus → io_uring SQE → Kafka sendfile/page cache
```
No `byte[]` copy, no `ByteBuffer.allocate()`, no heap serialization between the producer and the broker.

### 4. Backpressure by Design

`EventQueue` enforces Backpressure semantics. When the queue is full, publishers receive `EX-EVENT-6002`
instead of silently blocking a Carrier Thread or triggering unbounded heap growth.

---

## SPI Architecture (The Composite Façade)

`EventEngine` is the single entry point. It integrates four orthogonal components:

| Component         | Responsibility                                                                          |
|:------------------|:----------------------------------------------------------------------------------------|
| **`EventBus`**    | Pub/Sub. Manages subscriptions (returns `SubscriptionToken`), publishes fire-and-forget |
| **`EventQueue`**  | Durable backpressure buffer. Enterprise: lock-free off-heap ring buffer                 |
| **`EventLoop`**   | Drains the queue. Community: `StructuredTaskScope` (VTs). Enterprise: single-threaded lock-free |
| **`EventRegistry`** | Type system. Maps event names → `int` ordinals for O(1) hot-path routing            |

`EventRegistry` is the critical performance gate: ordinal-based routing eliminates `String` comparison on
the hot-path entirely. `registry.ordinalOf("OrderConfirmed")` is an O(1) lookup; the returned `int` fits
in the `EventDescriptor` primitive layout.

---

## Multi-Provider Strategy

| Backend              | Best For             | Technical Advantage                                           |
|:---------------------|:---------------------|:--------------------------------------------------------------|
| **PostgreSQL**       | Local Event Sourcing | ACID-compliant atomic commits with entities                   |
| **Kafka / Redpanda** | Distributed Systems  | Native Off-Heap Page Cache, Zero-Copy streaming               |
| **In-Memory**        | Testing / Ephemeral  | Zero latency, non-persistent                                  |

**Batch Flush (Enterprise):** `EventLoop` supports `EventBatchProcessor` registration. At 10,000 events/s,
the engine batches them into a single flush — one `io_uring` SQE submission instead of 10,000 individual
inserts or Kafka `ProducerRecord` objects.

---

## Responsibilities

**What Events SPI DOES:**

1. Define `EventDescriptor` (primitive-only routing metadata) and `EventPayload` (ref-counted off-heap bytes).
2. Provide `EventStreamReader` and `EventStreamAppender` interfaces.
3. Define `EventRegistry` ordinal contract for O(1) type routing.
4. Define conflict-resolution contracts (Optimistic Concurrency via version field in `EventDescriptor`).

**What Events Core DOES:**

1. Orchestrate the **Event Bus** for in-memory distribution with RAII ref-count lifecycle.
2. Manage the **Transactional Outbox** state machine to prevent Dual-Write problems.
3. Handle event serialization/deserialization using the Kernel's binary formats (no JSON on hot-path).

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

## Testing Strategy

### Unit Tests

- `EventDescriptor` scalarization: verify no heap objects are created during descriptor construction.
- `EventPayload` ref-count correctness: N subscribers → ref-count N; last `close()` → pool return.
- `EventRegistry` ordinal conflicts: `EX-EVENT-6003` thrown on duplicate registration.
- Backpressure: `EX-EVENT-6002` thrown with correct `rawArgs` when queue is at capacity.

### Integration Tests (TCK)

- **Outbox Guarantee:** Disconnect the broker mid-flight; verify events are not lost in the DB outbox.
- **Provider Switching:** Run the same TCK suite against PostgreSQL and then against a Kafka container.
- **Order Integrity:** Verify events for the same `StreamId` are processed in strict sequence.
- **Zero Subscriber Fast-Free:** Publish to a bus with zero subscribers; verify `payload.refCount() == 0`
  and slab is immediately returned to the pool (no silent leak).
- **Batch Flush (Enterprise):** Register `EventBatchProcessor`; verify 10,000 events produce a single
  `io_uring` SQE submission, not 10,000 individual calls.

---

## Summary

The Events subsystem is the nervous system of the Exeris Kernel. The `EventDescriptor` / `EventPayload`
separation is the architectural core: primitive routing metadata enables O(1) dispatch and Valhalla
scalarization, while RAII `EventPayload` ref-counting guarantees that off-heap memory is reclaimed
deterministically — regardless of how many subscribers fan out or how deep the retry chain goes. Together
with the Transactional Outbox and Native Kafka Zero-Copy path, it scales from a single-node application to a
globally distributed event-driven mesh without changing a single line of domain logic.

