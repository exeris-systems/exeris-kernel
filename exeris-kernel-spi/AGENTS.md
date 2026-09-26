---
title: "exeris-kernel-spi: the contract surface"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
paths:
  - exeris-kernel-spi/**
enforced-by:
  - test:ExerisArchitectureTest
  - test:KernelTierDirectionArchitectureTest#spiDependsOnNeitherCoreNorCommunity
  - script:tools/spi-contract-blindness-check/spi-contract-blindness-check.sh
  - ci:maven / spi-compatibility-gate
  - hook:guardrails-gate-on-stop
---

# exeris-kernel-spi

Scope-specific rules for the module that ships the contracts. It adds to the root
[`AGENTS.md`](../AGENTS.md) and restricts; nothing here relaxes a rule stated there.

## What is different here

This module is **published API on Maven Central and read by implementers we do not control.**
Everywhere else in this repository a wrong sentence is a defect; here it is a contract, and the
compatibility gate makes some of it un-take-back-able within a release line.

- **Implementation-blind.** A type, a name, a parameter or a Javadoc sentence that only makes
  sense for one binding does not belong in a contract position. `noImplLeaksInSpi` catches the
  import; nothing but review catches the sentence, which is why
  `spi-contract-blindness-check.sh` exists — see the L0 gate rule that requires it.
- **A contract states an obligation, never an observation.** "The Community client does not
  currently release the buffer" is a bug report written into the interface it falsifies. The
  obligation belongs on the rule-7 Javadoc line; the observation belongs in `@implNote` on the
  binding, per `javadoc-conventions.md` rule 6.
- **No `Arena` in the contract surface** (`noDirectArenaInSpi`), no `java.io`, no filesystem type
  in the storage contracts, no DI annotation, no `ThreadLocal`, no `Executor*`, no
  `CompletableFuture`, no `StructuredTaskScope` in the scheduling contracts. Each has its own
  named rule in `ExerisArchitectureTest`; read the rule before arguing with it.
- **Additive or nothing, inside a release line.** A record component, a method on an interface a
  driver implements, or a changed enum constant is a source break `japicmp` reports and a source
  diff does not. The gate compiles the SPI alone, so it runs even when the reactor does not.

## Before you change a signature here

The contract judge answers first, not last: an SPI change with no `Abstract*Tck` coverage is
[not merge-ready](../.agents/policies/definition-of-done.md), and the TCK lives one module away in
[`exeris-kernel-tck`](../exeris-kernel-tck/AGENTS.md). If the contract cannot be expressed as a test,
that is a design finding about the contract, not a gap in the test suite.

## Error codes

`KernelErrorCodes` is the single source of truth. An `EX-` string written as a literal anywhere else
survives a rename it should not, and a code with no row in
[`docs/subsystems/exceptions.md`](../docs/subsystems/exceptions.md) reaches an operator as an
identifier nothing explains. Both directions are gated by
`tools/error-code-registry-check/error-code-registry-check.sh`.
