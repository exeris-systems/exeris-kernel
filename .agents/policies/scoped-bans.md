---
title: Policy — scoped bans and strong defaults on runtime hot paths
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — scoped bans and strong defaults on runtime hot paths

**Classify the scope first.** Runtime hot path, runtime non-hot path, test/tooling, or docs-only.
The bans below and the depth of a review both follow from that class, and every verdict states
which class it assumed. A ban applied to the wrong class is a false finding.

## Banned everywhere — no scope class applies

- `java.lang.ThreadLocal`, in every module and every source set: runtime, tests, the testkit and
  tooling. ADR-007 states the ban without a scope, and a policy does not narrow an ADR. Context
  propagates through `ScopedValue`; state that is not context belongs to an instance or a parameter.
  `java.util.concurrent.ThreadLocalRandom` is a different type and is not covered.

The scope class is still stated in the verdict; it decides the review depth, not whether this ban
applies.

## Banned in production runtime hot paths

Unless explicitly justified by a subsystem contract, or the code is test-only or tooling:

- `ExecutorService`, `Executors`, `CompletableFuture` — when they replace structured orchestration.
- `java.io.*`, `java.net.Socket`, `ByteBuffer` — when used on a zero-copy runtime path.
- `sun.misc.Unsafe`.
- Ad-hoc `Arena` management (`Arena.ofConfined()` and friends) in subsystem runtime code where an
  approved ownership abstraction exists — it bypasses `WatermarkManager`. See
  [`memory-ownership.md`](memory-ownership.md).
- Checked exceptions on hot state-machine paths.
- `String.formatted()` or string concatenation on exception and failure paths — use the `rawArgs[]`
  primitive layout.
- Double-checked locking for lazy init — use the `Supplier` + `AtomicReference` compare-and-set
  compute-once pattern (`CONTRIBUTING.md`) or `LazyConstant`.

These do **not** automatically apply to test fixtures, build tooling, migration scripts or debug
harnesses.

## Strong defaults — what to reach for instead

Enforced by default; a departure needs a stated reason in the pull request. A ban list without its
replacements is half a rule.

- **`MemorySegment`, `LoanedBuffer` and `VarHandle` on runtime hot paths** — the approved
  alternatives to the `ByteBuffer`, `java.io.*` and `sun.misc.Unsafe` entries above. Who releases
  what: [`memory-ownership.md`](memory-ownership.md).
- **JFR-first instrumentation for subsystem lifecycle and failure points** — bootstrap, allocation
  failure, bind and start, state transitions. Glass-Box means the JFR event *is* the observability
  surface, not a log line beside it.
- **Expand TCK coverage when observable SPI behaviour changes.** The hard constraint in
  [`the-wall.md`](the-wall.md) is the floor: new surface does not merge without it. This is the
  softer half — behaviour that shifts inside an existing contract still owes the TCK an assertion.
- The remaining two defaults, **orchestration concurrency** and **Valhalla-ready carriers**, are
  branch-specific and live in [`jdk-and-preview-track.md`](jdk-and-preview-track.md).

## What enforces them, and how far it reaches

The `ThreadLocal`, `Executors`, `CompletableFuture` and `Unsafe` bans are enforced twice, and neither
is PMD. Checkstyle's `[EXERIS L0]` regexes (`checkstyle.xml`, `checkstyle-tck.xml`) run at `validate`
over main sources only; the regex is textual, skips comments but not string literals, and does not
match `ThreadLocalRandom`. The ArchUnit rules below run in the test phase over bytecode. Test sources
are reached by ArchUnit alone, so a build that skipped the architecture guard has not checked them
in test code.

**Reach is a property of the classpath, not of the rule text.** Every suite below declares
`@AnalyzeClasses` over a package prefix that reads wider than what its module can actually load.
Measured on this branch:

| Suite | Module | Sees | Enforces |
|:--|:--|:--|:--|
| `ExerisArchitectureTest` | `exeris-kernel-tck` | **SPI only** — that module's one compile dependency is `exeris-kernel-spi` | the four bans, plus `noStructuredTaskScopeInSchedulingSpi` |
| `KernelTierDirectionArchitectureTest` | `exeris-kernel-community` | all three tiers, and it asserts non-vacuity per tier so a missing classpath fails loudly | `coreDoesNotDependOnCommunity`, `spiDependsOnNeitherCoreNorCommunity` |
| `CommunitySchedulingArchitectureTest` | `exeris-kernel-community` | `eu.exeris.kernel.community.scheduling` only | `noStructuredTaskScope`, `noThreadLocal`, `noExecutors` |
| `KernelTierBanArchitectureTest` | `exeris-kernel-community` | all three tiers | the four bans above, across Core and Community |

Neither suite reaches `exeris-kernel-community-kafka` or `exeris-kernel-diagnostics-cli`; both are
leaves nothing depends on.

**Where the four bans do and do not reach on this branch.** `KernelTierBanArchitectureTest` covers Core and
Community, `ExerisArchitectureTest` covers the SPI, and `CommunitySchedulingArchitectureTest` covers
the scheduling driver twice over. There is no untested tier for these four.

**The unscoped `ThreadLocal` ban reaches test code only where a suite's classpath does.**
`KernelTierBanArchitectureTest` imports with no `ImportOption`, so it also loads Community's own
test classes and `exeris-kernel-community-testkit`, which is a test-scope dependency of Community: a
`ThreadLocal` field placed in either fails it. The test classes of SPI, Core and the two leaf modules
are on no suite's classpath, and there the ban is enforced by review alone.

Verify a ban by what the suite can load, never by reading its `packages` argument — and remember
that the guard living in `exeris-kernel-community` never runs under a `-pl exeris-kernel-tck -am`
invocation, because `-am` builds that module's dependencies, and Community is not one of them.


## Related

- [`the-wall.md`](the-wall.md) — the boundary rules the same guard is meant to protect.
- [`definition-of-done.md`](definition-of-done.md) — when the guard must be run and by which command.
