---
title: "Kernel Subsystem: Events (L3 Logic Engines)"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-28
---

# Kernel Subsystem: Events (L3 Logic Engines)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.events.*` (`EventEngine`, `EventBus`, `EventDescriptor`, `EventPayload`)
- Core: `eu.exeris.kernel.core.events.*` (Outbox Orchestrator, Projections)
- Drivers:
    - **`community`**: Standard Heap/NIO (PostgreSQL Event Store, JVM-heap Pub/Sub)
    - **`community-kafka`**: Apache Kafka driver on `kafka-clients` 4.0.x, version managed in `exeris-kernel-bom` (`KafkaEventEngine`, plus a `KafkaEventBrokerPort` Outbox adapter that is built but not wired — see *Delivery Boundary*)

**Layer:** L3 (Logic Engines)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Events subsystem** is the nervous system of the Exeris Kernel. It acts as the **"Invisible Wall"** for
event-driven communication — the engine is completely **implementation-blind**: it does not know whether it
operates on local memory, a PostgreSQL partition, or a Kafka cluster. (The SPI is implementation-blind; the bindings in this repository are the Community in-memory bus with a PostgreSQL outbox and event log, and the Community Kafka driver — see Current Repo Reality below. An off-heap binding is out of this repository.)

- **Unified Stream SPI:** Business logic appends events to a stream (`EventStreamAppender.append(StreamId, …)`)
  oblivious to whether it maps to a PostgreSQL table, a Kafka topic, or an off-heap log. *(In this repository:
  the `exeris_event_log` table and a Kafka log topic.)*
- **Transactional Outbox:** Guarantees at-least-once delivery by atomically binding event publication to
  database transactions (SQL-based persistence).
- **Ordered Aggregates:** On the durable log only — `EventStreamAppender` gives each `StreamId` a total
  order and an append-with-expected-version check (ADR-049). The `EventBus` is unordered by design.

---

## Current Repo Reality

**Implemented in this repository:**
- **`CommunityEventEngine`** (`eu.exeris.kernel.community.events.CommunityEventEngine`) — wires all components
- **Core `InMemoryEventBus`** — in-memory JVM-heap pub/sub (not persistent), wrapped by `CommunityEventEngine` in a private decorator that also enqueues persistent events onto the engine's queue
- **`CommunityEventQueue`** + **`CommunityEventLoop`** — in-process queue and drain loop
- **`CommunityEventRegistry`** — ordinal registry backed by `ConcurrentHashMap`
- **`CommunityHeapEventPayload`** — heap-backed `EventPayload` (not off-heap)
- **`CommunityJdbcEventStore`** (`eu.exeris.kernel.community.persistence.jdbc`) — PostgreSQL-backed `EventStore` over a caller-supplied `PersistenceConnection`
- **`CommunityJdbcOutboxEventStoreAdapter`** — adapts `CommunityJdbcEventStore` for the Outbox pattern
- **`CommunityEventBusOutboxBrokerPort`** — Outbox delivery port that publishes to the same engine's in-memory `InMemoryEventBus` (NOT to Kafka/Redpanda)
- **`CommunityEventProvider`** — `ServiceLoader` discovery for the Community in-memory binding (priority `0`).

**Implemented in 0.7 (Sprint 5a — SPI groundwork):**

- `EventStreamReader` / `EventStreamAppender` / `EventStream` / `StreamId` SPI contracts (`exeris-kernel-spi`) — implementation-blind replay/append surface, bound by the Kafka driver and the PostgreSQL event log (see *Event Replay API*). `KernelProviders.EVENT_STREAM_READER` / `KernelProviders.EVENT_STREAM_APPENDER` ScopedValue slots are wired for bootstrapper hand-off.
- `AbstractEventRegistryTck` (EVENT-205a) — binding-agnostic contract for `EX-EVENT-6003` ordinal conflict with `rawArgs == [eventType, ordinal]`. Community binding green.

**Implemented in 0.7 (Sprint 5b1 — backpressure + abstract TCKs + module skeleton):**

- `EventEngineConfig.busPublishFailFast` (since 0.7.0) — when `true`, persistent `EventBus.publish` raises `EX-EVENT-6002` with the documented Glass-Box `rawArgs == [eventType, queueDepth, queueCapacity]` instead of blocking the publishing virtual thread on a full queue. Default `false` preserves the v0.6 blocking-on-VT semantic; `EventEngineConfig.enterpriseDefaults()` flips it on. `CommunityEventQueue` selects `LinkedBlockingDeque.offerLast` (non-blocking) vs `putLast` (blocking) at construction time based on this flag; `CommunityEventEngine.PersistentQueueingBus` translates a `false` push into `EventBusException.publishOverflow(...)` in fail-fast mode, and into `EventBusException.publishFailed(ordinal, "interrupted", null)` (`EX-EVENT-6009`) in blocking mode, where `false` means the publisher was interrupted while parked.
- `AbstractEventBackpressureTck` (EVENT-205b) + `CommunityEventBackpressureTckTest` — closes the long-standing `EX-EVENT-6002` TCK gap. Asserts both error code and the `[String, long, long]` rawArgs layout.
- `AbstractEventStreamReaderTck` and `AbstractEventStreamAppenderTck` — the abstract suites land here so any binding (Kafka, the PostgreSQL event log, an off-heap log) inherits the same contract: replay-roundtrip non-null streams, idempotent close, single-owner payload hand-off (refCount=1), and append ownership-transfer + null-arg defence. Concrete bindings: `CommunityJdbcEventStreamReaderTckIT` / `CommunityJdbcEventStreamAppenderTckIT` and `CommunityKafkaEventStreamReaderTckIT` / `CommunityKafkaEventStreamAppenderTckIT`.
- `exeris-kernel-community-kafka` — submodule with reactor + BOM wiring, a `kafka-clients` dependency (version managed in `exeris-kernel-bom`), and Testcontainers Kafka (`confluentinc/cp-kafka:7.6.1`) in the test scope. Operators of single-node `exeris-kernel-community` keep their lean classpath; Kafka users add this jar.

**Implemented in 0.7 (Sprint 5b2 — Kafka driver):**

