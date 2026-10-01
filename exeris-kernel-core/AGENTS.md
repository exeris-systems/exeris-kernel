---
title: "exeris-kernel-core: the driver-agnostic runtime"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
paths:
  - exeris-kernel-core/**
enforced-by:
  - test:KernelTierDirectionArchitectureTest#coreDoesNotDependOnCommunity
  - test:KernelTierBanArchitectureTest
  - ci:maven / build-and-verify
---

# exeris-kernel-core

Scope-specific rules for the module that orchestrates without knowing who implements. It adds to
the root [`AGENTS.md`](../AGENTS.md) and restricts; nothing here relaxes a rule stated there.

## What is different here

Core is the half of The Wall that is easiest to breach by accident, because breaching it always
looks like the shortest fix: the provider is right there on the test classpath, `ServiceLoader`
returned nothing, and one import makes the failure go away.

- **No `eu.exeris.kernel.community.*` import, in `src/main/java` or anywhere it can reach
  production.** Selection happens through the SPI and the bootstrap DAG; if discovery failed, the
  defect is in discovery.
- **`coreDoesNotDependOnCommunity` does not run under the command most sessions use.**
  `-pl exeris-kernel-tck -am` never builds `exeris-kernel-community`, so the rule that would catch
  the import above is silently absent from that invocation. Run the Community-side suite too —
  [build and CI](../.agents/references/build-and-ci.md) names both commands, and
  [definition of done](../.agents/policies/definition-of-done.md) item 3 is why.
- **Bans apply at runtime scope, not by module.** `noThreadLocal`, `noExecutors`,
  `noCompletableFuture` and `noUnsafe` are asserted here by `KernelTierBanArchitectureTest`.
  Context propagates through `ScopedValue`; concurrency goes through `core.concurrent.StructuredScope`,
  which is the seam that keeps `StructuredTaskScope` on the `preview` branch and out of this one
  ([the JDK track](../.agents/policies/jdk-and-preview-track.md)).
- **Native memory has a named owner and a deterministic release.** An `Arena` opened here is closed
  here, on every path including the failing one — [memory
  ownership](../.agents/policies/memory-ownership.md).

## Telemetry

JFR-first, and single-phase. Never `begin()` → a blocking operation → `commit()` on a virtual
thread: the straddle has crashed the JVM in this repository, and the event is committed in one
shot instead. Emit at the site you measured, not after a `throw` that makes the miss branch dark.
