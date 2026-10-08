---
title: "ADR-102: The graph SPI has an anchor, and traverses more than one edge type"
type: adr
visibility: public
owning-repo: exeris-kernel
status: active
slug: adr/ADR-102
---

# ADR-102: The graph SPI has an anchor, and traverses more than one edge type

| Attribute       | Value                                                                                     |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (2026-10-08)                                                                 |
| **Deciders**    | Arkadiusz Przychocki                                                                      |
| **Date**        | 2026-10-08                                                                                |
| **Scope**       | `kernel/graph`                                                                            |
| **Owning Repo** | `exeris-kernel`                                                                           |
| **Driven By**   | [RFC-2026-10-07](../rfc/RFC-2026-10-07-graph-multi-hop.md) (Option A and rulings R1 to R5); [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) (`…spi.graph` has no anchor ADR); issue #466; ROADMAP §"Graph: Heterogeneous Multi-Hop Traversal" |
| **Compliance**  | [docs/subsystems/graph.md](../subsystems/graph.md), [docs/stability-matrix.md](../stability-matrix.md) |

## Context and Problem Statement

`…spi.graph` has been `preview` since 0.5.0. The stability matrix defines `stable` as an accepted ADR
**and** an executable TCK; the graph row has the TCKs and carries `—` in its anchor-ADR column. The only
registry entry that names the graph SPI is [ADR-030](ADR-030.link.md), which decides the Spring-side seam
onto the SPI and not the SPI itself. No ADR states what `GraphSession`, `GraphEngine`, `GraphProvider`,
`GraphTraversal` and `GraphDialect` promise as a whole.

[ADR-100](ADR-100-spi-surfaces-stable-at-1-0.md) keeps graph `preview` at 1.0 and names this ADR as the
precondition for its promotion in a 1.x minor. Graph **stays `preview`**: this ADR anchors the
subsystem and decides one contract widening; it does not promote anything, and the shape recorded here
can still be revised before promotion.

Two questions sat on the same classes and had to be answered together, because the answer to "what does
a request that names no relationship type mean" must be the same for traversal and for shortest path:

- **Heterogeneous multi-hop traversal.** A session follows exactly one relationship type per request.
  `User -[PURCHASED]-> Product -[SIMILAR_TO]-> Product` cannot be one request; the caller materialises
  the intermediate node set on the heap and issues the second hop once per node. The ROADMAP places the
  primitive in 1.0 and asks for an RFC first.
