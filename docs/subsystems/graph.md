# Kernel Subsystem: Graph (L2 Data Synthesis)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.graph.*` (MATCH DSL, Dialect SPI, Session Contracts)
- Core: `eu.exeris.kernel.core.graph.*` (Query Transpiler, Metadata Engine, Algo-Orchestrator)
- Drivers:
    - `community`: Standard JDBC (PostgreSQL PGQ) / Bolt (Neo4j / Memgraph)
    - `enterprise`: Native Wire (`io_uring` + PostgreSQL) / FFM-Native Bolt (Planned)

**Layer:** L2 (Data Synthesis)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Graph subsystem** is a semantic synthesis engine. It transforms structured data from L1 Persistence into
traversable relationships using a unified **MATCH DSL**, bridging the gap between relational storage and graph
logic.

- **The Allocation Gap Mitigation:** Standard Bolt/JDBC drivers exhibit a ~15x allocation-to-data ratio (30 GB
  allocated to process 2 GB of graph data). Exeris Community supports these drivers as a documented baseline.
  Enterprise drivers target a **< 1x ratio** via Panama FFM and `LoanedBuffer` slabs.
- **Unified MATCH DSL:** A protocol-blind query builder that transpiles to SQL:2023 PGQ (PostgreSQL 18) or Cypher
  (Neo4j / Memgraph / FalkorDB) based on the active driver — the same business code works on both.
- **No-Arena Enforcement:** All graph-related native memory is carved exclusively from L0 `MemoryAllocator` slabs.
  Drivers are prohibited from opening independent FFM `Arena` instances, ensuring full visibility to
  `GlobalMemoryArbiter` and JFR Telemetry.

---

## Core Philosophy

### 1. Intent over Implementation

We use the **MATCH** pattern to express relationship intent. The Kernel is responsible for finding the most
efficient way to execute this intent on the active driver (SQL/PGQ push-down for PostgreSQL or native GDS for
Neo4j) — business code never changes when the driver is swapped.

### 2. Metric Transparency (Churn-to-Data Ratio)

Exeris does not mask the cost of its abstraction. In TCK mode, every driver emits a **Churn-to-Data Ratio**:
bytes allocated per byte of graph data transferred. If a Community driver reports ~15x, that is the documented
baseline. If an Enterprise driver exceeds a ratio of **1.0**, the TCK fails with `EX-GRPH-5005` — this is the
binary enforcement point of the Performance Contract for L2.

### 3. No-Arena Policy (L0 Enforcement at L2)

Graph Drivers are prohibited from creating independent FFM `Arena` instances. They must request all memory
segments exclusively through the `MemoryAllocator` SPI. This ensures that graph-related off-heap usage is:

- **Visible** to `GlobalMemoryArbiter` (enabling backpressure and load-shedding).
- **Tracked** by `LeakTracker` and `WatermarkManager` (preventing silent OOM).
- **Auditable** via JFR Telemetry (`CryptoContextAllocEvent` equivalent for graph slabs).

### 4. Dual-Write Consistency

Built-in `GraphSyncService` orchestration ensures that relational state changes in L1 are atomically reflected
in L2 graph structure — or rolled back together on failure (`EX-GRPH-5003`).

---

## Performance Tiering

| Metric               | Community (Standard)          | Enterprise (Native)                   |
|:---------------------|:------------------------------|:--------------------------------------|
| **I/O Strategy**     | Standard Sockets / JDBC       | `io_uring` / Native Wire              |
| **Memory Policy**    | JVM Heap (High Churn)         | Off-Heap (`LoanedBuffer`)             |
| **Allocation Ratio** | ~15x (Documented Baseline)    | **< 1x (TCK-enforced Target)**        |
| **Thread Model**     | Virtual Threads (blocking)    | Virtual Threads (non-pinning)         |
| **Isolation**        | Logical (app-level filtering) | Physical (`StorageContext` RLS)       |

---

## Enterprise Driver Architecture (Secret Sauce)

Enterprise graph drivers utilize **Static Slab Traversal** and **Native Dialect Push-down**. This bypasses
the Object-Relational Impedance Mismatch by processing relationships directly in memory-mapped regions,
synchronized with the L1 `StorageContext`:

- **Static Slab Traversal:** Node and edge records are laid out in contiguous `MemoryAllocator` slabs.
  Traversal is pointer arithmetic — no object instantiation per record.
- **Native Dialect Push-down:** Complex MATCH patterns are compiled to a single SQL/PGQ or Cypher statement
  executed entirely server-side, eliminating Java-side row-by-row hydration.
