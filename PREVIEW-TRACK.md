# The `preview` line

This branch is **not** a research fork and **not** an adapter. It is the *intended future `main`*:
the richer kernel, built on the newest JDK, which converges into the default line at the LTS where
the features it exercises reach GA. `main` is its **preview-clean cut**, not the other way round.

Cut from `development/0.11.0` at `e9f5aefe`, deliberately **before** the ADR-066 substitution slice
(#301) landed — the GA `StructuredScope` layer is `main`'s downgrade artefact and would be dead code
here, where `StructuredTaskScope` is the mechanism.

## What `0.11.0-preview` contains

**The milestone itself is the distributed line's, and it is not restated here.** Read
[`docs/release/v0.11.0-release-notes.md`](docs/release/v0.11.0-release-notes.md) for what v0.11
delivered; every word of it applies to this artifact too, because this line carries the same
subsystems, the same SPI, and the same fixes.

This document is the **delta**, and only the delta: what differs is the table below, the
`StructuredTaskScope` sites the default line substituted away, the JEP 401 value-class modifiers on
the kernel's carriers, and the coordinates. There is deliberately no parallel release-notes file. A second copy of the same
milestone would need keeping in step with the first, and this repository has already paid for that
lesson twice in one milestone — a class count of `930`, then `15 181`, each true when written and
false by the next merge, in documents nobody thought to re-measure.

## How this line differs from `main`

| | `main` | `preview` (this branch) |
|---|---|---|
| JDK | 25 LTS | **newest, LTS or not** — JDK 28 EA today |
| Preview flag | main sources compile without it | `--enable-preview`, by definition |
| Concurrency | `core.concurrent.StructuredScope` (virtual threads + explicit `ScopedValue` carrier) | `StructuredTaskScope` |
| Artifact | `eu.exeris:*:0.11.0` | **`eu.exeris.preview:*:0.11.0`** — a distinct groupId, same version line |
| Distributed bytecode | major 69, **zero** preview-stamped | major 72, **783 of 2275** preview-stamped — expected here |

Tracking the newest JDK is a **requirement, not an indulgence**: this line converges into an LTS, so
it has to have absorbed the API churn *before* that LTS lands, not after.

## Merge-up: the v0.11 review sweep (#324, #325, #326)

Carried up from `development/0.11.0`. Most of it is track-neutral — the RLS session-key fix, the S3
download clamp and HEAD/GET split, the cron step revert, `FlowMigrationState`'s payload cap, the two
arrow-switch conversions, the `queueWrite` ownership TCK, and the japicmp gate now asking source
compatibility as well as binary. Three items needed a decision, and they are the reason a merge-up on
this line is never a fast-forward:

- **`OutboxOrchestrator`'s swallowed loop failure applies here too, and by a different route.** The
  default line lost it through `StructuredScope.join()` being await-all. This line reaches the same
  place through `Joiner.allUntil(_ -> false)`, whose `join()` also returns normally on a failed
  subtask. The fix is ported against `Subtask.state()` rather than `ForkedTask.state()`; the
  mechanism differs, the hole did not.
- **`SubsystemOrchestrator`'s round fail-fast fix does NOT apply here.** It repaired a property the
  in-thread substitution lost. This line still forks with `StructuredTaskScope.open()`, whose default
  joiner cancels siblings on the first failure, so the property was never lost. Preview's shape kept.
- **`InMemoryEventBus`: mechanism kept, two fixes — and one of them is this line's alone.** Handlers
  still fork here. The interrupt path dropping already-collected handler failures was the same defect
  on both lines, so that carried. Running the default line's regression case here then reported
  "expecting code to raise a throwable": an `Error` out of a forked handler killed its subtask
  without the fork body's `catch (RuntimeException)` seeing it, `allUntil` returned normally, and
  `publishAndAwait` told the publisher delivery had succeeded. Fixed by inspecting `Subtask.state()`
  after `join()` — the same shape as the outbox fix above, found the same way. The two lines still
  differ in HOW the failure arrives: unwrapped on the distributed line, where dispatch is sequential
  and unwinds the publisher's own stack; suppressed inside `EventBusException` here, where it is
  aggregated. The case says so.

**`TckScope` is not carried.** It is the TCK's own copy of the same downgrade — the default line
needs a GA fork helper there because the TCK cannot depend on Core (the reactor cycle), and this line
has `StructuredTaskScope` directly. The merge brought it into 13 call sites across 10 files; nine
files took `preview`'s spelling back wholesale, `AbstractSecurityInterceptorTck` was converted by hand
because it also carries real new cases, and the class and its test are deleted here.

**Pre-existing on this line, not introduced by a merge-up:** CLAUDE.md's standalone architecture-guard
command (`mvn -pl exeris-kernel-tck -am -Dtest=ExerisArchitectureTest ... test`) fails here with
`[No Class Loaded]` — the guard's own non-vacuity assertion, reporting that it scanned nothing. Checked
against a clean `origin/preview` checkout, where it fails identically. The guard itself is fine: it runs
and passes inside the full `mvn clean install`, which is what CI does. It is the isolated invocation
that does not set this line's classpath up, and anyone following the default line's runbook here will
read a tooling gap as a boundary breach.

**Still to measure on JDK 28:** the bytecode row in the table above (`311 of 927`) predates this
merge and is stale by whatever it added. Recomputing it needs a JDK 28 build, which
this merge-up now has — but the figure is a release-time measurement and belongs with the next cut of
this line, not with a merge-up.

## What the first JDK 28 build already absorbed

The `StructuredTaskScope` API moved in four ways between JDK 26 and 28. Every one of them was found
by compiling, not by reading release notes:

1. **A third type parameter.** `StructuredTaskScope<T, R>` → `StructuredTaskScope<T, R, R_X extends Throwable>`,
   where `R_X` is what `join()` throws.
2. **`Joiner.awaitAll()` was removed.** "Await every subtask, failures included" is now
   `allUntil(_ -> false)`. Three test sites and three production sites used it.
3. **`FailedException` was removed**, replaced by `java.util.concurrent.ExecutionException`.
   `CoreFailurePolicyTckTest` caught the old type by name and stopped compiling.
4. **`join()`'s failure is now checked.** On 26 it threw the unchecked `FailedException`, so nothing
   declared or caught it; on 28 every `join()` site needs a `throws` or a `catch`. Twelve test sites
   plus `SubsystemOrchestrator`.

Point 4 is worth keeping in view, because it fixes something on this line too. On JDK 26 the unchecked
`FailedException` escaped `SubsystemOrchestrator.startParallel` entirely, which made that method's own
failure-collection block **unreachable** — a mandatory subsystem failure surfaced as a preview class
rather than the `BootstrapException` the contract names. The checked exception forces a catch, and the
catch restores the intended type. The default line reached the same outcome by a different route
(ADR-066 §2).

## Four gates cannot run here — and the reason is structural, not temporary

Three of the four read class files, and **class-file readers ship support after a JDK releases**. This
line tracks the newest JDK, usually while it is still EA. The two facts do not reconcile: by the time
a tool supports the JDK this branch is on, this branch has moved to the next one.

That was measured, not assumed. ArchUnit 1.4.2's shaded ASM knows class-file versions only up to
**V26**; even the newest ArchUnit reaches 27, and JDK 28 is major 72. JaCoCo **0.8.15** — a release
newer than the one the build pins — still fails with "Unsupported class file major version 72".

So the correct expectation is not "re-enable when the tool catches up" but **these gates are
unavailable on this line as a standing condition**. Each is recorded rather than quietly skipped:

- **PMD** (`pmd.skip` in the root POM). PMD 7.22.0 cannot parse JDK 28 class files — type resolution
  fails on `java/lang/String` itself. With types unresolved it reports ~20 false positives on SPI
  sources that are clean under the same PMD on JDK 25/26.
- **ArchUnit** — the Wall guard in `exeris-kernel-tck` and the two driver-side guards in
  `exeris-kernel-community`. ArchUnit 1.4.2 imports **zero** classes at major 72. This was caught by
  the suites' own non-empty-analysis assertions (`verifyClassesArePresent`,
  `allThreeTiersAreOnTheAnalysisClasspath`), which exist precisely so an empty analysis can never pass
  as a green one.
