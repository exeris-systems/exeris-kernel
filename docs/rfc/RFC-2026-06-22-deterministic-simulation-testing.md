# RFC-2026-06-22: Deterministic Simulation Testing — execution-ordering control over the kernel runtime

| Field             | Value                                                                 |
|:------------------|:----------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                             |
| **Author(s)**     | Arkadiusz Przychocki                                                  |
| **Date Opened**   | 2026-06-22                                                            |
| **Date Closed**   | —                                                                    |
| **Target ADR(s)** | TBD (reserve next free number in `exeris-docs/adr-index.md` on acceptance) |
| **Affected Repos**| `exeris-kernel`, `exeris-benchmarks` (sim harness host), `exeris-kernel-enterprise` (native execution backend, later) |
| **Reviewers**     | —                                                                    |

## Question

To make the kernel's **"deterministic runtime"** claim *demonstrable* — run the whole runtime under a controlled scheduler, inject a fault, and replay bit-for-bit from a seed — the harness needs control over execution ordering. The load-bearing question is narrow and gating:

> **Must the execution seam control the *total order* of runnable selection (which virtual thread resumes next, when a timer fires, when a completion is delivered), or is *placement* control plus deterministic IO sufficient?**

Everything else in a DST harness (seeded RNG, injectable clock, in-memory IO bindings, fault injection, replay) is determined work once this is resolved. Total-order-vs-placement sets how deep into the semi-internal Loom carrier surface the harness must reach, and therefore the cost and the strength of the determinism guarantee.

## Context

Two product claims are load-bearing: **"deterministic runtime"** and **"replaces application + orchestration layer."** Today "deterministic" means *local* predictable mechanisms — deterministic admission deny, net-counter admission with no wall-clock, LIFO resource unwind. It does **not** mean whole-runtime, seeded, fault-injecting, bit-for-bit-replayable simulation. That stronger property is what TigerBeetle (VOPR) and FoundationDB (Flow simulation) built their reputations on. **No JVM runtime has it** — Lincheck and JPF model-check concurrent *data structures*, not a live runtime with native IO. It is a moat-class capability.

Retrofitting determinism onto a mature runtime normally fails on one thing: there is no clean point to inject a scheduler without rewriting the world. **The kernel does not have that problem — both hard seams already exist and are validated:**

1. **IO seam (The Wall).** All IO is behind SPI (`TransportProvider`, `PersistenceEngine`, `EventEngine`). An in-memory simulation binding swaps in via one ServiceLoader choice — the part everyone else fights for is structurally free here.
2. **Execution seam (PAQS/stream).** The parked research branch `research/loom-continuation-locality` (v0.6) extracted the execution backend (milestone M1) and **proved it refactor-neutral on PAQS/stream** (M2). The branch closed **NO_GO on the locality hypothesis** — transport-affine continuation gave no payoff (shop-order-saga: throughput flat at ~194.6 req/s, CPU `+12.97%`, mean latency `4.897 → 6.084 ms`). That result is **orthogonal to this RFC**: it answered "does affine scheduling raise throughput?" (no). DST asks "does deterministic single-stepping give reproducibility?" — not a throughput question. The byproduct that survives the NO_GO is exactly the leg every other JVM runtime lacks: a **validated, refactor-neutral execution injection point.**

The cost of leaving this unanswered: the "deterministic" claim stays an adjective, not a property — undemonstrable, and indistinguishable from any vendor saying "robust." The cost/benefit of building DST has tilted sharply now that the seams (the architecturally risky part) are done; what remains is implementation work, not architectural risk. This RFC does **not** recommend building the whole harness now — it resolves the one decision that scopes it.

## Investigation

### What is deterministic *for free* under simulated IO

When the IO binding is an in-memory simulation, IO completions are produced by the simulation, not the OS. So the **points at which a virtual thread unmounts / parks become deterministic** — there is no real epoll/socket nondeterminism deciding when a continuation is ready. This covers a large class of the kernel's interleavings, because the runtime is IO-bound (sagas await persistence, handlers await transport, the outbox awaits broker ACK).

### What is *not* free

Three orderings remain that simulated IO alone does not pin:

