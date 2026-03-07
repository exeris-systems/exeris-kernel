# Kernel Subsystem: Flow / Sagas (L4 Orchestration)

**Physical Layout:**

- **SPI:** `eu.exeris.kernel.spi.flow.*`
  (`Saga`, `SagaStep`, `Compensation`, `FlowContext`)
- **Core:** `eu.exeris.kernel.core.flow.*`
  (`FlowEngine`, `StateMachine`, `IdempotencyGuard`)
- **State Storage:**
    - **`community`:** PostgreSQL-backed state persistence
    - **`enterprise`:** Linear Probing Off-Heap Cache (lock-free) + Async DB Write-Behind

**Layer:** L4 (Orchestration)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Flow subsystem** is the business brain of the Kernel. It orchestrates **complex, multi-step
processes (Sagas)** that span multiple services or subsystems. It guarantees that even if the entire
cluster crashes mid-process, the business state will converge to a consistent outcome — either
**completion** or **compensation**.

- **Saga Orchestration Engine:** Strict state transitions, atomic steps, deterministic rollback.
- **Async Park/Wake (Virtual Threads):** A Saga waiting for an external event (e.g., a payment
  gateway callback) simply parks its Virtual Thread. Loom's sub-1 KB thread cost allows millions of
  suspended Sagas to coexist in memory without affecting Kernel throughput.
- **Off-Heap State Machine (Enterprise):** Active Saga states stored in a lock-free linear probing
  hash table, eliminating GC pauses on state transitions.
- **Idempotency by Design:** Every step guarded by an idempotency key to prevent duplicate business
  actions under retries or message redelivery.

---

## Core Philosophy: "Correctness at Scale"

### 1. The Saga Constitution

Every process must be reversible. If Step N fails → the Kernel automatically compensates Steps
1…N-1 in strict reverse order. There is no partial execution without a recovery path.

### 2. Lock-Free Orchestration

Zero database locks during processing. The Enterprise tier uses an off-heap linear probing hash
table with `VarHandle` CAS atomics — thousands of concurrent Sagas advance state without contention
or GC pressure.

### 3. Async Park/Wake

In the legacy model (Camunda, Temporal), waiting for an external event means dumping state to
a database and killing the thread. In Exeris, the Virtual Thread simply **parks**. The off-heap
state remains hot in the Linear Probing Cache. When the webhook arrives, `state.wake(event)` resumes
the thread in microseconds — without a DB round-trip, without heap allocation.

### 4. Invisible Durability

Hot state lives in memory for speed. Every transition is asynchronously persisted to:

- **Event Store (L3):** Full audit trail and replayability.
- **Persistence Layer (L1):** Disaster recovery checkpoint.

The Saga engine never forces a synchronous DB write on the hot path — durability is a background
concern, not a latency tax.

---

## Execution Strategies

| Feature            | Community (SQL-First)       | Enterprise (Memory-First)                  |
|:-------------------|:----------------------------|:-------------------------------------------|
| **State Storage**  | PostgreSQL State Table      | Off-Heap Linear Probing Cache              |
| **Persistence**    | Synchronous DB Commits      | Async Write-Behind / Event Sourcing        |
| **Latency**        | Milliseconds (DB-bound)     | Microseconds (Memory-bound)                |
| **Idempotency**    | DB Unique Constraints       | Bloom Filter + Off-Heap Guard              |

---

## Responsibilities

**What Flow SPI DOES:**

1. Define `Saga<T>` and `SagaStep` lifecycle contracts.
2. Provide `FlowDefinitionBuilder` fluent API for compensation chain definition.
3. Define `FlowContext` for passing business data through steps.
4. Define the `Compensatable` contract for rollback logic.

**What Flow Core DOES:**

