# Kernel Subsystem: Flow (L4 Orchestration)

**Physical Layout:**

- **SPI:** `eu.exeris.kernel.spi.flow.*`  
  *(Saga, SagaStep, Compensation, FlowContext)*

- **Core:** `eu.exeris.kernel.core.flow.*`  
  *(SagaEngine, StateMachine, IdempotencyGuard)*

- **State Storage:**
    - **community:** PostgreSQL‑backed state persistence
    - **enterprise:** Linear Probing Off‑Heap Cache (lock‑free) + Async DB Write‑behind

**Layer:** L4 (Orchestration)  
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Overview

The **Flow subsystem** is the business brain of the Kernel.  
It orchestrates **complex, multi‑step processes (Sagas)** that span multiple services or subsystems.  
It guarantees that even if the system crashes mid‑process, the business state will converge to a consistent end — either
**completion** or **compensation**.

### Key Characteristics

- **Saga Orchestration Engine**  
  Strict state transitions, atomic steps, deterministic rollback.

- **Async Park/Wake (Virtual Threads)**  
  A Saga can *park* while waiting for an external event (e.g., payment gateway callback) and *wake* instantly when the
  event arrives.

- **Off‑Heap State Machine (Enterprise)**  
  Active Saga states stored in a lock‑free linear probing hash table, eliminating GC pauses.

- **Idempotency by Design**  
  Every step guarded by an idempotency key to prevent duplicate business actions.

---

## Core Philosophy (“Correctness at Scale”)

### 1. The Saga Constitution

Every process must be reversible.  
If Step N fails → compensate Steps 1…N‑1 in reverse order.

### 2. Lock‑Free Orchestration

No DB locks.  
Off‑heap hash table + VarHandle atomics → thousands of concurrent Sagas without contention.

### 3. Invisible Durability

Hot state lives in memory for speed.  
Every transition is asynchronously persisted to:

- Event Store (L3)
- Persistence Layer (L1)

This provides:

- audit trail
- replayability
- disaster recovery

---

## Execution Strategies

| Feature           | Community (SQL‑First)   | Enterprise (Memory‑First)           |
|-------------------|-------------------------|-------------------------------------|
| **State Storage** | PostgreSQL State Table  | Off‑Heap Linear Probing Cache       |
| **Persistence**   | Synchronous DB Commits  | Async Write‑Behind / Event Sourcing |
| **Latency**       | Milliseconds (DB‑bound) | Microseconds (Memory‑bound)         |
| **Idempotency**   | DB Unique Constraints   | Bloom Filter + Off‑Heap Guard       |

---

## Responsibilities

### What Flow SPI **does**

- Defines `Saga<T>` and `SagaStep`
- Provides `FlowContext` for passing business data
- Defines `Compensatable` contract for rollback logic

### What Flow Core **does**

- Executes Saga State Machine (forward/backward logic)
- Manages park/wake cycles for long‑running processes
- Ensures idempotency via `IdempotencyGuard`
- Publishes progress events to Events Subsystem (L3)

---

## Error Codes (Black Box Telemetry)

| Code             | Meaning              | Action                     |
|------------------|----------------------|----------------------------|
| **EX‑FLOW‑6001** | Saga Step Failure    | Initiate compensation flow |
| **EX‑FLOW‑6002** | Idempotency Conflict | Return previous result     |
| **EX‑FLOW‑6003** | Saga Timeout         | Move Saga to DLQ           |
| **EX‑FLOW‑6004** | State Corruption     | Reload from L1 Persistence |

---

## Code Examples

### 1. Defining a Compensatable Saga (SPI)

```java
public class OrderSaga implements Saga<OrderData> {
    @Override
    public void configure(SagaBuilder<OrderData> builder) {
        builder
                .step("ReserveStock", stockService::reserve, stockService::compensate)
                .step("ProcessPayment", paymentService::charge, paymentService::refund)
                .step("ShipOrder", shippingService::requestShipment);
    }
}
```

---

### 2. Async Park/Wake (Core Concept)

```java
public void onExternalEvent(String correlationId, Event event) {
    // 1. Find Saga in the Off-Heap Hash Table
    SagaState state = offHeapCache.get(correlationId);

    // 2. Wake up the parked Virtual Thread
    state.wake(event);
}
```

---

## Testing Strategy

### Unit Tests

- Saga state transition logic (Success → Success → Failure → Compensation)
- Idempotency guard under concurrent requests
- Off‑heap hash table collision handling

### Integration Tests

- **Persistence Recovery:** kill Kernel mid‑Saga → verify resume
- **Event‑Driven Wake‑up:** external event resumes parked Saga
- **Compensation Integrity:** retry until consistency if DB is down

---

## Summary

The Flow subsystem is where technical excellence meets business value. By combining the safety of the Saga pattern with
the extreme performance of Off-Heap state management and Virtual Thread parking, Exeris provides an orchestration engine
capable of handling millions of concurrent business processes with guaranteed eventual consistency.