- `KafkaEventConfig` — driver-specific knobs (bootstrap servers, consumer group id, topic prefix, `requireAllAcks`, `producerLingerMs`, consumer poll timeout). `defaults(bootstrapServers, groupId)` factory + `topicFor(eventType)` topic-naming helper.
- `KafkaEventCodec` — fixed 48-byte wire header (`eventIdHigh/Low`, `streamIdHigh/Low`, `ordinal`, `flags`, `occurredAtMs`) followed by the payload tail, big-endian. `encode()` / `decodeDescriptor()` / `decodePayloadSegment()` keep `EventDescriptor` primitives bit-exact across the broker hop; `decodePayloadSegment()` returns a slice over the consumer record's array rather than a copy (`decodePayloadBytes()` remains for byte-level test assertions).
- `KafkaEventBrokerPort` — Core `OutboxBrokerPort` adapter wrapping an `org.apache.kafka.clients.producer.Producer<byte[], byte[]>` it does not own or close, sending each entry synchronously; takes an `IntFunction<String> ordinalToTopic` resolver (Wall-friendly: Core never sees Kafka classes). `brokerId() = "kafka"`.
- `KafkaEventEngine` — `EventEngine` implementation with three composed actors: `KafkaPublishBus` (publish-via-producer, subscribe-delegated to an in-process `InMemoryEventBus`), `ConsumerLoop` (single virtual-thread `KafkaConsumer` poll loop with dynamic subscription refresh per registered ordinal — quiesces on `LockSupport.parkNanos(pollTimeout)` while the registry has no entries, since `KafkaConsumer.poll()` throws `IllegalStateException` on an unsubscribed consumer), and `NoOpQueue` (degenerate — Kafka itself is the durable queue, so the local `EventQueue` slot is bypassed; `push` throws `EventEngineException`, `EX-EVENT-6001`).
- `KafkaEventRegistry` + `KafkaHeapEventPayload` — package-private heap-backed registry with a `specOfOrdinal(int)` reverse lookup (ADR-050: returns the full `EventTypeSpec` so the publish + subscribe paths honour a `topic` override via `KafkaEventEngine.effectiveTopic`) and an `AtomicInteger`-backed payload owner for the consumer loop hand-off.
- `KafkaEventProvider` (`META-INF/services/eu.exeris.kernel.spi.events.EventProvider`, priority `50` — outranks Community in-memory `0`, below the Enterprise tier slot `100`) reads `events.kafka.bootstrap-servers` / `group-id` / `topic-prefix` / `require-all-acks` / `producer-linger-ms` / `consumer-poll-timeout-ms` from `KernelProviders.config()`. Tests/TCK use the `KafkaEventProvider.create(spi, kafka)` factory which short-circuits the lookup.
- `AbstractKafkaEventEngineTck` (EVENT-206) + `CommunityKafkaEventEngineTckIT` (`@Tag("integration") @Testcontainers`, `confluentinc/cp-kafka:7.6.1`) — asserts publish/consume roundtrip with bit-exact descriptor preservation, idempotent close, and start-before-register warm-up safety (engine started with no registry entries must not crash the consumer loop and must still deliver after a later register+publish).
- `CommunityKafkaFlowChoreographyTckIT` (DIST-302 smoke) drives `AbstractFlowChoreographyTck` (Wake / Start / Ignore / RAII) over a Testcontainers Kafka broker; `CommunityCrossEngineChoreographyIT` (DIST-302 closure, Sprint 6c) wires two `FlowEngine`s against a shared `JdbcFlowSnapshotStore` (Postgres) plus the same Kafka broker and proves a saga parked on Service A is woken and completed on Service B via the snapshot fallback in `lookupParked`.

**Implemented in 0.10 (ADR-046 — SPI + Community driver + TCK + bootstrap binding + encode-failure JFR; tooling generator lockstep):**

- **Event-payload codec seam.** A tier-neutral `EventPayloadCodec` + `EventPayloadCodecRegistry`
  (`eu.exeris.kernel.spi.events.codec`) that turns a typed/structured domain-event payload into the
  already-serialized bytes the `EventBus` carries today — registry-selected by `(payloadType, contentType)`,
  Community JSON default, resolved **in the generated `*EventPublisher`** (not in the bus), wired via the
  optional `KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY` slot — bound at scope init by
  `CommunityEventsSubsystem` from `EventProvider.eventPayloadCodecRegistry()` (the `EVENT_STREAM_READER` /
  `EVENT_STREAM_APPENDER` precedent). Encode failures emit `CommunityEventPayloadEncodeFailedEvent` (JFR,
  secret-safe; emitted by `CommunityJsonEventPayloadCodec`). Mirrors the HTTP body-codec matrix
  (ADR-009/034/036). Strictly additive — `EventBus` / `EventEngine` / `EventPayload` unchanged. The
  generated `*EventPublisher` that consumes this seam is emitted by `exeris-tooling`, outside this
  repository; this page does not describe its state. See `docs/adr/ADR-046-event-payload-codec-spi.md`.

- **JSON mapper customization (since v0.10.1, ADR-052).** `CommunityEventProvider` sources the
  `CommunityJsonEventPayloadCodec`'s `tools.jackson` `ObjectMapper` through the `EVENTS` scope of the
  Community customization seam (`eu.exeris.kernel.community.json.CommunityJsonMappers.forScope(...)`)
  rather than a hardcoded `new ObjectMapper()`. An application-registered `JsonMapperCustomizer`
  (ServiceLoader) can tune the event-payload mapper (modules/features); with none registered it is
  byte-for-byte the pre-0.10.1 default. Jackson stays a Community driver detail — the seam never enters
  SPI. See `docs/adr/ADR-052-community-json-mapper-customization-seam.md`.

**Not yet implemented (later):**

- Per-subscription retry configuration on `EventBus`.
- Operator-triggered DLQ replay API (`EventEngine.replayFromDlq(dlqId)`).

---

## Core Philosophy: "Routing vs. Payload Separation"

### 1. Valhalla-Ready Routing (`EventDescriptor`)