1. Execute the Saga State Machine (forward and compensation paths).
2. Manage park/wake cycles for long-running processes via Virtual Thread parking.
3. Enforce idempotency via `IdempotencyGuard` (Bloom Filter in Enterprise).
4. Publish Saga progress events to the Events Subsystem (L3).
5. Coordinate with `WatermarkManager` to enforce backpressure when Saga capacity is exhausted.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code           | Meaning                  | Glass-Box Payload (`rawArgs`)                                                             |
|:---------------|:-------------------------|:------------------------------------------------------------------------------------------|
| `EX-FLOW-7001` | Provider Boot Failure    | `[0] String providerName, [1] String reason`                                              |
| `EX-FLOW-7002` | Lifecycle / Schedule Fail| `[0] String engineName, [1] String phase, [2] String staticReasonCode, [3] int contextVal`|
| `EX-FLOW-7003` | Step Execution Failure   | `[0] String defName, [1] long idMost, [2] long idLeast, [3] int stepIdx, [4] String reason, [5] String causeType` |
| `EX-FLOW-7004` | Registry Conflict        | `[0] int stepId, [1] String reason`                                                       |

**Forensics note for `EX-FLOW-7003`:** The `idMost` + `idLeast` pair encodes the `UUID` of the failing
Saga instance as two `long` primitives — autoboxed to `Long` per the Glass-Box contract, but decoded by
the Enterprise Glass-Box Decoder as a single `UUID` for instance tracing. The `causeType` is
`cause.getClass().getName()` or `"none"` — class names are stable and never user-controlled, making
them safe for binary telemetry.

**Lifecycle note for `EX-FLOW-7002`:** The `phase` field is one of the static constants `"START"`,
`"STOP"`, `"COMPILE"`, `"SCHEDULE"`. The `contextVal` carries a phase-specific integer (`-1` when
not applicable; queue depth for `"SCHEDULE"`). Glass-Box consumers MUST use the `phase` field to
select the correct interpretation of `contextVal`.

---

## Code Examples

### 1. Defining a Compensatable Saga (SPI)

Imperative logic mapping to a rigid State Machine. No annotation-based disk dumps mid-method.

```java
public class OrderSaga implements Saga<OrderData> {

    @Override
    public void configure(FlowDefinitionBuilder<OrderData> builder) {
        builder
                .step("ReserveStock",   stockService::reserve,          stockService::compensate)
                .step("ProcessPayment", paymentService::charge,         paymentService::refund)
                .step("ShipOrder",      shippingService::requestShipment);
    }
}
```

### 2. Async Park/Wake (Core — Virtual Thread Parking)

```java
public void onExternalEvent(String correlationId, Event event) {
    SagaState state = offHeapCache.get(correlationId);
    state.wake(event);
}
```

> `offHeapCache.get()` is an O(1) linear probing lookup — no heap allocation, no DB round-trip.
> `state.wake()` unparks the suspended Virtual Thread in microseconds. The Saga resumes
> exactly where it left off, with full stack trace intact.

### 3. Idempotency Guard (Core — Enterprise)

```java
public StepResult executeStep(SagaStep step, FlowContext ctx) {
    if (idempotencyGuard.isAlreadyExecuted(ctx.idempotencyKey())) {
        return idempotencyGuard.getPreviousResult(ctx.idempotencyKey());
    }
    StepResult result = step.execute(ctx);
    idempotencyGuard.record(ctx.idempotencyKey(), result);
    return result;
}
```

---

---

## Saga Timeout — Park Duration Contract

A Virtual Thread parked waiting for an external event MUST have a configurable maximum wait duration.

| Scope               | Default          | Config Key                                               |
|:--------------------|:----------------:|:---------------------------------------------------------|
| **Global timeout**  | 30 minutes       | `exeris.flow.saga.global-park-timeout-ms`                |
| **Per-step timeout**| Inherited        | Flow definition per-step timeout API in `FlowDefinitionBuilder` (replaces legacy `SagaBuilder.step(...).timeout(Duration)`) |
| **On timeout action** | COMPENSATE    | Flow definition timeout policy API in `FlowDefinitionBuilder` (replaces legacy `SagaBuilder.onTimeout(CompensationPolicy)`, default: COMPENSATE_ALL) |

