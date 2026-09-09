---
title: "exeris-kernel-community: the drivers, and where the guards live"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
paths:
  - exeris-kernel-community/**
enforced-by:
  - test:KernelTierDirectionArchitectureTest#coreDoesNotDependOnCommunity
  - test:KernelTierBanArchitectureTest
  - test:CommunitySchedulingArchitectureTest
  - ci:maven / persistence-rls-gate
  - ci:maven / recovery-continuity-gate
  - ci:maven / transport-stress-gate
---

# exeris-kernel-community

Scope-specific rules for the module that implements the contracts. It adds to the root
[`AGENTS.md`](../AGENTS.md) and restricts; nothing here relaxes a rule stated there.

## What is different here

This is the only tier that may bring third-party code — seventeen foreign group ids against Core's
six and the SPI's three — and it is where the guards that police every tier actually live.

- **Three of the four ArchUnit suites are in this module**, not in the tier they judge:
  `KernelTierDirectionArchitectureTest`, `KernelTierBanArchitectureTest` and
  `CommunitySchedulingArchitectureTest`. Only `ExerisArchitectureTest` lives in the TCK.
- **`coreDoesNotDependOnCommunity` runs nowhere else, and the usual command does not run it.**
  `-pl exeris-kernel-tck -am` builds the TCK's dependencies, and this module is not one of them, so
  the rule that catches a Core → Community import is silently absent from that invocation. Run the
  Community-side suite as well —
  [build and CI](../.agents/references/build-and-ci.md) names both commands, and
  [definition of done](../.agents/policies/definition-of-done.md) item 3 is why.
- **`-Dtest` here activates a profile no other module has.** `targeted-test-run` is declared in
  exactly one pom in this repository, this one, and it replaces the excluded groups with
  `flamegraph` alone. Selecting a suite by name therefore *unmasks* the tagged gates, which is what
  you want when you mean to select and a trap when you meant to exclude: use
  `-Dsurefire.excludesFile` for that.
- **Thirty-one tagged tests live here** — 22 `integration`, 7 `stress`, 2 `continuity` — and none of
  them runs in `mvn clean install`. A green default build is not evidence about any of them; the
  `exeris-tagged-gate-runner` skill maps a change to the gates it owes.
- **A dependency added here must stay here.** The direction is Community → Core → SPI, and it never
  inverts. A driver's library reaching Core is the breach `coreDoesNotDependOnCommunity` exists for.

## Naming

Classes carrying a driver's behaviour take the `Community` prefix — `CommunityWebClient`, not
`ExerisWebClient`. **Nothing checks this**, which is stated rather than implied: it is a convention
a reviewer enforces, and the one place it slipped is the reason it is written down at all.

## The transport is a hybrid, not plain NIO

NIO owns the selector and the accept loop; plain-TCP data I/O goes through POSIX `recv`/`send` via
Panama FFM when the seam is armed. Read the arming conditions in
[`docs/subsystems/transport.md`](../docs/subsystems/transport.md) before describing this module's
I/O anywhere — that page is authoritative and this paragraph exists only so nobody concludes "NIO"
from the imports.
