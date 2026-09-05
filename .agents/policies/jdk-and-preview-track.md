---
title: Policy — JDK baseline and the two distribution tracks
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — JDK baseline and the two distribution tracks

**This file is branch-specific by design, and you are on `preview`.** The distributed line carries
its own copy saying the opposite, and a statement correct there can be disqualifying here.
[`PREVIEW-TRACK.md`](../../PREVIEW-TRACK.md) is this line's identity document;
[ADR-066](../../docs/adr/ADR-066-preview-clean-ga-baseline.md) is the decision that split the two.

## This line (`preview`) — the `1.0-preview` artifact

**JDK 28 EA, `--enable-preview` on main sources as well as tests.** This branch picks its JDK by one
rule: **newest, LTS or not**. Coordinates are `eu.exeris.preview`; the distributed line is
`eu.exeris`.

It is **not** a research fork and not an adapter. It is the *intended future `main`* — the richer
kernel, converging into the default line at the LTS where the features it exercises reach GA. `main`
is this line's preview-clean cut, not the other way round. It ships as `1.0-preview` for
JVM-controlled deployments, where a whole-application `--enable-preview` flag is the operator's own
decision to make.

## The other line (`main` and `development/*`) — not this one

The distributable artifact is preview-clean on **LTS only**: no `--enable-preview`, no
`StructuredTaskScope`, concurrency on virtual threads plus explicit `ScopedValue` rebind, both GA. A
mandate that reads "prefer the GA `StructuredScope` layer" is that line's rule, and applying it here
would delete this branch's reason to exist.

## `StructuredTaskScope` stays, and that is the point

This line keeps all four sites the default line substituted away at v0.11. `main`'s
`core.concurrent.StructuredScope` is its downgrade artefact and would be dead code here, where
`StructuredTaskScope` is the mechanism. Two of those four could not become forks on the default line
at all: `InMemoryEventBus.publishAndAwait` and `SubsystemOrchestrator` phase start run in-thread
there, because their contracts need `ScopedValue` bindings the kernel does not define — an
application's own — and a `ScopedValue.Carrier` can only carry values named in advance. **You cannot
propagate what you cannot enumerate.** That constraint is what this line's structured scope avoids
paying, and it is worth understanding before proposing to align the two.

Removing a `StructuredTaskScope` here is a change to this line's premise, not a cleanup.

## Valhalla — executable here, a style rule there

JEP 401 (Value Objects) and JEP 539 (Strict Field Initialization) are preview in JDK 28, so the
carrier discipline is enforceable on this branch and stays unenforced discipline on the default
line: `record`s or immutable final classes, no `synchronized` on a carrier, no
`System.identityHashCode()`, no identity `==` on a domain object.

A record whose generated `equals` compares array components by reference violates it as surely as an
explicit `==` does. `FlowSnapshot` and `FlowMigrationState` both carry hand-written value equality
for exactly that reason.

The carriers already carry the value-class modifier here; that was `0.11.1-preview`'s entire content,
and `PREVIEW-TRACK.md` §JEP 401 is its release note.

## Verifying, on either line

`tools/preview-bytecode-scan/preview-bytecode-scan.sh` reads the published jars and fails on any
class stamped `minor_version 0xFFFF`. On the default line that is a gate. **Here it is the expected
state**, not a failure — do not "fix" a preview stamp on this branch.
