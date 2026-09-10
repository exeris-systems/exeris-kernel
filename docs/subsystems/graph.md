---
title: "Kernel Subsystem: Graph (L2 Data Synthesis)"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-08
---

# Kernel Subsystem: Graph (L2 Data Synthesis)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.graph.*` (MATCH DSL, Dialect SPI, Session Contracts)
- Core: `eu.exeris.kernel.core.graph.*` (Query Transpiler, Metadata Engine, Algo-Orchestrator)
- Drivers:
    - `community`: Standard JDBC (PostgreSQL PGQ) / Bolt (Neo4j / Memgraph)

**Layer:** L2 (Data Synthesis)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Graph subsystem** is a semantic synthesis engine. It transforms structured data from L1 Persistence into
traversable relationships using a unified **MATCH DSL**, bridging the gap between relational storage and graph
logic.

- **The Allocation Gap Mitigation:** Standard Bolt/JDBC drivers measure 17.4–20.8x allocation-to-data ratio in
  practice, settling into one of two allocation regimes on the Community Bolt path (see §2). Exeris Community
  supports these drivers against a documented `< 20x` contract, met in the faster regime.
- **Unified MATCH DSL:** A protocol-blind query surface — the same `GraphSession` calls render to plain SQL
  (PostgreSQL, via recursive CTEs) or Cypher (Neo4j / Memgraph / FalkorDB) depending on the active
  `GraphDialect` — the same business code works on both. A separate `MatchDslTranspiler` component targets
  literal SQL:2023 PGQ (`GRAPH_TABLE`) syntax but is not wired into that live path today (see Core
  Philosophy §1).
- **No-Arena Enforcement:** All graph-related native memory is carved exclusively from L0 `MemoryAllocator` slabs.
  Drivers are prohibited from opening independent FFM `Arena` instances, ensuring full visibility to
  `GlobalMemoryArbiter` and JFR Telemetry.

---

## Core Philosophy

### 1. Intent over Implementation

We use the **MATCH** pattern to express relationship intent. The Kernel is responsible for finding the most
efficient way to execute this intent on the active driver — business code never changes when the driver is
swapped.

> **Correction — not native GDS.** BFS traversal is pushed down as a query on both backends (SQL/PGQ recursive
> CTE, Cypher variable-length `MATCH`), but shortest-path is not: on Community, `findShortestPath` always runs
> the same in-JVM Dijkstra (`CommunityPathFinder`, a heap `ArrayList`/`PriorityQueue` implementation) after
> loading the relevant adjacency, for **both** the PostgreSQL and the Neo4j backend — it does not call Neo4j's
> native Graph Data Science procedures. `GraphDialect.buildShortestPathQuery` and Core's
> `MatchDslTranspiler.transpileShortestPath` generate a push-down shortest-path query string for both dialects,
> but nothing in the session or backend code calls either of them — `MatchDslTranspiler` is not instantiated
> anywhere outside its own source file and tests. "SQL/PGQ push-down for PostgreSQL or native GDS for Neo4j"
> describes a design this tree does not implement.

### 2. Metric Transparency (Churn-to-Data Ratio)

Exeris does not mask the cost of its abstraction. In TCK mode, every driver reports a **Churn-to-Data Ratio**:
bytes allocated per byte of graph data transferred. `GraphChurnRatioTck` measures the ratio over a fan-out
traversal. The tier contract is Community
`< 20x`, Enterprise `< 1x`; what the TCK *fails the build on* is a regression bound at or above it, for the
reason set out below.

Two properties of that measurement follow from what the ratio is *about*, and both are load-bearing:

- **The numerator counts driver allocation.** The documented cost is the driver's, not the kernel's, so the
  measurement uses the exact per-thread allocated-bytes delta, not a JFR event
  stream filtered to `eu.exeris.*` types. On the Community Bolt path the kernel's own share is ~1% of the total;
  a filtered numerator would exclude the thing being measured, and would report a perfect `0.0` for a driver that
  allocated gigabytes outside the `eu.exeris` namespace.