- **Checkstyle** (`checkstyle.skip` in `exeris-kernel-spi`, `exeris-kernel-core`,
  `exeris-kernel-community`, `exeris-kernel-community-kafka` and `exeris-kernel-community-testkit` —
  **not** the root: `tck` and `diagnostics-cli` carry no `value` syntax and keep real Checkstyle
  coverage, verified in the build log). **This is the one this document previously
  singled out as unaffected** — "syntax-level and unaffected" — and JEP 401 falsified it. Checkstyle
  13.2.0's Java grammar does not know the `value` modifier and fails to *parse* the file: "no viable
  alternative at input 'value'", reported as a configuration error rather than a style finding.

  The skip started in two modules because only two carried the modifier. The full carrier sweep put
  it in three more, and that is a real cost, paid deliberately: PR #307 had gone out of its way to
  keep those modules gated. What makes it acceptable is the inheritance argument below, and only
  that — the same sources, minus the modifier, are fully Checkstyle-gated on `main`.

  It also **widens the rule rather than repeating it.** PMD, ArchUnit and JaCoCo fail on the class-file
  **major version**, so they break when this line bumps its JDK. Checkstyle fails on preview **source
  syntax**, so it breaks the moment this line uses a preview *language* feature at all — which is what
  the line exists to do. Tool lag reaches source-level tools too, not only bytecode readers.