- **Runnable selection order** — when several virtual threads are simultaneously runnable, which resumes first.
- **Timer firing order** — when a scheduled timeout (saga TTL, retry backoff) fires relative to other runnables.
- **Completion delivery order** — when a ready IO completion is dispatched relative to CPU-bound runnables.

Whether the harness can pin these depends on what the PAQS/stream execution seam actually controls. The locality work built it to test *placement* (which carrier a continuation runs on — transport-affine), via an "affine prototype." It is **not yet established** whether the same seam can also own *selection* (a deterministic single-stepping pick of the next runnable from a seeded order). This is the crux.

### Clock and RNG (determined, not open)

- **Clock:** the runtime reads time ad hoc today (95× `System.nanoTime`, 21× `Instant.now`, including in the SPI). The "Unified Injectable Clock Seam" roadmap item unifies this anyway; DST consumes it as the virtual time source. No separate decision — DST is a downstream consumer.
- **RNG:** any nondeterministic choice (jitter, backoff splay, hash-seed) must be threaded through a seeded RNG. This is discipline, not architecture.

### Prior art and the determinism ladder

- **FoundationDB / TigerBeetle:** total-order deterministic schedulers with seeded fault injection; the gold standard, but both are purpose-built runtimes (Flow / single-threaded state machine) — they own selection by construction.
- **Lincheck / JPF (JVM):** model-checkers of structures; do not run a real runtime with native IO. Not comparable; this is why "no JVM runtime has whole-runtime DST" holds.
- **Implication:** matching the FDB/TigerBeetle guarantee requires Option B (total order). A weaker but still-valuable guarantee is reachable with Option A.

### Relationship to the `e2e-shop-order-saga` benchmark scenario (not a duplicate)

The `exeris-benchmarks` scenario `e2e-shop-order-saga` (the same scenario whose Community campaign produced the locality NO_GO numbers cited above) **shares the workload shape** DST wants — the register → recommend → cart → order journey, the four internal saga steps (`RESERVE_INVENTORY` → `CHARGE_PAYMENT` → `CONFIRM_ORDER` → `SEND_CONFIRMATION_EMAIL`), the compensation paths, and the `COMPLETED` / `COMPENSATED` / `FAILED` terminal states. That scenario spec is a **reusable asset** — DST should drive this exact saga topology as one of its first simulation workloads.

But the two are **orthogonal lenses, not duplicates**, and cannot be merged:

| | `e2e-shop-order-saga` benchmark | DST |
|---|---|---|
| Question | how fast under real load (throughput, p99, saga success rate) | correct under injected fault, **replayable from a seed** |
| Substrate | real: H2C loopback, PostgreSQL, Neo4j, k6 | simulated in-memory behind The Wall — no real IO |
| Time / order | stochastic: `random sleep 800–2500 ms`, real timers, OS scheduling | deterministic: virtual clock, seeded RNG, controlled scheduler |
| Faults | organic (~5% compensation as a natural distribution; uncontrolled timing) | injected at a precise, seeded point (drop/delay/partition/crash) |
| Output | statistical distribution (hence warmup, repeats, threshold-failure runs) | identical trace bit-for-bit across runs of the same seed |

The benchmark **cannot** be made deterministic without ceasing to measure throughput — it needs real contention, hence the loopback caveat, warmup, and steady-state windows. Conversely, DST **sharpens** what the benchmark can only assert statistically: the scenario's fairness gates (`saga_success_rate_min: 0.98`, `compensation_invocation_rate_max: 0.05`) are statistical thresholds; DST turns them into *exact* assertions ("under seed X with a payment fault injected after `RESERVE_INVENTORY` commits but before `CHARGE_PAYMENT` acks, this saga MUST reach `COMPENSATED` and roll back inventory — reproducibly"). Shared workload, different question; they reinforce rather than overlap.

## Options Considered

### Option A — Placement seam + deterministic IO ("IO-deterministic" simulation)

Reuse the existing M1/M2 seam as-is (placement control), add in-memory IO bindings + clock + RNG. Determinism holds **at IO-driven scheduling points**; pure-CPU interleavings between simultaneously-runnable VTs are *not* pinned to a total order.

