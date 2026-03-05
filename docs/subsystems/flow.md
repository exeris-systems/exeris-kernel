# Kernel Subsystem: Flow / Sagas (L4 Orchestration)

**Physical Layout:**

- **SPI:** `eu.exeris.kernel.spi.flow.*`
  (`Saga`, `SagaStep`, `Compensation`, `FlowContext`)
- **Core:** `eu.exeris.kernel.core.flow.*`
  (`SagaEngine`, `StateMachine`, `IdempotencyGuard`)
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
2. Provide `SagaBuilder` fluent API for compensation chain definition.
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
    public void configure(SagaBuilder<OrderData> builder) {
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
