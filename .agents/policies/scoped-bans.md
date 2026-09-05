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

The `ThreadLocal`, `Executors`, `CompletableFuture` and `Unsafe` bans are ArchUnit rules, not PMD
rules. If the architecture guard did not run, nothing has checked them — a green `mvn clean install`
that skipped the guard proves nothing here.

**Reach is a property of the classpath, not of the rule text.** Every suite below declares
`@AnalyzeClasses` over a package prefix that reads wider than what its module can actually load.
Measured on this branch:

| Suite | Module | Sees | Enforces |
|:--|:--|:--|:--|
| `ExerisArchitectureTest` | `exeris-kernel-tck` | **SPI only** — that module's one compile dependency is `exeris-kernel-spi` | the four bans, plus `noStructuredTaskScopeInSchedulingSpi` |
| `KernelTierDirectionArchitectureTest` | `exeris-kernel-community` | all three tiers, and it asserts non-vacuity per tier so a missing classpath fails loudly | `coreDoesNotDependOnCommunity`, `spiDependsOnNeitherCoreNorCommunity` |
| `CommunitySchedulingArchitectureTest` | `exeris-kernel-community` | `eu.exeris.kernel.community.scheduling` only | `noStructuredTaskScope`, `noThreadLocal`, `noExecutors` |

Neither suite reaches `exeris-kernel-community-kafka` or `exeris-kernel-diagnostics-cli`; both are
leaves nothing depends on.

**Where the four bans do and do not reach on this branch.** They are executable over the **SPI**
(`ExerisArchitectureTest`) and over **`community.scheduling`** (`CommunitySchedulingArchitectureTest`).
Core, and Community outside `scheduling`, are **not** guarded for the four bans here — that gap is
closed on the development line by `KernelTierBanArchitectureTest`. Until it reaches this branch, a
`ThreadLocal` in Core is a review finding, not a build failure. **Direction is a different matter and
is fully guarded**: `coreDoesNotDependOnCommunity` runs over all three tiers today.

Verify a ban by what the suite can load, never by reading its `packages` argument — and remember
that the guard living in `exeris-kernel-community` never runs under a `-pl exeris-kernel-tck -am`
invocation, because `-am` builds that module's dependencies, and Community is not one of them.


## Related

- [`the-wall.md`](the-wall.md) — the boundary rules the same guard is meant to protect.
- [`definition-of-done.md`](definition-of-done.md) — when the guard must be run and by which command.
