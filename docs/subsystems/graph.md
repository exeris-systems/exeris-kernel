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

- **The Allocation Gap Mitigation:** On the Community Neo4j Bolt path the measured allocation-to-data ratio is
  17.5–20.8x across two per-JVM regimes, against a `< 23x` contract that `GraphChurnRatioTck` enforces
  (see §2). The SQL/PGQ path is not measured.
- **Unified MATCH DSL:** A protocol-blind query surface — the same `GraphSession` calls render to plain SQL
  (PostgreSQL, via recursive CTEs) or Cypher (Neo4j / Memgraph / FalkorDB) depending on the active
  `GraphDialect` — the same business code works on both. A separate Core class, `MatchDslTranspiler`,
  dispatches a traversal to those same `GraphDialect` builders and writes no syntax of its own; no session
  or backend calls it (see Core Philosophy §1).
- **No-Arena Enforcement:** All graph-related native memory is carved exclusively from L0 `MemoryAllocator` slabs.
  Drivers are prohibited from opening independent FFM `Arena` instances, ensuring full visibility to
  `GlobalMemoryArbiter` and JFR Telemetry.
- **Slots:** the `ScopedValue` slots are `GraphKernelProviders.GRAPH_PROVIDER` and `GraphKernelProviders.GRAPH_ENGINE`, read through `GraphKernelProviders.graphEngine()`; the `KernelProviders` names of the same slots are deprecated for removal in the first 1.0 release candidate (ADR-100 §3).

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

Exeris does not mask the cost of its abstraction. A driver's **Churn-to-Data Ratio** is the bytes it
allocates per byte of graph data it returns, and `GraphChurnRatioTck` measures it over a 1-hop
traversal returning 500 ids. The figure the TCK fails the build on is the published contract:
Community `< 23x`, Enterprise `< 1x`.

Two properties of that measurement follow from what the ratio is *about*, and both are load-bearing:

- **The numerator counts driver allocation.** The documented cost is the driver's, not the kernel's, so the
  measurement uses the exact per-thread allocated-bytes delta, not a JFR event stream filtered to
  `eu.exeris.*` types. On the Community Bolt path the kernel's own share is ~1% of the total; a filtered
  numerator would exclude the thing being measured.
- **The traversal must return a result set.** The ratio is per byte of data returned, so the workload has to
  carry enough data for the fixed per-round-trip cost to amortise. A 1-hop traversal returning a single id
  costs ~11.7 KB of allocation for 16 bytes of payload, a ratio in the 700s that measures session and
  protocol setup rather than churn per data byte.

**Measured: two regimes, one bound.** On the Community Neo4j Bolt path (`CommunityGraphChurnRatioTckIT`)
the ratio settles into one of two allocation regimes, chosen once per JVM and held for the life of the
process:

| Regime | Bytes / traversal | Ratio |
|:---|---:|---:|
| fast | ~142 000 | 17.5 – 17.9x |
| slow | ~166 500 | 20.8x |

1 of 16 fresh processes took the slow regime. The Community contract covers both: a process in either
regime meets `< 23x`, and the published figure is the observed range, 17.5–20.8x, not the fast regime
alone. The bound sits about 10% above the slow-regime ceiling, and a change adding 128 bytes per
returned row breaches it from either regime. Kernel-owned allocation is ~1% of the total, so the split
lies in the driver path or in how the JIT compiles it; its cause is not established.

What the figure covers:

- **Steady state.** A fresh JVM allocates 18.0–18.3x over its first hundred traversals and 19.9–20.6x over
  the second, a JIT recompilation transient, before settling. The transient stays inside the bound; the
  published range is the settled one, and the TCK discards 300 warm-up traversals before measuring.
- **The Bolt path only.** The SQL/PGQ (JDBC) path has no churn binding and no published figure. A PGQ
  binding has to run its traversals inside a caller-supplied persistence scope, which
  `GraphChurnRatioTck` does not offer, and Exeris publishes no churn figure for a path a binding does
  not measure.
- **Semantic bytes.** The denominator counts 16 bytes per returned UUID, not the 36-character string the
  wire carries, which makes the ratio larger and the bound stricter than counting transferred bytes.

