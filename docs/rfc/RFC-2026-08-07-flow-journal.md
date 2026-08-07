# RFC-2026-08-07: What should a durable saga journal record, where should it live, and what may it cost?

| Field             | Value                                                                    |
|:------------------|:-------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                             |
| **Author(s)**     | Arkadiusz Przychocki                                                     |
| **Date Opened**   | 2026-08-07                                                               |
| **Date Closed**   | 2026-08-07                                                               |
| **Target ADR(s)** | none at acceptance — reserved when the implementing change reaches its build gate |
| **Affected Repos**| `exeris-kernel`                                                          |
| **Reviewers**     | —                                                                        |

## Question

The kernel keeps no history of what a saga did. **Should it, and if so: what does an entry contain,
where does it live (SPI contract or Community-local), on what substrate does it persist, what retention
does it carry, and what may it cost on the checkpoint path?** The last of those is the one that decides
whether the rest is worth designing.

## Context

`FlowSnapshotStore` is a **last-value row store, not a log**. `save` upserts one row per instance and
`complete()` calls `deleteSnapshot`, so a saga that finishes successfully leaves **no trace at all** —
the durable record of a correct run is its absence. The nearest thing to a transition vocabulary is
seven JFR event classes in `core/flow`, which are diagnostic and ephemeral: they answer "what is this
process doing", never "what did this saga do last Tuesday".

For an orchestration engine that is a product-level gap. Audit, replay, and after-the-fact dispute
resolution ("did we charge that card before or after the reservation expired?") all need history the
runtime does not keep, and every one of them is a reason teams buy an orchestration engine rather than
write a state machine.

The ordering objection that held this back is now discharged. ADR-062 gives an entry a step identity
that survives a deploy; ADR-064 gives it the definition version that makes the identity meaningful.
Before those, an entry reading `STEP_COMPLETED step=3` was false at the next deploy for exactly the
reason a position-bound resume was.

What has *not* existed at any point is a decision. Until the ROADMAP entry that accompanies this RFC,
FlowJournal had no entry, no owner, no tier row, no issue and no reserved number — it was the one
concept in this milestone's orbit with no tracking artifact of any kind. That absence, not the
implementation, is what this RFC is answering first.

## Investigation

### Prior art

**Temporal** and **Cadence** make the event history *the* execution model: a workflow's state is a
replay of its history, so the journal is not an addition but the substrate, and history size is a
first-class operational limit (Temporal enforces a hard event-count cap and offers "continue-as-new" to
escape it). **Netflix Conductor** takes the opposite shape — a task-status store with an optional
archival index, where history is a reporting concern layered beside execution. **Camunda 7/8** sits
between: a runtime store plus a separately-configured *history level* (`none` / `activity` / `audit` /
`full`), explicitly tunable because history writes are the dominant cost in high-throughput
deployments.

Two lessons carry directly. First, **the projects that made history mandatory made it the execution
model, and paid for it with hard size caps** — a journal bolted onto an existing execution path is not
the same design and should not inherit its ambitions. Second, **Camunda's tunable history level exists
because the write cost is real enough to need an off switch**; a kernel whose first rule is No Waste
Compute cannot ship a journal that is unconditionally on and unmeasured.

### Constraints

- **`spi.flow` is `stable` and has spent its milestone budget.** `FlowSnapshot`'s component list moved
  three times in v0.11 (ADR-062, ADR-064, A5), each requiring the retained-canonical-constructor bridge.
  A fourth is not forbidden, but it is the shape a journal should be able to avoid entirely.
- **The compatibility gate is silent where additive is not free.** Pure addition passes japicmp —
  nothing is removed — so a new package is genuinely cheap. But ADR-065 records that the tool "cannot
  see a removed record component, a removed constructor, or an interface" method addition, so an
  abstract method on `FlowSnapshotStore` would link fine, break every implementor's build, and the gate
  would say nothing. The in-repo answer is a `default`, and both dispositions have precedent
  (`registerMigration` refuses; `listParked()` returns empty).
- **Classification is mandatory and mechanical.** `--verify-surfaces` fails the build on any SPI class
  that resolves to no maturity label: *"every SPI class must resolve to a maturity label in
  stability-surfaces.conf (and a matching row in docs/stability-matrix.md)"*. New classes ship with
  their row, in the same commit.
- **JFR payloads must stay secret-safe.** Step names are definition metadata and safe; `opaqueState` is
  application data and is already rendered as a byte count rather than contents in `FlowSnapshot`'s
  `toString` for that reason. A journal entry recording payloads would be a new data-protection surface,
  not a new diagnostic one.
- **No flow SLO exists to measure against.** `docs/performance-contract.md` carries no flow or saga
  budget, and no benchmark in the repo touches a durable write. The flow park/wake benchmark wires no
  `FlowSnapshotStore` at all.

### Data gathered

