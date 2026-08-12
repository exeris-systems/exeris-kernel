# The `preview` line

This branch is **not** a research fork and **not** an adapter. It is the *intended future `main`*:
the richer kernel, built on the newest JDK, which converges into the default line at the LTS where
the features it exercises reach GA. `main` is its **preview-clean cut**, not the other way round.

Cut from `development/0.11.0` at `e9f5aefe`, deliberately **before** the ADR-066 substitution slice
(#301) landed — the GA `StructuredScope` layer is `main`'s downgrade artefact and would be dead code
here, where `StructuredTaskScope` is the mechanism.

## How this line differs from `main`

| | `main` | `preview` (this branch) |
|---|---|---|
| JDK | 25 LTS | **newest, LTS or not** — JDK 28 EA today |
| Preview flag | main sources compile without it | `--enable-preview`, by definition |
| Concurrency | `core.concurrent.StructuredScope` (virtual threads + explicit `ScopedValue` carrier) | `StructuredTaskScope` |
| Artifact | `0.11.0-SNAPSHOT` | `0.11.0-preview-SNAPSHOT` |
| Distributed bytecode | major 69, **zero** preview-stamped | major 72, **311 of 927** preview-stamped — expected here |

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
- **Checkstyle** (`checkstyle.skip` in `exeris-kernel-spi` and `exeris-kernel-core` only — **not** the root, because unlike the other three this failure is not reactor-wide: those two are the only modules carrying `value` syntax, and `community`, `community-kafka`, `community-testkit`, `tck` and `diagnostics-cli` keep real Checkstyle coverage; verified in the build log). **This is the one this document previously
  singled out as unaffected** — "syntax-level and unaffected" — and JEP 401 falsified it. Checkstyle
  13.2.0's Java grammar does not know the `value` modifier and fails to *parse* the file: "no viable
  alternative at input 'value'", reported as a configuration error rather than a style finding.

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

- **JEP 539 (Strict Field Initialization)** — not yet exercised.
- **A benchmark for the value carriers.** Six are declared (below) and none is measured; the numbers
  need the benchmark harness and are deliberately post-cut. This document makes **no claim** about
  what value classes buy until one exists.
- **Decide the coordinates for a preview RELEASE, before the cut.** SNAPSHOT publishing is on as of
  v0.11 (the workflow's publish step now includes this branch), and for SNAPSHOTs the risk below is
  tolerable because consuming one is always an explicit act. A *release* version is different, and
  the reason is measured rather than assumed:

  ```
  ComparableVersion:  0.11.0-preview  >  0.11.0     and  >  0.10.2
  ```

  Maven sorts an **unknown qualifier after the release**, so `0.11.0-preview` published under the
  same `groupId:artifactId` would win a `[0.11.0,)` range and `RELEASE` metadata resolution — it
  would capture precisely the consumers who came for the preview-clean artifact. Publishing it that
  way is worse than not publishing it at all.

  Three ways out, none yet chosen: a distinct **groupId** (`eu.exeris.preview:*`, so opting in is an
  explicit coordinate change and the version can stay plain `0.11.0`); a distinct **artifactId**
  suffix on all eleven modules; or a qualifier Maven sorts *before* the release, which means one of
  its known set (`alpha`/`beta`/`milestone`/`rc`) and therefore a name that misdescribes what this
  line is.

## JEP 401: six carriers are value classes here

Declared `public value record`: `MemoryStats`, `TlsShutdownResult`, `TlsHandshakeResult`,
`EventEngineStats` (SPI), `TransactionRetryPolicy`, `SyscallHandles` (Core). Chosen because the
repository already *claimed* they were Valhalla-ready — each has a `ValhallaReadiness` test predating
this work — so converting them tests the claim rather than asserting a new one.

**Verified in the bytecode, with a control.** `ACC_IDENTITY` (0x0020, formerly `ACC_SUPER`) is **clear**
on all six and **set** on an untouched carrier (`FlowSnapshot`). Compiling is not evidence that the
modifier did anything; the flag is.

**And the check is repeatable, not a one-time inspection.** Each carrier's pre-existing
`ValhallaReadiness` test now asserts `Class::isValue`, because the structural-equality cases already
there pass for an identity record too — nothing in the suite would have noticed the modifier being
lost to a merge or a reformat. Proven non-vacuous by mutation: dropping `value` from `MemoryStats`
reddens exactly `isValueClass` with the message naming `ACC_IDENTITY`.

**Three semantics change, all silently**, which is why the selection criterion mattered more than the
conversion:

| | identity `record` | `value record` |
|:--|:--|:--|
| `a == b` for structurally equal | `false` | **`true`** — comparison is by value |
| `System.identityHashCode` differs | yes | **no** |
| `IdentityHashMap` holding two equal instances | size 2 | **size 1** |

`synchronized` on a value throws `IdentityException` — **at runtime, not at compile time**, so a
converted carrier needs test coverage to prove the guardrail, not a green compile.

The criterion applied before converting: **zero identity comparisons and zero identity-keyed lookups
on the carrier anywhere in `*/src/main`** — checked per carrier, all six at zero. A carrier used as an
identity key would break silently, and nothing in the toolchain would say so.
