# ADR-049: Events Log-Ordering & Optimistic-Concurrency Boundary — the durable log owns append-with-expected-version

| Attribute       | Value                                                                                                                                                                                       |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ADR #**       | **049** (reserved 2026-07-01 in `exeris-docs/adr-index.md`).                                                                                                                              |
| **Status**      | **Accepted** — decision-only slice on the kernel v0.10 train. This slice ships the decision (this ADR), the `events.md` boundary record, and the `EventStreamAppender` contract Javadoc. The SPI signature change, the `EX-EVENT-6008` error code, and the TCK bindings are the deferred **implementation slice** (still v0.10). Reaches `main` with the 0.10 release. |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                       |
| **Date**        | 2026-07-01                                                                                                                                                                                 |
| **Scope**       | kernel/events                                                                                                                                                                             |
| **Owning Repo** | `exeris-kernel`                                                                                                                                                                            |
| **Driven By**   | The "one log, four views" sourcing/streaming fundament (v0.10 ROADMAP §"Events: Log-Ordering & Optimistic-Concurrency Boundary"); the gate on v0.12 KV-as-projection + distributed; downstream dogfooding (2026-06). |
| **Compliance**  | The Wall (ADR-006); No Waste Compute; Java-26 idioms (immutable carriers, `ScopedValue`); distinct from — and consistent with — the `FlowSnapshot` CAS (ADR-013).                          |

## Context and Problem Statement

The kernel's Events surface is the substrate for the "one log, four views" shape: *streaming* (the log read forward as transport), *sourcing* (the log as source of truth, state = fold over replay), *KV* (the log compacted to last-value-per-key), and *distributed* (the same log replicated across nodes). All four derivations require one explicit consistency boundary — per-stream ordering plus an optimistic-concurrency append — that they can all rely on. Today that boundary is **not decided and not on the Events SPI**.

Verified against the current source tree:

- **The append surface is versionless.** `EventStreamAppender.append(StreamId, EventDescriptor, EventPayload)` (`exeris-kernel-spi/.../events/EventStreamAppender.java:62`) takes no expected-version / sequence parameter and returns nothing — fire-and-forget. `EventStreamReader` / `EventStreamAppender` / `EventStream` are unbound skeletons (`@since 0.7.0`, zero main-source implementors; the `KernelProviders.EVENT_STREAM_READER` / `EVENT_STREAM_APPENDER` `ScopedValue` slots are wired but unbound).
- **The transient bus is intentionally unordered.** `InMemoryEventBus.publish()` (`exeris-kernel-core/.../events/InMemoryEventBus.java`) starts one virtual thread per handler — concurrent fan-out, fire-and-forget — so there is no per-key / per-aggregate delivery order by construction, and `AbstractEventBusTck` asserts none.
- **The only optimistic-concurrency mechanism that exists is flow-scoped and Persistence-owned.** `FlowSnapshot.schemaVersion` (`exeris-kernel-spi/.../flow/model/FlowSnapshot.java:71`) enforced by `JdbcFlowSnapshotStore` `SQL_UPDATE_OCC` (`exeris-kernel-community/.../flow/JdbcFlowSnapshotStore.java`) is a compare-and-set on *flow snapshot state* (ADR-013). It is **not** a general event-log append OCC.
- **The subsystem doc points at a method that does not exist.** `docs/subsystems/events.md:166` claims "optimistic concurrency version enforcement is a Persistence SPI concern (`PersistenceEngine.append(streamId, expectedVersion, …)`)". There is **no** `PersistenceEngine.append(StreamId, long expectedVersion, …)` anywhere in the persistence SPI — the documented owner is a phantom.

Sourcing (per-aggregate strict ordering + optimistic-concurrency append + long retention) and streaming (fan-out throughput + consumer offset + time/size retention) want *different* guarantees from the *same* log, and KV and distributed both build on the ordered log. The kernel has not decided **where the ordering + append-OCC guarantee lives**, so the fundament that gates the whole family is missing.

**This ADR answers: where does per-`StreamId` ordering + optimistic-concurrency append live, and is the guarantee mandatory or binding-defined?**

## 🏁 The Decision

**Per-`StreamId` total ordering and the optimistic-concurrency append-with-expected-version contract are owned by the Events SPI on the durable-log surface (`EventStreamAppender`); the transient `EventBus` stays unordered by design; and the `FlowSnapshot` CAS stays a distinct, Persistence-owned flow-state mechanism.**

The kernel already carries the clean surface split this decision needs: `EventStreamAppender`'s own contract distinguishes the *in-process pub/sub* path (`EventBus.publish`, no durability) from the *durable append to the shared distributed log*. The ordering/OCC guarantee is a property of the **log**, so it belongs on the log-append contract — not on the bus, and not on Persistence.

**Concrete obligations:**