When the park timeout fires:

1. The Saga transitions to `COMPENSATING` state via VarHandle CAS.
2. `EX-FLOW-7002` is emitted with `phase="TIMEOUT"` and `contextVal=<parkedMs>`.
3. Compensation steps execute in reverse order (identical to step failure compensation).
4. `EX-FLOW-7003` is thrown for the step that exceeded its timeout.

---

## Saga Versioning — Handling Schema Evolution

Sagas in production may be long-running (hours or days). A deployment may change the Saga definition
while older instances are still executing. The following versioning contract applies:

| Scenario                                           | Kernel Behaviour                                                                                         |
|:---------------------------------------------------|:---------------------------------------------------------------------------------------------------------|
| **New deployment adds a step** to a Saga           | Existing in-flight Sagas (persisted in `exeris_saga_state`) continue on the **old definition**. New Sagas use the new definition. The `FlowRegistry` stores the definition snapshot at submission time. |
| **New deployment removes a step**                  | If an in-flight Saga was parked on the removed step: on wake, the engine detects the missing step via the persisted `stepIdx`. `EX-FLOW-7002` with `phase="SCHEMA_MISMATCH"` is thrown and manual intervention is required. |
| **New deployment reorders steps**                  | Treated as removal + addition — highest risk scenario. Avoid during active Saga execution. Use blue/green deployment with Saga drain before switching. |
| **Safe migration pattern (current)**               | Perform blue/green deployment with Saga drain before switching traffic. Avoid changing step order while Sagas are in-flight. The engine currently maintains a single active definition per Saga type; fine-grained, version-aware routing is **planned** but not yet available in the public Flow SPI/Core. |

> **Planned feature:** Future Flow engine iterations may introduce explicit Saga definition versioning
> (for example, via annotations and version-aware routing in the Saga registry/engine) to allow multiple
> definition versions to coexist until all old instances complete. This capability is *not implemented*
> in the current codebase and MUST NOT be relied upon until the corresponding SPI/Core APIs exist.

---

## Compensation Failure Handling

If a compensation step itself throws an exception, the Kernel enters the **COMPENSATION_FAILED** terminal state:

```
COMPENSATING → COMPENSATION_FAILED (terminal — manual intervention required)
```

| Attempt | Action                                                                                   |
|:--------|:-----------------------------------------------------------------------------------------|
| 1–3     | Retry compensation step with exponential backoff (100 ms, 400 ms, 1 600 ms)              |
| > 3     | Transition to `COMPENSATION_FAILED`. Emit `EX-FLOW-7003` with `causeType="COMPENSATION_ERROR"`. Saga record persisted to `exeris_saga_state` with status `COMPENSATION_FAILED`. |

**Operator recovery:** Query `SELECT * FROM exeris_saga_state WHERE status = 'COMPENSATION_FAILED'`.
Each record contains the Saga UUID (`idMost` + `idLeast`), the failed step index, and the failure reason.
Use `FlowEngine` (planned: `forceCompensate(sagaId, fromStepIdx)`) to re-trigger compensation from a specific step
after the root cause is resolved.

> There is no automatic retry beyond attempt 3. This is deliberate — a compensation that fails repeatedly
> indicates a system-level problem (e.g., payment gateway down) that requires human intervention, not
> an infinite retry loop that masks the underlying issue.

---

## Distributed Saga Support — Clustering Model

At TRL-3, the Flow Engine operates in a **single-JVM model**. Distributed Saga support (multi-node cluster)
is deferred to TRL-5.