- **JaCoCo** (`jacoco.skip` in the root POM). JaCoCo 0.8.14 fails report generation outright —
  "Unsupported class file major version 72" — on the first module it reaches, and would fail
  identically on every one. Unlike the other two this is not noise: it fails
  `mvn clean verify -P coverage`, which is precisely the command CI runs. Coverage floors are not
  abandoned; they are ratcheted per module and enforced on `main` over the same sources. What is lost
  here is the ability to *observe* coverage on the preview toolchain.

**How this one was missed, and the rule it produced.** The first version of this branch reported
"`mvn clean install` green, 4123 tests" — true, and irrelevant, because CI runs
`mvn clean verify -P coverage` and `install` does not activate that profile. The JaCoCo failure was
invisible locally for exactly that reason. **Verify this line with the command CI runs, not a
neighbouring one**; the same slip produced two red gates on the default line's PR in the same week.

**Why the bar is not lowered:** all four gates' subject is identical on the two lines — the SPI /
Core / Community boundaries, the lint rules, and the coverage floors, over the same sources — and
`main` runs all three on JDK 25 LTS where the tools work.

**The permanence of the gap makes one constraint governing rather than advisory.** This line is
**never self-verifying** for boundaries, lint, or coverage; that verification is always inherited from
`main`. Inheriting is only sound while the two lines differ solely in the concurrency mechanism and
the build. **Keeping the delta small is therefore not a matter of taste — it is what makes this branch
verifiable at all.** Any structure added here that `main` does not have arrives with no boundary
guard, no lint and no coverage floor behind it, and nothing will say so.

The POM comment beside each skip carries the one command that re-tests the tool. Worth re-running at
each JDK bump — but expect the answer to stay "no" more often than not, for the reason above.

## Keeping this line in sync with `development/*`

Shared work lands on `development/*` first and reaches this branch by **merge-up**, not by a second
PR. Authoring twice is how `tools/spi-api-diff/spi-api-diff.sh` ended up with the same fix made
independently on both lines with different fallbacks, conflicting with itself.

Measured on the v0.11 merge-up: **13 conflicts, 35 files clean**, and every conflict was one or two
hunks. The resolution is mechanical, and `git rerere` records it:

| conflict group | resolution |
|---|---|
| the four concurrency sites + `CoreFailurePolicyTckTest` | keep this branch's — the two mechanisms are in direct opposition |
| root POM, `build-config` POM, both annotation processors and their tests, `maven.yml`, `spi-api-diff.sh` | keep this branch's — JDK level and toolchain |
| everything else | take the incoming side |

**The conflicts are the safe part. The hazard is what merges cleanly.** Two SPI javadocs arrived with
no conflict and were **false here**: `EventBus.publishAndAwait` claimed the in-memory binding runs
handlers on the calling thread, and `BootstrapPhase` claimed the orchestrator starts subsystems on the
booting thread. Both describe the default line. They have been rewritten to state the *contract* and
the constraint behind it rather than one line's mechanism, so they are now true on both — **`main`
should adopt the same wording**, at which point those two files stop conflicting forever.