1. **The append surface gains expected-version + a committed-sequence result (implementation slice).** The target shape is
   ```java
   AppendResult append(StreamId streamId, long expectedVersion,
                       EventDescriptor descriptor, EventPayload payload);
   record AppendResult(long committedSequence) {}
   long ANY_VERSION = -1L; // opt out of the OCC check for append-only / non-CAS callers
   ```
   A reviewer can assert: after the impl slice, `EventStreamAppender.append` carries an `expectedVersion` argument and returns the committed sequence; the versionless single-argument form is gone.
2. **Per-`StreamId` total ordering is mandatory for every `EventStreamAppender` binding.** Concurrent appends to the same `StreamId` are linearized and assigned strictly monotonic sequence numbers; `EventStreamReader.replayFromVersion(streamId, fromVersion)` reads them back in that order. A binding that cannot provide per-stream ordering does not implement `EventStreamAppender`.
3. **Expected-version conflict fails closed.** When `expectedVersion != ANY_VERSION` and it does not equal the stream's current head sequence, the append is rejected with the new structured `EX-EVENT-6008` (event-log append version conflict; `rawArgs [0] String streamType, [1] long expectedVersion, [2] long actualVersion`). No silent overwrite, no out-of-order append.
4. **The transient `EventBus` stays unordered by design.** `EventBus.publish` keeps its concurrent per-handler fan-out; no per-key ordering guarantee is added. `AbstractEventBusTck` records the *absence* of an ordering promise (a documented no-ordering note), rather than asserting ordering. Ordering is a property of the durable-log surface only — this keeps the intentionally-unordered in-memory bus unchanged (additive, no reshaping of `InMemoryEventBus.publish`).
5. **`FlowSnapshot.schemaVersion` CAS stays Persistence-owned and separate.** It remains the flow-snapshot state concurrency mechanism (`JdbcFlowSnapshotStore` `SQL_UPDATE_OCC`, ADR-013). It is **not** merged with, and **not** the same as, the event-log append OCC. `docs/subsystems/events.md` is corrected to stop attributing event OCC to a (non-existent) `PersistenceEngine.append(streamId, expectedVersion)`.
6. **The Wall holds.** No JDBC / Kafka / broker types enter the Events SPI. Each binding realizes ordering/OCC in its own layer: Kafka routes on `streamId` as the message key for per-partition order and maintains a per-stream sequence for the version check; the Postgres outbox uses a per-stream sequence column + an `INSERT … WHERE expected = current` CAS; the Enterprise off-heap log uses its native sequence.
7. **Verification is the merge gate for the fundament (implementation slice).** Bind `AbstractEventStreamAppenderTck` and `AbstractEventStreamReaderTck` on ≥2 durable bindings — the Community Postgres outbox path and the Kafka driver — asserting per-stream monotonic ordering, `expectedVersion` conflict → `EX-EVENT-6008`, and the `ANY_VERSION` append-only path. Add the `EventBus` no-ordering note to `AbstractEventBusTck`.

## Consequences

### ✅ Positive Outcomes

- **[+] One portable contract the four views rely on.** Sourcing (fold over ordered replay) and KV (compacted last-value-per-key) get their substrate; distributed replicates an already-ordered log. The v0.12 KV-as-projection and distributed items are unblocked at the contract level.
- **[+] The guarantee lives where it is true.** Ordering/OCC is a log property, expressed on the log-append contract — not smeared across Persistence (a phantom method) or forced onto a pub/sub bus that was never meant to order.
- **[+] No reshaping of the hot in-memory path.** The unordered `EventBus` is unchanged; only durable-log bindings carry the new obligation.
- **[+] The Wall is preserved.** Binding-private realization (Kafka key/sequence, Postgres CAS) keeps broker/JDBC detail out of the SPI.
- **[+] Additive, pre-1.0.** Zero external SPI consumers; the appender has no main-source implementors and its slot is unbound, so evolving the signature is safe (TRL-3 — no "breaking change" framing applies).

### ⚠️ Trade-offs

- **[-] Durable-log bindings must maintain a per-stream sequence.** Real implementation cost — notably Kafka, whose offsets are per-partition, needs a side per-stream sequence to honour the version check; it is not free from broker semantics alone.
- **[-] The `@FunctionalInterface` append signature changes.** Acceptable pre-1.0 (no implementors, unbound slot), but it does retire the current single-argument form and adds `AppendResult` + `ANY_VERSION` to the SPI surface.
- **[-] A new error code (`EX-EVENT-6008`) and TCK surface.** Small, additive verification debt carried into the implementation slice.
- **[-] An in-memory "log" appender (if ever built) must still provide ordering** or simply not implement `EventStreamAppender` — the unordered bus cannot double as a log.

### 📋 What is NOT in scope