- **Direct-to-NIC Binary Stream:** Results are written from the database wire buffer directly into a
  `LoanedBuffer` transport slot — no intermediate `ResultSet` object, no `String` column extraction.

---

## Responsibilities

**What Graph SPI DOES:**

1. Define `GraphSession` and `GraphBackend` lifecycle contracts.
2. Provide the fluent `MATCH` query builder and `GraphDialect` extension points.
3. Define metadata structures for Nodes and Edges based on domain annotations.

**What Graph Core DOES:**

1. Discover graph metadata and transpile DSL queries into native SQL/PGQ or Cypher strings.
2. Manage the `GraphSyncService` for cross-subsystem consistency.
3. Execute algorithmic traversals (Dijkstra, BFS) via pluggable `PathFinders`.
4. Enforce the No-Arena Policy by verifying all driver allocations go through `MemoryAllocator`.

---

## Error Codes

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`.

| Code           | Meaning                   | Glass-Box Payload (`rawArgs`)                                        |
|:---------------|:--------------------------|:---------------------------------------------------------------------|
| `EX-GRPH-5001` | Engine Bootstrap Failure  | `[0] String providerName, [1] String reason`                         |
| `EX-GRPH-5002` | Query Execution Failure   | `[0] String queryType, [1] String detail`                            |
| `EX-GRPH-5003` | Dual-Write Sync Failure   | `[0] String edgeType, [1] String detail`                             |
| `EX-GRPH-5004` | Path Not Found            | `[0] long sourceMost, [1] long sourceLeast, [2] long targetMost, [3] long targetLeast` |
| `EX-GRPH-5005` | Excessive Allocation      | `[0] String driverName, [1] long bytesAllocated, [2] long bytesXfer` |

**TCK enforcement for `EX-GRPH-5005`:** When running with `LeakDetectionMode.PARANOID`, the TCK measures the
Churn-to-Data Ratio after each traversal benchmark. A ratio exceeding **1.0** in any Enterprise driver causes
the test suite to emit `EX-GRPH-5005` and fail — this is the binary Performance Contract gate for L2.

---

## Code Examples

### 1. Protocol-Blind MATCH Traversal (SPI)

The same code runs unchanged on PostgreSQL 18 (SQL/PGQ) and Neo4j (Cypher).

```java
GraphQuery query = GraphQueryBuilder.match()
        .node("u", "User").where("id", userId)
        .outgoing("FOLLOWS").node("friend", "User")
        .returning("friend.handle")
        .build();

graphService.streamBfsJson(query, transportOutput);
```

> `streamBfsJson` writes results directly into the transport `LoanedBuffer` — no intermediate `List<User>`,
> no heap serialization. Data flows: DB wire buffer → Off-Heap slab → NIC.

### 2. GraphDialect Extension Point (SPI)

```java
package eu.exeris.kernel.spi.graph;

public interface GraphDialect {
    String transpile(GraphQuery query);
    boolean supportsNativePathAlgorithms();
}
```

### 3. No-Arena Policy — Correct Slab Allocation (Driver)

```java
public class NativeGraphDriver implements GraphBackend {
    private final MemoryAllocator allocator;

    public LoanedBuffer allocateResultSlab(int expectedRows) {
        return allocator.allocate(AllocationHint.LARGE);
    }
}
```

---

## Driver Roadmap and Production Readiness

| Driver                          | Tier        | Status      | Production Ready? | Notes                                                    |
|:--------------------------------|:------------|:-----------:|:-----------------:|:---------------------------------------------------------|
| PostgreSQL JDBC (PGQ)           | Community   | ✅ TRL-3    | ✅ Yes             | Standard JDBC, full VT-compatible                        |
| Neo4j Bolt (Java driver)        | Community   | ✅ TRL-3    | ✅ Yes             | Standard Bolt driver, heap allocating (~15x churn documented) |
| Memgraph Bolt                   | Community   | ✅ TRL-3    | ✅ Yes             | Same driver as Neo4j Bolt (Bolt protocol compatible)    |
| PostgreSQL `io_uring` native    | Enterprise  | 🚧 TRL-4    | ❌ Not yet         | Wire protocol implementation over `io_uring` + Panama FFM |
| FFM-Native Bolt (Neo4j/Memgraph)| Enterprise  | 🚧 TRL-4    | ❌ Not yet         | Binary Bolt v5 over Panama FFM socket + `LoanedBuffer` result streaming. Eliminates `org.neo4j:neo4j-java-driver` heap allocations entirely. |

**FFM-Native Bolt — what it means when available:** The Community Bolt driver (`org.neo4j:neo4j-java-driver`)
allocates Java objects for every record in `ResultSet`: `Value` wrappers, `Record` instances, property `Map`s.
Under sustained graph traversal this produces the ~15x churn documented in the Performance Tiering table.
The Enterprise FFM-Native Bolt driver will speak the Bolt binary protocol directly over a Panama FFM socket,
writing results from the Neo4j wire stream directly into `LoanedBuffer` slabs — eliminating record wrappers
entirely. Target churn ratio: < 1x.

**Until TRL-4:** For applications requiring near-zero allocation on graph hot-paths today, prefer the
PostgreSQL PGQ backend with `io_uring` transport (Community tier uses standard JDBC but avoids the
Bolt object wrapper tax). PGQ is a first-class graph query language in PostgreSQL 18 and produces
comparable traversal results to Cypher for standard relationship patterns.

---

## BFS Traversal — Pagination and Cycle Detection

### Result Pagination / Cursor API

`streamBfsJson` writes results directly into a `LoanedBuffer` slab. If the slab is smaller than the
traversal result, the Kernel does NOT buffer the remainder in heap — it streams using a cursor:

```java
public interface GraphCursor extends AutoCloseable {
    boolean hasNext();
    void writeNextBatch(LoanedBuffer target);   // zero-copy batch write
    long totalEstimatedRows();                  // hint only — may be -1 (unbounded)
}