Event routing metadata is encapsulated in `EventDescriptor` — a record of seven primitive fields (`long`,
`int`). It avoids `synchronized`, `System.identityHashCode()`, and identity `==`, which makes it a candidate
for C2's scalar replacement. It is designed for future migration to a value class once JEP 401 (Value
Classes and Objects) reaches mainline GA, which will further eliminate object headers.
A primitive descriptor does not by itself make a publication allocation-free: the in-memory bus allocates
per publication (a snapshot of the subscriber list, one virtual thread per handler), and
`EventBusZeroAllocTck` bounds the Community bus's per-publish `eu.exeris.*` allocations rather than
asserting zero.

### 2. RAII Payload Lifecycle (`EventPayload`)

An `EventPayload` owns a reference to its backing memory — a heap `byte[]` in every binding in this
repository, an off-heap slab in a binding that pools. On a bus that is not brokered
(`EventBus.isBrokered() == false`), broadcasting to N subscribers retains the payload N-1 times before
dispatch, so its reference count is N; each handler closes its own reference, and the last close returns
the memory (to the pool, on a pooling binding). If N == 0 (no subscribers), the bus releases the payload
immediately — eliminating silent leaks from dead events. A brokered bus (the Kafka driver) copies the
payload onto the wire and releases the caller's reference exactly once, accepted or refused; the retain
protocol then applies to the fresh payload it creates for each consumed record.

### 3. Zero-Copy Native Flow

On the in-memory bus the same `EventPayload` object reaches every subscriber with RAII ref-count lifecycle — no copy and no serialization on the dispatch path. Serialization of a typed payload into those bytes happens **upstream of the bus**, at the producer (the ADR-046 codec seam, resolved in the generated publisher). Copies happen at the hops that leave the bus: when the Outbox is enabled, `CommunityEventBusOutboxBrokerPort` copies each polled row's bytes into a fresh heap payload and publishes it to the in-memory `EventBus` after the database commit; the Kafka driver's broker hop is a fixed-layout byte copy through `KafkaEventCodec` (48-byte header + payload tail), with no JSON or reflection, and the consume side slices the record's array rather than copying it. An off-heap log driver is out of this repository.

### 4. Backpressure by Design

`EventQueue` enforces Backpressure semantics. Two operating modes selected by
`EventEngineConfig.busPublishFailFast` (since 0.7.0):

- **`busPublishFailFast = false` (default — v0.6 backward-compat).** Persistent publishes block the
  publishing virtual thread on a full queue (`LinkedBlockingDeque.putLast`). Safe for VTs (no
  carrier pinning); never silently drops events but can stall the publisher under sustained
  saturation.
- **`busPublishFailFast = true` (Enterprise default; opt-in for Community).** Persistent publishes
  raise `EX-EVENT-6002` carrying `rawArgs == [String eventType, long queueDepth, long queueCapacity]`
  the moment the queue would overflow — the publisher's structured scope can then
  decide whether to fail-fast or shed the event. On every fail-fast refusal the engine's
  persistent-queueing bus (`CommunityEventEngine.PersistentQueueingBus`) also emits
  `CommunityEventQueueOverflowEvent` (JFR name `eu.exeris.kernel.events.CommunityEventQueueOverflow`,
  fields `engineName, eventType, queueDepth, queueCapacity`; EVENT-111, v0.8 Sprint 5) so operators
  can attribute overflow rates to specific event types and track backpressure trends — the per-call
  `EventBusException` leaves no post-mortem trail.

Only persistent descriptors (`FLAG_PERSISTENT`) enter the queue; a non-persistent publish goes straight
to the in-memory bus and never sees either mode.

The Kafka driver bypasses the local `EventQueue` (`NoOpQueue` slot) and does not map
`busPublishFailFast` onto anything: it never raises `EX-EVENT-6002`. A failure `producer.send` throws
synchronously surfaces as `EX-EVENT-6009` with reason `delivery-failed` and the Kafka exception as the
cause. `publish` does not wait on the send's future and registers no callback, so a failure the producer
reports asynchronously reaches neither the caller nor `KafkaPublishFailedEvent`; `publishAndAwait` waits
on that future and surfaces it as the same `EX-EVENT-6009`.

---

## SPI Architecture (The Composite Façade)

`EventEngine` is the single entry point. It integrates four orthogonal components:

| Component         | Responsibility                                                                          |
|:------------------|:----------------------------------------------------------------------------------------|
| **`EventBus`**    | Pub/Sub. Manages subscriptions (returns `SubscriptionToken`); `publish` is fire-and-forget, `publishAndAwait` blocks (see *Delivery Boundary* for what it waits on) |
| **`EventQueue`**  | Bounded backpressure buffer. Community: an in-memory heap `LinkedBlockingDeque`, not durable |
| **`EventLoop`**   | Drains the queue. Community: one virtual thread, dispatching each batch through a per-batch `StructuredScope` |
| **`EventRegistry`** | Type system. Maps event names → `int` ordinals for O(1) hot-path routing            |

`EventRegistry` is the critical performance gate: ordinal-based routing eliminates `String` comparison on
the hot-path entirely. `registry.ordinalOf("OrderConfirmed")` is an O(1) lookup; the returned `int` fits
in the `EventDescriptor` primitive layout.

---

## Multi-Provider Strategy

| Backend              | Best For             | Technical Advantage                                           |
|:---------------------|:---------------------|:--------------------------------------------------------------|
| **PostgreSQL**       | Local Event Sourcing | ACID-compliant atomic commits with entities                   |
| **Kafka / Redpanda** | Distributed Systems  | Broker-side page cache and zero-copy streaming. Community Kafka binding shipped in 0.7 (`exeris-kernel-community-kafka`); Redpanda speaks the same wire protocol, untested here (see *Redpanda vs. Kafka*) |
| **In-Memory**        | Testing / Ephemeral  | No broker hop, non-persistent                                 |

---

## Delivery Boundary: Single-Node Default vs Cross-Node (Kafka)

The default Community Events driver is **single-node**. Nothing in the SPI says so — it is
implementation-blind by design — and "Transactional Outbox" reads as cross-node delivery to anyone who
has met the pattern elsewhere. Stating the boundary is therefore the documentation's job, not the
contract's.

