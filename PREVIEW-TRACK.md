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

## Two gates cannot run here, and neither is a lowered bar

Both are **tooling limits on an EA JDK**, recorded rather than quietly skipped:

- **PMD** (`pmd.skip` in the root POM). PMD 7.22.0 cannot parse JDK 28 class files — type resolution
  fails on `java/lang/String` itself. With types unresolved it reports ~20 false positives on SPI
  sources that are clean under the same PMD on JDK 25/26.
- **ArchUnit** — the Wall guard in `exeris-kernel-tck` and the two driver-side guards in
  `exeris-kernel-community`. ArchUnit 1.4.2 imports **zero** classes at major 72. This was caught by
  the suites' own non-empty-analysis assertions (`verifyClassesArePresent`,
  `allThreeTiersAreOnTheAnalysisClasspath`), which exist precisely so an empty analysis can never pass
  as a green one.

**Why the bar is not lowered:** both gates' subject is identical on the two lines — the SPI / Core /
Community boundaries and the source they constrain — and `main` runs both on JDK 25 LTS where the
tools work. **That argument has a limit, and it is the branch's main standing risk:** it holds only
while the two lines differ solely in the concurrency mechanism. If this branch grows structure `main`
does not have, the coverage borrowed from `main` disappears with it.

Each skip carries its own re-enable trigger and the one command that tests it, in the POM comment
beside it.

## Still to do on this line

- **Exercise JEP 401 (Value Objects) and JEP 539 (Strict Field Initialization)**, both preview in
  JDK 28. This is the reason the line exists beyond `StructuredTaskScope`: the "Valhalla-ready
  carriers" guardrail is a style rule on `main` and becomes *executable* here. Nothing has been
  measured yet — no value class has been declared and no benchmark has been run, so this document
  makes no claim about what it buys.
- Decide whether this line publishes `0.11.0-preview-SNAPSHOT` to GitHub Packages. The workflow's
  publish step is currently gated on `main` and `development/*`, so today it does not.
