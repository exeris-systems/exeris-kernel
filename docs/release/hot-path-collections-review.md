# Hot-Path Collections Review — v0.7 Sprint 8e

> Status: **complete — no replacement justified for v0.7**.
> Scope: Community runtime hot paths only — SPI / Core contracts unchanged.
> Authoring date: 2026-05-10. Author: Arkadiusz Przychocki (solo dev).

## 1. Mandate and review gate

Sprint 8 §2 of the v0.7 sprint map defines this review:

> Targeted profiling Community runtime hot paths — Flow scheduler dispatch, Transport reactor, Events outbox flush. Replacement structures **only** when profiling shows real contention. Identity-based maps (e.g. JCTools `NonBlockingIdentityHashMap`) are **NOT** a fit for Flow registries (value-semantic keys per restart/wake/idempotency). Queue-oriented JCTools structures may be considered for bounded internal handoff (as already used in PERF-063). Output: design note **or** PR; SPI/Core contracts unchanged, replacement Community-internal only.

The cross-sprint dependency table additionally pins this review to "*review on real contention, not speculation*" (S8 row, "Hot-Path Collections Review").

This document discharges the review without code changes. Each candidate path is named with file:line, the current data structure is identified, the reason a replacement is not justified is stated, and any v0.8 candidate is recorded as deferred work.

## 2. Methodology

- Inventory the four candidate paths called out by the sprint map (Flow scheduler, idempotency guard, transport reactor, outbox flush).
- For each, record the current container, the access pattern (single-writer, MPSC, MPMC, read-mostly), and whether the keys are value-semantic or reference-identity.
- Apply the two pre-stated exclusion rules from the sprint map:
  - **Identity-based maps disqualified** for any path whose keys are value records (FlowKey, plan name, step index).
  - **Queue replacements only justified by profiling-grounded contention**; the only such evidence in v0.7 is PERF-063 (NIC reactor control queue).
- No JFR/JMH bench was run for this review; v0.7 has no captured contention trace beyond PERF-063. Per the gate, that is sufficient grounds to defer further changes — the review is a *negative* result by design.

## 3. Path-by-path findings

### 3.1 Flow scheduler — `liveInstances`, `parkedInstances`, `planCatalog`

- File: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/flow/CoreFlowRuntime.java:50–52`.
- Containers:
  - `ConcurrentMap<FlowKey, RuntimeFlowInstance> liveInstances` — `ConcurrentHashMap`.
  - `ConcurrentMap<FlowKey, RuntimeFlowInstance> parkedInstances` — `ConcurrentHashMap`.
  - `ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog` — `ConcurrentHashMap`.
- Keys are value-semantic: `FlowKey` is a composite-UUID record (most-significant + least-significant longs); `String` plan name is by-value. **Identity-based JCTools maps are domain-misfit** — restart resurrection and saga wake paths look up flows by value-equal key, not by-reference, so an identity map would silently miss every restart-loaded instance.
- Access pattern: read-mostly on the dispatch path (lookup at wake/resume); writes occur at park/unpark transitions and at engine shutdown (`clear()`). Read scaling on `ConcurrentHashMap` is already lock-free at the bin level.
- **No replacement justified.** No measured contention; no identity-map fit; no JCTools alternative has matching value-semantic equality contract.

### 3.2 Flow scheduler — parked-lookup miss cache

- File: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/flow/CoreFlowRuntime.java:54–56`.
- Containers: `ConcurrentHashMap.newKeySet()` for membership + `ArrayDeque<FlowKey>` for FIFO eviction order, guarded by an explicit lock object (`parkedLookupMissLock`).
- Bounded by `MAX_PARKED_LOOKUP_MISSES = 256`; eviction is a single `pollFirst()` per insert past the cap. The cache exists only to suppress repeated negative lookups from the choreography bridge after a flow has terminally departed; it is **not on the steady-state dispatch path**.
- Access pattern: occasional writes from the bridge’s `lookupParked` miss callback; reads are bounded by negative-lookup frequency, which the structure itself dampens.
- **No replacement justified.** The lock is held for O(1) work; bench evidence for contention is absent. A ring-buffer rewrite would be net-zero on observed loads and would couple the eviction order to the membership representation, which currently keeps responsibilities cleanly separated.

### 3.3 Flow scheduler — `runningThreads`

- File: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/flow/CoreFlowRuntime.java:57`.
- Container: `Set<Thread>` from `ConcurrentHashMap.newKeySet()`.
- Used only for shutdown bookkeeping (`interruptAndJoinRunningThreads`, dead-thread sweep); not on the per-step dispatch path. **Out of scope** for replacement.

### 3.4 Idempotency guard — nested concurrent map

- File: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/flow/CoreIdempotencyGuard.java:27–32`.
- Container: `ConcurrentMap<FlowKey, ConcurrentMap<Integer, Boolean>>` — outer keyed by FlowKey, inner keyed by step-index `Integer`.
- Access pattern: per-step `claim` writes one boxed `Integer→Boolean` entry per first-execution of a step; subsequent rerun attempts read it back. Step counts per flow are typically <16 in practice, so the inner map is small and short-lived alongside its `RuntimeFlowInstance`.
- **No replacement justified for v0.7.** The boxed `Integer` key is a real allocation cost, but the inner-map churn is modest and not visible in any captured profile.
- **v0.8 candidate (deferred):** when the step count is known at plan compile time, the inner map can be packed into a `volatile long[]` bitmap held on `RuntimeFlowInstance`, eliminating both the inner `ConcurrentHashMap` and the `Integer` autobox. Tracked as a candidate under v0.8 quality batch alongside QA-010..018.