**The in-heap bus never crosses the node boundary.** `CommunityEventEngine` composes an in-JVM
`InMemoryEventBus`; a subscriber is an `EventHandler` registered in the same process. A second kernel
instance running the same application does not observe the first instance's publications.

**The Outbox is durable *emission*, not cross-node *delivery*.** A row is written to `exeris_outbox`
through `EventStore.append` inside the caller's own transaction, so the event commits atomically with
the entity that caused it and survives a crash between commit and dispatch. That is the guarantee it
buys. Where it dispatches *to* is the part that surprises: the default `OutboxBrokerPort` is
`CommunityEventBusOutboxBrokerPort`, which republishes onto **that same node's bus**. The durability is
real; the fan-out is local. Two conditions are worth knowing because neither announces itself — the
orchestrator is constructed only when `outboxEnabled` (default `true` in
`EventEngineConfig.communityDefaults()`) *and* a `PersistenceEngine` is bound, so on a kernel with no
persistence the outbox is silently inert; and the relay is a poll loop over committed rows, so it is
asynchronous with respect to the transaction that produced them.

**Cross-node delivery requires `exeris-kernel-community-kafka`.** `KafkaEventProvider` registers at
priority 50 and outranks the in-memory Community provider (priority 0), so adding the jar to the
classpath swaps the engine — intra-Community precedence, still below the Enterprise tier slot (100).
Publication then goes producer → broker, and local subscribers see the event only after the roundtrip
— deliberately, so a local subscriber has the same semantics as a remote one. The consume-side caveats documented for that driver hold: the poll loop runs with
`enable.auto.commit=true` (at-most-once on consume) and hands each decoded record to an internal
in-memory bus for local fan-out.

**`publishAndAwait` means something different on each side of that boundary.** `EventBus.isBrokered()`
says which contract a bus keeps. On a bus that is not brokered — every in-memory bus, including
`CommunityEventEngine`'s — `publishAndAwait` blocks until every handler has finished (the in-memory bus
runs them on the calling thread, in subscription order, so they observe every `ScopedValue` the publisher
bound), and throws `EX-EVENT-6010` if any handler threw. The Kafka bus reports `isBrokered() == true`:
its `publishAndAwait` blocks until the broker acknowledges the record — from every in-sync replica when
`requireAllAcks` is set (`acks=all`), from the partition leader otherwise (`acks=1`) — and promises
nothing about any handler. A handler may run after the call returns, on another thread or in another
process, sees none of the publisher's `ScopedValue` bindings, and cannot make the call throw
`EX-EVENT-6010`. Code that uses `publishAndAwait` to learn that a handler ran does not carry over to the
Kafka driver unchanged.

**The two are not composable today.** `KafkaEventEngine` runs no outbox orchestrator at all — its
`EventQueue` slot is a `NoOpQueue`, and `KafkaEventBrokerPort` ships as a built adapter that is not
wired into any runtime path. `CommunityEventEngine`, for its part, constructs the local broker port
directly with no seam to substitute. So a deployment gets durable emission on one node *or* cross-node
fan-out, not both from one engine. This is a current limit, not a contract: the outbox-orchestrator-driven
Kafka delivery path is listed as deferred in that module's `package-info`. [ADR-101](../adr/ADR-101-events-spi-contract-anchor.md)
(PROPOSED) keeps it a documented limit at 1.0 and places a configured broker-port selection after 1.0.

### Multi-node substrate inventory

What a "5 instances of the same app" deployment gets from the kernel today. This table is the
authoritative home for the answer; other docs link here rather than restating it.

| Concern | Substrate today | Where |
|:--------|:----------------|:------|
| Per-request state | Stateless — `PrincipalContext` / `StorageContext` propagate through `ScopedValue` per request, so any instance can serve any request | `spi.context`, no shared store needed |
| Durable shared state | PostgreSQL — saga snapshots with optimistic concurrency on the shared row | [ADR-013](../adr/ADR-013-distributed-saga-state-distribution-model.md); the snapshot store binds through the [ADR-022](../adr/ADR-022-persistence-spi-extension-instant-binders.md) persistence-SPI extension |
| Cross-node event fan-out | Kafka / Redpanda driver — see the boundary above | `exeris-kernel-community-kafka` |
| Cross-node coordination (distributed lock, leader election, singleton execution) | **No kernel seam.** Hand-rolled over Postgres advisory locks or Kafka consumer groups, with the fencing-token and lease-expiry burden on application code | ROADMAP → *Runtime: Cross-Node Coordination (Leader Election / Distributed Lock) — RFC Track* |

---

## Responsibilities

**What Events SPI DOES:**

1. Define `EventDescriptor` (primitive-only routing metadata) and `EventPayload` (ref-counted bytes — a heap array or an off-heap slab, as the binding chooses).
2. Provide `EventStreamReader` and `EventStreamAppender` interfaces (since 0.7.0, EVENT-203) plus the `StreamId` / `EventStream` carriers used to query and stream events. Implementation-blind — bindings (the PostgreSQL event log and the Kafka driver in this repository; an off-heap log outside it) provide the cursor.
3. Define `EventRegistry` ordinal contract for O(1) type routing.
4. Define routing flags on `EventDescriptor.flags` (PERSISTENT, ASYNC, ORDERED, BROADCAST). On Community, PERSISTENT decides whether a publish also enters the engine queue; ORDERED is a hint, **not** the ordering guarantee itself. Per **[ADR-049](../adr/ADR-049-events-log-ordering-and-optimistic-concurrency-boundary.md)**, per-`StreamId` total ordering and optimistic-concurrency **append-with-expected-version** are owned by the Events SPI on the durable-log surface (`EventStreamAppender`) — **not** by the transient `EventBus` (unordered by design) and **not** by Persistence. The separate `FlowSnapshot.schemaVersion` CAS (`JdbcFlowSnapshotStore`, ADR-013) is flow-snapshot state concurrency — a distinct mechanism, not the event-log append OCC. Persistence has no event-log append of its own: there is no `PersistenceEngine.append(streamId, expectedVersion, …)`.

**What Events Core DOES:**