- **`findShortestPath(UUID, UUID)` (#466).** The overload names no relationship type and, on the
  Community binding, performs no search.

RFC-2026-10-07 compared an ordered path specification (Option A), a set-valued edge descriptor with a
per-hop predicate (Option B) and declining in favour of client-side composition (Option C). It was
accepted on 2026-10-08 with Option A and five rulings, R1 to R5. This ADR records that decision as
binding obligations.

## What the SPI promises today (measured)

Measured on `development/0.13.0` at `161c534a`. This section is the contract being anchored; the
decision below changes it only where it says so.

**Types.** `GraphProvider` (`ServiceLoader` entry, `priority()` selects the highest) creates a
`GraphEngine` through `createEngine(GraphConfig)`. `GraphEngine` is the facade: `openSession()`,
`dialect()`, node and edge metadata registration and listing (`registerNodes`, `registerEdges`,
`registeredNodes`, `registeredEdges`), `engineName()`, `isRunning()`, `close()`. The engine is bound once
at bootstrap to `GraphKernelProviders.GRAPH_ENGINE` (and the provider to `GRAPH_PROVIDER`), read through
`GraphKernelProviders.graphEngine()`; the slots live in `spi.graph` so that the generic `KernelProviders`
names no preview type (ADR-100).

**Session.** `GraphSession` is one unit of work, opened by `GraphEngine.openSession()`, confined to the
thread that opened it and closed by that thread. Its operations are:

- traversal: `traverseBreadthFirst(GraphTraversal)` returns a heap `List<UUID>`;
  `streamBfsJson(GraphTraversal)` returns a caller-owned `LoanedBuffer` of UTF-8 JSON that the session
  never closes once returned;
- edge and node writes: `createEdge`, `upsertEdge`, `deleteEdge`, `upsertNode`, `deleteNode`;
- `getRootNode()`;
- shortest path: `findShortestPath(UUID, UUID)` and `findShortestPath(GraphEdgeDescriptor, UUID, UUID)`;
- transaction control: `beginTransaction`, `commit`, `rollback`, and `close`, which is idempotent and
  releases only what the session itself retained.

A query failure is a `GraphQueryException` (`EX-GRPH-5002`, `rawArgs` = `queryType`, `detail`).

**Request model.** `GraphTraversal` is a record of `startNodeId`, one `GraphEdgeDescriptor`, `maxDepth`
(at least 1), `includeStartNode` and `includePayload`. `GraphEdgeDescriptor` carries `sourceNode`,
`edgeType`, `targetNode`, `weight`, `bidirectional`, `direction` (`OUTGOING`, `INCOMING`, `BOTH`) and
`tableName`. Both are records free of identity operations, as Valhalla readiness requires.

**Dialect.** `GraphDialect` produces opaque query strings and DDL: `buildMatchQuery(edge)` for one hop,
`buildMultiHopQuery(edge, minHops, maxHops)` for repetitions of **one** relationship type,
`buildShortestPathQuery`, `buildCreatePropertyGraph`, `buildCreateEdgeTable`, `buildDropPropertyGraph`
and `dialectName()`. No method builds a query over two edge descriptors. Callers never see which query
language the text is in; the Wall keeps JDBC, Bolt and query-language types out of every signature.

**Behaviour that diverges from what the types suggest.**

- Every traversal query ignores the descriptor's `direction` and `bidirectional`. The SQL forms join
  `source_id` to `target_id` only and the Cypher forms hard-code `->`. Only the Dijkstra adjacency
  loader reads the two fields.
- Nothing outside `GraphTraversal` reads `includeStartNode` or `includePayload`.
- The two-argument `findShortestPath` on the Community session returns a one-node path when the ids are
  equal and `PathResult.notFound(...)` for every other pair, without a lookup. The SPI default of the
  three-argument overload discards its descriptor and delegates to the two-argument one, so a binding
  that does not override the three-argument form inherits the no-op on both. Community overrides the
  three-argument form and runs Dijkstra over the descriptor's adjacency.
- The SQL generator's edge tables carry no tenant column. Community acquires the SQL backend's
  connection through the persistence engine under the ambient `StorageContext`, so `SEPARATED_SCHEMA`
  (`search_path`) and `DEDICATED` (pool routing) scope a graph query, while `SHARED` (an RLS variable)
  has no column to compare against. The Cypher backend opens every driver session against one database
  fixed from configuration and reads no `StorageContext`.
- The session contract TCK (`AbstractGraphSessionContractTck`) has three cases: a same-node shortest
  path, an unknown-pair shortest path and idempotent close. The latter two pass against an
  implementation that never searches. No graph TCK touches a tenant, an isolation key or
  `StorageContext`, and none calls `streamBfsJson`.

## Decision

**The graph SPI is anchored as described in "What the SPI promises today", amended by the rulings
below. Graph stays `preview` at 1.0 (ADR-100). Heterogeneous multi-hop traversal is an ordered
`GraphPathSpec` of `GraphHop`s run by `traversePath` and `streamPathJson`, with a closed scope.**

Implementation is planned for 0.13. Until it lands, none of the behaviour below exists, and the
documentation does not describe it as shipped.

### 1. The path specification (Option A)

Two records are added in `eu.exeris.kernel.spi.graph.model`, and two methods to `GraphSession`:

```java
public record GraphHop(GraphEdgeDescriptor edge, int minRepeat, int maxRepeat) { }   // 1 <= minRepeat <= maxRepeat
public record GraphPathSpec(UUID startNodeId, List<GraphHop> hops) { }               // hops non-empty, List.copyOf

List<UUID>   traversePath(GraphPathSpec path);
LoanedBuffer streamPathJson(GraphPathSpec path);                                     // caller-owned
```

`GraphHop.of(edge)` is exactly one repetition. The spec has **no global depth**: each hop carries its
own repetition range, the path length lies in `[Σ minRepeat, Σ maxRepeat]`, and both bounds are explicit
in the request. A `GraphTraversal(start, edge, maxDepth)` is exactly
`GraphPathSpec(start, List.of(new GraphHop(edge, 1, maxDepth)))`. `GraphDialect` gains
`buildPathQuery(List<GraphHop> hops)`; `buildMultiHopQuery` remains the single-hop special case.

The scope is **closed**: ordered hops, a per-hop repetition range, direction, and a terminal-frontier
result. Per-hop property filters, node-label constraints, optional hops, alternation, returned paths and
aggregation over the frontier are out of scope and each needs its own RFC.

### 2. Result semantics (R3)

The result is the deduplicated set of node ids reached at the end of the last hop, the **terminal
frontier**, not the union of intermediate nodes. For a single hop with range `[1, d]` that is every node
reachable in 1 to `d` steps, which is what `traverseBreadthFirst` returns. A hop that matches nothing
yields an empty result, not an error. Ordering is unspecified.

### 3. Direction (R4)

`traversePath` honours each hop's `GraphEdgeDescriptor.direction` and `bidirectional`, so
`User -[PURCHASED]-> Product <-[PURCHASED]- User` is expressible. In the same release
`traverseBreadthFirst` and `streamBfsJson` honour the descriptor's direction too. That is a behaviour
change for a caller whose descriptor carries a direction other than `OUTGOING`, and the release notes
record it.

### 4. The two unread flags (R5)

`GraphPathSpec` omits `includeStartNode` and `includePayload`, since no backend reads them. On
`GraphTraversal` the two components are deprecated and their Javadoc states that they have no effect.
They are not implemented.

### 5. The two-argument shortest path (R1, #466)

`findShortestPath(UUID, UUID)` refuses with `GraphQueryException`, `EX-GRPH-5002`,
`queryType = "SHORTEST_PATH"` and a detail naming the three-argument overload. It does not search every
registered edge type, because that would silently widen the question and change the answer whenever an
unrelated edge type is registered. The overload is deprecated for removal before promotion and is kept
refusing, rather than removed, so a caller gets a named error instead of a missing method. The
three-argument overload becomes abstract: a binding can no longer inherit the refusal for the call that
names its edge type. No new error code is added.

### 6. Tenant scoping (R2)

Every hop of one request runs under the `StorageContext` bound when the request is made; nothing in the
spec can name a scope. The graph engine **refuses** a traversal under a non-system `StorageContext`
whose isolation strategy the binding cannot scope. The PostgreSQL binding declares `SEPARATED_SCHEMA` and
`DEDICATED` supported and refuses `SHARED`; the Neo4j binding, which has no per-scope routing, declares
no strategy supported and refuses any non-system context. The rule applies to the existing one-hop
methods as well as the new ones.

### Obligations

Each obligation is testable. All land in 0.13.

1. **SPI change, one commit.** `GraphHop`, `GraphPathSpec`, `GraphSession.traversePath`,
   `GraphSession.streamPathJson` and `GraphDialect.buildPathQuery` are added; the three-argument
   `findShortestPath` becomes abstract; the two-argument overload and the two `GraphTraversal` flags are
   `@Deprecated`. The `stability-matrix.md` note and the `stability-surfaces.conf` entry land in the same
   commit (ADR-065). Every type added stays in the preview package.
2. **Construction rules.** `GraphHop` and `GraphPathSpec` reject null components, a `minRepeat` below 1, a
   `maxRepeat` below `minRepeat` and an empty hop list; `hops` is copied immutably.
3. **Single request.** On both Community backends one `traversePath` call issues exactly one statement on
   one connection (PostgreSQL) or one query in one driver session (Neo4j). The intermediate frontier
   never reaches the JVM heap; only the terminal result does.
4. **PostgreSQL shape.** One statement, a chain of CTEs, one per hop. A hop with `maxRepeat = 1` is a
   join seeded from the previous frontier; a ranged hop is a `WITH RECURSIVE` over its edge table seeded
   the same way; `SELECT DISTINCT` between hops keeps the frontier a set. Every table name passes the
   existing identifier check.
5. **Neo4j shape.** One `MATCH` with one segment per hop and the arrow taken from the descriptor's
   direction; the result is `RETURN DISTINCT`. Every relationship type passes the existing Cypher
   identifier check.
6. **Streaming.** `streamPathJson` returns the same id set as `traversePath`, in one caller-owned
   `LoanedBuffer`. PostgreSQL aggregates in the query (`json_agg`) and copies the text into one network
   buffer; the Cypher backend encodes the id list into one buffer.
7. **Direction.** `traverseBreadthFirst`, `streamBfsJson` and `traversePath` honour `direction` and
   `bidirectional` on both backends, and the release notes name the behaviour change.
8. **Refusal.** `findShortestPath(UUID, UUID)` for distinct ids throws `GraphQueryException`
   (`EX-GRPH-5002`, `queryType = "SHORTEST_PATH"`, detail naming the three-argument overload) on
   Community and never returns `notFound`; no SPI default delegates the three-argument form to it.
9. **Scoping.** A traversal under a non-system `StorageContext` whose strategy the binding cannot scope
   is refused; the refusal names the strategy and the binding. `graph.md` lists the supported
   strategies per binding.
10. **Zero-allocation and pinning TCKs.** `ExecutionGraphZeroAllocTck` and `GraphCarrierPinningTck` move
    from the two-argument to the three-argument overload over a seeded connected pair, so they measure a
    search.
11. **Docs.** `graph.md` documents the path specification, direction, the terminal frontier, the
    refusal and the supported isolation strategies when, and only when, the code above lands. No
    allocation factor is published before the measurement below has run.

## Consequences

### ✅ Positive Outcomes

- **[+] `…spi.graph` has an anchor ADR**, which removes the one blocker ADR-100 names for promoting it
  in a 1.x minor.
- **[+] The canonical recommendation traversal is one request**: one round trip where client-side
  composition needs `1 + |frontier|`, and a streaming variant that keeps the intermediate frontier off
  the heap.
- **[+] The depth question disappears.** There is no global depth to reconcile with a hop count, and
  `traverseBreadthFirst` is the single-hop case of one contract and one TCK.
- **[+] A request that names no relationship type is observable** as a refusal instead of a `notFound`
  that cannot be told from "searched and found nothing".
- **[+] Graph tenant scoping becomes fail-closed** instead of silently unscoped on `SHARED` and on Neo4j.
- **[+] The standalone graph benchmark scenario can be measured** against an engine-side path.

### ⚠️ Trade-offs

- **[-] Two session methods and a dialect method on a preview surface**, and two query generators with a
  parity obligation between them.
- **[-] A behaviour change for single-hop callers**: direction is honoured, and a descriptor with a
  non-`OUTGOING` direction returns a different set than before.
- **[-] Neo4j rejects tenant-scoped traversal** until a routing mechanism exists; a deployment that uses
  the Cypher backend with non-system contexts cannot use graph until then.
- **[-] Two deprecated members remain** on a preview surface until they are removed before promotion.

### 📋 What is NOT in scope

- Heterogeneous shortest path, `findShortestPath` over a `GraphPathSpec`: Dijkstra over a per-hop
  adjacency is a different algorithmic question, owned by the graph subsystem after this ADR.
- A visited-node or result-size cap, whether configuration or a request field.
- Routing by scope on Neo4j (a database per `DEDICATED` tenant) and a tenant column under `SHARED`.
- Promoting `…spi.graph` (ADR-100: a 1.x minor).

### 🚫 Non-Goals

- A query language in the kernel: per-hop property filters, node-label constraints, optional hops,
  alternation, returned paths and frontier aggregation.
- A global depth on `GraphPathSpec`.
- Searching every registered edge type for the two-argument shortest path.
- Implementing `includeStartNode` or `includePayload`.
- A caller-supplied tenant, schema or isolation key on any graph method.

### ⚠️ Risks and Assumptions

- **Assumes:** the per-segment shape of Cypher, SQL/PGQ, GQL and Gremlin is what future graph query
  needs map onto, so a hop with its own repetition range does not have to change when a query language
  arrives. **Reversed by:** a requirement the per-hop record cannot carry without a global construct,
  which would be a new RFC while the surface is still `preview`.
- **Risk:** scope creep toward a query language. The closed scope above is the mitigation; each
  extension needs its own RFC.
- **Risk:** the two query generators disagree on duplicates, direction or empty hops. Every case in
  "Verification obligations" runs against both backends in containers, not against generated text.
- **Risk:** frontier explosion. `Σ maxRepeat` bounds path length, not result size, and the kernel has no
  node-count cap; the caller chooses every bound, as with `maxDepth` today.
- **Assumes:** a PostgreSQL container is available to the TCK lanes. **Reversed by:** a lane that
  cannot run one, in which case the PostgreSQL cases move to a separately tagged gate and the primitive
  does not merge until that gate is green.
- **Assumes:** the benefit rests on the round-trip count, which is structural. **Reversed by:** a
  measured allocation ratio far below the derived 35x to 41x does not reverse the decision, because
  the round-trip count and the missing streaming variant stand alone; it changes only what is
  published.
- **Assumes:** a caller relying on a non-`OUTGOING` direction on `traverseBreadthFirst` is rare, since
  the SPI never honoured it. **Reversed by:** a consumer that depends on the old result, which moves to
  an explicit `OUTGOING` descriptor.

## Verification obligations

The cases are pinned in `AbstractGraphSessionContractTck`, or a sibling abstract TCK in the same
package, bound for both Community backends (PostgreSQL and Neo4j, each in a container) and green on
both before the primitive merges. Each case is shown red first against the mutation named with it.

| Case | Asserts | Made red by |
|:---|:---|:---|
| Heterogeneous two-hop | `PURCHASED` then `SIMILAR_TO` from a seeded user returns exactly the expected product set, and not the set the reversed order returns | a query that applies the first hop's type to both hops |
| Empty hop | a second hop over a type with no rows returns an empty result, no exception | an implementation that throws on an empty frontier |
| Depth interaction | a ranged hop `[1, 2]` followed by a fixed hop returns the nodes at lengths 2 and 3 and none at length 4; the reverse direction of each check holds too | dropping the per-hop upper bound |
| Cross-tenant second hop | hop one is seeded in tenant T1, and an edge from the same intermediate id to a T2-only node exists in T2's scope; a traversal bound to T1 does not return the T2 node | opening hop two under the system context |
| Streaming | `streamPathJson` returns the same id set as `traversePath`, parsed from the buffer, and the caller closes the buffer | an implementation that streams only the first hop |
| Connected-pair shortest path | `findShortestPath(edge, a, b)` over a seeded connected pair returns `found()` with the expected hop count and cost, through a `GraphSession` | an implementation that returns `notFound` for distinct ids |
| Two-argument refusal | `findShortestPath(a, b)` for distinct ids fails with `EX-GRPH-5002` and does not return `notFound` | the current Community body |

Two further cases cover the rulings the RFC table does not name:

| Case | Asserts | Made red by |
|:---|:---|:---|
| Direction | `traversePath` over an `INCOMING` hop and over a `BOTH` hop returns the reverse and the union of the outgoing set; `traverseBreadthFirst` agrees with the single-hop `traversePath` for each direction | a query that reads only `source_id` to `target_id` |
| Unscopable strategy | a traversal under a non-system context whose strategy the binding does not support is refused, on the one-hop and the path methods alike | an engine that opens the connection under the system context |

The cross-tenant case runs once per isolation strategy a binding declares it supports. The connected-pair
case is the one #466 asks for: the current two cases cannot tell "searched and found nothing" from
"never searched". The cases are made red in both directions, so a check that merely ignores its input
does not pass: the direction case needs one case per direction, not only a positive one.

**Allocation figure, measurement definition.** No factor is published in `graph.md` or the ROADMAP
until this has run. The roadmap's figure of roughly forty times is a derivation over a single-type
stand-in on one backend, not a measurement.

- **Workloads.** W1, client composition: one single-type traversal from a start node returning N = 500
  ids over `PURCHASED`, then one single-type traversal over `SIMILAR_TO` per returned id, each returning
  k ids with k in {1, 5}. W2, engine-side: the same two-hop shape as one `traversePath` request, and
  again through `streamPathJson`.
- **Numerator.** Per-thread allocated bytes across the measured requests, by the `GraphChurnRatioTck`
  method (a byte count, not a sum of sampled JFR allocation events), or JMH `-prof gc` `norm.alloc`.
- **Independent samples are processes.** At least five fresh JVMs per workload and backend. Repetitions
  inside one JVM are one sample, because a regime is chosen once per process and held for its life. A
  JMH run therefore sets forks to at least 5; `AbstractExerisBenchmark` declares `@Fork(1)`, which yields
  one sample per run.
- **Warm-up.** At least 300 requests discarded per process.
- **Backends.** Neo4j Bolt and PostgreSQL, each in a container, over the same seeded graph.
- **Report.** Per process: bytes per answered query for W1 and W2 and their ratio. Across processes: the
  minimum and maximum ratio and the regime each process landed in. The published figure is the
  observed range, not a mean.

## Related

- [RFC-2026-10-07](../rfc/RFC-2026-10-07-graph-multi-hop.md) — the option comparison, the dissent and
  the rulings R1 to R5 this ADR records.
- [RFC-2026-09-02](../rfc/RFC-2026-09-02-preview-spi-promotion.md) — the promotion question this
  anchor unblocks.
- [ADR-100](ADR-100-spi-surfaces-stable-at-1-0.md) — graph stays `preview` at 1.0; this ADR is the
  precondition for its promotion.
- [ADR-101](ADR-101-events-spi-contract-anchor.md) — the events anchor, whose shape this ADR follows.
- [ADR-030](ADR-030.link.md) — the Spring-side seam onto `GraphSession`, owned by
  `exeris-spring-runtime`; it calls `traverseBreadthFirst` and no other traversal or shortest-path
  method.
- [ADR-065](ADR-065-spi-compatibility-gate.md) — the gate that reports the SPI change.
- [ADR-006](ADR-006.link.md) — the Wall; no query-language or driver type appears in a signature.
- [ADR-012](ADR-012-security-trust-model-upgrade-for-resource-server-validation-and-fail-closed-runtime.md)
  — `StorageContext` as the tenant-isolation carrier and the fail-closed reading Obligation 9 follows.
- [docs/subsystems/graph.md](../subsystems/graph.md) — the subsystem narrative.

## Engineering Protocol

- Obligation 1 lands as one commit: ADR-065's gate fails the build on an unclassified SPI change.
- Each case under "Verification obligations" is shown red before it is shown green, against the
  mutation named with it.
- The stability-matrix anchor column for `…spi.graph` names this ADR only once obligations 1 to 11 have
  landed; until then the row stays `preview` with `—`.
