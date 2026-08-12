# ADR-066: The distributable line is preview-clean on a GA LTS

- **Status:** Accepted (2026-08-08)
- **Scope:** kernel/platform
- **Visibility:** public
- **Target:** exeris-kernel 0.11.0
- **Supersedes / amends:** none. Scopes the CLAUDE.md strong default "prefer `StructuredTaskScope`" to the `preview` branch.

## Context

The build targeted JDK 26 with `--enable-preview` enabled for the whole reactor. That flag is not a
per-library opt-in: it is a whole-compilation and whole-JVM switch, and the bytecode it produces is
stamped `minor_version = 0xFFFF` and pinned to one exact class-file major. A class built that way
will not load on a different JDK **even with the flag**.

For an artifact published to a repository this inverts the dependency contract. A consumer would have
to build and run *their entire application* with `--enable-preview`, pin to our exact JDK, and accept
that all of their own code falls under the "may change or disappear next release" preview contract.
Many organisations forbid preview in production outright. A "1.0 GA" resting on a preview API is
internally contradictory.

The pressure is not hypothetical. A downstream consumer holds to LTS releases and does not admit
preview flags, and no intermediate step helps them: a preview-compiled class at `--release 25` still
loads only on JDK 25 and only with the flag. Both properties have to change together or neither
matters.

### What was measured, rather than assumed

The prior plan asked for "a clean no-`--enable-preview` compile of the default reactor" as the
definitive confirmation that `StructuredTaskScope` was the only preview dependency. That was run on a
real JDK 25 LTS, and the two blockers turned out to be separable and very unequal.

**The JDK-26 target cost nothing.** `mvn clean install` at `--release 25` on JDK 25 is green across
all eleven modules, 747 tests at the time of the probe. No JDK-26-only API is used anywhere in the
tree; the `26` in the build was never load-bearing. What it did cost was four constants that fail the
build *before* any preview error appears, and therefore mislead anyone attempting this without
measuring first: `@SupportedSourceVersion(SourceVersion.RELEASE_26)` in both `exeris-kernel-build-config`
annotation processors, and a hardcoded `--release 26` in two processor tests.

**The preview flag was the whole of the work**, and exactly four sites carried it. `exeris-kernel-spi`
compiled clean without the flag, which upgrades "the SPI is already preview-clean" from a grep result
to a compiler result.

### A preview leak no signature audit could have found

`StructuredTaskScope.open()` — the bare form used by `SubsystemOrchestrator.startParallel` — defaults
to `Joiner.awaitAllSuccessfulOrThrow()`, so `join()` throws `StructuredTaskScope.FailedException`, an
unchecked **preview** class. This was verified by running it, not inferred from the javadoc.
`FOUNDATION` starts sequentially and every other phase started in parallel, so a mandatory subsystem
failure in any L1 phase surfaced to callers as a preview type. `CoreFailurePolicyTckTest` already
caught `BootstrapException | StructuredTaskScope.FailedException` for exactly that reason.

That is preview in *observable failure behaviour*, not merely in shipped bytecode, and neither of the
two binary acceptance checks in this ADR's merge gate would have seen it on their own.

## Decision

**The default distributable line baselines on JDK 25 LTS and compiles its main sources with no
`--enable-preview`. `StructuredTaskScope` moves to the `preview` branch**, which remains the intended
future `main` and converges at the LTS where the feature goes GA.

Four rulings carry it.

### 1. A GA structured-concurrency layer, with binding propagation made explicit

`eu.exeris.kernel.core.concurrent.StructuredScope` provides owner-confined, await-all
`fork`/`join`/`cancel` over virtual threads (GA 21) and `ScopedValue` (GA 25).

Binding propagation is the part that cannot be ported mechanically, and the API refuses to hide it.
`StructuredTaskScope` forks **inherit** the `ScopedValue` bindings in effect at scope open. A plain
virtual thread does **not** — measured on JDK 25: `isBound()` reads `false` inside
`Thread.ofVirtual().start(...)` and `true` inside a forked subtask. There is also no GA API that
snapshots a thread's live bindings, so a silently-inheriting drop-in **cannot be written**.

`StructuredScope` therefore takes the bindings as a `ScopedValue.Carrier` and re-establishes them
inside each task, or requires the caller to say `openWithoutBindings()`. There is deliberately no
no-argument `open()`: the failure it would produce — a child reading an unbound provider slot, far
from the fork — is precisely the failure this class exists to prevent.

### 2. Where the contract needs bindings the kernel cannot name, the work runs in-thread

This is the ruling with the most consequence, and it was forced by evidence rather than chosen.

Two of the four sites turned out not to be portable to *any* fork-based helper, because they depend on
inheriting scoped values **the kernel does not define**:

- **`InMemoryEventBus.publishAndAwait`.** `AbstractEventBusTck`'s golden case binds a `ScopedValue`
  created inside the test — an arbitrary application value — and asserts the handler reads it. A
  `Carrier` can only carry values named in advance, so this contract is unreachable through a fork.
  Handlers now run on the calling thread, in subscription order. The method already blocked until all
  handlers completed; it now does that work rather than delegating it and waiting. The contract is
  satisfied **by construction** instead of reconstructed, and the golden test passes unchanged.

- **`SubsystemOrchestrator` phase start.** Rebuilding the kernel's own carrier and forking with it was
  implemented and **failed**: the HTTP subsystem started with no handler bound and every route
  answered 404. The lost binding was not a kernel slot at all — it was `HTTP_SERVER_HANDLER`, bound by
  the *application* around `boot()`. `KernelBootstrap` binds `CURRENT_CONFIG` in a second layer the
  orchestrator also cannot see through. Subsystems now start in dependency-safe rounds on the booting
  thread.