1. Orchestrate the **Event Bus** for in-memory distribution with RAII ref-count lifecycle (`InMemoryEventBus`).
2. Manage the **Transactional Outbox** state machine to prevent Dual-Write problems (`OutboxOrchestrator`, `OutboxStateMachine`, `OutboxBatchFlusher`).
3. Manage local **Projections** via `ProjectionEngine` — each registered `Projection` subscribes a typed `ProjectionHandler<S>` to the bus and folds events into its state inside a `synchronized` handler, publishing the result through an `AtomicReference` so `state()` is a plain read.
4. Provide binary `EventDescriptorCodec` for off-heap serialisation of `EventDescriptor` structs (a 64-byte Panama FFM `MemoryLayout.structLayout`, little-endian). Core does not serialize payloads: payload bytes arrive already serialized (ADR-046 codec seam, Community JSON default).

---

## Log-Ordering & Optimistic-Concurrency Boundary (ADR-049)

The "one log, four views" family — streaming, sourcing, KV, distributed — all derive from a single durable log and must honour one consistency boundary. **[ADR-049](../adr/ADR-049-events-log-ordering-and-optimistic-concurrency-boundary.md)** settles where that boundary lives:

- **Durable log (`EventStreamAppender`) — ordering + OCC owned here (mandatory).** Every binding provides **per-`StreamId` total ordering** (concurrent appends to one stream are linearized and assigned strictly monotonic sequences; `EventStreamReader.replayFromVersion` reads them back in order) and honours an **append-with-expected-version** optimistic-concurrency check. Append shape: `AppendResult append(StreamId, long expectedVersion, EventDescriptor, EventPayload)` returning the committed 1-based per-stream sequence; an `ANY_VERSION` sentinel opts append-only callers out of the check; a version mismatch fails closed with `EX-EVENT-6008` (no silent overwrite).
- **Transient bus (`EventBus.publish`) — unordered by design.** The in-memory bus keeps its concurrent per-handler fan-out and makes **no** per-key / per-aggregate ordering promise. Ordering is a property of the durable-log surface only.
- **`FlowSnapshot.schemaVersion` CAS — a separate, Persistence-owned mechanism.** It is flow-snapshot state concurrency (ADR-013), not the event-log append OCC. The two are distinct and are not merged.
- **The Wall holds.** Bindings realize ordering/OCC privately (Kafka: `streamId`-keyed partition order + a log-authoritative per-stream sequence, OCC guaranteed only for a single writer per stream because Kafka has no compare-and-set; PostgreSQL event log: `exeris_event_log` with a per-stream `committed_sequence` column in the composite primary key — an append reads the head and inserts at head + 1, and a mismatch or a lost insert race fails closed; an off-heap log, outside this repository, would use a native sequence). No broker/JDBC type enters the Events SPI.

**Current state:** SPI surface (ADR-049, v0.10) — `EventStreamAppender.append(StreamId, long expectedVersion, EventDescriptor, EventPayload)` returning `AppendResult`, the `ANY_VERSION` sentinel, `EX-EVENT-6008` + `EventStreamAppendConflictException`, the `EventStreamReader` ordering contract, and the abstract TCKs (`AbstractEventStreamAppenderTck` ordering + OCC cases; `AbstractEventBusTck` no-ordering note). Both durable logs bind those TCKs: `CommunityJdbcEventStreamAppenderTckIT` / `CommunityJdbcEventStreamReaderTckIT` (PostgreSQL) and `CommunityKafkaEventStreamAppenderTckIT` / `CommunityKafkaEventStreamReaderTckIT` (Kafka), all integration-tagged.

---

## Binding-Agnostic `topic` (ADR-050)

The SDK `@DomainEvent.topic` attribute captures an author's routing target; **[ADR-050](../adr/ADR-050-events-binding-agnostic-topic.md)** gives it a kernel sink so it becomes portable across bindings.

- **Where it lives: `EventTypeSpec.topic` (per-*type*), NOT `EventDescriptor`.** `topic` is a static per-type attribute, so it rides the type-registration record alongside the existing `name` `String` (both registration/lookup only, never the hot dispatch path). The primitive-only, Valhalla-ready `EventDescriptor` — and both Kafka wire codecs — stay byte-for-byte unchanged. `null`/blank means "no override"; `EventTypeSpec.hasTopic()` reports a real override.
- **Kafka binding — honours the override on publish AND subscribe.** A single resolution `effectiveTopic(spec) = topicFor(spec.hasTopic() ? spec.topic() : spec.name())` feeds both the producer (`buildRecord`) and the consumer subscription (`refreshSubscriptions`); the `topicPrefix` still applies. A type with no override keeps the historical type-name topic.
- **In-memory bus — topic-blind (advisory).** `InMemoryEventBus` routes by `eventTypeOrdinal` only and does not consult `topic`; delivery is unaffected by whether a type carries one (consistent with the ADR-049 unordered-bus stance).
- **The Wall holds.** `topic` is a plain SPI `String`; only broker bindings assign it broker meaning.

**Current state:** kernel slice landed (v0.10) — `EventTypeSpec.topic` + factories + `hasTopic()`; the Kafka `effectiveTopic` resolution on both paths; `AbstractEventRegistryTck` topic round-trip, `KafkaTopicResolutionTest`, the `AbstractKafkaEventEngineTck` override round-trip, and the `AbstractEventBusTck` topic-blind note. The producing side — `exeris-tooling`'s `KernelEventGenerator` populating `EventTypeSpec.ofPersistent(name, ordinal, topic)`, and the `exeris-sdk` `@DomainEvent.topic` Javadoc — lives in other repositories; this page does not describe their state.

---