Two standing costs, so neither is a surprise next time:
- `core/concurrent/StructuredScope` and its test are **deleted here** — they are the default line's
  downgrade artefact and would be dead code on a branch that uses `StructuredTaskScope`. The price is
  a modify/delete conflict on every future merge-up that touches them.
- `tools/preview-bytecode-scan/` is **kept, byte-identical to `main`'s, and deliberately unwired**
  here: it asserts zero preview bytecode, which is the exact inverse of this line's design. Identical
  files never conflict, which is why keeping it costs less than deleting it.

**Always finish a merge-up by running `mvn clean verify -P coverage` — the command CI runs.** A clean
merge is not evidence of a correct one.

**Sweep the incoming delta, not the files you thought of.** The same miss happened three times on the
v0.11 merge-up — two SPI javadocs, then two subsystem docs, then two CHANGELOG bullets — and each time
the sweep was a grep over files chosen by hand, which is why each round found a different subset. The
check that catches all three looks at what the merge *brought in*:

```bash
git diff "$(git merge-base origin/preview HEAD)"..HEAD -- '*.md' '*.java' \
  | grep -nE '^\+.*(calling thread|booting thread|in-thread|`StructuredScope`|preview-clean)' \
  | grep -vE 'on the .0\.[0-9]+\.[0-9]+. artifact|distributed line|on .main.'
```

The first grep finds every added line that describes a mechanism; the second drops the ones already
qualified by line. Run it after every merge-up, before pushing. Verified against this milestone's
history: it flags both CHANGELOG bullets and both subsystem-doc sites that three hand-made sweeps
missed.

## Still to do on this line

- **JEP 539 (Strict Field Initialization)** — now exercised, but only incidentally: every value class
  gets strict init, so the 159 carriers below carry it. Nothing here yet exercises it *deliberately*,
  and the two hand-written value classes were the only places it constrained anything.
- **A benchmark for the value carriers.** 159 are declared (below) and none is measured; the numbers
  need the benchmark harness and are deliberately post-cut. This document makes **no claim** about
  what value classes buy until one exists — and most of these carriers hold reference components,
  which will not flatten.
- **Coordinates for a preview RELEASE: decided at the 0.11.0 cut — a distinct groupId,
  `eu.exeris.preview:*`, with the version staying plain `0.11.0`.** Opting in is therefore an explicit
  coordinate change, and the two lines never share a version axis to compete on.

  The hazard that forced the decision is real but narrower than this document first claimed. Measured
  on `maven-artifact` 3.9.16:

  ```
  ComparableVersion:  0.11.0-preview  >  0.11.0     and  >  0.10.2
  VersionRange     :  [0.11.0,) contains 0.11.0-preview  ->  true
  exact pin        :  <version>0.11.0</version>          ->  resolves to 0.11.0
  ```

  So an **exact pin is immune** — Maven never upgrades a pinned version. What `0.11.0-preview` under
  shared coordinates would have captured is narrower: a version **range**, `RELEASE`/`LATEST`
  resolution, and the `<release>` field of `maven-metadata.xml`, which makes update tooling report a
  "newer" version to mainline consumers. The earlier wording here — that it "would capture precisely
  the consumers who came for the preview-clean artifact" — overstated it, and that overstatement is
  what made this read as a blocker on every cut.

  It was still worth closing now rather than at the first Central deploy, and that is the actual
  reason: today both lines publish only to GitHub Packages, which nobody resolves without configuring
  it, so the exposure is theoretical. **Maven Central is where it stops being reversible** — a
  published version can never be replaced or withdrawn there. Changing coordinates today costs an
  edit to eleven poms; after a Central deploy it costs a groupId migration for every consumer.

## JEP 401: the carriers are value classes here

**159 carriers** — 157 `value record` and two hand-written `value class` (`RouteRequirement` in SPI,
`CoreSslHandles` in Core). Per module: SPI 79, Core 41, community 35, kafka 3, testkit 1. This is the
whole of the kernel's carrier surface; what is left out is left out for a stated reason, below.

