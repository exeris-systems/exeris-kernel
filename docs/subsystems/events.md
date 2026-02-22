# Kernel Subsystem: Events (L3 Logic Engines)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.events.*` (Event definitions, Stream contracts, Append/Read API)
- Core: `eu.exeris.kernel.core.events.*` (Event Bus, Outbox Orchestrator, Projections)
- Drivers: `exeris-kernel-community` (PostgreSQL Event Store) / `exeris-kernel-enterprise` (Native Kafka / Redpanda
  Integration)
  **Layer:** L3 (Logic Engines)  
  **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Events subsystem** provides the immutable backbone for state changes and inter-service communication. It
implements a unified **Event Stream SPI**, allowing the Kernel to operate seamlessly across different streaming
backends (SQL or Log-based).

- **Unified Stream SPI:** Business logic appends events to a `Stream`, oblivious to whether it maps to a Postgres
  partition or a Kafka topic.
- **Transactional Outbox:** Guarantees "At-Least-Once" delivery by atomically binding event publication to database
  transactions (when using SQL-based persistence).
- **Off-Heap Native Flow:** Leverages the Zero-Copy capabilities of both the Kernel (Panama FFM) and the transport (
  Kafka sendfile/page cache) to minimize latency.
- **Ordered Aggregates:** Enforces strict ordering and versioning per Aggregate Root, preventing race conditions in
  high-concurrency Virtual Thread environments.

---

## Core Philosophy

### 1. "Facts are Immutable"

Once an event is appended to the stream, it can never be changed or deleted. This provides a perfect audit trail and the
ability to reconstruct system state at any point in time.

### 2. Backend Agnosticism

The Kernel does not dictate the storage for events.

- **Postgres Driver (Standard):** Uses `PARTITION BY RANGE` for monthly chunks, ideal for ACID-heavy event sourcing
  within a single database.
- **Kafka Driver (HPC):** Directly streams events to distributed logs, leveraging Kafka's native off-heap performance
  for global-scale messaging.

### 3. Decoupled Persistence

Events can exist independently of the Persistence subsystem. In a pure "Streaming" mode, the Kernel can act as a
stateless event processor (Log Aggregator, IoT Gateway) without ever touching a relational database.

---

## Responsibilities

**What Events SPI DOES:**

1. Define the `Event` base record and `StreamId` abstractions.
2. Provide `EventStreamReader` and `EventStreamAppender` interfaces.
3. Define conflict resolution contracts (Optimistic Concurrency).

**What Events Core DOES:**

1. Orchestrate the **Event Bus** for in-memory distribution to local subscribers.
2. Manage the **Transactional Outbox** state machine to prevent "Dual Writes" problems.
3. Handle event serialization/deserialization using the Kernel's binary formats.

---

## Multi-Provider Strategy

The subsystem selects the driver based on the `EventStreamConfiguration`:

| Backend              | Best For             | Technical Advantage                              |
|:---------------------|:---------------------|:-------------------------------------------------|
| **PostgreSQL**       | Local Event Sourcing | ACID-compliant atomic commits with entities.     |
| **Kafka / Redpanda** | Distributed Systems  | Native Off-Heap Page Cache, Zero-Copy streaming. |
| **In-Memory**        | Testing / Ephemeral  | Zero latency, non-persistent.                    |

---

## Error Codes (Black Box Telemetry)

| Code           | Meaning                    | Action                                        |
|:---------------|:---------------------------|:----------------------------------------------|
| `EX-EVNT-3001` | Concurrent Append Conflict | Version mismatch; trigger retry or fail-fast. |
| `EX-EVNT-3002` | Stream Not Found           | Invalid aggregate ID or topic name.           |
| `EX-EVNT-3003` | Outbox Delivery Failure    | Retry backoff initiated, log at WARN level.   |
| `EX-EVNT-3004` | Event Serialization Error  | Possible schema mismatch; halt processing.    |

---

## Code Examples

### 1. Agnostic Event Append (SPI)

Business logic remains "clean" and protocol-blind.

```java
package eu.exeris.kernel.core.logic;

import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;

public class OrderAggregate {
    public void confirm(EventStreamAppender appender) {
        OrderConfirmed event = new OrderConfirmed(orderId, Instant.now());

        // Appender could be Postgres-backed or Kafka-backed.
        // Core ensures transactional integrity.
        appender.append(StreamId.of("orders", orderId), event);
    }
}
```

### 2. Transactional Outbox Bridge (Core Logic)

Ensures that if we are using a DB, the event is only "visible" to Kafka after the DB commit.

```java

@Transactional
public void saveAndPublish(Entity entity, Event event) {
    persistence.save(entity);
    // This goes to a local 'outbox' table first (if using SQL driver)
    events.append(event);
}
// After commit, the Outbox Poller sends the event to the physical Broker.
```

## Testing Strategy

### Unit Tests

Event serialization/deserialization speed.

Version-based conflict detection logic.

### Integration Tests (TCK)

Outbox Guarantee: Pull the plug on the Broker and verify events are not lost in the DB.

Provider Switching: Run the same TCK suite against Postgres and then against a Kafka container.

Order Integrity: Verify that events for the same StreamId are processed in strict sequence.

## Summary

The Events subsystem is the nervous system of the Exeris Kernel. By providing a protocol-agnostic SPI that supports both
SQL-based Event Sourcing and native Off-Heap streaming via Kafka, it ensures the platform can scale from a single-node
application to a globally distributed event-driven mesh without changing a single line of domain logic.