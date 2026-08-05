# ADR-062: Bind flow resume to a named step, not a position

| Attribute       | Value                                                                                    |
|:----------------|:------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                             |
| **Deciders**    | Arkadiusz Przychocki                                                                     |
| **Date**        | 2026-08-05                                                                               |
| **Scope**       | `kernel/flow`                                                                            |
| **Owning Repo** | `exeris-kernel`                                                                          |
| **Driven By**   | [`docs/subsystems/flow.md`](../subsystems/flow.md) §"Redeployment and Saga Compatibility" — the same-arity reorder row, open since v0.10; prerequisite for the FlowJournal work |
| **Compliance**  | [Fail-Closed Architecture](../subsystems/security.md) §3; [No Waste Compute](../whitepaper.md) |

## Context and Problem Statement

A parked saga is resumed from `FlowSnapshot.currentStep` — a zero-based `int`. On wake,
`CoreFlowRuntime.resolvePlanForSnapshot` rebinds the instance to whatever plan is currently registered
under `definitionName` and replays that index into it. The index is a **position in a list**, and
nothing about it is tied to the step it was recorded for.

v0.10 added a guard. `validateSnapshotStepBounds` (`CoreFlowRuntime.java:504`) rejects a snapshot whose
step no longer indexes anything:

```java
if (step < 0 || step >= stepCount) { … throw FlowEngineException.schemaMismatch(…); }
```

That closes the *removed-step* case: shrink the plan and the wake fails closed with
`EX-FLOW-7002 / phase=SCHEMA_MISMATCH` before any step replays. What it cannot close — because arity is
all it looks at — is the **same-arity reorder**. Swap two steps, or replace one with another, and the
persisted index stays in range. The saga resumes on a *different* step than the one it parked at, and
nothing anywhere notices. `flow.md:37` already records this as the highest-risk redeployment scenario
and its mitigation is a procedure, not a mechanism: "avoid reordering while Sagas are in-flight".

The pieces for a real fix are already present but not connected. `FlowStepDescriptor` carries a
`name`, documented as "human-readable step name (for JFR / diagnostics)" — validated non-blank, not
unique, and not consulted by anything that makes decisions. Meanwhile `stepId` is described as a
"unique step identifier" in the same file and is in fact the slab slot address:
`address = stepSlabBase + stepId * STEP_DESCRIPTOR_STRIDE`. So the type has a field that reads like an
identity but is a position, and a field that is an identity but is labelled decoration.

This also blocks work downstream. A durable journal of saga transitions is only as meaningful as its
references: an entry reading `STEP_COMPLETED step=3` says nothing once a step is inserted ahead of it.
A history recorded in positions becomes false at the next deploy.

The question this ADR answers: **what does a snapshot have to record so that resume binds to the step
it actually parked at, without giving up the O(1) descriptor lookup that the index exists to serve?**

## 🏁 The Decision

**A snapshot records the identity of the step it parked at, and resume refuses to continue unless the
plan agrees.**

Position and identity are separated rather than merged. The index keeps doing what it is good at —
addressing — and a name does what an index cannot: survive a definition changing shape.

**Concrete obligations:**

1. **`FlowStepDescriptor.name` is the step identity.** Its Javadoc stops saying "for JFR /
   diagnostics". Identity means the contract now depends on it, so it is validated where definitions
   are built rather than trusted: `FlowDefinition`'s compact constructor rejects duplicate step names
   within a definition. A definition that cannot name its steps distinctly cannot be registered.
2. **`FlowSnapshot` carries the resumed step's identity alongside `currentStep`.** Additive — the
   index stays, because it is what the runtime dispatches on. Pre-1.0, no external SPI consumers, so
   the carrier gains a component rather than growing a parallel type.
3. **Resume validates identity, not merely arity.** `validateSnapshotStepBounds` grows into an
   identity check that runs after the bounds check and before any step replays: if
   `plan.step(currentStep).name()` differs from the persisted identity, the wake fails closed.
4. **The failure reuses `EX-FLOW-7002 / phase=SCHEMA_MISMATCH`, with a distinct reason
   discriminator.** `STEP_OUT_OF_RANGE` already exists for the arity case; the reorder case gets its
   own reason so an operator can tell "the step vanished" from "the step at that position is now
   something else". No new error code, no new phase — the taxonomy already has the right shape.
5. **`stepId` is not repurposed.** It remains the slab slot address. Making it a stable identifier
   would cost the Enterprise tier its O(1) descriptor lookup, and would trade a real property for a
   naming preference. The Javadoc that calls it a "unique step identifier" is corrected to say what it
   is.