- **The concrete SPI signature change, `EX-EVENT-6008`, and the TCK bindings** — those are the implementation slice (still v0.10); this ADR records the decision and the target contract only.
- **The Events `topic` seam** (a separate v0.10 roadmap item) and the **Event-Payload Codec** (ADR-046, already landed) — orthogonal additive events-SPI seams.
- **Any change to `FlowSnapshot` / `JdbcFlowSnapshotStore` CAS** (ADR-013) — it stays as-is, documented as a distinct mechanism.
- **Compaction / keyed projection state** (v0.12 KV-as-projection) — this ADR is the fundament they depend on, not the KV implementation.
- **`EventDescriptor.flags` `ORDERED`** semantics beyond noting it as an advisory routing hint, not the ordering guarantee.

## Cross-references

- ADR-013 (Distributed Saga State Distribution Model) — the `FlowSnapshot.schemaVersion` CAS that stays Persistence-owned and separate from this event-log OCC.
- ADR-046 (Event-Payload Codec SPI) — sibling additive events-SPI seam; the `EVENT_STREAM_*` slot precedent this decision builds on.
- ADR-006 (Spring-Free Kernel Boundary / The Wall) — the boundary the binding-private ordering realization respects.
- `exeris-kernel/docs/subsystems/events.md` — the boundary record updated by this slice.
- `exeris-kernel/docs/ROADMAP.md` §"Events: Log-Ordering & Optimistic-Concurrency Boundary" (v0.10) and §"Runtime: KV-as-Projection" (v0.12).

## Engineering Protocol

1. **This slice (decision-only):** this ADR; the `events.md` boundary record + the corrected Responsibilities line (no more phantom `PersistenceEngine.append`); the `EventStreamAppender` Javadoc stating the sequencing/OCC semantics. No signature change yet. Global index row registered in `exeris-docs/adr-index.md` as a separate commit.
2. **Implementation slice (still v0.10):** change `EventStreamAppender` to `AppendResult append(StreamId, long expectedVersion, EventDescriptor, EventPayload)` with `ANY_VERSION`; add `EX-EVENT-6008`; state the per-stream ordering contract on `EventStreamReader`; bind `AbstractEventStreamAppenderTck` / `AbstractEventStreamReaderTck` on the Community Postgres outbox + Kafka bindings; add the `EventBus` no-ordering note to `AbstractEventBusTck`. That TCK pass on ≥2 bindings is the merge gate.
3. **Downstream (v0.12):** KV-as-projection and the distributed replicated-log work consume this contract; they do not reopen it.

## Implementation Addendum (v0.10, PR 2 — 2026-07-01)

The Community durable binding lands on a **dedicated `exeris_event_log` table** (migration `V0.10.0`), **not** the existing `exeris_outbox`. This **refines obligation 6 and the Context's "Postgres outbox uses a per-stream sequence column" phrasing**: the outbox is a transactional *delivery drain* — a global `outbox_seq`, `aggregate_id` as TEXT (not the `StreamId` UUID high/low pair), and rows marked-published / moved-to-DLQ / deleted — so it cannot serve as the replayable, per-stream-ordered source of truth this contract requires. A separate table keeps the ordered/replayable log decoupled from the outbox drain (the "one log, four views" intent) and mirrors how `exeris_saga_state` is its own table.

The **OCC mechanism is unchanged** from the decision: a per-`StreamId` `committed_sequence` head (1-based) + `INSERT`-at-`head+1` under `READ_COMMITTED`, with the composite primary key `(stream_id_high, stream_id_low, committed_sequence)` doubling as the uniqueness constraint that fails a lost race closed (`EX-EVENT-6008`; `ANY_VERSION` retries at the new head, bounded). Bindings: `JdbcEventStreamAppender` / `JdbcEventStreamReader` / `JdbcEventStream` in `eu.exeris.kernel.community.events`, bound into `KernelProviders.EVENT_STREAM_APPENDER` / `EVENT_STREAM_READER` by `CommunityEventsSubsystem` when a persistence engine is present. Conflict/failure paths emit secret-safe JFR (`EventLogAppendConflictEvent` / `EventLogAppendFailedEvent`). Verified by `CommunityJdbcEventStream{Appender,Reader}TckIT` (Testcontainers Postgres); the reader TCK asserts the append→replay ascending-sequence round-trip. The **Kafka binding (PR 3)** completes the ≥2-binding merge gate: `KafkaEventStreamAppender` / `KafkaEventStreamReader` (`eu.exeris.kernel.community.kafka`) over a single `streamId`-keyed log topic, **log-authoritative** — the 1-based `committedSequence` is stamped in the frame and the log is the sole source of truth; an in-memory per-stream head is recovered from the log tail on a miss (no compacted-head topic, no Kafka transactions). OCC is **single-writer-per-stream** best-effort (Kafka has no cross-instance compare-and-set) — the documented Community-tier limit; a scale/durability upgrade (an *advisory* compacted-head checkpoint, still transaction-free because the log stays authoritative) is deferred until a real Kafka consumer needs it. Verified by `CommunityKafkaEventStream{Appender,Reader}TckIT` (Testcontainers Kafka 3.x); conflict/failure paths emit secret-safe JFR (reused `EventLogAppendConflictEvent` + `KafkaEventLogAppendFailedEvent`).