The general rule this establishes: **you cannot propagate what you cannot enumerate.** Where a forked
body needs only slots the kernel itself defines, `open(Carrier)` carries them explicitly and forking
stays correct. Where an application-defined value must arrive, in-thread execution is the only GA
answer, and a richer joiner API would not have changed either outcome.

The costs are stated rather than buried. `publishAndAwait` latency becomes the sum of handler
durations rather than the longest, and a slow handler delays its successors; callers wanting fan-out
have `publish`, which is unchanged. Boot latency becomes the sum of a phase's subsystem start times
rather than the longest; it is paid once per JVM and `FOUNDATION` was already sequential.

The remaining two sites — `OutboxOrchestrator` and `CommunityEventLoop` — keep their scopes and use
`openWithoutBindings()`. That is not a compromise: both loop threads are created by a plain
`Thread.ofVirtual()`, so they hold no bindings, and the `StructuredTaskScope` being replaced inherited
an empty set into every child. The behaviour is identical and the name now says so.

### 3. Preview stays in test scope, and the line is drawn at what ships

Twenty-six test and TCK fixtures still use `StructuredTaskScope`. They compile with
`--enable-preview` via a test-compile-only execution and run under it, because they are **not
distributed**. Main sources compile without it. This is the whole of the distinction the gate enforces.

### 4. The gate reads bytecode, not sources

`tools/preview-bytecode-scan/` reads the **published jars** and fails the build if any distributed
class carries `minor_version 0xFFFF` or targets a class-file major other than the LTS baseline.
Bytecode rather than a source grep, for three reasons: the stamp is what a consumer actually trips
over; it survives generated and annotation-processor-emitted code that a grep would miss; and it
cannot be satisfied by a comment claiming compliance.

Jars rather than `*/target/classes`, and the reactor's declared modules rather than a disk glob —
both because a gate must not be able to report success on work it did not do. `exeris-kernel-tck`
has no `src/main` at all, so its entire distributed surface is a test-jar that a `classes` glob
cannot see; 55 of its classes shipped preview-stamped for a whole milestone, invisible by
construction. And a glob answers "what did this build happen to produce", which after a partial
`mvn -pl <module> package` means the gate scans one jar and prints a clean result. The module list
now comes from the reactor POM, a module that published no jar is a failure rather than an absence,
and an unreadable artifact is reported as unscanned instead of ending the run in a traceback.

Both failure modes are proven to fail the gate by byte-level mutation of a built class, not by
inspection.

## Consequences

- The distributed artifact loads on JDK 25 LTS with no flags, and imposes none on consumers.
- Measured after the change: **930 distributed classes, class-file major 69, zero preview-stamped**;
  128 test classes remain preview-stamped and are not published.
- `SubsystemOrchestrator`'s post-join failure-collection block becomes reachable for the first time.
  It was dead code: `join()` threw `FailedException` before it ran, so a mandatory subsystem failure
  escaped as a preview type instead of the `BootstrapException` the block builds. The preview
  alternative disappears from `CoreFailurePolicyTckTest`'s catch.
- CI baselines on Temurin 25.0.4 LTS, pinned by URL and SHA-256 as before.
  `--enable-final-field-mutation=ALL-UNNAMED` is removed from `MAVEN_OPTS`: it is a JDK 26 flag
  (JEP 500) and **JDK 25 refuses to start with it** — verified, not assumed. There is nothing to
  re-enable on the release the restriction has not reached.
- `eu.exeris.kernel.core.concurrent.StructuredScope` is **Core, not SPI**, and is therefore not a
  supported consumer surface: the ADR-065 compatibility gate covers `exeris-kernel-spi` only, so the
  class carries no stability row and no binary-compatibility guarantee. Promoting it — and whether
  richer joiner policies belong on it — is a separate decision, not settled here.
- The CLAUDE.md strong default "prefer `StructuredTaskScope` for orchestration concurrency" is scoped
  to the `preview` branch and to JVM-controlled deployments. On the default line it is the thing being
  removed.

## Alternatives considered

**Keep `StructuredTaskScope` in `InMemoryEventBus` alone.** Preserves handler concurrency and the
golden contract untouched. Rejected: one preview site taints the artifact exactly as thoroughly as
four, since the flag and the bytecode stamp are not per-class properties from a consumer's side. The
milestone's purpose would be unmet.

**Narrow the propagation contract to kernel-known slots**, keeping handlers concurrent and carrying
`KernelProviders.*` explicitly. Rejected: it silently stops delivering application-defined scoped
values — a trace or tenant context an application binds itself — and there is no test that can catch
the regression in a consumer's code. Trading a named contract for a silent partial one is the failure
mode `StructuredScope`'s missing no-arg `open()` exists to prevent, and it would have been
inconsistent to design against it in one place and rely on it in another.

**Baseline at JDK 25 while keeping `--enable-preview`.** Rejected as not a step toward anything: a
preview-compiled class at `--release 25` loads only on JDK 25 and only with the flag, so it unblocks
nobody.

## Not in scope

JEP 401 value classes, which are preview in JDK 28 and belong to the `preview` branch's own track.
Promotion of `StructuredScope` to SPI. Joiner-policy surface beyond await-all.

## Merge gate

- Default Core + Community reactor compiles and all TCK/CI gates pass on **JDK 25 LTS with no
  `--enable-preview` on main sources**.
- `tools/preview-bytecode-scan/preview-bytecode-scan.sh --expect-major 69` passes in CI, and is proven
  to fail on both a preview stamp and a wrong major.
- `AbstractEventBusTck`'s golden `ScopedValue` case passes **unchanged** — the contract was preserved,
  not renegotiated.
- `ExerisArchitectureTest` green.