### 3.5 Transport reactor — control queue (PERF-063 reference site)

- File: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/transport/NativeTcpCarrier.java:1197–1204` (and parallel use at `NativeTcpStream.java:99` for the per-stream outbound queue).
- Container: `Queue<ReactorRequest>` = `org.jctools.queues.MpscUnboundedArrayQueue` (chunk size 128). Backed by a chunked array node layout, single-consumer assumption matches the reactor thread.
- Access pattern: multi-producer (any caller of `enqueueRegistration` / `enqueueWriteInterest` / `cancelKey`), single-consumer (reactor thread inside `runLoop`). MPSC semantics are exactly satisfied; chunked allocation removes the per-element node churn that the prior `ConcurrentLinkedQueue` exhibited under burst arrivals.
- **Already replaced in PERF-063 (Sprint 2).** Documented in `docs/subsystems/transport.md` §"Reactor loop" and exercised by `NativeTcpTransportStressTest`. No further change for v0.7.

### 3.6 Transport reactor — `pendingWriteInterest`

- File: `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/transport/NativeTcpCarrier.java:1204`.
- Container: `Set<SocketChannel>` from `ConcurrentHashMap.newKeySet()`.
- Keys are reference-identity (`SocketChannel` instance lifetime is tied to the channel itself, no aliasing across the carrier). An identity-keyed structure would be technically admissible here, unlike in §3.1.
- Access pattern: producers are VT handlers signalling write readiness; consumer is the reactor thread on selector wake. Cardinality is bounded by the number of channels per reactor (small under any current load profile).
- **No replacement justified.** Identity-map savings are dominated by the surrounding `Selector` / `SocketChannel` allocation and selector-key bookkeeping; no profile shows the set as a contention site. Recorded as a v0.8 watch-item if a future stress run shows hot CAS retries on this set.

### 3.7 Outbox flush — per-batch staging list

- File: `exeris-kernel-core/src/main/java/eu/exeris/kernel/core/events/outbox/OutboxOrchestrator.java:259`.
- Container: `List<EventDescriptor> toDeliver = new ArrayList<>(batch.size());` allocated once per flush cycle with exact capacity.
- Access pattern: bulk staging within a single owner-thread `executeTick`; produced in a tight loop bounded by `batch.size() <= batchSize` (configured), consumed once via `eventStore.markDelivered(toDeliver)`. No cross-thread visibility required.
- **No replacement justified.** Pre-sized `ArrayList` backed by a single object array is the minimal-allocation single-threaded staging container in the JDK. Moving to a JCTools queue would add MPSC machinery for a single-threaded site and reorder the bulk-mark contract with the event store. The flush is throughput-bounded by the broker port and the JDBC `markDelivered`, neither of which this list is on the critical path of.

## 4. JCTools fit matrix

| Path                                   | Domain key                | Prod | Cons | JCTools fit             | Decision (v0.7)                       |
|:---------------------------------------|:--------------------------|:----:|:----:|:------------------------|:--------------------------------------|
| Flow live/parked instance maps         | Value (FlowKey)           | many | many | No — identity-disq.     | Keep CHM                              |
| Flow plan catalog                      | Value (String name)       | few  | many | No — identity-disq.     | Keep CHM                              |
| Idempotency guard inner step-claim map | Value (Integer)           | 1/i  | 1/i  | No — identity-disq.     | Keep CHM; v0.8 packed-bitmap watch    |
| NIC reactor pending-requests queue     | Carrier record            | many | one  | **Yes — MPSC**          | Already PERF-063                      |
| NIC stream outbound queue              | Carrier record            | many | one  | **Yes — MPSC**          | Already PERF-063 (parity)             |
| NIC reactor `pendingWriteInterest`     | Reference (SocketChannel) | many | one  | Identity admissible     | Keep CHM keyset; v0.8 watch           |
| Outbox flush staging list              | n/a (single thread)       | one  | one  | No — single-threaded    | Keep ArrayList                        |

## 5. Conclusion

For v0.7 the only confirmed JCTools-shaped contention point is the NIC reactor control path, which has already been addressed in Sprint 2 under PERF-063 and is documented in the transport subsystem doc. No additional replacement meets both stated gates of the sprint map (real-contention evidence + non-disqualified key semantics).

The review is therefore closed with **no Community runtime changes**, and the SPI / Core contract surface is untouched as required.

## 6. v0.8 carry-over candidates

These are recorded for the v0.8 quality batch and intentionally **not** scheduled into v0.7:

- **Idempotency guard inner-map → packed `volatile long[]` bitmap** (§3.4) once plan-compile-time step count is reachable from `CoreIdempotencyGuard`. Removes inner `ConcurrentHashMap` and `Integer` autobox; gated on a measured allocation hit on the dispatch path.
- **`pendingWriteInterest` identity keyset** (§3.6) only if a stress profile shows it as a hot CAS site; otherwise leave the CHM keyset.
- **Parked-lookup miss-cache as ring buffer** (§3.2) only if the lock-guarded `ArrayDeque` shows up under contended bridge-miss workloads; otherwise leave the explicit-lock pair.

## 7. Exit criteria mapping

| Sprint 8 exit criterion (v0.7 map §"Exit criteria")        | Discharge                                                                                          |
|:-----------------------------------------------------------|:---------------------------------------------------------------------------------------------------|
| Hot-Path Collections Review — design note **or** PR merged | This document, merged via Sprint 8e PR.                                                            |
| SPI/Core contracts unchanged                               | No SPI or Core contract surface modified; review is read-only and recorded as a release artifact. |
