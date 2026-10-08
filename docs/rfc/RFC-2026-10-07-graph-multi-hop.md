---
title: "RFC-2026-10-07: How should a graph session express a heterogeneous multi-hop traversal?"
type: rfc
visibility: public
owning-repo: exeris-kernel
status: active
---

# RFC-2026-10-07: How should a graph session express a heterogeneous multi-hop traversal?

|                    |                                                                          |
|:-------------------|:-------------------------------------------------------------------------|
| **Status**         | **ACCEPTED** — Option A; decision recorded in ADR-102, to be written     |
| **Author(s)**      | Arkadiusz Przychocki                                                     |
| **Date Opened**    | 2026-10-07                                                               |
| **Date Closed**    | 2026-10-08                                                               |
| **Target ADR(s)**  | ADR-102 (graph subsystem anchor; records the shape chosen here)          |
| **Affected Repos** | `exeris-kernel` (authoritative); `exeris-spring-runtime` (exposes `GraphSession` to Spring beans through the [ADR-030](../adr/ADR-030.link.md) seam) |
| **Reviewers**      | —                                                                        |

## Question

A graph session can follow exactly one relationship type per request. **Should the kernel add a
primitive that follows an ordered sequence of different relationship types in one request — and if
so, as a new path specification, as a widened `GraphTraversal`, or not at all?**