### 3. No-Arena Policy (L0 Enforcement at L2)

Graph Drivers are prohibited from creating independent FFM `Arena` instances. They must request all memory
segments exclusively through the `MemoryAllocator` SPI. This ensures that graph-related off-heap usage is:

- **Visible** to `GlobalMemoryArbiter` (enabling backpressure and load-shedding).
- **Tracked** by `LeakTracker` and `WatermarkManager` (preventing silent OOM).
- **Auditable** via JFR Telemetry (`CryptoContextAllocEvent` equivalent for graph slabs).

### 4. Dual-Write Consistency

Built-in `GraphSyncService` orchestration ensures that relational state changes in L1 are atomically reflected
in L2 graph structure — or rolled back together on failure (`EX-GRPH-5003`).

Both the Cypher and SQL/PGQ backends remove the node and its connected edges on `deleteNode`. On
SQL/PGQ, connected edges in registered edge tables whose descriptor names the deleted node's label on
the source or target side are removed; a registered table that does not exist in the current scope
fails the call and nothing is deleted.

---

## Responsibilities

**What Graph SPI DOES:**

1. Define `GraphEngine` and `GraphSession` lifecycle contracts.
2. Provide the fluent `MATCH` query builder and `GraphDialect` extension points.
3. Define metadata structures for Nodes and Edges based on domain annotations.

**What Graph Core DOES:**

1. Discover graph metadata (`GraphMetadataEngine`). `MatchDslTranspiler` returns the query string the
   bound `GraphDialect` builds for a traversal or shortest-path request, and only tests call it: no
   session or backend instantiates it, and the Community backends call `GraphDialect` methods
   (`buildMatchQuery`, `buildMultiHopQuery`) themselves (see Core Philosophy §1 above).
