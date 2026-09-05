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

**Two suites, and the split is about classpath reach, not about taste.** Both declare
`@AnalyzeClasses(packages = "eu.exeris.kernel")`, which reads as repository-wide, and neither is:

- `ExerisArchitectureTest` (`exeris-kernel-tck`) sees only the SPI, because that module's one
  compile dependency is `exeris-kernel-spi`.
- `KernelTierBanArchitectureTest` (`exeris-kernel-community`) is where the same four bans reach Core
  and Community — that being the first module with all three tiers on one classpath.

Neither suite sees `exeris-kernel-community-kafka` or `exeris-kernel-diagnostics-cli`; both are
leaves nothing depends on. Until v0.12 only the first suite existed and its rules named the whole
repository, so a `ThreadLocal` in Core left it 13/13 green — measured, not supposed.

Verify a ban by looking at what the suite can load, never by reading the rule's `packages`
argument.

## Related

- [`the-wall.md`](the-wall.md) — the boundary rules the same guard is meant to protect.
- [`definition-of-done.md`](definition-of-done.md) — when the guard must be run and by which command.