## Error Codes 

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code            | Meaning               | Glass-Box Payload (`rawArgs`)                                          |
|:----------------|:----------------------|:-----------------------------------------------------------------------|
| `EX-EVENT-6001` | Generic Engine Failure | None — set by the message constructors of `EventEngineException` and `EventBusException`, which leave `rawArgs` empty. The diagnostic is `getMessage()`; an upstream failure, when there is one, is `getCause()` |
| `EX-EVENT-6002` | Bus Queue Overflow    | `[0] String eventType, [1] long queueDepth, [2] long queueCapacity` (`EventBusException.publishOverflow`) |
| `EX-EVENT-6003` | Registry Conflict     | `[0] String eventType, [1] int ordinal` (`EventRegistryException.duplicateConflict`; `postStartRejected` reports ordinal `-1`) |
| `EX-EVENT-6004` | Provider Boot Failure | `[0] String providerName, [1] String reason` (`EventProviderException.creationFailure`) |
| `EX-EVENT-6005` | Outbox Event → DLQ | `[0] String eventType, [1] String reason, [2] int retryCount` — defined, not raised in this repository: the DLQ path records `OutboxDlqEvent` (JFR) and the DLQ row instead |
| `EX-EVENT-6006` | Projection Handler Failure | `[0] String projectionName, [1] int eventTypeOrdinal` — defined, not raised in this repository: `Projection` rethrows the handler's own exception and emits `ProjectionHandlerFailureEvent` |
| `EX-EVENT-6007` | Event-Loop VT Uncaught | `[0] String loopName, [1] String exceptionType` — defined, not raised in this repository: `CommunityEventLoop` emits `EventLoopFailureEvent` |
| `EX-EVENT-6008` | Append Version Conflict | `[0] String streamType, [1] long expectedVersion, [2] long actualVersion` (`EventStreamAppendConflictException.versionConflict`) |
| `EX-EVENT-6009` | Publish Not Accepted  | `[0] int eventTypeOrdinal, [1] String reason` (`EventBusException.publishFailed`). The kernel's bindings emit `delivery-failed` (the queue or broker client threw; its exception is the cause), `unregistered-type` (the Kafka bus was handed an ordinal its registry does not know; no cause) and `interrupted` (interrupted while waiting for queue space; no cause, interrupt status left set). No handler was given the event |
| `EX-EVENT-6010` | Handlers Failed After Delivery | `[0] int eventTypeOrdinal, [1] int failedHandlerCount` (`EventBusException.handlersFailed`). Raised by `publishAndAwait` on a bus that is not brokered only; no cause, each handler's exception attached as suppressed. The event was delivered, so a retry re-runs the handlers that succeeded |
| `EX-EVENT-6011` | Subscription Rejected | `[0] String eventType` (`EventBusException.subscriptionRejected`). The in-memory bus raises it for a type the `EventRegistry` does not know — register the type before subscribing |

Each exception class's message constructors set its code and leave `rawArgs` empty; the layouts above
are those of the static factories named in each row.

**Backpressure note for `EX-EVENT-6002`:** When thrown, the publisher MUST NOT retry inline. The
`EventBus` must propagate this exception to the caller's structured-scope boundary, allowing
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
            // read the bytes in place: a heap array on Community, a slab on a pooling binding
        }
        // auto-close releases this handler's reference; the last close frees the memory
    }
}
```

### 2. O(1) Routing via `EventDescriptor` (SPI)

`EventDescriptor` holds primitives only; routing reads its `int` ordinal, never a `String`.

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
> call `payload.close()` after this point, on success or on failure — the bus manages the ref-count lifecycle.

### 3. Transactional Outbox Bridge (Community)

The outbox row is written through `EventStore.append` on the same connection and transaction as the
aggregate write. `CommunityJdbcEventStore` is the Community `EventStore`:

```java
try (PersistenceConnection connection = KernelProviders.persistenceEngine().openConnection()) {
    connection.beginTransaction();
    try {
        // ... the aggregate write, on this connection ...
        new CommunityJdbcEventStore(connection).append(new EventStore.OutboxEvent(
                eventId, orderId, "Order", "OrderPlaced", payloadBytes, System.currentTimeMillis()));
        connection.commit();
    } catch (RuntimeException e) {
        connection.rollback();
        throw e;
    }
}
// After commit, the OutboxOrchestrator's poll loop reads the row and hands it to its
// OutboxBrokerPort — on Community, the same node's in-memory bus.
```

---

## Event Schema Evolution Strategy

`EventPayload` bytes are opaque to the Kernel. The schema (serialisation format) is the
responsibility of the application layer. The Kernel provides **no schema-version field**: routing is
by event-type ordinal, so a schema version is expressed as a distinct event type.

| Approach                             | Mechanism                                                                                       |
|:-------------------------------------|:------------------------------------------------------------------------------------------------|
| **Additive changes** (new fields)    | Use a schema registry (Avro/Protobuf) with backward-compatible evolution. Old consumers ignore unknown fields. |
| **Breaking changes** (renamed/removed fields) | Introduce a new event type (e.g., `OrderConfirmedV2`) and register it separately in `EventRegistry`. Route both versions simultaneously during the migration window. |
| **Version field in descriptor**      | Not available. `EventDescriptor.flags` bits 0–3 are the four routing flags (`FLAG_PERSISTENT` `0x01`, `FLAG_ASYNC` `0x02`, `FLAG_ORDERED` `0x04`, `FLAG_BROADCAST` `0x08`), and the SPI defines no schema-version encoding in the remaining bits. |
| **Kernel guarantee**                 | The durable event log (`EventStreamAppender`) is written only by append; the PostgreSQL binding issues no `UPDATE` or `DELETE` against `exeris_event_log`. Projection rebuilds (see Replay API below) can re-read all historical versions. The transactional outbox is not a history: delivered rows are marked published, and a row moved to the DLQ is deleted. |

---

## Dead Letter Queue (DLQ)

Outbox entries whose delivery the outbox's broker port keeps refusing are moved to a Dead Letter Queue.
The DLQ belongs to the transactional outbox, not to the `EventBus`.

**Trigger:** `OutboxBatchFlusher` retries each entry the broker port did not accept, one at a time, up to
`maxRetries` attempts with a linear backoff of `pollInterval × attempt`; an attempt that throws counts
as a refusal. When the attempts run out, the entry is written to `exeris_outbox_dlq` and its
`exeris_outbox` row is deleted, in one transaction. `maxRetries` is an `OutboxOrchestrator.Builder` setting (default 5);
`CommunityEventEngine` does not set it, and `EventEngineConfig` has no retry field, so on Community the
limit is the default. There is no per-subscription retry configuration on `EventBus`.

A handler that throws does not send an event to the DLQ. `CommunityEventBusOutboxBrokerPort` delivers
with the fire-and-forget `EventBus.publish`, so the orchestrator only ever learns whether the bus accepted
the event, never how its handlers fared.

**DLQ table** (created by the Community persistence migration `V0.5.0__create_outbox.sql` when
`persistence.runMigrations` is enabled):

```sql
CREATE TABLE IF NOT EXISTS exeris_outbox_dlq (
    id UUID PRIMARY KEY,
    stream_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload BYTEA NOT NULL,
    occurred_at BIGINT NOT NULL,
    failure_reason TEXT NOT NULL,
    moved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

**JFR event:** `OutboxDlqEvent` (`eu.exeris.kernel.core.events.jfr`) emitted on DLQ transition.
fields: `eventType (String)` — the decimal ordinal, not the type name; `streamIdHigh (long)`,
`streamIdLow (long)`, `reason (String)` — `max retries exhausted`, or the message of an exception that
escaped the retry path;
`retryCount (int)` — the configured limit, not the attempts made. No exception is raised on this path
(`EX-EVENT-6005` is not thrown).

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
    long ANY_VERSION = -1L; // skip the OCC check (unconditional append-only)
    // Per-StreamId ordering + optimistic concurrency (ADR-049): expectedVersion must match the
    // stream head (or ANY_VERSION to skip); mismatch -> EventStreamAppendConflictException (EX-EVENT-6008).
    // Same RAII ownership transfer as EventBus.publish(...); returns the committed per-stream sequence.
    AppendResult append(StreamId streamId, long expectedVersion, EventDescriptor descriptor, EventPayload payload);
}

