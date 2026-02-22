# Kernel Subsystem: Bootstrap (L0 Orchestration)

**Physical Layout:**

- **SPI:** `eu.exeris.kernel.spi.bootstrap.*`  
  *(Subsystem contracts, Lifecycle, Registry)*

- **Core:** `eu.exeris.kernel.core.bootstrap.*`  
  *(Sequence Manager, Health Monitor, Failure Policies)*

- **Runner:** `eu.exeris.kernel.launcher.*`  
  *(CLI, Main-Class, Signal Handling)*

**Layer:** L0 (Orchestration)  
**Status:** Validated Architectural Prototype (TRL‑3)

---

## Overview

The **Bootstrap subsystem** is the central orchestrator of the Exeris Kernel.  
It manages the strictly ordered initialization sequence of all subsystems, ensuring that foundational layers (Config,
Memory) are fully operational before higher‑level logic (Transport, Flow) is activated.

### Key Characteristics

- **Dependency‑Ordered Init**  
  Directed acyclic graph (DAG) resolves subsystem order.  
  Config always first → Memory seconds → everything else follows.

- **Virtual Thread Parallelization**  
  L1 and L2 subsystems (Security, Persistence, Graph, Transport) initialize in parallel using `StructuredTaskScope`.

- **Fail‑Fast vs Degrade**  
  Configurable failure policies:
    - *FAIL_FAST* → any error kills the Kernel
    - *DEGRADE* → optional subsystems may fail without blocking startup

- **Graceful Shutdown**  
  Transport stops accepting new streams before Persistence closes pools (no‑downtime drain).

---

## The “Holy Order” of Initialization

A layer can only start if all layers below it are **READY**.

```
Foundation (L0):   Config → Memory → Exceptions
Data & Integrity (L1):   Security & Persistence
Data Synthesis (L2):     Graph & Transport
Logic Engines (L3/L4):   Events & Flow
```

---

## Core Philosophy

### 1. Deterministic Startup

No classpath magic.  
Every subsystem must be explicitly registered or discovered via `ServiceLoader`.

### 2. Failure Sovereignty

Bootstrap decides the fate of the Kernel:

- **FAIL_FAST** → recommended for production
- **DEGRADE** → ideal for local dev or emergency maintenance

### 3. Signal Awareness

Bootstrap translates OS signals (`SIGTERM`, `SIGINT`) into a controlled, reverse‑ordered shutdown sequence.

---

## Responsibilities

### What Bootstrap SPI **does**

- Defines `KernelSubsystem` and `Lifecycle`
- Provides `KernelContext` during boot
- Defines `HealthStatus` and `SubsystemPriority`

### What Bootstrap Core **does**

- Resolves dependency graph of all subsystems
- Manages subsystem state machine:  
  `INIT → STARTING → READY → SHUTTING_DOWN`
- Provides Health Check Registry for Kubernetes probes

---

## Error Codes (Black Box Telemetry)

| Code             | Meaning                   | Action             |
|------------------|---------------------------|--------------------|
| **EX‑BOOT‑0001** | Dependency cycle detected | Kernel cannot boot |
| **EX‑BOOT‑0002** | Foundation failure        | Fatal exit         |
| **EX‑BOOT‑0003** | Timeout during init       | Kill or degrade    |
| **EX‑BOOT‑0004** | Shutdown hook interrupted | Log as CRITICAL    |

---

## Code Examples

### 1. Subsystem Registration (SPI)

```java
public class PersistenceSubsystem implements KernelSubsystem {

    @Override
    public List<Class<? extends KernelSubsystem>> dependsOn() {
        return List.of(ConfigSubsystem.class, MemorySubsystem.class);
    }

    @Override
    public void initialize(KernelContext ctx) {
        // Setup Connection Pools using Config
    }
}
```

---

### 2. Parallel Boot Strategy (Core Concept)

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Parallel init for layers that don't depend on each other
        scope.fork(() -> security.start());
        scope.fork(() -> persistence.start());

        scope.join();
    scope.throwIfFailed();
}
```

---

## Testing Strategy

### Unit Tests

- DAG resolver detects circular dependencies
- Failure policy correctness (FAIL_FAST vs DEGRADE)

### Integration Tests

- Full boot trace with JFR timing
- Signal handling (SIGTERM → correct shutdown order)
- Health probe accuracy (`/health/ready` returns 503 until L4 READY)

---

## Summary

The Bootstrap subsystem is the guardian of the Kernel's lifecycle. By enforcing a strict, dependency-aware boot sequence
and using Java 26's structured concurrency, it ensures that the Exeris Kernel starts fast, fails safely, and shuts
down gracefully, maintaining system integrity at all times.

