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
9. **A migration's output is validated by ADR-062's identity check, not trusted.** The transform runs
   first, and the step it produces is then checked against the target version's plan exactly as any
   other resume is. A transform that returns a step that does not exist, or that names a different step
   than the index addresses, fails closed on the existing surface. Application code on the resume path
   is not a new trust boundary.
10. **Failures reuse `EX-FLOW-7002 / phase=SCHEMA_MISMATCH`** with new reason discriminators beside
    `STEP_OUT_OF_RANGE`, `STEP_IDENTITY_MISMATCH` and `STEP_IDENTITY_ABSENT`. An operator needs to
    tell "this saga's version was never deployed here" from "its step moved" — different remedies, so
    different reasons. No new error code and no new phase; the taxonomy already has the right shape.
11. **`AbstractFlowDefinitionVersioningTck` is the merge gate.** Version-keyed resume with two versions
    registered at once, a vN→vN+1 migration, a chained vN→vN+2, and the no-path rejection — with the
    rejection case mandatory, because a suite that only proves migration would pass against a runtime
    that migrates anything to anything.

## Amendments (settled during implementation, v0.11)

Three questions this ADR left open or under-specified, decided before any code was written and
recorded here rather than in a commit message.

**A1 — Migration runs on `wake()` only; `schedule()` continues to refuse.** The resubmit path fixes
the target version at the plan the *caller* supplies, which makes the chain's terminating condition
path-dependent — one policy cannot serve both doors. Scoping to wake also keeps application code out
of three places it does not belong: the `liveInstances.compute` mapping function (a concurrent-map bin
lock), `lookupParked`'s read-only query, and a `COMPENSATING` snapshot reaching a transform its author
wrote for a parked saga. A resubmit against a mismatched version therefore keeps failing closed with
`DEFINITION_VERSION_UNRESOLVED` — a refusal, not a silent wrong-version resume — and that is a
**functional narrowing stated plainly**: choreography can reach `schedule()` directly, and a saga
resubmitted rather than woken is not migrated.

**A2 — The chain stops at the first registered version, and adjacency is enforced at registration.**
Not a preference: `planCatalog` is keyed by `PlanKey(name, version)` and offers point-gets only. There
is no name→versions index, and the single name-scoped query (`hostsDefinition`) is a full `keySet()`
scan — adding another to the resume *success* path would be a No-Waste-Compute regression. Requiring
`to == from + 1` at registration makes the chain terminate by construction; the configured chain bound
is a blast-radius limit, not the termination mechanism.

**A3 — A successful migration persists its result.** The alternative — re-running the chain on every
wake — makes purity and idempotence load-bearing obligations that no document states, and leaves the
durable row asserting a version the saga no longer runs. ADR-062's thesis is that the checkpoint must
be truthful; a row saying v1 for a saga executing v2 is the same class of lie as a step recorded by
position. The write happens on the resume path and participates in the ADR-013 `schemaVersion` OCC
model like any other checkpoint.

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
