# Kernel Subsystem: Graph (L2 Data Synthesis)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.graph.*` (MATCH DSL, Dialect SPI, Session Contracts)
- Core: `eu.exeris.kernel.core.graph.*` (Query Transpiler, Metadata Engine, Algo-Orchestrator)
- Drivers:
    - `community`: Standard JDBC (Postgres) / Bolt (Neo4j/Memgraph)
    - `enterprise`: Native `io_uring` (PostgreSQL) / FFM-Native Bolt (Planned)
      **Layer:** L2 (Data Synthesis)
      **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Graph subsystem** is a semantic synthesis engine. Its primary goal is to transform structured data from L1
Persistence into traversable relationships using a unified **MATCH DSL**. It explicitly acknowledges the efficiency gap
between standard JVM drivers and native I/O, providing a migration path from high-allocation prototypes to zero-copy
production environments.

- **The Allocation Gap:** Standard Bolt/Netty drivers exhibit high **Object Churn** (measured at ~15x allocation-to-data
  ratio). The subsystem allows utilizing these drivers in the Community tier while providing the SPI for "Clean" (
  Off-heap) Enterprise drivers.
- **Transpilation Engine:** Converts abstract MATCH patterns into target-specific dialects:
    - **SQL:2023 PGQ** for PostgreSQL 18.
    - **Cypher** for Bolt-compatible backends (Neo4j, Memgraph, FalkorDB).
- **Protocol-Blind SPI:** Business logic is decoupled from the driver's memory management policy or specific network
  protocol.

---

## Core Philosophy

### 1. Intent over Implementation

We use the **MATCH** pattern to express relationship intent. The Kernel is responsible for finding the most efficient
way to execute this intent on the active driver (e.g., JSON push-down for Postgres or native GDS for Neo4j).

### 2. Zero-BS Performance Metrics

We do not mask the cost of abstraction. If a driver allocates 30GB to process 2GB of data, it is documented as a
baseline. Success is defined by the reduction of this ratio toward 1:1 using Panama FFM and `io_uring`.

### 3. Dual-Write Consistency

The subsystem provides built-in orchestration (`GraphSyncService`) to ensure that relational state changes in L1 are
reflected in the L2 graph structure.

---

## Performance Tiering (The Reality Check)

| Metric               | Community (Standard)       | Enterprise (Native)               |
|:---------------------|:---------------------------|:----------------------------------|
| **I/O Strategy**     | Standard Sockets / NIO.2   | **`io_uring` / Panama FFM**       |
| **Memory Policy**    | JVM Heap (High Churn)      | **Off-heap (`LoanedBuffer`)**     |
| **Allocation Ratio** | ~15x (Baseline)            | **< 1x (Target)**                 |
| **Thread Model**     | Virtual Threads (blocking) | **Virtual Threads (Non-pinning)** |

---

## Responsibilities

**What Graph SPI DOES:**

1. Define `GraphSession` and `GraphBackend` lifecycle contracts.
2. Provide the fluent `MATCH` Query Builder and `GraphDialect` extension points.
3. Define metadata structures for Nodes and Edges based on domain annotations.

**What Graph Core DOES:**

1. Discover graph metadata and transpile DSL queries into native SQL/Cypher strings.
2. Manage the `GraphSyncService` for cross-subsystem consistency.
3. Execute algorithmic traversals (Dijkstra, BFS) via pluggable `PathFinders`.
4. Provide `GraphQueryCache` for distributed result caching using Redis.

---

## Code Examples

### 1. Agnostic MATCH Pattern (SPI)

The same code is used regardless of the underlying driver or dialect.

```java
GraphQuery query = GraphQueryBuilder.match()
        .node("u", "User").where("id", userId)
        .outgoing("FOLLOWS").node("friend", "User")
        .returning("friend.id", "friend.handle")
        .build();

// Executor chooses the most efficient driver-specific implementation
TraversalResult results = graphService.execute(query);
```

### 2. Zero-Copy JSON Streaming (Core)

Eliminates Java-side serialization overhead by utilizing database-native JSON aggregation.

```java
// Inside a Virtual Thread / Request Handler
graphService.streamBfsJson(request, outputStream);
// Result: Data flows NIC -> Off-Heap -> NIC with minimal GC pressure
```

## Error Codes (Black Box Telemetry)

| Code           | Meaning               | Action                                         |
|:---------------|:----------------------|:-----------------------------------------------|
| `EX-GRPH-5001` | Transpilation Failure | Mismatch between DSL and Dialect capabilities. |
| `EX-GRPH-5002` | Path Not Found        | Algorithm failed to reach target node.         |
| `EX-GRPH-5003` | Sync Inconsistency    | Relational change failed to reflect in Graph.  |
| `EX-GRPH-5005` | Excessive Allocation  | Driver exceeded pre-defined churn thresholds.  |

## Testing Strategy

### Unit Tests

Dialect parity: Ensure identical DSL produces correct SQL and Cypher.

Metadata discovery: Verify extraction from annotated records.

Pathfinding: Validate algorithm logic (Dijkstra, BFS) on mock datasets.

### Integration Tests

Sync Integrity: Verify that L1 Persistence changes trigger correct L2 Graph updates.

Isolation Leak Test: Ensure StorageContext (or PrincipalContext) correctly restricts traversals.

Dialect Consistency: Verify bit-identical TraversalResult across different backends.

### Lab & Load Tests

Carrier Pinning: JFR-based validation that driver synchronization doesn't stall Virtual Threads.

Churn-to-Data Ratio: Measure bytes allocated per byte transferred for performance profiling.

## Summary

The Graph subsystem acts as a semantic bridge between raw data and relationship synthesis. By enforcing a protocol-blind
SPI, it allows the Exeris Kernel to evolve from standard Java drivers to specialized, kernel-bypass implementations
while maintaining a strict discipline of TRL-3 readiness.