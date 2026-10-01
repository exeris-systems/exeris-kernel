---
title: Policy — JDK baseline and the two distribution tracks
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — JDK baseline and the two distribution tracks

**This file is branch-specific by design.** It describes the line you are standing on. The `preview`
branch carries its own copy, and a statement that is correct there can be disqualifying here.
Authority for the decision itself is
[ADR-066](../../docs/adr/ADR-066-preview-clean-ga-baseline.md) and `docs/ROADMAP.md`
§"Platform Baseline for 1.0 GA".

## This line (`main` and `development/*`) — the distributable artifact

**JDK 25 LTS baseline, and anything newer also works.** The line is **preview-clean in every
scope** — main sources, test sources and TCK fixtures alike — and the build sets `--enable-preview`
nowhere (ADR-066 and its Amendment A1). That is what un-pinned the JDK: the flag is legal only when
`--release` equals the running JDK, so while it was set anywhere, 25 was the only JDK that could
build the repository. Maven 3.9+ multi-module reactor, JUnit 5, ArchUnit, JMH, JFR, Testcontainers
(Postgres and Kafka) behind tagged gates.

The default line picks its JDK by one rule: **LTS only, preview-clean** — 25 today, 29 next. No
`--enable-preview` on main sources, no `StructuredTaskScope`, concurrency on virtual threads plus
explicit `ScopedValue` rebind, both GA.

## The other line (`preview` branch) — not this one

The `preview` branch targets the **newest JDK, LTS or not**, with `--enable-preview` on main sources
as well as tests, keeps `StructuredTaskScope`, and is where JEP 401 value classes get exercised. It
ships as `1.0-preview` for JVM-controlled deployments and is the intended future `main`, converging
at the LTS where those features go GA. `PREVIEW-TRACK.md` on that branch is its identity document.

**Which track a statement is about changes what it means.** "Prefer `StructuredTaskScope` for
orchestration concurrency" is correct on `preview` and disqualifying here.

## The substitution, and the two sites that did not become forks

Done on this line at v0.11 (ADR-066). `OutboxOrchestrator` and `CommunityEventLoop` moved to
`core.concurrent.StructuredScope`. `InMemoryEventBus.publishAndAwait` and `SubsystemOrchestrator`
phase start now run **in-thread**, because their contracts require `ScopedValue` bindings the kernel
does not define — an application's own — and a `ScopedValue.Carrier` can only carry values named in
advance. **You cannot propagate what you cannot enumerate.** Rebuilding the kernel's carrier and
forking was tried: it booted the HTTP subsystem with no handler bound and every route returned 404.

A move *away* from `StructuredTaskScope` on the default path is therefore not a guardrail violation.

**Verify the state by bytecode, never by grep.** `tools/preview-bytecode-scan/preview-bytecode-scan.sh`
is the gate; it reads the published jars and fails on any class stamped `minor_version 0xFFFF`. As of
v0.12 the test fixtures are converted too — `TckScope`, plus `BlockingPeerPair` for the three that
must drive blocking peers on platform threads — so a `StructuredTaskScope` import **anywhere** on
this line is now wrong rather than exempt. Neither main nor test sources carry one.

## Valhalla-ready carriers

Design data carriers as `record`s or immutable final classes, and avoid identity-sensitive
operations: no `synchronized` on a carrier, no `System.identityHashCode()`, no identity `==` on a
domain object. On this line that is discipline, unenforced — JEP 401 (Value Objects) and JEP 539
(Strict Field Initialization) are preview in JDK 28, so the rule becomes executable on the `preview`
branch and stays a style rule here.

A record whose generated `equals` compares array components by reference violates it as surely as an
explicit `==` does. `FlowSnapshot` and `FlowMigrationState` both carry hand-written value equality
for exactly that reason.
