# ADR-064: Version flow definitions, and resume a saga on the version it parked under

| Attribute       | Value                                                                                    |
|:----------------|:-----------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                             |
| **Deciders**    | Arkadiusz Przychocki                                                                     |
| **Date**        | 2026-08-05                                                                               |
| **Scope**       | `kernel/flow`                                                                            |
| **Owning Repo** | `exeris-kernel`                                                                          |
| **Driven By**   | [`docs/ROADMAP.md`](../ROADMAP.md) §"Differentiator: Flow/Saga Definition Versioning + In-Flight Migration" — stage 2; ADR-062 supplied the detection this builds coexistence on |
| **Compliance**  | [Fail-Closed Architecture](../subsystems/security.md) §3; [No Waste Compute](../whitepaper.md) |

## Context and Problem Statement

ADR-062 made the runtime *detect* that a parked saga's definition changed underneath it: the snapshot
records the identity of the step it parked at, and a same-arity reorder now fails closed instead of
silently resuming on the wrong step. Detection is where that ADR deliberately stopped — it says so, and
the reason it gave was that version coexistence built before the runtime can detect a mismatch is built
on sand.

The sand is gone. What remains is that the kernel has exactly one answer to "which definition is this
saga running?", and it is "whichever one is registered right now":

```java
private final ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog = new ConcurrentHashMap<>();
…
CoreFlowExecutionPlan catalogPlan = planCatalog.get(persisted.definitionName());
```

`CoreFlowRuntime.java:52` and `:470`. The catalog is keyed by name alone, so **two versions of a
definition cannot exist at the same time** — registering a changed definition replaces the one every
in-flight saga parked under. `FlowDefinition` (`:36`) carries `name`, `steps`, `timeoutDurationNanos`
and `maxRetries`; there is nowhere to put a version even if the catalog could hold one.

So the current behaviour on a deploy that changes a definition is: the old plan is gone, and every
parked saga either resumes against the new one or — since ADR-062 — refuses to resume at all. Refusing
is the correct half of the answer. It is not the whole answer, because *"drain every in-flight saga
before deploying"* is not a property of an orchestration engine; it is the absence of one. A saga that
runs for three days across a Tuesday deploy is the entire reason a team buys Camunda or Temporal rather
than writing a state machine.

`FlowSnapshot.schemaVersion` does not help and must not be conscripted: it is the ADR-013
optimistic-lock counter on the durable row, incremented by concurrent writers. Reading it as a
definition version would make every concurrent checkpoint look like a redeploy.

The question this ADR answers: **what has to exist so that a definition can change while sagas are
running under the old one, and so that moving those sagas forward is a decision the application states
rather than a coincidence of deploy timing?**

## 🏁 The Decision

**A definition is identified by name *and* version; a saga resumes on the exact version it parked
under; and moving it to another version happens only through a transform the application wrote.**

Coexistence and migration are separated. Coexistence is the runtime's job and needs no application
input beyond a version number. Migration is the application's job, because only it knows whether a
parked saga's state means the same thing under the new definition.

**Concrete obligations:**

1. **`FlowDefinition` gains an `int version`.** Explicit, application-declared, monotonic within a
   name. Not derived from the definition's content: a content hash would make an added comment or a
   renamed lambda a new version, and would make "which version is this" unanswerable by reading the
   source. A definition built without one is version `1`, so existing call sites compile and behave
   exactly as they do today.
2. **The plan catalog is keyed by `(name, version)`.** Registering v2 no longer evicts v1. Both serve
   traffic: new instances start on the newest registered version, parked instances resume on theirs.
   `FlowEngineConfig.maxExecutionPlans` now bounds versions as well as definitions, which is a real
   consequence and is documented rather than discovered under load.
3. **`FlowSnapshot` carries `definitionVersion`,** written at park time from the plan that was running.
   Additive, alongside `currentStepName` from ADR-062 — the snapshot now records *which definition* and
   *which step within it*, which is the pair that makes a checkpoint meaningful across a deploy.
4. **Resume resolves the exact version, or does not resume.** `resolvePlanForSnapshot` looks up
   `(definitionName, definitionVersion)`. A snapshot whose version is not registered is not quietly
   rebound to the newest one — that rebinding is the defect this epic exists to remove, and doing it
   silently after ADR-062 refused to do it loudly would be a regression wearing a feature's name.
5. **A snapshot with no recorded version fails closed,** with its own reason discriminator, exactly as
   ADR-062 treats a snapshot with no recorded step identity. The upgrade cost is already paid: ADR-062
   requires draining in-flight sagas across the 0.10→0.11 boundary, and both changes ship in 0.11, so a
   deployment following the documented procedure has no ambiguous rows.