This began as six, chosen because the repository already *claimed* they were Valhalla-ready and
converting them tested the claim rather than asserting a new one. The claim held, so the sweep
discharged it everywhere else.

### Two records are deliberately not value classes

- **`SubsystemTopologicalSorter.DependencyGraph`** — it would compile and run correctly as a value
  record. Its fields are final and nothing compares it with `==`. But `runKahnBfs` mutates the `Map`
  it holds, in place, during the sort, so the modifier would assert an immutability that is false.
  This is the one criterion below a compiler cannot check.
- **`RequiresRoleProcessor.MethodDescriptor`** in `exeris-kernel-build-config` — the annotation
  processor. Build tooling, not a runtime carrier, and never in scope.

The `AbstractLoanedBuffer` / `LoanedBuffer` family is **permanently** excluded rather than re-audited
each cycle: it has a mutable ref-count behind a `VarHandle`, registers with `Cleaner`, uses
`System.identityHashCode` as its forensic leak id, and is recycled by slab pools. It hits four
disqualifiers at once, and every one of them is load-bearing.

JFR event classes are excluded by construction, not judgement: they extend `jdk.jfr.Event`, and a
value class may extend only `Object` or an abstract value class.

### The rubric

Applied in order; first hit disqualifies. This is the reusable part — a reader deciding about a new
carrier needs this section and nothing else.

| # | Disqualifier | How to check it |
|:--|:--|:--|
| D0 | Not a carrier — not a `record`, or a `final class` with any non-final field | declaration site; a non-final field is a compile error under `value` |
| D1 | The type is used as a monitor | `grep -rhoE 'synchronized *\([^)]*\)' --include=*.java */src/main/java \| sort -u` — **enumerable once for the whole repo**, not per type |
| D2 | Reaches `WeakReference` / `Soft` / `Phantom` / `Cleaner` / `WeakHashMap` | one site repo-wide, in the buffer family |
| D3 | Reaches `System.identityHashCode` or `IdentityHashMap` | one site repo-wide, same family |
| D4 | Compared with `==` / `!=` | **exception**: `this == other` as a fast path inside the type's *own* `equals`/`compareTo` is fine — it becomes a deep compare that still implies `equals` |
| D5 | Is the **non-null expected value** of a reference CAS | the silent one; `compareAndSet(null, x)` is unaffected |
| D6 | Owns mutation machinery — `VarHandle` on its own field, pooling, `resetForReuse` | the buffer shape |
| D7 | `Serializable` and not a record | zero hits repo-wide |
| D8 | The *represented value* mutates in place — fields final, but a held object is mutated | the only judgement call |

**Not disqualifiers**, each compiled to confirm on 28-ea+10: a `MethodHandle`, `MemorySegment`,
`Arena` or `SymbolLookup` component; a live service, engine, handler or `Runnable` component; array
components; a ref-counted resource as a component; an `Object` component that is used as a monitor
*elsewhere*; generics; nesting inside an interface; implementing a sealed interface; a compact
constructor doing null-checks or defensive copies.

The three that look worst and are fine: `HttpRequest`/`HttpResponse`/`HttpEncodedBody` carry a
`LoanedBuffer`, `RequestPersistenceSession` carries a live JDBC connection, and `TlsContext` carries
the `Object` that `NativeTcpStreamPendingWrite` synchronizes on. In each case the *component* keeps
its identity and its lifecycle; only the carrier around it loses identity. Monitor-as-component is
not monitor-as-carrier.

**JEP 539 costs nothing on records.** Under `--enable-preview` on JDK 28 every record already adopts
strict field initialization, so `record` → `value record` adds no construction constraint whatsoever.
The burden falls only on hand-written classes: all fields final, every field assigned before any use
of `this`, no instance initializer block.

### Four semantics change, all silently

| | identity `record` | `value record` |
|:--|:--|:--|
| `a == b` for structurally equal | `false` | **`true`** — comparison is by value |
| `System.identityHashCode` differs | yes | **no** |
| `IdentityHashMap` holding two equal instances | size 2 | **size 1** |
| `compareAndSet(expected, x)`, `expected` non-null | succeeds only for *that instance* | **succeeds for any equal value** |