**The write-cost baseline, from source rather than estimate.** `persistSnapshot` has exactly four call
sites in `CoreFlowRuntime` — `PARKED` at `:315` and `:1126`, `COMPENSATING` at `:1163`,
`FAILED_ROLLEDBACK` at `:1214`. All four are state-transition-scoped. **There is no write on step
completion.** So a ten-step saga that never parks writes **zero** durable rows today, and a per-step
journal makes that ten.

That is the finding that shapes everything else: a journal is not a marginal cost on an existing write
path, it *introduces* a write path where none exists. Any option that writes per step must be argued
against zero, not against "one more row".

**The substrate question already has a working answer, and it is not the snapshot store.**
`FlowSnapshotStore` cannot host history — it is last-value by contract and its rows are deleted on
success. `EventStreamAppender` (ADR-049) can: append-only, per-stream monotonic head, optimistic
concurrency via `expectedVersion`, with the durable DDL's primary key
`(stream_id_high, stream_id_low, committed_sequence)` documented as *"BOTH the ordering key for replay
AND"* the concurrency discriminator — and it is TCK-bound on **two** bindings, JDBC and Kafka. A
second durable log would be a second answer to a question the repo has already answered once.

**The entry-contents question is a diff against a known inventory, not a blank page.** Seven JFR event
classes already exist in `core/flow`, covering step failure, timeout, schema mismatch (with reason plus
both step names), wake-on-load fallback, optimistic-lock conflict, snapshot-save failure and definition
migration. What none of them carries is the pair the prerequisites just added — no event carries
`definitionVersion`, and the transient progress payload carries a step *index* rather than a step
*name*. So the journal's contents are: the existing vocabulary, re-expressed in identities rather than
positions, plus the version.

### Spike outcomes

None, and the absence is the point: the one input a spike would have to produce is the checkpoint-write
measurement, and the apparatus to take it does not exist yet (see §Constraints). Building a prototype
before the benchmark would produce a design with a number-shaped hole in exactly the place the decision
turns on.

## Options Considered

### Option A: Per-step journal on the events log, always on

Every step transition appends an entry to `EventStreamAppender` under a per-saga stream id.

**Pros:**
- Complete history; replay and audit both fall out of it.
- Reuses a durable, ordered, TCK-proven substrate rather than inventing one.

**Cons:**
- Turns a zero-durable-write happy path into N writes — the write-frequency class change, unmeasured.
- Makes history mandatory, which is the design Temporal pays for with hard size caps and Camunda offers
  an off switch for.

**Cost:** the highest, and the only option whose cost is unknown rather than merely nonzero.

### Option B: Per-step journal, opt-in per definition, on the events log

Same substrate and entry shape, but a definition declares whether it is journalled. Off by default.

**Pros:**
- The zero-write happy path stays zero-write for every saga that does not ask for history.
- Matches Camunda's history-level lesson without inventing a level taxonomy: one boolean, per definition.
- Sagas that want audit are exactly the sagas whose steps are expensive enough that a durable append is
  not the dominant cost — the pricing lands where the value does.

**Cons:**
- A per-definition flag is a new configuration surface, and the "captured-but-dead SDK flag" failure
  mode is a live cautionary tale in this repo.
- Still needs the measurement before the on-path is defensible; opt-in bounds the blast radius, it does
  not price the write.

**Cost:** moderate; the flag is cheap, the append path is the same as A.

### Option C: Transition journal at state boundaries only — no per-step entry

Journal exactly what already writes: park, compensating, failed-rolledback, plus completion (which
today writes nothing because the row is deleted).

**Pros:**
- Costs nothing new on the happy path *except* the completion entry, because the other three already
  write. The delta is one append per saga, not N.
- Fixes the sharpest current defect on its own: a successfully completed saga currently leaves no trace.

**Cons:**
- Not a history of *what the saga did* — it cannot answer "which steps ran, in what order", which is
  most of why a journal is wanted.
- Risks being the version that ships because it is cheap and then blocks the version that is useful.

**Cost:** low, and the only option whose cost is knowable without new measurement.

### Option D (do nothing): no journal

**Pros:** zero surface; the prerequisites keep, and nothing built on a guess.

**Cons:** leaves the product-level gap — a completed saga's durable record is its absence — and leaves
seven JFR classes as the only transition vocabulary, none of which survives the process.

**Cost:** zero now.

## Recommendation

**Select Option B — per-step journal on the events log, opt-in per definition — as the shape, and gate
implementation on a checkpoint-write measurement that does not exist yet.**

B is the only option that takes the write-cost finding seriously instead of around it. A is the design
the prior art warns about: mandatory history is affordable when history *is* the execution model and
comes with hard caps, and this journal would be neither. C is honest about cost but buys the wrong
thing — it fixes the "completed saga leaves no trace" defect while failing to answer what a journal is
for, and a cheap partial answer that occupies the slot is worse here than no answer.