| Capability                              | TRL-3 (Current)                        | TRL-5 (Planned)                              |
|:----------------------------------------|:---------------------------------------|:---------------------------------------------|
| Saga state storage                      | PostgreSQL (single DB)                 | Partitioned PostgreSQL / Distributed KV      |
| Saga assignment to JVM node             | All Sagas on one node                  | Consistent hashing by Saga UUID              |
| Node crash recovery                     | Manual restart (saga resumes from DB)  | Automatic rebalancing via `SagaPartitionSpi` |
| Concurrent Saga updates across nodes    | Not applicable                         | OCC via `definition_version` + `stepIdx` CAS |

**TRL-3 crash recovery:** If the JVM crashes while a Saga is in `RUNNING` state, the next boot of the
same node will detect in-progress Sagas in `exeris_saga_state` (status `RUNNING` or `COMPENSATING`)
and resume them from the last persisted `stepIdx`. This works because every step completion is persisted
atomically via the `@Transactional` boundary before the next step executes.

---

## Saga Observability — Monitoring API

SRE visibility into running Sagas is provided via JFR events and a diagnostic query API.

| Metric                             | Access Method                                                               |
|:-----------------------------------|:----------------------------------------------------------------------------|
| Active Sagas (RUNNING)             | JFR `SagaLifecycleEvent` (category: `Exeris/Flow`) — `status=RUNNING` count|
| Parked Sagas (waiting for event)   | JFR `SagaLifecycleEvent` — `status=PARKED` count                           |
| Compensating Sagas                 | JFR `SagaLifecycleEvent` — `status=COMPENSATING` count                     |
| Failed / Stuck Sagas               | `SELECT COUNT(*) FROM exeris_saga_state WHERE status IN ('FAILED', 'COMPENSATION_FAILED')` |
| P99 step execution latency         | JMH `AbstractFlowParkWakeBenchmark` (TCK) — `EX-FLOW-7002` latency histogram         |

**JFR event:**

```java
@jdk.jfr.Label("Saga Lifecycle")
@jdk.jfr.Category({"Exeris Kernel", "Flow"})
@jdk.jfr.StackTrace(false)
public final class SagaLifecycleEvent extends jdk.jfr.Event {
    String sagaType;
    String status;       // RUNNING, PARKED, COMPENSATING, COMPLETED, FAILED
    long durationNanos;
    int stepIndex;
}
```

---

## Testing Strategy

### Unit Tests

- Saga state transition: `STEP_1_OK → STEP_2_FAIL → COMPENSATE_1` in strict order.
- Idempotency guard under concurrent step retries — no duplicate execution.
- Off-heap hash table: collision handling and CAS correctness under contention.
- `EX-FLOW-7004` thrown on duplicate step registration.

### Integration Tests (TCK)

- **Persistence Recovery:** Kill the Kernel mid-Saga → verify resume from persisted checkpoint.
- **Event-Driven Wake-Up:** External event correctly unparks the suspended Virtual Thread.
- **Compensation Integrity:** All preceding steps compensated in reverse order on step failure.
- **`EX-FLOW-7003` Forensics:** Verify `rawArgs[1]` + `rawArgs[2]` decode to the correct Saga UUID.
- **Community / Enterprise Parity:** Same `OrderSaga` produces identical business outcomes on both
  tiers — only latency differs.

### Load Tests

- **Concurrency:** 100k concurrent Sagas in the off-heap cache with < 1 µs state transition P99.
- **Park Density:** 1M parked Virtual Threads with < 100 MB total memory footprint.
- **Compensation Throughput:** Worst-case full rollback (10-step Saga) completes < 10 ms P99.

---

## Summary

The Flow subsystem is where the entire Exeris architecture delivers its ultimate business value.
By combining Events (Zero-Copy Kafka) with Flow (Off-Heap State Machine), the platform accepts
HFT-class traffic volumes while processing business logic with the clarity of imperative Java code.
The Async Park/Wake model solves the "Stateful Serverless" problem without paying licensing costs
for NoSQL state stores — the off-heap Linear Probing Cache is your state store, managed
deterministically by the L0 Memory Contract.