2. Manage the `GraphSyncService` for cross-subsystem consistency.
3. Execute shortest-path algorithms (Dijkstra, Yen's k-shortest) via the pluggable `PathFinder` SPI.
   BFS traversal (`traverseBreadthFirst`, `streamBfsJson`) is the single-hop case of `traversePath`,
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
| `EX-GRPH-5005` | Excessive Allocation      | `[0] String driverName, [1] long bytesAllocated, [2] long bytesXfer` — Reserved: declared as `ExcessiveAllocationException` (`eu.exeris.kernel.spi.exceptions.graph.ExcessiveAllocationException`); nothing throws it |

**`EX-GRPH-5005` status: reserved.** `ExcessiveAllocationException` is declared in the stable `spi.exceptions`
surface and its `rawArgs` layout is covered by `GraphExceptionLayoutTest`, but no kernel code path throws it.
It is kept for a driver that reports a churn-budget breach at runtime. The churn contract itself is enforced at
build time by `GraphChurnRatioTck`, which fails with an assertion carrying the measured ratio, the allocated
bytes and the transferred bytes.

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

## Multi-Hop Traversal (`GraphPathSpec`)

The multi-hop traversal primitive executes an ordered path specification as one statement / query,
honouring direction and returning the terminal frontier with walk semantics.

### Semantics

- **Path specification:** `GraphPathSpec(startNodeId, hops)` with ordered `GraphHop` steps. Hop *i* is
  `GraphHop(edge, minRepeat, maxRepeat)`, with `1 <= minRepeat <= maxRepeat`.
- **Direction:** Evaluated via effective direction `edge.bidirectional() ? BOTH : edge.direction()`:
  - `OUTGOING`: step from `u` reaches `t` where `s = u`.
  - `INCOMING`: step from `u` reaches `s` where `t = u`.
  - `BOTH`: step from `u` reaches `t` where `s = u`, and `s` where `t = u`.
- **Walk semantics:** Edges and nodes may repeat within and across hops. Cycles terminate by step bounds.
- **Terminal frontier:** Result is the set of nodes reached at the end of the last hop (not the union of
  intermediate frontiers), returned as `List<UUID>` without duplicates in unspecified order.
- **Empty hop:** A hop that matches nothing yields an empty result (`[]`), never an error.
- **Single-hop equivalence:** `traverseBreadthFirst(GraphTraversal(start, edge, d, ...))` is equivalent
  to `traversePath(new GraphPathSpec(start, List.of(new GraphHop(edge, 1, d))))`. Both honour direction.
- **Streaming:** `streamPathJson` returns one caller-owned `LoanedBuffer` holding a UTF-8 JSON array of
  quoted UUID strings (`["...","..."]`, `[]` when empty). The session never closes it.
- **Community limit:** Community bounds paths to `Σ maxRepeat <= 64` (and `maxDepth <= 64` for BFS). Exceeding
  the bound is refused before any I/O with `GraphQueryException` (`EX-GRPH-5002`, `queryType = "PATH_LIMIT"`).

### Generated Query Shapes

For hops `[GraphHop.of(PURCHASED), new GraphHop(SIMILAR_TO, 1, 2)]`:

**PostgreSQL (SQL/PGQ):**
```sql
WITH RECURSIVE
f0 (id) AS (SELECT CAST($1 AS uuid)),
f1 (id) AS (SELECT DISTINCT e.target_id AS id FROM f0 p INNER JOIN tck_purchased_edges e ON e.source_id = p.id),
h2 (id, depth) AS (SELECT p.id, 0 FROM f1 p UNION SELECT e.target_id, h.depth + 1 FROM h2 h INNER JOIN tck_similar_to_edges e ON e.source_id = h.id WHERE h.depth < 2),
f2 (id) AS (SELECT DISTINCT id FROM h2 WHERE depth >= 1)
SELECT id FROM f2
```

**Neo4j (Cypher):**
```cypher
MATCH (source) WHERE source.id = $sourceId
WITH DISTINCT source
MATCH (source)-[:PURCHASED]->(n1)
WITH DISTINCT n1
MATCH (n1)-[:SIMILAR_TO]->(n2)
WITH DISTINCT n2
MATCH (n2)-[:SIMILAR_TO*0..1]->(target)
WITH DISTINCT target
RETURN DISTINCT target.id AS id
```

### Java Example

```java
GraphPathSpec path = new GraphPathSpec(userId, List.of(
        GraphHop.of(PURCHASED),
        new GraphHop(SIMILAR_TO, 1, 2)
));
List<UUID> recommendations = session.traversePath(path);
```

### Tenant Scoping

Graph traversals execute under the ambient `StorageContext` (`KernelProviders.storageContextOrSystem()`).
A context is system-scope if its strategy is `SHARED` with an empty isolation key (such as `ImmutableStorageContext.GLOBAL`).
A non-system context whose isolation strategy the backend cannot scope is refused before any database I/O with
`GraphQueryException` (`EX-GRPH-5002`, `queryType = "ISOLATION_SCOPE"`).

| Binding | `SHARED` (tenant) | `SEPARATED_SCHEMA` | `DEDICATED` | system context |
|:---|:---:|:---:|:---:|:---:|
| SQL/PGQ | refused | scoped (`search_path`) | scoped (pool routing) | served |
| Cypher | refused | refused | refused | served |

A tenant context under SQL/PGQ is also refused when no `PersistenceEngine` is bound in the calling scope
(`KernelProviders.PERSISTENCE_ENGINE`): only the engine opens a connection under the ambient context,
while the request-session fallback serves a pooled connection that no interceptor has scoped.
System-scope calls are still served through that fallback.

Precondition for `SEPARATED_SCHEMA`: the connection's `search_path` is `<schema>, public`, so a graph table
that is missing from the tenant schema resolves to the table in `public`, the system graph, without an error.
Scoping holds only when every registered node and edge table exists in the tenant schema.
`registerNodes` and `registerEdges` create tables only in the scope they run under, so a deployment creates
them under each tenant context or provisions them per tenant. `DEDICATED` fails with an error on a missing table.

Guarded: the four traversal methods (`traverseBreadthFirst`, `streamBfsJson`, `traversePath`, `streamPathJson`). Not guarded: writes, `getRootNode`, `findShortestPath`.

---

## Driver Roadmap and Production Readiness

| Driver                          | Tier        | Status      | Production Ready? | Notes                                                    |
|:--------------------------------|:------------|:-----------:|:-----------------:|:---------------------------------------------------------|
| PostgreSQL JDBC (PGQ)           | Community   | ✅ TRL-3    | ✅ Yes             | Standard JDBC, full VT-compatible. Shortest-path adjacency treats `INCOMING` as outgoing (known gap). `CommunityGraphSessionPostgresContractTckIT` runs the SQL/PGQ dialect against a live PostgreSQL instance. Churn is not measured (see §2). |
| Neo4j Bolt (Java driver)        | Community   | ✅ TRL-3    | ✅ Yes             | Standard Bolt driver, heap allocating (17.5–20.8x churn across two regimes, `< 23x` contract, see §2). Shortest-path adjacency treats `INCOMING` as outgoing (known gap). |
| Memgraph Bolt                   | Community   | ✅ TRL-3    | ✅ Yes             | Same driver as Neo4j Bolt (Bolt protocol compatible). Shortest-path adjacency treats `INCOMING` as outgoing (known gap). |

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

Community's `traversePath`, `streamPathJson`, `traverseBreadthFirst` and `streamBfsJson` do not run a
traversal loop in kernel code at all — they delegate to one push-down query per call
(`GraphDialect.buildPathQuery`: a `WITH RECURSIVE` CTE on SQL/PGQ with `UNION` deduplicating `(id, depth)`
rows, bounded step `MATCH` clauses on Cypher), so cycle safety is a property of that query, not of a
heap-resident visited set.

| Control                        | Where it lives                                                              | Default |
|:--------------------------------|:-----------------------------------------------------------------------------|:-------:|
| **Max traversal depth**         | Community limit: `Σ maxRepeat <= 64` (and `maxDepth <= 64` for `GraphTraversal`); exceeding the limit is refused before I/O with `EX-GRPH-5002` (`PATH_LIMIT`) | — (Community refuses above 64) |
| **Max visited nodes**            | not implemented — `GraphTraversal` has no `maxNodes` field and no such cap exists anywhere in the traversal path | — |
| **Cycle termination mechanism** | the query itself: SQL recursive CTE bounds each hop by `depth < maxRepeat` and deduplicates `(id, depth)` rows with `UNION` (never `UNION ALL`); Cypher bounds per-hop repetition with explicit step `MATCH` clauses joined by `WITH DISTINCT`. Step bounds guarantee termination without a heap-resident visited set | — |

The 64 cap bounds the length of the generated query, not the work a Cypher ranged tail does: a variable-length
segment enumerates relationship-unique paths, which can grow exponentially on a dense cyclic graph, and nothing in
the traversal path verifies that the planner prunes it.

`CommunityPathFinder` — the Dijkstra implementation behind `findShortestPath(GraphEdgeDescriptor, …)`,
not the traversal methods — does hold a heap `HashSet<UUID>` of visited nodes while it walks adjacency;
that is a shortest-path detail, not a traversal one.

## Testing Strategy

### Unit Tests

- Dialect parity: verify identical DSL produces correct SQL/PGQ and Cypher.
- Metadata discovery: verify extraction from annotated `record` nodes/edges.
- Pathfinding: `AbstractAlgoOrchestratorTck` (bound in `CommunityAlgoOrchestratorTckTest`) validates
  Dijkstra correctness — found/not-found, hop count, cost — against `PathFinder` directly, not through
  a `GraphSession`.
- Dialect path query generation: `AbstractGraphDialectTck` and `CommunityGraphDialectTest` validate
  multi-hop SQL CTE and Cypher generation, directional arrows, and bound validation.
- Session path limit guard: `CommunityGraphSessionGuardTest` verifies the 64-hop cap and `PATH_LIMIT` refusal.

### Integration Tests (TCK)

- **Sync Integrity:** `AbstractGraphSyncServiceTck` verifies `GraphSession`'s own write/rollback/
  exception-signalling behaviour directly — it never imports or calls `GraphSyncService` — node and
  edge upsert/delete complete cleanly against a healthy session, and a session put into fail mode
  fails the operation with `rollback()` still callable; separately, a directly-constructed
  `GraphSyncException` reports `EX-GRPH-5003` with the edge type or node label in `rawArgs[0]`. It
  does not test an automatic L1-write-triggers-L2-sync wiring, and none exists — but `GraphSyncService`
  itself has no call site anywhere in this tree outside test code: nothing in application code calls
  it at all.
- **Live Contract:** `AbstractGraphSessionLiveContractTck` asserts `GraphSession` operations against
  a live backend: connected-pair shortest path, unconnected pairs, `deleteNode` edge cascade and
  re-creation, heterogeneous two-hop terminal frontier
  (`heterogeneousTwoHopPathReturnsTerminalFrontierInOrder`), empty hop
  (`hopMatchingNothingYieldsEmptyResult`), ranged depth bounds
  (`rangedHopFollowedByFixedHopHonoursLengths`), deduplication (`resultHoldsEachNodeOnce`), walk
  semantics (`hopMayTraverseEdgePreviousHopTraversed`), cycle termination
  (`cyclicGraphTerminatesAndHonoursExactLengthsWithWalkSemantics`), direction coverage
  (`eachDirectionSelectsItsOwnEdgesAndOneHopAgrees`), streaming parity and buffer ownership
  (`streamPathJsonReturnsSameIdSetInCallerOwnedBuffer`).
  Tenant scoping: `crossTenantIsolationPreventsReachingOutsideScope` (a dynamic test per scopable
  strategy) and `unscopableStorageContextRefusesTraversals` (a dynamic test per unscopable strategy, all
  four traversal methods). Against PostgreSQL both run; against Neo4j no strategy is scopable, so the first
  yields no tests and only the refusal cases run.
  Bound in Community by `CommunityGraphSessionPostgresContractTckIT` (live PostgreSQL) and
  `CommunityGraphSessionNeo4jContractTckIT` (live Neo4j).
  `CommunityGraphSessionGuardTest` covers the refusal and the unbound-engine case without a database.

The following two checks are described in earlier documentation but have no backing test or type in
this tree today — listed here as gaps, not coverage:

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

> **TCK bindings:** `ExecutionGraphZeroAllocTck`, `AbstractGraphSessionLiveContractTck` and
> `GraphChurnRatioTck` have Community-tier concrete bindings in `exeris-kernel-community/src/test/`.
> `CommunityExecutionGraphZeroAllocTckIT`, `CommunityGraphSessionPostgresContractTckIT`,
> `CommunityGraphSessionNeo4jContractTckIT` and `CommunityGraphChurnRatioTckIT` are
> `@Tag("integration")` and run in the `persistence-rls-gate` CI job (live PostgreSQL / Neo4j via
> Testcontainers). `GraphCarrierPinningTck` remains unbound.

---

## Summary

The Graph subsystem acts as the semantic bridge between raw relational data (L1) and graph-native reasoning (L2).
By enforcing the No-Arena Policy, it extends the L0 Memory Contract to L2 — every off-heap byte consumed by a
graph driver is visible to `GlobalMemoryArbiter`, auditable via JFR, and subject to backpressure. The unified
MATCH DSL decouples business intent from backend implementation, enabling seamless migration from high-allocation
Community drivers without changing a single line of business code.

---

## Owning ADRs

[ADR-102](../adr/ADR-102-graph-spi-anchor-and-multi-hop-traversal.md) anchors this subsystem and decides heterogeneous multi-hop traversal
(`GraphPathSpec`, `traversePath`, `streamPathJson`), the refusal of the two-argument `findShortestPath`,
and tenant scoping for graph access; it was written from
[RFC-2026-10-07](../rfc/RFC-2026-10-07-graph-multi-hop.md). Its obligations landed in 0.13 and this
document describes them as shipped. The other registry entry naming the kernel
graph SPI is [ADR-030](../adr/ADR-030.link.md), owned by `exeris-spring-runtime`, which decides the
Spring-side seam *onto* this SPI rather than the SPI itself.

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.graph.*`) is classified **preview** in the
[SPI Stability Matrix](../stability-matrix.md): baseline hardening is still pending. See the matrix
for the semver policy and TCK coverage status.