6. **Migration is an explicit, registered transform between adjacent versions.** A
   `FlowDefinitionMigration` maps a saga parked under vN onto a resumable position under vN+1: **the
   step it parked at**, its compensation stack, and its opaque state. Adjacent hops are chained by the
   runtime (v1→v2→v3), so an application registers *n-1* transforms rather than *n²* pairs. A missing
   link means no path.

   *Amended during implementation.* This obligation first read "the step it should resume at", which is
   a different step: `FlowSnapshot.currentStep` records where the saga **parked**, and `wake()` resumes
   at `currentStep + 1`. A transform written to the original wording would emit the resume step, the
   runtime would advance past it, and the saga would **skip a step — while ADR-062's identity check
   passed**, because the emitted (index, name) pair is internally consistent. A silent drop with a
   guard reporting success is the defect class this milestone exists to remove, so the wording is
   corrected rather than left for each implementer to trip over.
7. **The compensation stack is part of what a migration transforms, not a detail it may ignore.** The
   stack holds step indices from the version that pushed them. Carrying it across a version boundary
   unchanged would compensate the wrong steps on failure — the same class of defect as position-bound
   resume, one level down, and it is why the transform's signature takes the whole resumable state
   rather than just a step.
8. **No migration path means rejection, and rejection does not mutate the row.** The saga stays
   `PARKED` and stays recoverable: an operator who deploys the missing version, or registers the
   missing transform, gets their sagas back. Marking it terminal would be irreversible, would run
   compensation for a definition the runtime cannot even bind, and would destroy the one remedy that
   works. **A quarantine `FlowState` is deliberately not introduced** — it would trade a reversible
   failure for an unrecoverable one.
9. **A migration's output is validated, not trusted.** The transform runs first, and what it produces
   is then checked against the target version's plan exactly as any other resume is. A transform that
   returns a step that does not exist, or that names a different step than the index addresses, fails
   closed on the existing surface. Application code on the resume path is not a new trust boundary.

   *Scope, corrected during implementation — see amendment A4.* As accepted, this obligation named
   ADR-062's identity check as the whole of the validation, which covers the cursor
   (`parkedStep`, `parkedStepName`) and nothing else. `FlowMigrationState` has five components, and
   the compensation stack is the one that drives rollback. The obligation is only met because a
   bounds guard on the stack was added; the identity half of it is not met yet.
10. **Failures reuse `EX-FLOW-7002 / phase=SCHEMA_MISMATCH`** with new reason discriminators beside
    `STEP_OUT_OF_RANGE`, `STEP_IDENTITY_MISMATCH` and `STEP_IDENTITY_ABSENT`. An operator needs to
    tell "this saga's version was never deployed here" from "its step moved" — different remedies, so
    different reasons. No new error code and no new phase; the taxonomy already has the right shape.
11. **`AbstractFlowDefinitionVersioningTck` is the merge gate.** Version-keyed resume with two versions
    registered at once, a vN→vN+1 migration, a chained vN→vN+2, and the no-path rejection — with the
    rejection case mandatory, because a suite that only proves migration would pass against a runtime
    that migrates anything to anything.

## Amendments (settled during implementation, v0.11)

Four questions this ADR left open or under-specified. A1–A3 were decided before any code was written.
A4 is different in kind: it corrects an obligation this ADR stated as met when it was met for two of
five components. Recorded here rather than in a commit message, and rather than quietly narrowed.

**A1 — Migration is scoped to the resume-restore path; `schedule()` continues to refuse.** The
resubmit path fixes the target version at the plan the *caller* supplies, which makes the chain's
terminating condition path-dependent — one policy cannot serve both doors. A resubmit against a
mismatched version therefore keeps failing closed with `DEFINITION_VERSION_UNRESOLVED` — a refusal, not
a silent wrong-version resume — and that is a **functional narrowing stated plainly**: choreography can
reach `schedule()` directly, and a saga resubmitted rather than woken is not migrated.

An earlier draft of this amendment said "wake() only", and justified it partly by keeping application
code out of `lookupParked`'s "read-only query". That was wrong twice over, and is corrected here rather
than left to be discovered.

Wrong on the facts: `lookupParked` has two branches. The in-memory branch returns a context view and
never migrates, by construction. The durable-store branch is a **restore, not a read** — it was already
building a `RuntimeFlowInstance`, registering it as parked, clearing miss tracking and emitting
`WakeOnLoadFallbackEvent` before this ADR existed. Calling it read-only described nothing that was true.