6. **A snapshot with no recorded identity fails closed.** Rows written before this change cannot be
   validated, and admitting them would leave a permanent branch where a silent index replay is still
   reachable — the exact behaviour this ADR exists to remove. The operator procedure is the one
   `flow.md` already prescribes for reordering deploys: drain in-flight sagas before switching.
7. **`FlowSchemaMismatchEvent` carries the reason and both step names.** Step names are definition
   metadata written by the application's own code, not user data, so they are safe to record under the
   JFR payload rules — and without them the event says a mismatch happened while withholding the only
   two facts that explain it.
8. **`AbstractSagaRecoveryTck` gains the reorder case.** A saga parks, the definition is re-registered
   with the same step count and different order, and the wake must fail closed. The bounds case must
   keep passing beside it — a suite that only proves the reorder case would pass against a runtime
   that rejects every resume.

## Consequences

### ✅ Positive Outcomes

- **[+] A data-corruption class becomes a startup failure.** The worst outcome moves from "the saga
  silently ran the wrong compensation" to "the wake refused and said why".
- **[+] The mitigation stops being a procedure.** `flow.md`'s "avoid reordering while Sagas are
  in-flight" is advice nobody can enforce; a guard is enforcement.
- **[+] The journal becomes possible.** Transition entries can reference a step by something that
  still means the same thing after the next deploy.
- **[+] Two mislabelled fields get their real names.** `name` is identity, `stepId` is an address, and
  the Javadoc says so.

### ⚠️ Trade-offs

- **[-] In-flight sagas do not survive the upgrade.** Obligation 6 means snapshots written before this
  change fail closed on wake. That is a real operational cost, paid once, and it is the honest price
  of not carrying a fail-open branch forever. It belongs in the release notes, not in a footnote.
- **[-] Step names become a compatibility surface.** Renaming a step is now a breaking change to
  in-flight sagas, where before it was invisible — because before, it was invisible in the way that
  corrupts. Applications gain a constraint they did not know they had.
- **[-] The snapshot grows.** One more field, persisted per parked instance, on a row that durable
  stores write on every checkpoint. Small, but not free, and the durable-store bindings carry it.
- **[-] Duplicate step names now fail definition registration.** Any existing definition that reused a
  name breaks at build time. That is the correct failure, and it is still a failure that did not
  happen yesterday.

### 📋 What is NOT in scope

- **A `FlowDefinition` version field, coexisting definition versions, and in-flight migration** — the
  differentiator tracked in the kernel ROADMAP, **decided by ADR-064**. Version coexistence built
  before the runtime can *detect* a mismatch would be built on sand; this ADR supplies the detection
  those need, and ADR-064 validates every migration's output through the identity check decided here.
- **`loadByDefinition()`**, deferred in the ROADMAP to "the definition-versioning epic". It waits on
  versioning, not on identity.
- **The FlowJournal contract itself.** This ADR is its prerequisite, not its design.
- **Changing how the runtime dispatches.** Execution still addresses steps by index.

## Cross-references

- [`docs/subsystems/flow.md`](../subsystems/flow.md) — the redeployment compatibility matrix; the
  same-arity reorder row is what this ADR closes, and the v0.10 bounds guard row is what it builds on.
- ADR-013 (Distributed saga state distribution model) — the snapshot is the carrier this ADR extends.
- ADR-049 (Events log ordering and optimistic-concurrency boundary) — untouched here.
  `FlowSnapshot.schemaVersion` is the durable store's CAS counter, **not** a definition version, and
  this ADR neither merges the two nor adds a third meaning to it.
- [`docs/ROADMAP.md`](../ROADMAP.md) → *Differentiator: Flow/Saga Definition Versioning + In-Flight
  Migration* — the epic this is the first slice of.

## Engineering Protocol

The codebase is not yet compliant; this ADR is prescriptive.

1. **`AbstractSagaRecoveryTck` reorder case plus the Community binding**, per obligation 8. Both the
   reorder and the bounds case are mandatory, and the identity check must be shown to reject rather
   than merely to exist.
2. **`ExerisArchitectureTest`** run explicitly, not assumed from CI.
3. **The integration gate is run, and named.** `mvn -pl exeris-kernel-community
   -DincludedGroups=integration -DexcludedGroups= test` covers the durable-store bindings that persist
   the new field. That gate only began selecting `*IT` classes recently; a green default build says
   nothing about it.
4. **Docs updated in the implementing slice** — `flow.md`'s compatibility matrix (the reorder row
   stops being a warning and becomes a guard), and the stale ROADMAP claim that no step-bounds
   validation exists in code, which has been wrong since v0.10.
5. **Release notes carry the in-flight-saga consequence** from the first trade-off above.
