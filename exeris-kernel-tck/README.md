# Exeris Kernel TCK

**Module:** `eu.exeris:exeris-kernel-tck`  
**Role:** Technology Compatibility Kit

## Overview
The TCK (Technology Compatibility Kit) ensures that any implementation of Exeris SPIs (Community or Enterprise) adheres to strict performance and safety contracts.

> Implementing a provider and looking for the end-to-end path — SPI interface, `META-INF/services`
> registration, TCK binding? See
> [`docs/guides/03-implement-a-provider.md`](../docs/guides/03-implement-a-provider.md).

## 🧪 Testing Scope
- **Lifecycle Compliance:** Verifies that subsystems respect topological initialization order.
- **Memory Safety:** Ensures zero-copy buffers are correctly released via `LoanedBuffer` `retain()`/`release()` through the `MemoryAllocator` SPI.
- **Context Inheritance:** Validates that `ScopedValue` propagation works across Virtual Thread forks.

## Proving a New Contract Case Is Not Vacuous

A TCK case that asserts a **deny**, a **failure mode**, or any other negative behaviour is worthless if it
would stay green against a binding that does not actually implement it. The failure mode is quiet: a
non-conforming provider passes the suite, and the contract looks enforced when nothing enforces it.

**Every new negative case must be demonstrated to fail against a non-conforming binding.** Do not reason
about it — run it. Two techniques, in order of preference.

### 1. Committed meta-test (preferred — it does not decay)

Drive the abstract contract method directly against two in-memory fakes: one conforming, one deliberately
non-conforming. Assert the first passes and the second fails. This lives in the repository and runs in CI
forever, so the proof survives refactors of the contract it guards.

Reference implementation: `contract/transport/ResetDiscriminatorSelfTest` (issue #180), which proves the
`AbstractTransportStreamTck` reset-abandon discriminator rejects a binding that drains on `reset()` while
declaring `expectsTrueReset() = true`. Note the two structural details worth copying — it calls the
contract method directly (no JUnit-in-JUnit) and the fake binding is a `static` nested class so it is not
itself discovered as a runnable suite.

Use this whenever the contract can be exercised against a fake. Most lifecycle, state-machine, and
ownership contracts can.

### 2. One-off guard mutation (fallback — record the evidence in the PR)

Temporarily disable the production guard the case is meant to pin, re-run the suite, and confirm that
**exactly the new case fails, and that it fails for the right reason**. The reason matters as much as the
count: a deny case that starts erroring on a `NullPointerException` proves nothing, whereas one reporting
*"Expecting code to raise a throwable"* proves the operation succeeded where it should have been rejected —
which is the fail-open itself, observed. Then revert; the mutation is never committed.

Reference: PR #252 (wrong-typed isolation-strategy deny). Use this when standing up a non-conforming fake
would mean re-implementing a real provider pipeline — there, the fake costs more than the proof is worth.

Because this technique leaves nothing in the tree, **the PR body is the only durable artifact**: paste the
failing output, name the guard you disabled, and state that the diff carries no `src/main` change.

### When the case is a driver obligation, say so in the factory Javadoc

Some contract cases cannot be satisfied centrally by the kernel and must be implemented by each binding —
for example a deny the kernel's own mapping structurally cannot make. Those are the cases most likely to be
silently skipped, so the abstract factory's Javadoc must state *why* the binding owns it, not merely what
to return. See `AbstractSecurityProviderTck.createTokenWithWrongTypedStrategy()` (ADR-012 §9).

## ⚖️ Licence

This module is licensed under the **Apache License, Version 2.0** (`SPDX-License-Identifier: Apache-2.0`).
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution. Using the TCK to certify a
third-party SPI implementation is explicitly permitted.

For questions: legal@exeris.eu
