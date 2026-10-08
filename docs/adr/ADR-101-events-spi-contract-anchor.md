---
title: "ADR-101: Anchor the events SPI — isolation travels with the event, and acceptance is not acknowledgement"
type: adr
visibility: public
owning-repo: exeris-kernel
status: draft
slug: adr/ADR-101
---

# ADR-101: Anchor the events SPI — isolation travels with the event, and acceptance is not acknowledgement

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **PROPOSED**                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-10-07                                                                                |
| **Scope**       | `kernel/events`                                                                           |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) "Kind 1" (`…spi.events` has no anchor ADR, so it cannot be promoted); issues #600 and #544; ROADMAP §"Events: Durable Emission And Cross-Node Delivery Cannot Be Had Together" |
| **Compliance**  | [docs/subsystems/events.md](../subsystems/events.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

`…spi.events` has been `preview` since 0.5.0. The stability matrix defines `stable` as an accepted
ADR **and** an executable TCK; the events row has the TCKs (`AbstractEventBusTck`,
`AbstractEventLoopTck`, `AbstractKafkaEventEngineTck` and siblings) and carries `—` in its anchor-ADR
column. ADR-046 (payload codec), ADR-049 (log ordering and optimistic concurrency) and ADR-050
(binding-agnostic `topic`) each rule one seam; none states what `EventBus`, `EventEngine` and
`EventDescriptor` promise as a whole. RFC-2026-09-02 names that absence as the one blocker on events
that cannot be resolved by improving the code. ADR-100 names this ADR as the precondition for
promoting `…spi.events` at 1.0.

Three open questions sit on the same classes and have to be answered before the surface freezes,
because each answer changes a signature or a documented promise:

- **#600 — an event carries no isolation key.** `EventDescriptor` has seven primitive components and
  no tenant field; `EventBus.subscribe(String, EventHandler)` filters by type only. A subscriber that
  serves one tenant receives every tenant's events. Whether a handler can see the publisher's
  `StorageContext` depends on the publish path (below), so no subscriber-side filter works on every
  path. exeris-tooling refuses tenant-partitioned live views at compile time until this is decided.
- **#544 — what `publish` promises on a brokered bus.** `KafkaPublishBus.publish` calls
  `producer.send(record)` with no callback and drops the returned future. A send that fails after
  `publish` returns is observed by nothing. `EventBus.publish`'s Javadoc does not say whether a
  normal return on a brokered bus means "handed to the client" or "acknowledged by the broker".
- **Durable emission vs cross-node delivery.** `KafkaEventEngine` runs no outbox orchestrator and
  `CommunityEventEngine` constructs `CommunityEventBusOutboxBrokerPort` inline, so a deployment gets
  the transactional outbox on one node or Kafka fan-out across nodes, not both. The ROADMAP entry has
  no 1.0 disposition.

The question this ADR answers: **what does the events SPI promise at 1.0, and how do isolation,
brokered acceptance and the durable/cross-node limit fit inside that promise?**

## What the SPI promises today (measured)

Measured on `development/0.13.0` at `66e33c92`. This section is the contract being anchored; the
rulings below change it only where they say so.

**Types.** `EventProvider` (`ServiceLoader` entry) yields an `EventEngine`, a composite facade over
`bus()`, `queue()`, `loop()`, `registry()` and `stats()`. `EventDescriptor` is a record of seven
primitives — `eventIdHigh`, `eventIdLow`, `streamIdHigh`, `streamIdLow`, `eventTypeOrdinal`, `flags`,
`occurredAtEpochMs` — and its Javadoc states that all components are primitives so that a future
`value` modifier needs no field change. `EventTypeSpec` (`name`, `ordinal`, `persistent`, `ordered`,
`topic`) is the per-type registration record. `EventRegistry` ordinals are **caller-defined**: the
registry arbitrates uniqueness (`EX-EVENT-6003`) and does not assign numbers.

**Lifecycle.** `EventEngine.start()` / `close()`; the engine is bound to
`KernelProviders.EVENT_ENGINE` once during bootstrap for the kernel's lifetime. Payload ownership
passes to the bus on `publish`/`publishAndAwait` entry on every outcome; the bus performs the
broadcast retain protocol (`N - 1` retains for `N` handlers) on a bus that is not brokered, and a
brokered bus copies the payload to the wire and releases the caller's reference exactly once.

**Two kinds of bus, selected by `EventBus.isBrokered()`** (default `false`, since 0.12):

| | Not brokered (`InMemoryEventBus`, Community `PersistentQueueingBus`) | Brokered (`KafkaPublishBus`) |
|:--|:--|:--|
| `publish` returns | after handing the event to the bus; handlers may already be running | after `producer.send(record)` returns |
| `publish` refuses with | `EX-EVENT-6002` (queue full, fail-fast), `EX-EVENT-6009` (`delivery-failed`, `interrupted`, `unregistered-type`) | `EX-EVENT-6009` (`unregistered-type`, or `delivery-failed` for a synchronous client exception) |
| `publishAndAwait` returns | after every handler has finished; `EX-EVENT-6010` if any threw | after the broker acknowledges (`acks=all` when `requireAllAcks`, else `acks=1`); never `EX-EVENT-6010` |
| Ordering | none (ADR-049); `FLAG_ORDERED` is a hint | none on the bus; the Kafka partition key is the stream UUID |

**`ScopedValue` inheritance, as implemented.** `InMemoryEventBus.publish` starts one handler thread
with `Thread.ofVirtual().start(...)`, which inherits no `ScopedValue` binding.
`InMemoryEventBus.publishAndAwait` runs handlers on the calling thread in subscription order, so they
observe every binding the publisher had, including values the kernel does not define;
`AbstractEventBusTck$ScopedValuePropagation#handlerInheritsPublishScopeScopedValue` pins this and is
skipped (`assumeFalse(isBrokered())`) on a brokered bus. A brokered bus's handlers run on the
consumer loop's thread after a broker round trip and observe none of the publisher's bindings. The
consequence for isolation: the publisher's `KernelProviders.STORAGE_CONTEXT` is visible to a handler
on exactly one of the four paths (`publishAndAwait`, not brokered).

**Wire formats.** `KafkaEventCodec` (bus topic) writes a fixed 48-byte big-endian header — the seven
descriptor components in record order — followed by the payload. `KafkaEventLogCodec` (durable log
topic, ADR-049) writes a 56-byte header: an 8-byte `committedSequence`, then the same seven
components. Neither frame carries a version or magic number; each decoder checks only
`frame.length >= HEADER_SIZE`. Core's `EventDescriptorCodec` is a 64-byte little-endian layout whose
last 16 bytes are padding. The JDBC outbox (`exeris_outbox`) stores no descriptor: the relay
rebuilds one in `CommunityJdbcOutboxEventStoreAdapter.toDescriptor` from the row's event id,
aggregate id, type name and timestamp, with flags fixed to `FLAG_PERSISTENT | FLAG_ASYNC`.

## Options Considered and Rulings

### Ruling 1 (#600) — where the isolation key lives

**Option 1A — a registered `int` isolation ordinal on `EventDescriptor` *(recommended)*.** An eighth
component, `int isolationOrdinal`, where `0` means *unscoped* (no bound context, or a context whose
`isolationKey()` is empty) and a positive value is a registered isolation key.

- Keeps every component primitive, as the record's Valhalla-readiness Javadoc requires, and keeps
  routing a plain `int` comparison with no allocation, which `EventBusZeroAllocTck` requires of the
  publish path ("`new EventDescriptor` per-call is NOT" acceptable on Community).
- `0` is also the default value of an `int`, so a future value-class default instance is unscoped —
  the fail-closed reading.
- Follows the existing registry pattern: `EventTypeSpec.ordinal` is caller-defined and the registry
  arbitrates uniqueness. The isolation registry has the same semantics.
- Survives both Kafka codecs and the outbox as a fixed-width field (see "What changes on the wire").
- **Cost:** a component added to a record. Mitigated as ADR-074 did for `HttpRequest`: the seven-arg
  canonical shape is **retained as a bridge constructor** passing `0`, and `EventDescriptor.of`'s
  seven-arg overload is kept beside a new eight-arg one, so every existing call site compiles and
  publishes unscoped. Measured call sites: `new EventDescriptor(` — 14 in main sources (the factory
  itself, both Kafka codecs, Core `EventDescriptorCodec.read`, 10 in `exeris-kernel-tck`) and 16 in
  test sources across 14 files; `EventDescriptor.of(` — 10 in main and 5 in test sources. Downstream,
  on each repository's `origin/main`: exeris-tooling 1 (`KernelEventGenerator`, the generated
  publisher), exeris-spring-runtime 5 (1 main, 4 test). No record pattern deconstructs
  `EventDescriptor` in any of the three repositories, so the bridge covers every site found.