- **The traversal must return a result set.** The ratio is per byte of data transferred, so the workload has to
  carry enough data for the fixed per-round-trip cost to amortise. A 1-hop traversal returning a single id costs
  ~11.7 KB of allocation for 16 bytes of payload — a ratio in the 700s that measures session and protocol setup
  rather than churn per data byte.

**Measured, and it does not land on one number.** On the Community Bolt path the ratio settles into one of two
regimes, chosen once per JVM and then held for the life of the process (three JVMs × 4 200 traversals each, no
window mixing):

| Regime | Bytes / traversal | Ratio | Against the `< 20x` contract |
|:---|---:|---:|:---|
| fast | ~142 000 | 17.4 – 18.0x | inside |
| slow | ~166 000 | 20.5 – 20.8x | **breached** |

Roughly two runs in seven take the slow regime (4 of 14 observed processes). The `< 20x` figure above is
therefore the contract, not a description of every run; `GraphChurnRatioTck` enforces a higher *regression* bound so that a pre-existing regime
choice does not read as a regression, and reports the contract alongside each measurement. Closing the gap — or
deciding which figure is the honest one to publish — is tracked in `docs/ROADMAP.md`.

A cold process does not meet the contract at all: allocation runs 18.0–18.3x over the first hundred traversals
and 19.9–20.6x over the second, a JIT recompilation transient, before settling from traversal ~200 onward. The
published ratio is a steady-state figure.

### 3. No-Arena Policy (L0 Enforcement at L2)

Graph Drivers are prohibited from creating independent FFM `Arena` instances. They must request all memory
segments exclusively through the `MemoryAllocator` SPI. This ensures that graph-related off-heap usage is:

- **Visible** to `GlobalMemoryArbiter` (enabling backpressure and load-shedding).
- **Tracked** by `LeakTracker` and `WatermarkManager` (preventing silent OOM).
- **Auditable** via JFR Telemetry (`CryptoContextAllocEvent` equivalent for graph slabs).

### 4. Dual-Write Consistency

Built-in `GraphSyncService` orchestration ensures that relational state changes in L1 are atomically reflected
in L2 graph structure — or rolled back together on failure (`EX-GRPH-5003`).