Wrong on the consequence: `FlowChoreographyBridge` wakes a saga as
`lookupParked(most, least).ifPresent(scheduler::wake)`. Cutting migration out of the store fallback
would not make it read-only; it would make a cross-engine saga on a retired version **refuse instead of
migrate**, on exactly the topology ADR-013 §8 defines the fallback for. The narrowing would have been
far larger than the one this amendment claims to state plainly.

What the original list did get right holds by construction, and is worth keeping written down: no
transform runs inside a concurrent-map mapping function (the restore completes before
`liveInstances.putIfAbsent`), and no transform sees a non-`PARKED` snapshot (the restore requires
`PARKED`, and the walk bails on terminal states).

The residual is real and bounded: a bare `lookupParked` — introspection, not a prelude to a wake — can
run a transform and write. It runs **once**; A3's persistence is what stops a polled lookup re-running
application code, and `repeatedLookupRunsTheTransformOnce` pins that rather than leaving it to
argument. Making the fallback build a snapshot-backed context without binding a plan would recover a
genuinely read-only `lookupParked`, but it changes what the call registers and what the cross-engine
recovery IT observes, so it is a separate decision and is not taken here.

**A2 — The chain stops at the first *hosted* version, and adjacency is structural.**
Not a preference: `planCatalog` is keyed by `PlanKey(name, version)` and offers point-gets only. There
is no name→versions index, and the single name-scoped query (`hostsDefinition`) is a full `keySet()`
scan — adding another to the resume *success* path would be a No-Waste-Compute regression. So the walk
tests one key per hop and returns the moment that key is present.

Adjacency is not validated, it is unrepresentable: `registerMigration(definitionName, fromVersion,
migration)` takes no target version, and the runtime rebuilds the snapshot at `fromVersion + 1`. There
is no malformed edge to reject. The configured hop bound is a blast-radius limit, not the termination
mechanism.

"First hosted" and "last registered" coincide in every chain whose transforms stop where hosting stops,
which is the ordinary case and therefore not evidence for either rule. They disagree when an
application keeps a transform registered past what it still hosts — and there the stopping rule is
what decides between resuming the saga and refusing it, so
`AbstractFlowDefinitionVersioningTck$Migration#migrationStopsAtTheFirstHostedVersion` constructs that
disagreement rather than leaving the rule pinned by coincidence.

**A3 — A successful migration persists its result.** The alternative — re-running the chain on every
wake — makes purity and idempotence load-bearing obligations that no document states, and leaves the
durable row asserting a version the saga no longer runs. ADR-062's thesis is that the checkpoint must
be truthful; a row saying v1 for a saga executing v2 is the same class of lie as a step recorded by
position. The write happens on the resume path and participates in the ADR-013 `schemaVersion` OCC
model like any other checkpoint.

**A4 — The compensation stack is validated too; obligation 9 overstated what covered it.**
Obligation 9 said a transform's output is checked by ADR-062's identity check. That check reads
`currentStep` and `currentStepName` — the cursor. It never looks at the compensation stack, which is
the component that decides what a rollback undoes, and which the transform may rewrite.
`FlowMigrationState` has five components; two were covered.

The gap was neither hypothetical nor confined to migration. `runCompensationStep` resolves each entry
with `plan.stepAt(entry)`, a bare array read, **outside its own catch**. A stale entry therefore throws
out of `runCompensations` and skips both the remaining unwind and `finalizeFailedInstance` — leaving
the saga mid-compensation, terminal state unwritten and idempotency guard still held, and only after
some other failure has already put it on the rollback path. Strictly worse than refusing to resume. A
shrinking redeploy could reach it before this ADR existed; migration made it ordinary rather than
exceptional, because moving a saga onto a changed definition is now the sanctioned path.

So the live prefix of the stack is validated against the target plan on every resume, refusing with
`COMPENSATION_STACK_OUT_OF_RANGE` and leaving the row intact like every other resume refusal. Only the
prefix below `stackPointer`: entries above it are dead, and closing on those would refuse a sound saga.

This is the **bounds** half. An entry that indexes the plan but names a step the saga never ran is not
detectable from indices — exactly as a same-arity reorder was not detectable from the cursor's index.
Closing it needs the stack to carry identities, which is a `FlowSnapshot` shape change and therefore
its own slice, taken in v0.11 rather than later: the record's component list already changed this
milestone, so a third component costs nothing further on the stability ledger, while deferring it to
v0.12 would be a fresh change to a surface declared stable.

---

## Consequences

### ✅ Positive Outcomes

- **[+] A long-running saga survives a deploy that changed its definition.** This is the claim the
  ROADMAP calls the Camunda wedge, and until now the kernel could not make it.
