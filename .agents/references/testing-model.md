---
title: Reference — the testing model
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Reference — the testing model

Authoritative sources: [`CONTRIBUTING.md`](../../CONTRIBUTING.md), the `exeris-kernel-tck` module,
and the subsystem contract in [`docs/subsystems/`](../../docs/subsystems) for the behaviour under
test. This is the short form.

- **Test triad for a feature:** unit test + integration test + TCK expansion. A pull request that
  touches an SPI boundary with only unit tests is incomplete.
- **TCK pattern:** contract behaviour lives in `Abstract*Tck` classes in `exeris-kernel-tck`, and
  each provider module binds them with a concrete subclass. Assert semantics, not just the happy
  path — a test that passes against an implementation ignoring the discriminating input has not
  tested the contract.
- **Tagged tests do not run in the default build.** `@Tag("integration")`, `@Tag("continuity")` and
  `@Tag("stress")` are excluded from `mvn clean install` and run in dedicated CI gates. A green
  default build is not proof that they pass; the commands are in
  [`build-and-ci.md`](build-and-ci.md).
- **Leak detection:** the TCK runs `LeakDetectionMode.PARANOID`. See
  [`../policies/memory-ownership.md`](../policies/memory-ownership.md).
- **A skipped test reports green.** A test whose fixture is absent, or whose precondition probe
  fails, still counts as a pass in the reactor summary. When a test matters to a claim, confirm it
  *ran*, and confirm the reactor total moved by the number of tests the change added — the
  `^[INFO] Tests run:` line omits every module that recorded a skip.