> **Known gap in the node-delete path (#468):** `GraphSyncService.syncNodeDelete` reaches L2 through
> `GraphSession.deleteNode`, and that method's two Community backends do not agree on what "reflected"
> means. The Cypher backend issues `DETACH DELETE`, which removes the node and every edge that touched
> it. The SQL/PGQ backend issues a bare `DELETE FROM graph_nodes …` — the edge tables have no foreign
> key back to `graph_nodes` (see `CommunityGraphDialect.buildCreateEdgeTable`) and nothing else in the
> write path cleans them up, so edge rows referencing the deleted node survive and a later traversal
> still walks them. Which behaviour a caller gets depends on which backend is bound; the SQL/PGQ path
> does not honour the "and all connected edges" half of `GraphSession#deleteNode`'s contract.

---

## Responsibilities

**What Graph SPI DOES:**

1. Define `GraphEngine` and `GraphSession` lifecycle contracts.
2. Provide the fluent `MATCH` query builder and `GraphDialect` extension points.
3. Define metadata structures for Nodes and Edges based on domain annotations.

**What Graph Core DOES:**

1. Discover graph metadata (`GraphMetadataEngine`). `MatchDslTranspiler` — the Core class documented as
   transpiling DSL queries into native SQL/PGQ or Cypher strings — exists and is unit-tested but is not
   instantiated anywhere in the live session path today: the Community backends call `GraphDialect`
   methods (`buildMatchQuery`, `buildMultiHopQuery`) directly (see Core Philosophy §1 above).
2. Manage the `GraphSyncService` for cross-subsystem consistency.
3. Execute shortest-path algorithms (Dijkstra, Yen's k-shortest) via the pluggable `PathFinder` SPI.
   BFS traversal (`traverseBreadthFirst`, `streamBfsJson`) does not go through `PathFinder` — it is
   pushed down as one dialect-generated query per call (see the Cycle Detection section below).
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
| `EX-GRPH-5005` | Excessive Allocation      | `[0] String driverName, [1] long bytesAllocated, [2] long bytesXfer` — Thrown as: `ExcessiveAllocationException` (`eu.exeris.kernel.spi.exceptions.graph.ExcessiveAllocationException`) |

**`EX-GRPH-5005` status:** `ExcessiveAllocationException` is declared in the SPI and its `rawArgs` layout is
covered by `GraphExceptionLayoutTest`, but no kernel code path throws it — a driver self-reporting a breach at
runtime would be its first caller. TCK enforcement of the ratio does not go through it: `GraphChurnRatioTck`
fails the build with an assertion carrying the measured ratio, the allocated bytes and the transferred bytes.

**`GraphSession#findShortestPath(source, target)` status (#466):** the two-argument overload — the one the
SPI documents as returning "path result (may indicate not found)" — never searches for a distinct pair on the
Community binding. `CommunityGraphSession` returns a zero-hop path only when `source.equals(target)`, and
`PathResult.notFound(...)` for every other pair, unconditionally; it has no edge descriptor to load adjacency
from. The capability exists — `findShortestPath(GraphEdgeDescriptor, source, target)` loads adjacency for the
given relationship type into a `CommunityPathFinder` and runs Dijkstra correctly — but the two-argument entry
point cannot reach it. A caller holding two connected node IDs and calling the SPI method as documented gets
"not found" every time.

---

## Code Examples

### 1. Protocol-Blind MATCH Traversal (SPI)

The same code runs unchanged on PostgreSQL (plain SQL via `CommunityGraphDialect`, not literal SQL/PGQ
syntax — see Core Philosophy §1) and Neo4j (Cypher).

```java
// Example: BFS traversal using GraphTraversal
GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
GraphTraversal traversal = GraphTraversal.create(
    startNodeId,  // UUID
    follows,      // GraphEdgeDescriptor — not a raw String edge type
    2             // maxDepth (required; GraphTraversal has no default)
);

try (GraphSession session = engine.openSession()) {
    // Typed result: List<UUID>
    List<UUID> reachable = session.traverseBreadthFirst(traversal);

    // Or zero-copy streaming result (LoanedBuffer containing JSON):
    try (LoanedBuffer result = session.streamBfsJson(traversal)) {
        // consume bytes from result.segment()
    }
}
```

> **Note:** The fluent DSL (`GraphQueryBuilder`) described in earlier documentation does not exist in the current SPI. The current traversal API uses `GraphTraversal` record directly.

### 2. GraphDialect Extension Point (SPI)

> **Corrected:** earlier documentation showed a `transpile(GraphQuery)` / `supportsNativePathAlgorithms()`
> shape. Neither method, nor a `GraphQuery` type, exists anywhere in this tree. The real contract below is
> transcribed from `eu.exeris.kernel.spi.graph.GraphDialect` (abridged; see the source for the DDL-building
> methods and full Javadoc).

```java
package eu.exeris.kernel.spi.graph;

public interface GraphDialect {
    String buildMatchQuery(GraphEdgeDescriptor edge);
    String buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops);
    String buildShortestPathQuery(GraphEdgeDescriptor edge, int maxDepth);
    String buildCreatePropertyGraph(List<GraphNodeDescriptor> nodes, List<GraphEdgeDescriptor> edges);
    String buildCreateEdgeTable(GraphEdgeDescriptor edge);
    String buildDropPropertyGraph();
    String dialectName();
}
```

---

## Driver Roadmap and Production Readiness

| Driver                          | Tier        | Status      | Production Ready? | Notes                                                    |
|:--------------------------------|:------------|:-----------:|:-----------------:|:---------------------------------------------------------|
| PostgreSQL JDBC (PGQ)           | Community   | ✅ TRL-3    | ✅ Yes             | Standard JDBC, full VT-compatible. `deleteNode` does not remove connected edges (#468, dangling rows — see §4 above); `findShortestPath(source, target)` never searches a distinct pair (#466, shared with the Bolt backends below). No test in this tree runs the SQL/PGQ dialect against a live PostgreSQL instance — the only integration coverage (`CommunityGraphBackendParityIT`) exercises Cypher against Neo4j and checks SQL/PGQ only for non-blank generated query text. |
| Neo4j Bolt (Java driver)        | Community   | ✅ TRL-3    | ✅ Yes             | Standard Bolt driver, heap allocating (17.4–20.8x churn measured across two regimes, see §2). `findShortestPath(source, target)` never searches a distinct pair (#466). |
| Memgraph Bolt                   | Community   | ✅ TRL-3    | ✅ Yes             | Same driver as Neo4j Bolt (Bolt protocol compatible). Same `findShortestPath(source, target)` gap (#466). |

---

## BFS Traversal — Pagination and Cycle Detection

### Result Pagination / Cursor API

> **Planned — not yet implemented.** `GraphCursor` and `GraphSession.bfsCursor()` do not exist in the current SPI. The current API supports single-result traversal via `traverseBreadthFirst(GraphTraversal)` and `streamBfsJson(GraphTraversal)`.

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
> There is no cursor surface for a result that does not: `bfsCursor()` appears in no source file,
> and `EX-GRPH-5005` is never thrown (see the error-code table above). Today an unbounded traversal
> materialises its whole result — `traverseBreadthFirst` into an `ArrayList<UUID>`, `streamBfsJson`
> into one `LoanedBuffer` sized to the encoded array — so the bound is whatever the driver returns.

### Cycle Detection

Community's `traverseBreadthFirst` and `streamBfsJson` do not run a BFS loop in kernel code at all — they
delegate to one push-down query per call (`GraphDialect.buildMatchQuery` for a single hop,
`buildMultiHopQuery` otherwise: a `WITH RECURSIVE` CTE on SQL/PGQ, a bounded variable-length `MATCH` on
Cypher), so cycle safety is a property of that query, not of a heap-resident visited set.

| Control                        | Where it lives                                                              | Default |
|:--------------------------------|:-----------------------------------------------------------------------------|:-------:|
| **Max traversal depth**         | `GraphTraversal`'s canonical constructor: `maxDepth` (`int`), rejected below 1 with `IllegalArgumentException` | none — always explicit |
| **Max visited nodes**            | not implemented — `GraphTraversal` has no `maxNodes` field and no such cap exists anywhere in the traversal path | — |
| **Cycle termination mechanism** | the query itself: the recursive CTE stops recursing once `depth` reaches `maxDepth` (`WHERE t.depth < maxHops`), and the Cypher form bounds the same range in `*minHops..maxHops` — a cyclic edge can produce duplicate visits within that bound, deduplicated by `SELECT DISTINCT` / `RETURN DISTINCT`, but the depth bound alone is what stops the query, not a visited-node set | — |

There is no `exeris.graph.bfs.max-depth` or `exeris.graph.bfs.max-nodes` configuration key anywhere in this
tree, and no code path emits `EX-GRPH-5002` for a depth or node-count limit — `maxDepth` is a required,
always-explicit constructor argument with no default to fall back to, so "leave it at the default" does not
apply: every caller of `GraphTraversal`'s constructor (directly or via `GraphTraversal.create`) must choose a
value. `CommunityPathFinder` — the Dijkstra implementation behind `findShortestPath(GraphEdgeDescriptor, …)`,
not the BFS traversal methods — does hold a heap `HashSet<UUID>` of visited nodes while it walks adjacency;
that is a shortest-path detail, not a BFS one.

## Testing Strategy

### Unit Tests

- Dialect parity: verify identical DSL produces correct SQL/PGQ and Cypher.
- Metadata discovery: verify extraction from annotated `record` nodes/edges.
- Pathfinding: `AbstractAlgoOrchestratorTck` (bound in `CommunityAlgoOrchestratorTckTest`) validates
  Dijkstra correctness — found/not-found, hop count, cost — against `PathFinder` directly, not through
  a `GraphSession`; there is no separate unit test for BFS traversal logic, since BFS has none of its
  own to test (see Cycle Detection above).

### Integration Tests (TCK)

- **Sync Integrity:** `AbstractGraphSyncServiceTck` verifies `GraphSession`'s own write/rollback/
  exception-signalling behaviour directly — it never imports or calls `GraphSyncService` — node and
  edge upsert/delete complete cleanly against a healthy session, and a session put into fail mode
  fails the operation with `rollback()` still callable; separately, a directly-constructed
  `GraphSyncException` reports `EX-GRPH-5003` with the edge type or node label in `rawArgs[0]`. It
  does not test an automatic L1-write-triggers-L2-sync wiring, and none exists — but `GraphSyncService`
  itself has no call site anywhere in this tree outside test code: nothing in application code calls
  it at all.

The following three checks are described in earlier documentation but have no backing test or type in
this tree today — listed here as gaps, not coverage:

- No test exercises `StorageContext`-based tenant isolation for graph traversals; `CommunityGraphDialect`'s
  generated edge-table DDL deliberately has no `tenant_id` column (`CommunityGraphDialectTest`'s
  `tenantIdNotPresent()` asserts exactly that), so any tenant scoping for the SQL/PGQ backend would have
  to come from the connection `StorageContext` routes to, not from a column the graph layer owns — and
  nothing in this tree proves that routing holds for a graph traversal specifically.
- There is no `TraversalResult` type anywhere in the codebase, and no test comparing PostgreSQL and Neo4j
  output bit-for-bit; `CommunityGraphBackendParityIT` (Testcontainers Neo4j, `@Tag("integration")`) proves
  the Cypher backend executes real writes and reads, and separately asserts both dialects produce
  non-blank query strings for the same edge descriptor — not output parity between the two live backends.
- No test asserts that direct `Arena` instantiation from a graph driver is detected and rejected. The
  closest existing rule, `ExerisArchitectureTest.noDirectArenaInSpi`, forbids `java.lang.foreign.Arena`
  as a dependency of the SPI module generally; it is not graph-specific and does not run against Community
  driver code.

### Load Tests

- **Churn-to-Data Ratio:** `GraphChurnRatioTck` measures allocated bytes per byte of data
  transferred over a fan-out traversal, and fails the build with an assertion carrying the measured
  ratio, the allocated bytes and the transferred bytes. It does **not** emit `EX-GRPH-5005`; nothing
  throws that exception (see the error-code table above).
- **Carrier Pinning (unbound):** `GraphCarrierPinningTck` (extends `AbstractSubsystemCarrierPinningTck`)
  defines JFR-based validation that driver I/O does not stall Virtual Threads over the
  `openSession() → findShortestPath() → close()` hot path (`CarrierPinnedEvent` must not fire). No
  Community (or any other tier) binding subclasses it, so this check does not run in this tree today.

> **TCK bindings:** Both `ExecutionGraphZeroAllocTck` and `GraphChurnRatioTck` now have Community-tier concrete bindings in `exeris-kernel-community/src/test/`. `CommunityExecutionGraphZeroAllocTckTest` runs in the main `build-and-verify` lane (in-process shortest-path hot path, no database). `CommunityGraphChurnRatioTckIT` is `@Tag("integration")` (live Neo4j via Testcontainers) and runs in the `persistence-rls-gate` CI job — gating the Community churn-to-data ratio below the **regression** bound described in §2, not below the published 20x contract, which this path meets in only one of its two allocation regimes.

---

## Summary

The Graph subsystem acts as the semantic bridge between raw relational data (L1) and graph-native reasoning (L2).
By enforcing the No-Arena Policy, it extends the L0 Memory Contract to L2 — every off-heap byte consumed by a
graph driver is visible to `GlobalMemoryArbiter`, auditable via JFR, and subject to backpressure. The unified
MATCH DSL decouples business intent from backend implementation, enabling seamless migration from high-allocation
Community drivers without changing a single line of business code.

---

## Owning ADRs

**No ADR in this repository decides this subsystem.** The only registry entry naming the
kernel graph SPI is [ADR-030](../adr/ADR-030.link.md), which is owned by `exeris-spring-runtime` and
decides the Spring-side seam *onto* this SPI rather than the SPI itself. The one graph decision the
roadmap requires — heterogeneous multi-hop traversal, which `docs/ROADMAP.md` places **in 1.0** and
sequences out of 0.12 — is an RFC that has not been written.

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.graph.*`) is classified **preview** in the
[SPI Stability Matrix](../stability-matrix.md): baseline hardening is still pending. See the matrix
for the semver policy and TCK coverage status.