- **[+] "Drain before deploying" stops being the answer to every definition change.** It remains the
  answer for a *rename* within a version, which ADR-062 governs and this does not relax.
- **[+] Moving a saga forward becomes a stated decision with a test.** A transform is application code
  like any other: it can be unit-tested before the deploy that depends on it, which is not true of
  "hope the reorder was harmless".
- **[+] The journal becomes meaningful.** ADR-062 gave an entry a step that survives a deploy; this
  gives it a definition version, so a history says *which* definition produced the transition rather
  than only which step name did.

### ⚠️ Trade-offs

- **[-] Applications inherit a versioning obligation they did not have.** Change a definition and you
  must decide: bump and migrate, or do not bump and accept that ADR-062 refuses parked sagas whose
  steps moved. Not bumping is still a valid choice, and an application that never bumps behaves as it
  does today — but it is now a choice rather than the only behaviour.
- **[-] The catalog holds more plans.** Every retained version costs its slab, and `maxExecutionPlans`
  now bounds versions too. An application that bumps on every deploy and never retires old versions
  will hit that ceiling; retiring a version is an operator action with no automatic reclamation in this
  slice.
- **[-] Application code runs on the resume path.** A transform can throw, loop, or be slow, and it
  does so while a saga is being woken. Obligation 9 validates its *output*, not its behaviour.
- **[-] Chained migration multiplies the blast radius of one bad transform.** A faulty v2→v3 breaks
  every saga still parked at v1 as well, because the chain runs through it.
- **[-] The snapshot grows again.** A second field added to the same durable row in the same milestone,
  after ADR-062's. Small, and paid on every checkpoint.

### 📋 What is NOT in scope

- **The FlowJournal contract.** It follows this rather than preceding it, and it is not obviously an
  ADR yet: what an entry contains, whether the contract is SPI or Community-local, where it persists,
  retention, and its write cost on the saga path are all open. That shape is an RFC's question.
- **A quarantine `FlowState`**, per obligation 8 — rejection stays reversible.
- **Automatic retirement of old versions.** Deciding that no saga will ever again resume on v1 requires
  knowing every parked instance across every node, which is a query this slice does not add.
- **`loadByDefinition()`**, deferred since FLOW-101 to "the definition-versioning epic". The version key
  it waits on lands here; the method itself does not exist in code today and is not added by this ADR.
- **Downgrade.** Transforms are vN→vN+1 only. Moving a saga backwards is not a supported operation.

## Cross-references

- ADR-062 (Bind flow resume to a named step, not a position) — supplies the detection this builds on,
  and its identity check validates every migration's output per obligation 9.
- ADR-013 (Distributed saga state distribution model) — the snapshot carrier this extends.
  `FlowSnapshot.schemaVersion` is that ADR's optimistic-lock counter, **not** a definition version, and
  this ADR neither merges the two nor adds a third meaning to it.
- [`docs/subsystems/flow.md`](../subsystems/flow.md) — the redeployment compatibility matrix; the rows
  that today read "drain in-flight Sagas before upgrading" gain a second option.
- [`docs/ROADMAP.md`](../ROADMAP.md) → *Differentiator: Flow/Saga Definition Versioning + In-Flight
  Migration* — stage 2, which this decides.

## Engineering Protocol

The codebase is not yet compliant; this ADR is prescriptive.

1. **`AbstractFlowDefinitionVersioningTck` plus its Community binding**, per obligation 11. The
   no-migration-path rejection is mandatory, and the migration cases must be shown to *reject* a bad
   transform's output, not merely to accept a good one's.
2. **`AbstractSagaRecoveryTck` keeps passing unchanged.** ADR-062's guards are not relaxed by
   versioning; a suite where the identity cases went green by becoming unreachable would be a
   regression this ADR must not hide.
3. **`ExerisArchitectureTest`** run explicitly, not assumed from CI.
4. **The integration gate is run, and named.** `mvn -pl exeris-kernel-community
   -DincludedGroups=integration -DexcludedGroups= test` covers the durable-store bindings that persist
   the new column; a green default build says nothing about it.
5. **A durable-store migration ships with the column,** ordered by the version-aware comparator the
   0.11 migration runner uses — `V0.11.0__add_saga_step_name.sql` is the sibling precedent, and the
   plain lexicographic sort it replaced is the cautionary tale.
6. **Docs updated in the implementing slice** — `flow.md`'s compatibility matrix, and the ROADMAP entry
   moving from "gap" to delivered.
7. **Release notes carry the catalog-growth consequence** and the fact that not bumping is still a
   supported choice, so nobody reads versioning as newly mandatory.