- **Cost:** the mapping from `StorageContext.isolationKey()` (a `String`) to the ordinal must be
  **identical on every node and stable over time**, because a brokered bus carries the ordinal to
  other processes and a durable log replays it later. An event-type ordinal is fixed at build time; a
  tenant key is runtime data. Two nodes assigning the same ordinal to different tenants would deliver
  one tenant's events to another. The registry therefore never assigns: the application supplies the
  ordinal (typically a tenant's numeric identifier), exactly as it supplies event-type ordinals.

**Option 1B — a deterministic 128-bit digest on `EventDescriptor`.** Two `long` components holding a
fixed digest of the isolation key. Primitive, needs no registry and no cross-node agreement, and keeps
tenant identifiers out of the wire and JFR. Costs: 16 bytes per descriptor instead of 4, a digest
computed per publish (cacheable per context), a key that cannot be read back for diagnostics, and a
collision probability that is negligible at 128 bits but not zero.

**Option 1C — the key outside the descriptor** (payload metadata, a Kafka record header only, or the
subscription alone). Leaves the descriptor untouched. Rejected: a key in payload metadata is
codec-specific (ADR-046) and invisible to routing; a key only in the subscription has nothing on the
event to compare against; a key only in a transport header does not exist on the in-memory bus.

**Option 1D — stamped key, compared by the subscriber.** The key travels with the event and each
subscriber drops mismatches. Rejected as the primary API: it is fail-open by construction — a
subscriber that forgets the comparison receives every tenant — which is the property #600 exists to
remove. The stamped key it relies on is still part of Option 1A, so a subscriber that wants to
compare can.

**Ruling required — the encoding of the key: registered `int` ordinal (1A) or 128-bit digest (1B).**
The evidence is balanced. 1A is the smaller, cheaper, zero-allocation shape and matches the existing
ordinal pattern; its correctness rests on an application-supplied mapping being consistent across
nodes and over time, which the kernel can enforce only per process. 1B removes that dependency at
the cost of width and readability. The recommendation is 1A, with the obligations below making an
unregistered key refuse rather than fall back.

**Delivery rule (either encoding):**

1. **Stamped at publish from `STORAGE_CONTEXT`.** The engine's public `bus()` resolves the effective
   ordinal from the bound `KernelProviders.STORAGE_CONTEXT`: unbound or empty `isolationKey()` → `0`;
   a registered key → its ordinal; an unregistered key → refused with `EX-EVENT-6009`, reason
   `"unregistered-isolation-key"`. A descriptor carrying `0` under a bound key is stamped with the
   bound ordinal; a descriptor carrying a non-zero ordinal that differs from the effective one is
   refused with `EX-EVENT-6009`, reason `"isolation-mismatch"`. A publisher can therefore neither
   forget nor forge the key, and one that pre-stamps the descriptor (as a generated publisher will)
   pays no descriptor allocation.
2. **Internal redistribution is not re-stamped.** The Kafka consumer loop's republish onto its local
   delegate and the outbox relay run on threads with no bound context; they carry the ordinal they
   decoded or persisted. Stamping applies at the engine's public `bus()` only.
3. **Consumer API: a scoped subscription.** `EventBus` gains
   `SubscriptionToken subscribeScoped(String eventType, EventHandler handler)`, which captures the
   caller's bound `STORAGE_CONTEXT` at call time and delivers only events whose ordinal equals it. With
   no bound context, an empty key or an unregistered key it refuses with `EX-EVENT-6011`. The default
   method refuses with `EX-EVENT-6011`, so an implementation that has not adopted the rule is
   fail-closed rather than silently unscoped — the refusing-default pattern
   `FlowExecutionPlanFactory.registerMigration` already uses. An explicit-key overload is not offered: a subscriber naming a tenant it is
   not bound to is the forgery the stamp exists to prevent.
4. **Unscoped subscription is unchanged.** `subscribe(String, EventHandler)` still receives every
   event of the type, scoped or not. A tenant-serving subscriber must use `subscribeScoped`.
5. **An event published with no bound context reaches only unscoped subscribers.**

**What changes on the wire.** Because neither Kafka frame carries a version, growing either header
would make frames written by 0.12 decode with the first four payload bytes read as an ordinal — on
the log topic, permanently, since that topic is replayed. The ordinal therefore travels as a Kafka
**record header** (four bytes, big-endian) written on every producer path (`KafkaPublishBus`,
`KafkaEventStreamAppender`, `KafkaEventBrokerPort`) and read on every consumer path; an absent header
decodes as `0`. Both frame layouts stay byte-identical, and a record written by 0.12 is read as
unscoped, which is the fail-closed reading. Core's `EventDescriptorCodec` places the ordinal at offset
48 inside its existing padding; its size stays 64 bytes and a 0.12 frame (zero padding) decodes as
`0`. The JDBC outbox and event log gain an `isolation_ordinal` column (default `0`) through the
schema ledger (ADR-073), written from the context bound at append time; without it an outbox-relayed
event reaches only unscoped subscribers.

### Ruling 2 (#544) — what `publish` promises on a brokered bus

**Option 2A — fire-and-forget with an observable failure *(recommended)*.** On a brokered bus a
normal return from `publish` means the binding's client **accepted** the record for sending; it does
not mean the broker acknowledged it. A failure detected before return is refused as today
(`EX-EVENT-6009`). A failure after return is never reported to the caller and is always recorded: the
send carries a callback that, on an exception, emits the existing `KafkaPublishFailedEvent` (with a
`publishMode` value distinct from the synchronous `"publish"` and `"publishAndAwait"`) and increments
a counter. A caller that needs acknowledgement uses `publishAndAwait`, which already blocks on it.

- Keeps `publish` and `publishAndAwait` distinct on a brokered bus, and keeps the producer's batching
  (`linger.ms`) useful.
- Matches the counter already in place: `publishedTotal` is incremented after `producer.send`
  returns, so it already counts accepted records.
- The callback runs on the producer's I/O thread; it emits a JFR event and increments an atomic, and
  does nothing that blocks.

**Option 2B — "not accepted until acknowledged".** `publish` blocks on the send's future. Rejected:
it makes `publish` identical to `publishAndAwait` on a brokered bus, serialises every publisher on a
broker round trip, and removes the reason the two methods differ.

**Option 2C — `publish` returns a completion handle.** Rejected: a signature change on both bus kinds
for a property only one of them has, and a handle the non-brokered bus cannot complete meaningfully.

**Ruling required — where the counter lives.** (a) A new `EventEngineStats` component,
`publishFailedTotal` (accepted, then failed asynchronously), with a bridge constructor; measured
`new EventEngineStats(` sites: 3 in main, 14 in test sources. (b) A binding-local counter carried
only in the JFR event. `failedTotal` is not reused: it counts handler errors. The recommendation is
(a): `…spi.events` is about to freeze, and a counter that `stats()` cannot report would need a second
change to a stable record later.

### Ruling 3 — durable emission vs cross-node delivery at 1.0

**Option 3A — a documented 1.0 limit; the broker-port selection is post-1.0 *(recommended)*.** At 1.0
the transactional outbox and cross-node fan-out are not composable, and the documentation says so
(`events.md` "Delivery Boundary"). Making the `OutboxBrokerPort` a configured selection — the engine
taking its port from configuration, and the Kafka module running the orchestrator with
`KafkaEventBrokerPort` bound — adds a seam and a configuration key without changing any SPI
signature, so it is additive after 1.0.

- No SPI type changes: `OutboxBrokerPort` is a Core interface, and both adapters already implement it.
- The work it needs is not a seam alone. With a Kafka broker port, the outbox relay and
  `KafkaPublishBus` are two routes to one topic and one must become the only one; and the relay must
  use an acknowledging send (as `KafkaEventBrokerPort` does with `send(...).get()`), never Ruling 2's
  fire-and-forget `publish`, or the outbox would mark a row published that the broker never received.
  That is a design, not a wiring change, and does not fit before the RC.

**Option 3B — make it composable before 1.0.** Rejected for 1.0 on the grounds above; no consumer
has asked for it in the 0.13 window, and the merge gate the ROADMAP sets (an event committed on one
instance reaching a second after a crash of the first) needs a two-instance harness that does not
exist.

**Option 3C — remove the Kafka outbox adapter until it is wired.** Rejected: `KafkaEventBrokerPort`
is Wall-clean, tested (`KafkaEventBrokerPortTest`) and is the post-1.0 path.

## 🏁 The Decision

**The events SPI is anchored as described in "What the SPI promises today", amended by three rulings
(proposed): an isolation ordinal travels on `EventDescriptor` and a scoped subscription delivers only
matching events; a brokered `publish` means accepted, not acknowledged, and an asynchronous failure
is always recorded; durable emission and cross-node delivery are a documented 1.0 limit.**

Implementation is planned for 0.13 (wave W2). Until it lands, none of the behaviour in Rulings 1 and
2 exists, and the documentation does not describe it as shipped.

**Concrete obligations (W2):**

1. **SPI change, one commit.** `EventDescriptor` gains `isolationOrdinal` with the seven-arg bridge
   constructor and seven-arg `of` retained; `EventBus.subscribeScoped` lands as a refusing default;
   the isolation-key registry lands with `EventRegistry`'s semantics (caller-defined ordinal,
   uniqueness arbitrated, `EX-EVENT-6003`-shaped conflict, positive ordinals only); the two new
   `EX-EVENT-6009` reasons are added to `KernelErrorCodes.EX_EVENT_6009`'s Javadoc. The
   `stability-matrix.md` note and `stability-surfaces.conf` entry land in the same commit (ADR-065).