public record StreamId(long streamIdHigh, long streamIdLow, String streamType) { ... }
```

`StreamId` is wire-compatible with `EventDescriptor.streamIdHigh()` / `streamIdLow()` so the same routing index serves both descriptor dispatch and stream-scoped queries.

**Replay allocation contract:** `EventStream extends Iterable<EventPayload>, AutoCloseable`; each payload arrives at refCount 1 and the consumer closes it (no broadcast retain protocol on replay), and the cursor is released via `EventStream.close()`.

Per-event allocation is bounded to one heap `byte[]` payload per row/record (no `List<EventPayload>` accumulation). Tier reality (ADR-049 Community bindings): the **JDBC** binding streams lazily over a live JDBC cursor; the **Kafka** binding performs a bounded read-to-end and materialises the matching `List<byte[]>` frames on heap before iterating (correct-but-not-scale-tuned — an advisory compacted-head checkpoint + partition-targeted reads are the deferred optimisation). Neither Community binding uses off-heap `LoanedBuffer` slabs — that zero-copy / WAL-streamed path is the out-of-repo Enterprise target-state, not the current Community behaviour.

**Bindings (status):**

- PostgreSQL event-log — **shipped (ADR-049, v0.10)**: `JdbcEventStreamAppender` / `JdbcEventStreamReader` over the dedicated `exeris_event_log` table (per-`StreamId` monotonic ordering + append-with-expected-version OCC → `EX-EVENT-6008`; `V0.10.0` migration). Bound into `KernelProviders.EVENT_STREAM_APPENDER` / `EVENT_STREAM_READER` by `CommunityEventsSubsystem` when a persistence engine is present. Distinct from the transactional `exeris_outbox` delivery drain.
- Kafka driver — **shipped in 0.7 Sprint 5b2** (`exeris-kernel-community-kafka`). `KafkaEventEngine` exposes the consumer roundtrip via its in-process `EventBus` delegate today; the ADR-049 durable event-log binding shipped in v0.10 — `KafkaEventStreamAppender` / `KafkaEventStreamReader` over a `streamId`-keyed log topic (log-authoritative per-`StreamId` sequence + append-with-expected-version OCC → `EX-EVENT-6008`), single-writer-per-stream best-effort (Kafka has no cross-instance CAS). Publish-side failures the bus observes (since v0.8 Sprint 5, JFR-091 — see *Backpressure by Design* for which ones `publish` observes) emit `KafkaPublishFailedEvent` (JFR name `eu.exeris.kernel.events.kafka.PublishFailed`, fields `engineName, topic, eventTypeOrdinal, publishMode, exceptionClass, exceptionMessage`) before the bus wraps the cause as `EX-EVENT-6009` with reason `delivery-failed`. **Payload bytes are never logged** (Glass-Box secret-safe contract); the `topic` lookup is best-effort and falls back to `"<unknown>"` on unregistered ordinals so the JFR emit never NPEs inside the catch block.
- Enterprise off-heap log — out-of-repo, target-state.

---

## Deduplication — Design Contract

The Kernel **does not provide built-in deduplication** at the `EventBus` level. This is an
intentional design contract, not an oversight.

| Layer           | Deduplication Mechanism                                                       |
|:----------------|:------------------------------------------------------------------------------|
| **EventBus (L3)** | None. The bus neither retries nor deduplicates. The outbox relay is at-least-once — a row published but not yet marked delivered when the process stops is published again — so an outbox-fed subscriber can see a duplicate. The Kafka consume path runs with auto-commit (at-most-once). |
| **Flow Engine (L4)** | `IdempotencyGuard` — per-Saga-step idempotency key prevents duplicate step execution. This covers the most critical deduplication requirement (business actions). |
| **Application layer** | Each subscriber must implement idempotency for its own state mutations. The `EventDescriptor.eventIdHigh/eventIdLow` fields provide a stable deduplication key (UUID stable across retries). |

**Rationale:** Built-in bus-level deduplication requires shared state (bloom filter or seen-set)
across all subscribers. In a multi-subscriber scenario this state is either a heap-allocated
`ConcurrentHashMap` (GC pressure) or a distributed lock (latency). Neither is acceptable at the
performance tier targeted by the Events subsystem. Subscribers that require exactly-once semantics
must implement their own idempotency using the event UUID as the deduplication key.

---

## Redpanda vs. Kafka — Semantic Differences

> **Status.** The Apache Kafka driver shipped in 0.7 Sprint 5b2 (`exeris-kernel-community-kafka`) and is
> tested against `confluentinc/cp-kafka:7.6.1`. Redpanda speaks the Kafka wire protocol, so pointing
> `events.kafka.bootstrap-servers` at a Redpanda broker needs no driver change; no test in this
> repository runs against Redpanda, so that compatibility is expected rather than verified. A dedicated
> Redpanda binding is out of scope.

The table describes the brokers, not the kernel. The driver uses no Kafka transactions and does not rely
on compaction, and no row below is exercised by a test in this repository:

| Behaviour              | Apache Kafka                                       | Redpanda                                              |
|:-----------------------|:---------------------------------------------------|:------------------------------------------------------|
| **Transactions**       | Kafka Transactions API (EOS — Exactly-Once Semantics) | Redpanda supports Kafka Transactions API (v23.2+)   |
| **Log compaction**     | Background `log.cleaner` thread (asynchronous, configurable delay) | Redpanda compaction is asynchronous, similar semantics |
| **Retention semantics**| Time-based (`retention.ms`) + size-based (`retention.bytes`) | Same API — compatible                                |
| **Consumer groups**    | Standard Kafka consumer group protocol             | Compatible (Kafka protocol v2)                        |
| **`sendfile` / zero-copy** | Page cache → socket via `sendfile(2)` (no JVM copy) | Identical — same Linux kernel path                 |
| **Ecosystem**          | Kafka Streams, ksqlDB                              | Single binary, no ZooKeeper                           |

---

## Testing Strategy

### Unit Tests

- `EventDescriptor` scalarization: no dedicated test. `EventBusZeroAllocTck` (Community binding `CommunityEventBusZeroAllocTckTest`) pre-builds the descriptor and bounds the per-publish `eu.exeris.*` allocations of the bus.
- `EventPayload` ref-count correctness: N subscribers → N-1 retains, every reference closed. Covered by `AbstractEventBusTck` on a bus that is not brokered and by `InMemoryEventBusTest`.
- `EventRegistry` ordinal conflicts: `EX-EVENT-6003` thrown on duplicate registration.
  > **Closed in 0.7 (EVENT-205a).** Covered by `AbstractEventRegistryTck`; Community binding `CommunityEventRegistryTckTest` green. Asserts both error code and `rawArgs == [String eventType, int ordinal]` per the documented Glass-Box layout.
- Backpressure: `EX-EVENT-6002` thrown with correct `rawArgs` when queue is at capacity.
  > **Closed in 0.7 Sprint 5b1 (EVENT-205b).** Covered by `AbstractEventBackpressureTck`; Community binding `CommunityEventBackpressureTckTest` green when the engine is configured with `busPublishFailFast = true`. Asserts both `EX-EVENT-6002` error code and the `[String eventType, long queueDepth, long queueCapacity]` rawArgs layout.

### Integration Tests (TCK)

- **Outbox Guarantee:** Take the broker down; verify committed events accumulate in the DB outbox and are all delivered once it recovers, survive an engine rebuild, and never reappear once marked published. *(`AbstractOutboxGuaranteeTck`, Community binding `CommunityOutboxGuaranteeTckTest`.)*
- **Provider Switching:** The same abstract suites run against more than one binding. `AbstractEventStreamAppenderTck` / `AbstractEventStreamReaderTck` bind to PostgreSQL and Kafka (see ADR-049 above). `AbstractEventBusTck` binds to the Community bus (`CommunityEventBusTckTest`) and to the Kafka bus (`KafkaEventBusTckTest`, mock producer and consumer, no broker); the Kafka bus reports itself brokered, so the five cases that assert in-process fan-out are reported as skipped there. *(Kafka roundtrip + bit-exact descriptor preservation: `AbstractKafkaEventEngineTck` / `CommunityKafkaEventEngineTckIT` — Testcontainers `confluentinc/cp-kafka:7.6.1`.)*
- **Order Integrity:** Per-`StreamId` order is a durable-log property, asserted by the ordering and OCC cases of `AbstractEventStreamAppenderTck`.
  > The `EventBus` makes no ordering promise (ADR-049), so no bus suite asserts one. On the Kafka bus the record key is the stream id, so one stream's records share a partition, but each consumed record is republished through the in-memory bus (one virtual thread per handler) and `AbstractKafkaEventEngineTck` asserts set equality for a stream, not order.
- **Zero Subscriber Fast-Free:** Publish to a bus with zero subscribers; verify the bus calls
  `payload.close()` exactly once (no silent leak). *(`AbstractEventBusTck`, every bus.)*
- **Broken Fan-Out Balance:** Fail the dispatch part-way — a `retain()` that throws, or a handler thread
  that cannot be started — and verify the refs no handler will ever own are released before the failure
  reaches the caller. Both `publish` and `publishAndAwait` owe this: the retain protocol above balances
  only when every subscriber is actually reached, and the paths that do not reach them are the ones
  where a leak is permanent. *(The obligation is part of the `EventBus` `@implSpec` for a bus that is not
  brokered; `AbstractEventBusTck` injects no failing `retain()` or thread start, so the coverage is
  `InMemoryEventBusTest`, for the in-memory bus only.)*

---

## Summary

The Events subsystem is the nervous system of the Exeris Kernel. The `EventDescriptor` / `EventPayload`
separation is the architectural core: primitive routing metadata enables O(1) dispatch and Valhalla
scalarization, while RAII `EventPayload` ref-counting is what lets a pooling binding reclaim memory
deterministically however many subscribers fan out. The bindings in this repository are heap-backed, so
there the count is a correctness discipline that the collector backs up.

Going from a single-node application to a distributed event-driven mesh costs no change to how
publishers and subscribers are written — the SPI is implementation-blind. It does cost a **deployment**
change: the default driver is single-node, and cross-node delivery means running on the Kafka driver.
One semantic changes with it: on the Kafka bus `publishAndAwait` waits for the broker, not for handlers.
See *Delivery Boundary: Single-Node Default vs Cross-Node (Kafka)* above for what each driver actually
carries.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.events.*`) is classified **preview** in the
[SPI Stability Matrix](../stability-matrix.md), which also records the 0.12 narrowing of
`EventBus.publishAndAwait` to buses that are not brokered. See the matrix for the semver policy and TCK
coverage status.