// Usage in transport layer:
try (GraphCursor cursor = graphService.bfsCursor(query)) {
    while (cursor.hasNext()) {
        try (LoanedBuffer slab = allocator.allocate(AllocationHint.LARGE)) {
            cursor.writeNextBatch(slab);
            transport.send(slab);
        }
    }
}
```

> If a traversal result fits in a single slab, `streamBfsJson` is the zero-allocation fast path.
> For unbounded graph traversals, use `bfsCursor()` to prevent `EX-GRPH-5005` (slab overflow).

### Cycle Detection

BFS on graphs with cycles without a visited set is an infinite loop. Exeris BFS enforces:

| Control                       | Config Key / SPI Method                          | Default    |
|:------------------------------|:-------------------------------------------------|:----------:|
| **Max traversal depth**       | `GraphQueryBuilder.maxDepth(int)` or `exeris.graph.bfs.max-depth` | 10 |
| **Max visited nodes**         | `GraphQueryBuilder.maxNodes(long)` or `exeris.graph.bfs.max-nodes` | 100 000 |
| **Visited set implementation**| Off-heap bitset (Community: heap `HashSet`)      | Off-heap (Enterprise) |

When `maxDepth` or `maxNodes` is exceeded, the traversal terminates immediately and returns the
partial result accumulated up to that point. `EX-GRPH-5002` is emitted with
`rawArgs[0]="BFS_LIMIT_EXCEEDED"` and `rawArgs[1]=detail`.

**Operators must explicitly set `maxDepth`** for user-driven graph traversals (e.g., "find all
connections of user X"). Leaving it at the default 10 prevents runaway traversals on dense graphs.

## Testing Strategy

### Unit Tests

- Dialect parity: verify identical DSL produces correct SQL/PGQ and Cypher.
- Metadata discovery: verify extraction from annotated `record` nodes/edges.
- Pathfinding: validate Dijkstra and BFS logic on mock datasets.

### Integration Tests (TCK)

- **Sync Integrity:** L1 Persistence changes trigger correct L2 Graph updates (`EX-GRPH-5003` on failure).
- **Isolation Leak Test:** `StorageContext` correctly restricts traversals to the bound tenant.
- **Dialect Consistency:** Bit-identical `TraversalResult` across PostgreSQL and Neo4j backends.
- **No-Arena Compliance:** Driver allocations are verified to flow through `MemoryAllocator` — direct
  `Arena` instantiation detected and rejected.

### Load Tests

- **Churn-to-Data Ratio:** TCK measures bytes allocated per byte transferred. Enterprise target: < 1x.
  Failure emits `EX-GRPH-5005` with `bytesAllocated` and `bytesTransferred` in `rawArgs`.
- **Carrier Pinning:** JFR-based validation that driver I/O does not stall Virtual Threads
  (`CarrierPinnedEvent` must not fire during standard traversal).

---

## Summary

The Graph subsystem acts as the semantic bridge between raw relational data (L1) and graph-native reasoning (L2).
By enforcing the No-Arena Policy, it extends the L0 Memory Contract to L2 — every off-heap byte consumed by a
graph driver is visible to `GlobalMemoryArbiter`, auditable via JFR, and subject to backpressure. The unified
MATCH DSL decouples business intent from backend implementation, enabling seamless migration from high-allocation
Community drivers to zero-copy Enterprise engines without changing a single line of business code.