The fourth is the one this sweep added to the table, because it is the one that actually blocked a
conversion. `AtomicReference.compareAndSet` is specified against `==`, and on JDK 28 `Unsafe` takes a
substitutability path for value objects — verified in the JDK source and by execution. A CAS that
means *"only if nobody swapped this exact object"* silently degrades to *"only if the contents still
look like this"*. It does not throw and it does not warn.

`synchronized` on a statically value-typed expression is a **compile error**; on an `Object`-typed
reference holding a value it is a runtime `IdentityException`. Weak references and `Cleaner.register`
throw `IdentityException` too. Those are the loud ones — the table above is the dangerous half.

### Two identity dependencies were removed before the sweep

- **`FileSink`** compared queued events against a `POISON` sentinel with `!=`. Deleted rather than
  relocated: `close()` already clears `running` and the loop already knew how to stop. Removing it
  exposed that the drain-on-close contract, stated in the test class's own javadoc, was pinned by
  nothing — a writer discarding its entire backlog passed all 16 tests.
- **`CommunityRotatingKeySet`** used `compareAndSet` with a non-null expected value on `Generations`.
  Every write is inside `synchronized (refreshLock)` and there is no second writer, so the CAS always
  succeeded and would still succeed after conversion — it was not a live defect. It was a redundant
  CAS asserting an exclusivity witness that value semantics cannot provide.

### How the modifier is held in place

Per-carrier `ValhallaReadiness` blocks do not scale to 159, and a list of converted names is worse
than useless: it silently under-reports the moment someone forgets an entry. Each module has a
`ValhallaValueCarrierRegistryTest` stating the check in the strong direction instead — **every record
discovered in the module must be a value class** unless it appears in `IDENTITY_BY_DESIGN` with a
reason, and an excused record must still *be* an identity class, so the exclusion map cannot rot into
stale to-dos.

Non-vacuity is the point, and it is guarded three ways, because a reflective sweep that discovers
nothing passes every assertion made over it — which is exactly how `ExerisArchitectureTest` came to
inspect zero Core and Community classes while reporting green:

- a discovery floor per module, so a broken class-file walk reddens instead of passing;
- `isValue()` and `accessFlags()` asserted as two independent reads of the same bit (interfaces
  excluded — they are neither, and the first draft of this check got that wrong);
- JDK controls in both directions: `Integer` is a value class on this JDK, `String` is not.

Proven by mutation, in both directions: dropping `value` from `StreamId` reddens
`everyRecordIsAValueClass` naming the type, and adding `value` to `DependencyGraph` reddens
`excusedRecordsAreStillIdentityClasses` printing its stated reason back.

**Verified in the bytecode, with a control**, because compiling only proves the modifier parsed:
`ACC_IDENTITY` (0x0020, formerly `ACC_SUPER`) is clear on converted carriers across all three tiers
and **set** on `DependencyGraph`. The control is now that record — the previous version of this
document used `FlowSnapshot`, which this sweep converted.

The Core registry also pins **`ScopedValue.Carrier` as an identity class**. Nothing else does, and
`SubsystemOrchestrator:575` decides whether a provider contributed bindings by comparing two carriers
with `!=`. If a future JDK migrates `Carrier`, that probe silently reports "no bindings" and provider
wiring is dropped at boot with nothing thrown.

### What this sweep does not claim

**No benchmark.** 159 carriers are declared and none is measured. Most of them hold reference
components and will not flatten. This document still makes **no claim** about what value classes buy.

**The SPI compatibility gate was never in question, and that was checked rather than assumed.**
`spi.memory` is on the `stable` list and has carried a `value record` since #307 with
`spi-api-diff --fail-on-stable` green. JEP 401 is explicit that adding or removing `value` on a final
class with final fields is binary-compatible.

**One open question, recorded rather than closed.** `CommunityConnectionRefusalTest` failed three
times in seven runs on the sweep branch and zero times in two runs on the pre-conversion baseline.
The mechanism is a check-then-stop race in the test — `recordRefusal` increments the counter *before*
emitting the JFR event, and the test stopped the recording as soon as the counter moved. The test now
waits for the event; four consecutive clean runs followed. Whether the conversion widened that window
was not established: `emit()` reads five accessors off `TransportConfig`, a value class, in exactly
that gap, and a two-run baseline cannot separate that from machine load.