- **Pros:** smallest delta from what exists; does not push deeper into the evolving Loom carrier API; already enough to make most fault-injection scenarios (saga survives persistence partition, transport drop, broker delay) **replayable**, because those are IO-bound.
- **Cons:** not bit-for-bit on CPU-bound interleavings; weaker than the FDB/TigerBeetle headline; a skeptic can construct a non-reproducing pure-CPU race.

### Option B — Total-order seam (deterministic single-stepping scheduler)

Extend the seam to own next-runnable selection: a single-stepping scheduler picks the next runnable from a seeded deterministic order; timers and completions are enqueued into the same ordered queue.

- **Pros:** full bit-for-bit reproducibility including CPU interleavings; matches the moat-class headline; the strongest possible claim.
- **Cons:** most work; reaches deepest into the semi-internal/evolving Loom custom-scheduler surface (carrier API still moving); interacts with the `StructuredTaskScope` GA-clean-substitution split (see Open Questions) — the deterministic scheduler must compose with whatever structured-concurrency layer `main` ships.

### Option C — Staged: A first, B as a gated follow-on

Ship Option A (IO-deterministic + placement) to get replayable fault-injection over the IO-bound scenarios that matter most, **then** decide whether pure-CPU-interleaving reproducibility (Option B) is worth the deeper Loom coupling, driven by whether a real scenario needs it.

- **Pros:** delivers demonstrable value early without betting on the most volatile API surface; keeps the total-order cost gated behind evidence of need.
- **Cons:** the early claim must be stated precisely ("deterministic over IO-driven scheduling") so it is not over-sold as full DST.

## Recommendation

**Lean Option C (staged), and resolve the crux as the first action.** Concretely:

1. **Establish what the M1/M2 seam controls** — selection or only placement. The branch built it for placement (affine prototype); confirm whether single-stepping selection is reachable through the same injection point or requires deeper carrier control. This one finding decides how far Option B is from Option A.
2. **Stage 1 = Option A:** in-memory IO bindings (`TransportProvider` / `PersistenceEngine` / `EventEngine`) + unified Clock + seeded RNG + a sim-driver with fault injection and seed-replay, asserting reproducibility over IO-driven scheduling points. State the guarantee precisely.
3. **Stage 2 = Option B, gated:** total-order single-stepping, pursued only if a concrete scenario needs pure-CPU-interleaving reproducibility, and only after the Loom carrier surface and the `StructuredTaskScope` baseline have settled enough to commit.

This keeps the architecturally-de-risked seams paying off early while not betting the harness on the most volatile JDK surface before there is evidence it is needed.

## Consequences

- DST runs in **test/tooling scope only** — performance overhead of a deterministic scheduler is irrelevant to the shipped runtime; the scoped-ban and No-Waste-Compute contracts do not apply to the sim harness.
- The in-memory SPI simulation bindings must pass the **same `Abstract*Tck` suites** as the real bindings, or the simulation is testing a different system than production.
- The unified Clock seam becomes a hard dependency (already roadmap-tracked, independently justified).
- Reinforces the value of the existing deterministic primitives (admission net-counter, LIFO unwind) — the sim asserts them as invariants.

## Open Questions

1. **Total order vs. placement** — the gating question above. First to resolve.
2. **Loom carrier API stability** — the custom-scheduler surface is semi-internal and evolving; how much of Option B is exposed without `--add-opens` / internal API, and how does that interact with the JDK 25 LTS preview-clean baseline?
3. **`StructuredTaskScope` interaction** — the GA-clean-substitution split (`main` = GA-clean fork/join layer, `preview` = STS) means a deterministic scheduler must compose with *both* structured-concurrency layers, or be scoped to one. Which?
4. **JFR determinism** — JFR timestamps read wall-clock; the sim must either virtualize them through the Clock seam or exclude them from the replayed trace.
5. **Trace identity** — what exactly is asserted "bit-for-bit": the event/transition trace, the JFR stream, or persisted state? Define the canonical replay artifact.

---

*On acceptance, this RFC produces one or more ADRs (execution-seam determinism contract; in-memory simulation-binding contract). Reserve the ADR number in `exeris-docs/adr-index.md` at that point, not before.*