The substrate choice is not really a choice: `EventStreamAppender` is append-only, per-stream ordered,
OCC-guarded and bound on two providers, while `FlowSnapshotStore` is last-value by contract and deletes
on success. Placing the journal on the events log also keeps `spi.flow` untouched, which matters more
than usual after a milestone in which `FlowSnapshot`'s component list moved three times.

Implementation waits on one number. Not because the design is uncertain — B's shape is settled here —
but because "how much does one durable append cost on the flow path" has no answer in this repo and no
apparatus to produce one. Building that benchmark is worth doing whether or not the journal follows: the
flow subsystem has no numeric budget in the performance contract at all, and the park/wake benchmark
wires no snapshot store, so nothing currently measures the durable path.

Residual uncertainty, stated. The opt-in flag is a configuration surface, and this repo's own
`cacheable`/`cacheRegion`/`cacheTtl` flags have sat captured-but-dead in the SDK for four milestones —
a per-definition journal flag with no sink would be the same failure with a different name. The
implementation gate must land the flag and its consumer together or neither.

### Why not the alternatives?

- **Option A** — makes an unmeasured write-frequency class change mandatory, which is the one thing the
  baseline finding says not to do.
- **Option C** — knowably cheap, but answers a different question than the one asked, and occupying the
  slot with it is worse than leaving it open.
- **Option D** — leaves a completed saga's durable record as its absence, in an engine bought for
  exactly that record.

### Risks of the recommendation

- **The measurement may kill it.** If a durable append is expensive enough that opt-in is not enough,
  B collapses toward C or D. That is the correct outcome and this RFC would rather be superseded by a
  number than defended against one.
- **Opt-in bounds blast radius, it does not price the write.** A journalled saga still pays N appends;
  the flag only decides who volunteers.
- **A journal is a data-protection surface.** Step names are definition metadata and safe; anything
  drawn from `opaqueState` is application data. The ADR must rule that entries carry identities and
  versions, never payload.
- **Retention has no precedent to inherit.** The saga-state TTL that `flow.md` and `CoreFlowRuntime`
  both point at (DIST-303) shipped only its deadline half — the TTL-eviction half does not exist, and
  `JdbcFlowSnapshotStore`'s only `DELETE` is keyed by instance id with no age predicate. The journal
  either designs retention or inherits an unimplemented promise.

## Decision Record

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | **ACCEPTED** — Option B (per-step journal on `EventStreamAppender`, opt-in per definition) selected as the shape; implementation gated on a checkpoint-write measurement that does not exist yet. |
| **Date**             | 2026-08-07 |
| **Resulting ADR(s)** | **none at acceptance.** Accepting this RFC commits no kernel surface — decision-only track. The number is reserved when the implementing change reaches its build gate. |
| **Notes**            | This RFC decides a *shape*, not a schedule, and explicitly does not decide the milestone. The gate it sets is a measurement, and that measurement is worth taking regardless: the flow subsystem carries no SLO in the performance contract and no benchmark touches a durable write, so "what does a checkpoint write cost" is currently unanswerable for reasons that have nothing to do with journalling. Dissent recorded: Option C (state-boundary journal only) is the cheap answer and a reviewer could reasonably prefer it, since it fixes the "completed saga leaves no trace" defect for a delta of one append per saga and needs no new measurement. It is rejected because it would occupy the slot with a partial answer — but if the measurement rules Option B out, C is where this should land rather than D. |

## Open questions / follow-ups

- **The measurement itself.** A persistence-enabled variant of the existing flow park/wake benchmark —
  the abstract benchmark and its Core binding already exist, so the work is a binding that wires a
  `FlowSnapshotStore`, not a new harness. Owner: flow + performance.
- **A flow SLO in the performance contract.** The subsystem has none. Whatever the benchmark measures
  should become the first one, journal or no journal. Owner: performance contract.
- **Stream identity for a saga's journal.** `EventStreamAppender` keys on a 128-bit stream id and a
  saga already has a 128-bit instance id; whether they are the same identifier or the journal namespaces
  its own is unsettled and affects whether a saga's journal collides with an application's event stream.
  Owner: resulting ADR.
- **Retention, and DIST-303's unimplemented half.** `flow.md` tells an operator that saga row lifetime
  "is governed by saga TTL retention (DIST-303)"; the eviction half of that was never built. The journal
  should not inherit that promise silently — and the doc claim is worth correcting independently.
  Owner: flow subsystem.
- **The opt-in flag's home.** Per-definition means `FlowDefinition`, which is `spi.flow` and `stable`.
  Whether the flag rides there, on a separate journalling contract, or in configuration decides whether
  this touches the surface the recommendation is trying to keep untouched. Owner: resulting ADR.
- **Interaction with the events log's own retention.** If a saga journal shares the substrate with
  application event streams, compaction and retention policies apply to both. The coordination RFC
  raised the same shape of question about a second consumer of that log; they should be answered
  together. Owner: events subsystem.
