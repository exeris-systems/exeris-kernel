---
title: "exeris-kernel-tck: the contract judge"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
paths:
  - exeris-kernel-tck/**
enforced-by:
  - test:ExerisArchitectureTest
  - script:tools/checkstyle-parity-check/checkstyle-parity-check.sh
  - ci:maven / build-and-verify
  - ci:maven / spi-compatibility-gate
---

# exeris-kernel-tck

Scope-specific rules for the module that decides whether a binding honours a contract. It adds to
the root [`AGENTS.md`](../AGENTS.md) and restricts; nothing here relaxes a rule stated there.

## What is different here

A test in this module is not a test of this module. It is the executable half of a contract
published to implementers, and it is inherited by every binding — so a weak assertion here is a
promise the SPI stops keeping everywhere at once.

- **Assert semantics, not the happy flow.** A positive case plus a disabled case both pass against
  an implementation that ignores the discriminating input. Write a case per *direction* of the
  behaviour, not per outcome.
- **A capacity test must hold the resource.** Workers that finish immediately recycle the slot
  faster than work arrives, so a limit test written that way passes under any limit. Block on a
  latch, assert occupancy, then release.
- **Mutation-check before claiming coverage.** Break the line the test is supposed to catch and
  watch it go red. `git stash` silently no-ops when the fix is already committed — restore the
  pre-fix file with `git checkout origin/<base> -- <path>` instead.
- **An `Abstract*Tck` with no binding subclass runs nowhere.** Adding one is half the work; the
  other half is the binding test in Core, Community or the testkit that extends it.

## Checkstyle here is a declared subtraction

`checkstyle-tck.xml` is a **copy** of `checkstyle.xml`, because Checkstyle has no mechanism for one
config to extend another. A module added upstream therefore never reaches these contract classes,
silently, and the build stays green because the TCK config is by itself valid.
`tools/checkstyle-parity-check/checkstyle-parity-check.sh` is what buys back the guarantee PMD gets
from its own format, and the L0 stop gate requires it once either file is edited. The child POM's
`<checkstyle.config.location>` property is how the selection is made — a plugin-level literal there
silently beats the `-D` the gate passes.

## Tagged gates

`@Tag("integration")`, `@Tag("continuity")` and `@Tag("stress")` do not run in `mvn clean install`.
A green default build is not evidence about any of them; the `exeris-tagged-gate-runner` skill maps
a change to the gates it owes.