2. **Bindings.** `InMemoryEventBus` filters scoped slots by `int` comparison with no added allocation
   on the unscoped path; Community `PersistentQueueingBus` and `KafkaPublishBus` stamp at publish;
   the Kafka record header is written on all three producer paths and read on both consumer paths;
   `EventDescriptorCodec` uses its padding; the outbox and event-log tables gain `isolation_ordinal`.
3. **`publish` contract text.** `EventBus.publish`'s Javadoc states that on a brokered bus a normal
   return means accepted by the binding's client, not acknowledged, and that a later failure is
   recorded rather than thrown. `KafkaPublishBus.publish` sends with a callback that emits
   `KafkaPublishFailedEvent` and increments the counter (Ruling 2's placement).
4. **TCK — isolation.** In `AbstractEventBusTck` (extended by `CommunityEventBusTckTest` and
   `KafkaEventBusTckTest`), and in `AbstractKafkaEventEngineTck` against a real broker
   (`CommunityKafkaEventEngineTckIT`, `@Tag("integration")`, run by `kafka-integration-gate`) so the
   record header makes the round trip: a **cross-tenant delivery probe** — two scoped subscribers
   bound to different keys, one publish under each, each subscriber receives exactly its own event,
   on `publish` and on `publishAndAwait`; an event published with no bound context reaches an unscoped
   subscriber and no scoped one; a forged non-zero ordinal and an unregistered key are refused with
   the named reasons; `subscribeScoped` with no bound context is refused with `EX-EVENT-6011`. Each
   case is mutation-checked in both directions: dropping the filter must redden the probe, and
   filtering everything must redden the unscoped case.
5. **TCK — failure observability.** A Kafka-binding test over `MockProducer` with
   `autoComplete = false`: `publish` returns, `errorNext(...)` fails the send, and the test reads back
   `KafkaPublishFailedEvent` from a JFR recording and observes the counter at `1`. The test must fail
   against the current `KafkaPublishBus.publish` (no callback).
6. **Wire compatibility.** A codec test decodes a frame written without the header as ordinal `0`,
   for both Kafka codecs; `EventBusZeroAllocTck` gains a case publishing a pre-stamped descriptor
   under a bound context.
7. **Gate.** `kafka-integration-gate` (`.github/workflows/maven.yml`) runs green on the change.
8. **Docs.** `events.md` gains the isolation rule and the brokered-`publish` promise when, and only
   when, the code above lands.

## Consequences

### ✅ Positive Outcomes

- **[+] `…spi.events` has an anchor ADR**, which removes the blocker RFC-2026-09-02 named and lets
  ADR-100 rule on promotion.
- **[+] Isolation is fail-closed in the kernel**, not re-implemented per subscriber; exeris-tooling can
  retire its compile-time refusal once W2 lands.
- **[+] A dropped Kafka send leaves a trace** — JFR event and counter — instead of nothing.
- **[+] The 1.0 limit is a decision**, so the operator-visible cost recorded in the ROADMAP is a
  documented boundary rather than an open question.

### ⚠️ Trade-offs

- **[-] An eighth descriptor component**, and a bridge constructor kept for the life of 1.x.
- **[-] Correctness under 1A depends on the application** supplying the same key-to-ordinal mapping on
  every node and across restarts; the kernel can refuse an unregistered key but cannot detect two
  nodes that disagree.
- **[-] A brokered `publish` still cannot tell its caller about a later failure**; it only records it.
- **[-] Multi-node deployments still choose** between the outbox and Kafka fan-out at 1.0.

### 📋 What is NOT in scope

- Tenant filtering of `EventStreamReader` replay. The log carries the ordinal after W2, so a later
  ruling can filter on it; this ADR does not.
- The payload codec (ADR-046), log ordering (ADR-049) and `topic` (ADR-050): unchanged.
- Consume-side delivery semantics of the Kafka binding (`enable.auto.commit=true`, at-most-once on
  consume), recorded in `KafkaEventEngine`'s Javadoc.

### 🚫 Non-Goals

- A scoped subscription that names a key other than the bound one.
- A completion handle on `publish`.
- Any cross-node coordination beyond what the broker already provides.

### ⚠️ Risks and Assumptions

- **Assumes:** Kafka record headers are carried end to end by the brokers and tooling in use
  (supported since Kafka 0.11; the binding uses `kafka-clients` 4.0.x).
- **Reversed by:** a deployment that cannot supply a stable key-to-ordinal mapping — which argues
  for Option 1B — or a measured need for acknowledged `publish` on the brokered bus.
- **Risk:** a subscriber that keeps using unscoped `subscribe` for tenant data still receives every
  tenant's events. The SPI cannot prevent that; exeris-tooling's generated live view is the consumer
  that switches.

## Cross-references

- [ADR-046](ADR-046-event-payload-codec-spi.md) — payload codec; why the key is not payload metadata.
- [ADR-049](ADR-049-events-log-ordering-and-optimistic-concurrency-boundary.md) — the bus is
  unordered; the log owns ordering and replays the frame Ruling 1 must not break.
- [ADR-050](ADR-050-events-binding-agnostic-topic.md) — the per-type registration pattern Ruling 1
  follows, and the first record of the unwired Kafka outbox adapter.
- [ADR-074](ADR-074-http-client-peer-addressing.md) — the retained-canonical-constructor bridge for a
  component added to a record.
- [ADR-065](ADR-065-spi-compatibility-gate.md) — the gate that reports the record change.
- [ADR-073](ADR-073-schema-history-ledger.md) — the ledger the outbox and log column additions go
  through.
- [ADR-012](ADR-012-security-trust-model-upgrade-for-resource-server-validation-and-fail-closed-runtime.md)
  — `StorageContext` as the tenant-isolation carrier.
- [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) — the promotion question this
  anchor unblocks.
- [docs/subsystems/events.md](../subsystems/events.md) — "Delivery Boundary".

## Engineering Protocol

- Obligation 1 lands as one commit: ADR-065's gate fails the build on an unclassified SPI change.
- Each TCK case in obligations 4 and 5 is shown red before it is shown green, against the
  implementation it is meant to reject.
- The stability-matrix anchor column for `…spi.events` names this ADR only once it is ACCEPTED and
  obligations 1–7 have landed; until then the row stays `preview` with `—`.