The RFC also settles the one existing entry point that has no relationship type at all,
`GraphSession#findShortestPath(UUID, UUID)` (#466), because the answer to "what does a request that
names no relationship type mean" has to be the same for traversal and for shortest path.

## Context

The roadmap entry *Graph: Heterogeneous Multi-Hop Traversal* places this primitive in 1.0, asks for an
RFC before an ADR, and names three options and a merge gate. The graph SPI stays **preview** at 1.0;
ADR-102 anchors the subsystem without promoting it, so the shape chosen here can still be revised in
1.x before promotion. The implementation is planned for 0.13.

Recommendation traversal is the use case that exposes the gap:
`User -[PURCHASED]-> Product -[SIMILAR_TO]-> Product` is two relationship types in a fixed order. Today
a caller issues hop one, materialises the intermediate node set on the heap, and issues hop two once per
intermediate node. The roadmap entry argues three costs of that composition: a round trip per hop plus
an N+1 fan-out on the second, an intermediate result set on the heap, and second-hop tenant scoping left
to the caller. The first two are examined in §Investigation; the third is weaker than stated and is
examined in §Dissent.

Leaving the question open has a concrete cost beyond the API: the standalone graph scenario in the
benchmark track is blocked because client-side hop composition is what it would measure.

## Investigation

### What the SPI and the Community binding do today

Every statement in this section is a reading of `development/0.13.0`.

- **One edge descriptor per request.** `GraphTraversal` is a record of `startNodeId`,
  `edgeDescriptor` (a single `GraphEdgeDescriptor`), `maxDepth`, `includeStartNode`,
  `includePayload`. Every `GraphSession` entry point that consumes a traversal takes that shape:
  `traverseBreadthFirst(GraphTraversal)`, `streamBfsJson(GraphTraversal)`,
  `findShortestPath(GraphEdgeDescriptor, UUID, UUID)`. No method accepts a sequence of descriptors.
- **`GraphDialect.buildMultiHopQuery` is homogeneous.** Its signature is
  `buildMultiHopQuery(GraphEdgeDescriptor edge, int minHops, int maxHops)`: "multi-hop" means
  *repetitions of one relationship type*. Both Community backends call it — `CommunityGraphSqlHelper`
  for `maxDepth > 1` (a `WITH RECURSIVE` CTE over one edge table), `CommunityGraphCypherReader` for
  every BFS (a bounded variable-length `MATCH` over one relationship type). Core's
  `MatchDslTranspiler` also delegates to it, but only tests call the transpiler. The dialect has no
  method that builds a query over two descriptors.
- **The SQL dialect is not SQL/PGQ at the query level.** Both the single-hop and the multi-hop SQL
  text are plain `SELECT`/`WITH RECURSIVE` over a per-type edge table; no generated query uses
  `GRAPH_TABLE`.
- **Direction is ignored by every traversal query.** `GraphEdgeDescriptor` carries `direction` and
  `bidirectional`. `CommunityGraphSqlHelper.loadAdjacency` adds the reverse edge for `bidirectional`
  or `BOTH` when it builds adjacency for Dijkstra, and treats `INCOMING` as outgoing. The traversal
  queries read neither: the SQL forms join `source_id → target_id` only, and the Cypher forms
  hard-code `->`.
- **Two `GraphTraversal` flags are read by nothing.** No main-source file outside `GraphTraversal`
  itself reads `includeStartNode` or `includePayload`.
- **The two-argument `findShortestPath` never searches (#466).** `CommunityGraphSession`'s
  `findShortestPath(UUID, UUID)` returns a zero-hop path when `source.equals(target)` and
  `PathResult.notFound(...)` for every other pair, with no adjacency lookup. The SPI makes this
  worse for any binding that does not override the three-argument form: the SPI default
  `findShortestPath(GraphEdgeDescriptor, UUID, UUID)` checks its arguments and then **delegates to
  the two-argument overload**, discarding the descriptor. Community overrides the three-argument form
  (it loads adjacency for the descriptor into a fresh `CommunityPathFinder` and runs Dijkstra), so the
  defect on Community is confined to the two-argument entry point; a binding relying on the default
  inherits it on both.
- **The two-argument overload is the hot path two TCKs measure.** `ExecutionGraphZeroAllocTck`
  (bound by `CommunityExecutionGraphZeroAllocTckTest`, main build lane) and `GraphCarrierPinningTck`
  (unbound) run `openSession() → findShortestPath(src, tgt) → close()` with two random ids. On
  Community that measures a path that performs no search.
- **Graph tenant scoping is connection-borne on PostgreSQL and absent on Neo4j.**
  `CommunityGraphSession` acquires the SQL backend's connection through
  `PersistenceEngine.openConnection(KernelProviders.storageContextOrSystem())`, so the connection
  interceptors for the ambient `StorageContext` run on it. The edge-table DDL the dialect generates has
  no `tenant_id` column (`CommunityGraphDialectTest#tenantIdNotPresent` asserts exactly that), so of the
  three isolation strategies `ConnectionInterceptor` documents, `SEPARATED_SCHEMA` (`search_path`) and
  `DEDICATED` (pool routing) can scope a graph query and `SHARED` (an RLS variable) has no column on a
  generated edge table to compare against. The Cypher backend opens every driver session against one
  database fixed from configuration (`CommunityNeo4jClient`, `neo4j.database`); nothing in the
  Community graph package outside the SQL connection path reads `StorageContext`.
- **No graph TCK touches tenant isolation.** No class in `eu.exeris.kernel.tck.contract.graph`
  references a tenant, an isolation key or `StorageContext`.
- **The session contract TCK has three cases.** `AbstractGraphSessionContractTck` (the roadmap's merge
  gate calls it `AbstractGraphSessionTck`; no class of that name exists) pins
  `sameNodeShortestPathReturnsFoundSingleNodePath`, `unknownDistinctNodesShortestPathReturnsNotFound`
  and `closeIsIdempotent`. Both shortest-path cases pass against an implementation that never
  searches. No graph TCK calls `streamBfsJson`.

### Prior art

- **Cypher** expresses a heterogeneous path as a pattern with one relationship type per segment and an
  optional per-segment length: `(u)-[:PURCHASED]->(p)-[:SIMILAR_TO*1..2]->(q)`. Per-segment
  quantifiers are the native shape; there is no global depth.
- **SQL/PGQ** (ISO/IEC 9075-16) and **GQL** (ISO/IEC 39075) quantify each element of a path pattern
  separately (`-[:SIMILAR_TO]->{1,2}`), again with no single depth for the whole path.
- **Gremlin** composes step by step (`out('PURCHASED').out('SIMILAR_TO')`); each step names its own
  label, and repetition is a per-step `repeat(...).times(n)`.

All three put the length bound on the segment, not on the path. That is the strongest available input
to the depth-versus-hop question below.

### Constraints

- **The Wall.** `spi.graph` stays implementation-blind: no type in a signature may name JDBC, Bolt or
  a query language. A path specification is a value; query text stays in `GraphDialect`.
- **`StorageContext` stays engine-side.** No option may make the caller pass a tenant, schema or
  isolation key per hop. Every hop of one request runs under the `StorageContext` bound when the
  request is made.
- **No-Waste-Compute.** `streamBfsJson` exists so a large result reaches the caller in one off-heap
  `LoanedBuffer`. A multi-hop primitive that only offers `List<UUID>` would reintroduce the heap
  materialisation the streaming method avoids.
- **Valhalla readiness.** New model types are records with no identity operations, like
  `GraphTraversal` and `GraphEdgeDescriptor`.
- **Preview semantics.** `spi.graph` is preview in `docs/stability-matrix.md`; additions and removals
  are permitted before promotion, so none of the options below is constrained by binary compatibility.
  `exeris-spring-runtime`'s graph module calls `traverseBreadthFirst` and no other traversal or
  shortest-path method.

### Data gathered: the allocation argument

The roadmap entry states that composing a two-hop query client-side over 500 intermediate nodes costs
"roughly forty times the allocation for the same answer". **That figure is derived, not measured.** Its
inputs and their sources:

| Input | Value | Source | Status |
|:---|---:|:---|:---|
| One 1-hop traversal returning one id, Neo4j Bolt | ~11.7 KB | `graph.md` §2 | recorded figure; no tracked test reproduces it (the churn TCK's `traversalFanOut()` is 500) |
| One traversal returning 500 ids, fast regime | ~142 000 B | `GraphChurnRatioTck` class Javadoc, `graph.md` §2 | measured on Neo4j Bolt, three JVMs |
| Same, slow regime | ~166 000 B | same | measured on Neo4j Bolt |

The derivation is `500 × 11.7 KB ≈ 5.85 MB` for the second-hop fan-out, against one 500-id traversal:
5.85 MB / 142 KB ≈ **41x** in the fast regime, 5.85 MB / 166 KB ≈ **35x** in the slow one. Four things
the arithmetic does not account for:

1. **The engine-side number is a stand-in.** No heterogeneous two-hop query exists to measure, so the
   denominator is a *single-type* 500-id traversal. A two-hop query that returns 500 ids may allocate a
   different amount; a two-hop query that returns more (fan-out multiplies) certainly will.
2. **The client-side first hop is omitted.** Hop one is itself a 500-id traversal (~142 KB), which the
   client-side path also pays.
3. **Second-hop calls return k ids, not one.** In a real recommendation fan-out each second-hop call
   returns its neighbours; the 11.7 KB unit is a one-id call.
4. **One backend only.** Every input is from the Neo4j Bolt path. No test in this tree runs the SQL
   dialect against a live PostgreSQL instance, so the PostgreSQL ratio is unknown.

What does not depend on the derivation is the round-trip count: client-side composition issues
`1 + |frontier|` requests where an engine-side query issues one. The recommendation below rests on that
structural difference; the factor decides how loudly the cost is published, not whether the primitive
is worth having.

#### Measurement that would confirm or replace the factor

Defined here, not run. It is a precondition for publishing any factor in `graph.md` or the ROADMAP.

- **Workloads.** W1, client composition: one single-type traversal from a start node returning
  N = 500 ids over `PURCHASED`, then one single-type traversal over `SIMILAR_TO` per returned id, each
  returning k ids (k ∈ {1, 5}). W2, engine-side: before the primitive lands, one single-type 500-id
  traversal (the proxy the derivation uses, labelled as a proxy); after it lands, the same two-hop
  shape as one `traversePath` request, and again through the streaming variant.
- **Numerator.** Per-thread allocated bytes across the measured requests, the `GraphChurnRatioTck`
  method (a byte count, not a sum of sampled JFR allocation events), or JMH `-prof gc` `norm.alloc`.
- **Independent samples are processes.** At least five fresh JVMs per workload and backend. Repetitions
  inside one JVM are one sample: the churn measurement already shows a regime chosen once per process
  and held for its life. A JMH run must therefore set forks ≥ 5; `AbstractExerisBenchmark` declares
  `@Fork(1)`, which yields one sample per run.
- **Warm-up.** At least 300 requests discarded per process, sized from the churn TCK's convergence
  curve.
- **Backends.** Neo4j Bolt and PostgreSQL, each in a container, the same seeded graph.
- **Report.** Per process: bytes per answered query for W1 and W2 and their ratio; across processes:
  the minimum and maximum ratio and the regime each process landed in. The published figure is the
  observed range, not a mean.

## Options Considered

All three options share the #466 ruling in [§The two-argument shortest path](#the-two-argument-shortest-path-466),
which is independent of the traversal shape.

### Option A: an ordered `GraphPathSpec` consumed by `traversePath`

A new value type names the path; new session methods run it as one engine-side request.

```java
// eu.exeris.kernel.spi.graph.model
public record GraphHop(GraphEdgeDescriptor edge, int minRepeat, int maxRepeat) {
    // 1 <= minRepeat <= maxRepeat; GraphHop.of(edge) is exactly one repetition
}

public record GraphPathSpec(UUID startNodeId, List<GraphHop> hops) {
    // hops non-empty, copied with List.copyOf
}

// eu.exeris.kernel.spi.graph.GraphSession
List<UUID>   traversePath(GraphPathSpec path);
LoanedBuffer streamPathJson(GraphPathSpec path);   // caller-owned, as streamBfsJson
```

`GraphDialect` gains `buildPathQuery(List<GraphHop> hops)`; `buildMultiHopQuery` remains the
single-hop special case.

**Depth versus hop count.** The spec has no global depth. Each hop carries its own repetition range, so
the path length lies in `[Σ minRepeat, Σ maxRepeat]` and both bounds are explicit in the request. The
roadmap's example — a five-hop path with `maxDepth = 3` — cannot be stated, which removes the ambiguity
instead of resolving it. An existing `GraphTraversal(start, edge, maxDepth)` is exactly
`GraphPathSpec(start, List.of(new GraphHop(edge, 1, maxDepth)))`, and the result semantics below make
the two agree, so `traverseBreadthFirst` can be specified as that special case.

**Result semantics (ruled R3, 2026-10-08).** The result is the deduplicated set of node ids reached at the end of
the last hop — the terminal frontier — not the union of intermediate nodes. For a single hop with
range `[1, d]` that is every node reachable in 1..d steps, which is what `traverseBreadthFirst` returns
today. A hop that matches nothing yields an empty result, not an error. Ordering is unspecified, as it
is for BFS today.

**Direction.** `traversePath` honours each hop's `GraphEdgeDescriptor.direction` and `bidirectional`.
Without it the commonest recommendation shape —
`User -[PURCHASED]-> Product <-[PURCHASED]- User` — is not expressible. This diverges from
`traverseBreadthFirst`, which ignores direction today; see ruling R4.

**`StorageContext`.** The whole path is one statement on one connection (PostgreSQL) or one query in
one driver session (Neo4j), so every hop runs under the scope bound for the request by construction.
Nothing in the spec can name a scope.

**PostgreSQL implementability.** One statement: a chain of CTEs, one per hop. A hop with
`maxRepeat = 1` is a join against its edge table seeded from the previous frontier; a ranged hop is a
`WITH RECURSIVE` over its edge table seeded the same way, as `buildMultiHopQuery` does for one table
today. `SELECT DISTINCT` between hops keeps the frontier a set. Each table name passes the existing
identifier check.

**Neo4j implementability.** Native: one `MATCH` with one segment per hop,
`(s {id: $sourceId})-[:A]->()-[:B*1..2]->(t) RETURN DISTINCT t.id`, with the arrow per hop taken from
the descriptor's direction. Each relationship type passes the existing Cypher identifier check.

**Streaming.** `streamPathJson` follows `streamBfsJson`: PostgreSQL pushes the aggregation into the
query (`json_agg`) and copies the JSON text into one network buffer; the Cypher backend encodes the id
list in Java into one buffer. The intermediate frontier never reaches the JVM heap on either backend.

**Allocation cost.** One round trip regardless of frontier size; the heap holds the terminal result
only. Expected to sit near a single traversal returning the same number of ids — expected, not
measured, see the measurement above.

**Pros:** matches the per-segment shape of Cypher, SQL/PGQ, GQL and Gremlin; inspectable value type
the dialect can translate without executing caller code; the existing BFS is a special case, so one
contract covers both; the depth question disappears.
**Cons:** two new session methods and a dialect method; two Community query generators to write and a
cross-backend parity question (`CommunityGraphBackendParityIT` today checks only that SQL text is
non-blank).
**Cost:** SPI additions in a preview package, two dialect implementations, the TCK cases in §Testing.

### Option B: a set-valued edge descriptor on `GraphTraversal` with a per-hop predicate

`GraphTraversal.edgeDescriptor` becomes `Set<GraphEdgeDescriptor> edges`, plus a per-hop selector,
for example `IntFunction<Set<GraphEdgeDescriptor>> hopEdges` mapping depth to the types allowed at
that depth. The existing record keeps a bridge constructor taking one descriptor.

**Depth versus hop count.** `maxDepth` stays global and the predicate is asked per depth `1..maxDepth`.
A five-hop intent with `maxDepth = 3` silently stops at three; whether that is an error or a
truncation has to be stated separately.

**`StorageContext`.** Same as A — one request, engine-side.

**PostgreSQL and Neo4j implementability.** A predicate is caller code; neither backend can push it
down. The engine has to evaluate it for every depth up to `maxDepth` at query-build time and translate
the resulting per-depth sets into the same per-hop query Option A builds — so B reaches A's query shape
through a less inspectable input. Without the predicate, a bare set means "any of these types at any
depth" (Cypher `[:A|B*1..3]`), which is a different and weaker query: unordered, so
`PURCHASED` then `SIMILAR_TO` cannot be distinguished from the reverse.

**Streaming.** `streamBfsJson(GraphTraversal)` covers it with no new method.

**Allocation cost.** Same as A when pushed down, plus one predicate evaluation per depth at build time.

**Pros:** no new session method; existing callers unchanged through the bridge constructor.
**Cons:** a lambda in a Valhalla-ready value record (identity-bearing, not comparable, not loggable);
ordering is encoded in caller code the engine must execute to learn; two semantics (set without
predicate, set with predicate) behind one type; the depth ambiguity the roadmap asks to define stays.
**Cost:** comparable to A in dialect work, higher in contract text.

### Option C: decline the primitive and document client-side composition

No SPI change. `graph.md` documents the composition pattern: traverse hop one, then issue hop two per
returned id inside the same `StorageContext` scope, and states the cost.

**Depth versus hop count.** Each call keeps today's single-type `maxDepth`; the caller owns the
composition.

**`StorageContext`.** Each call scopes itself engine-side as today. A caller composing within one scope
gets the same isolation per call; a caller that moves the second hop to another thread loses it unless
the binding is inherited, which `ScopedValue` does for structured forks and does not for an
arbitrary executor.

**PostgreSQL and Neo4j implementability.** Nothing to implement.

**Streaming.** Not available across hops: the intermediate frontier must be a `List<UUID>` on the heap
to drive hop two, and the second hop yields one buffer per intermediate node.

**Allocation cost.** `1 + |frontier|` round trips; the derived ~35–41x above on Neo4j Bolt for a
500-node frontier, unknown on PostgreSQL.

**Pros:** smallest surface; keeps arbitrary path expressions — a query-language problem — out of the
kernel; no parity work.
**Cons:** the canonical graph use case costs O(frontier) round trips; streaming is unavailable exactly
where results are largest; the blocked benchmark scenario stays blocked; the roadmap's 1.0 disposition
is reversed.
**Cost:** a documentation change, and a published cost the kernel then has to defend.

### The two-argument shortest path (#466)

Independent of A/B/C. `findShortestPath(UUID, UUID)` names no relationship type, so it can either
search every registered type or refuse.

- **Search all relationship types.** Union the adjacency of every registered edge descriptor and run
  Dijkstra over it. This silently widens the question: a `FOLLOWS` path can answer a `REPORTS_TO`
  question, and the answer changes whenever another edge type is registered. It also loads the full
  adjacency of every edge table into the heap on each call.
- **Refuse.** The call fails with a stated exception, naming the overload to use instead. A caller
  learns at the first call that the request is underspecified, instead of receiving "not found" for a
  connected pair.

Refusal has two SPI consequences. The SPI default of the three-argument overload must stop delegating
to the two-argument one — it becomes abstract, so a binding cannot inherit a refusal for the call that
names its edge type. And the two TCKs that measure the two-argument path
(`ExecutionGraphZeroAllocTck`, `GraphCarrierPinningTck`) move to the three-argument overload over a
seeded connected pair, which turns a measurement of a no-op into a measurement of a search.

## Dissent

The strongest case against Option A, stated as its proponent would.

**The kernel is a runtime, not a query engine.** A path specification is the first step of a path
language. Once ordered hops exist, the next requests are predictable: per-hop property filters, node
label constraints, optional hops, alternation, returning paths rather than endpoints, aggregation over
the frontier. Each is reasonable alone; together they re-implement a subset of Cypher or GQL behind a
Java record, translated into two dialects, with a parity obligation between them that the current
integration test does not meet even for single-hop queries. Option C keeps that whole surface out of
the kernel, where it belongs to the backend's own query language.

**The cost argument is weaker than it reads.** The ~40x is a derivation over a one-id unit and a
single-type stand-in, from one backend; §Data gathered lists four ways it is off. The No-Waste-Compute
objection to client-side composition applies equally to `traverseBreadthFirst`, which already returns a
heap `List<UUID>` and is the only traversal method the Spring seam calls.

**The isolation argument is weaker than the roadmap states.** Client-side composition through the same
`GraphSession` does not leave second-hop scoping to the caller: every call scopes itself engine-side
from the ambient `StorageContext`. The isolation gap this RFC finds — `SHARED` on PostgreSQL, every
strategy on Neo4j — affects one-hop traversal today exactly as it would affect a multi-hop primitive. A
new primitive does not close it; only R2 below does.

**Response.** The dissent is correct about the trajectory and about isolation, and the recommendation
takes both on board instead of answering them with the cost figure. Option A is recommended with a
closed scope — ordered hops, per-hop repetition range, direction, terminal-frontier result — and every
extension listed above is out of scope for ADR-102 and would need its own RFC. The deciding argument is
structural rather than the factor: `1 + |frontier|` round trips against one, and a streaming method
that cannot exist across hops under Option C. The isolation gap is ruled separately (R2), because it
exists with or without this primitive.

## Testing

Pinned in `AbstractGraphSessionContractTck` (or a sibling abstract TCK in the same package), bound for
both Community backends — PostgreSQL and Neo4j, each in a container — and green on both before the
primitive merges. Each case is made red first against a mutation named with it.

| Case | Asserts | Made red by |
|:---|:---|:---|
| Heterogeneous two-hop | `PURCHASED` then `SIMILAR_TO` from a seeded user returns exactly the expected product set, and not the set the reversed order returns | a query that applies the first hop's type to both hops |
| Empty hop | a second hop over a type with no rows returns an empty result, no exception | an implementation that throws on an empty frontier |
| Depth interaction | a ranged hop `[1, 2]` followed by a fixed hop returns the nodes at lengths 2 and 3 and none at length 4; the reverse direction of each check holds too | dropping the per-hop upper bound |
| Cross-tenant second hop | seed hop one in tenant T1 and an edge from the same intermediate id to a T2-only node in T2's scope; a traversal bound to T1 does not return the T2 node | opening hop two under the system context |
| Streaming | `streamPathJson` returns the same id set as `traversePath`, parsed from the buffer, and the caller closes the buffer | an implementation that streams only the first hop |
| Connected-pair shortest path | `findShortestPath(edge, a, b)` over a seeded connected pair returns `found()` with the expected hop count and cost, through a `GraphSession` | an implementation that returns `notFound` for distinct ids |
| Two-argument refusal | `findShortestPath(a, b)` for distinct ids fails with the exception ruled in R1, and does not return `notFound` | the current Community body |

The cross-tenant case runs once per isolation strategy a binding declares it supports (R2). The
`findShortestPath` connected-pair case is the one #466 asks for: the current two cases cannot tell
"searched and found nothing" from "never searched".

What cannot be tested before implementation: the allocation ratio of W2 in its real form; the
measurement defined above runs once `traversePath` exists, and only its proxy form runs before.

## Recommendation

**Adopt Option A — an ordered `GraphPathSpec` of `GraphHop`s with per-hop repetition ranges, consumed
by `traversePath` and `streamPathJson` — with a closed scope, and make the two-argument
`findShortestPath` refuse.**

Option A is the only option whose depth semantics are defined by construction, whose input the engine
can translate without executing caller code, and whose streaming variant keeps the intermediate
frontier off the heap on both backends. It matches the per-segment shape every surveyed graph query
language uses, so both dialects translate it natively. The existing BFS becomes its single-hop case,
which gives one contract and one TCK for both.

For #466, refusal is recommended because searching every relationship type silently widens the
question being answered, and because a refusal is observable where the current `notFound` is not.

The decision is recorded in ADR-102, which also anchors the graph subsystem as preview at 1.0.

### Why not the alternatives?

- **Option B** — reaches Option A's query shape through a lambda the engine must execute, inside a
  value record, and keeps the global-depth ambiguity.
- **Option C** — leaves the canonical use case at `1 + |frontier|` round trips with no streaming
  variant, and keeps the benchmark scenario blocked.
- **Search all types for #466** — answers a question the caller did not ask, and changes the answer
  when an unrelated edge type is registered.

### Rulings (2026-10-08)

The owner ruled that each point takes the option recommended below. A point whose recommendation
covers only part of it had its remainder ruled by the owner on the same date.

- **R1 — the refusal's exception.** Options: `GraphQueryException` `EX-GRPH-5002` with
  `queryType = "SHORTEST_PATH"` and a detail naming the three-argument overload (existing code, but the
  code's meaning is "query execution failure", not "caller error"); a new `EX-GRPH-5006` (an accurate
  code, plus a `KernelErrorCodes` entry and a `graph.md` row); `UnsupportedOperationException` (no
  Exeris code). **Ruled:** `EX-GRPH-5002`, because it adds no code to a preview surface and keeps the
  graph exception family single. The overload is not removed outright in 0.13, which preview permits;
  it is kept refusing and deprecated for removal before promotion, so a caller gets a named error
  instead of a missing method.
- **R2 — isolation for tenant-scoped graph access.** The cross-tenant case cannot pass on Neo4j, which
  has no per-scope routing, nor on PostgreSQL under `SHARED`, whose generated edge tables have no
  column for an RLS policy. Options: the graph engine refuses a traversal under a non-system
  `StorageContext` whose strategy the binding cannot scope (fail-closed); route by scope (Neo4j
  database per `DEDICATED` tenant, a tenant column under `SHARED`); or document the strategies the graph
  subsystem supports and leave the others unscoped. **Ruled:** refuse what cannot be scoped, declare
  `SEPARATED_SCHEMA` and `DEDICATED` on PostgreSQL as supported, and run the cross-tenant case per
  declared strategy. This rule applies to the existing one-hop methods as well, which is why it is a
  separate ruling.
- **R3 — result semantics.** **Ruled:** terminal frontier, not the union of every node visited
  on the path. Terminal frontier is what a recommendation query asks for and what makes
  `traverseBreadthFirst` a special case.
- **R4 — direction in `traverseBreadthFirst`.** **Ruled:** `traversePath` honours direction. **Ruled (2026-10-08):**
  `traverseBreadthFirst` is brought into line in the same release, honouring the descriptor's
  direction — a behaviour change for any caller relying on a descriptor whose direction is not
  `OUTGOING`, recorded in the release notes.
- **R5 — the two unread `GraphTraversal` flags.** **Ruled:** `GraphPathSpec` omits `includeStartNode` and
  `includePayload`, since no backend reads them. **Ruled (2026-10-08):** on `GraphTraversal` the two
  flags are deprecated, with javadoc stating they have no effect; they are not implemented.

### Risks of the recommendation

- **Scope creep toward a query language** — the dissent's trajectory. Mitigation: ADR-102 lists the
  out-of-scope extensions explicitly.
- **Backend divergence.** Two query generators can disagree on duplicates, direction and empty hops.
  Mitigation: every TCK case runs against both backends in containers, not only against generated text.
- **Frontier explosion.** A ranged hop on a dense graph multiplies the frontier; `Σ maxRepeat` bounds
  path length, not result size, and the kernel has no node-count cap today (`graph.md`, *Cycle
  Detection*). The caller chooses every bound explicitly, as with `maxDepth` now.
- **The published cost may move.** If the measurement lands well below the derived factor, the
  roadmap's 1.0 argument rests on the structural round-trip count alone, which still holds.

## Decision Record

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | Option A: an ordered `GraphPathSpec` consumed by `traversePath` and `streamPathJson`; the two-argument `findShortestPath` refuses (R1). Rulings R1 to R5 as recorded above. |
| **Date**             | 2026-10-08 |
| **Resulting ADR(s)** | ADR-102 (to be written; the decision is recorded there) |
| **Notes**            | R4 also brings `traverseBreadthFirst` into line on direction; R5 deprecates the two `GraphTraversal` flags. |

## Open questions / follow-ups

- **Heterogeneous shortest path.** `findShortestPath` over a `GraphPathSpec` is not part of this RFC;
  Dijkstra over a per-hop adjacency is a different algorithmic question. Owner: graph subsystem, after
  ADR-102.
- **Node-count cap.** Whether `GraphPathSpec` or the engine gains a visited-node or result-size cap, and
  whether it is configuration or a request field. Owner: graph subsystem.
- **SQL dialect live coverage.** No test runs the SQL dialect against a live PostgreSQL instance;
  §Testing requires one for the new cases, and the existing single-hop methods would benefit from the
  same fixture. Owner: graph testkit fixture work in 0.13.
