# ADR-039: Open-Core Observability Boundary — Shared Telemetry Wire Contract & Crash-File Decoder Cut

**Status:** Accepted
**Date:** 2026-06-07
**Owner:** kernel/telemetry
**Visibility:** public
**Scope:** exeris-kernel (open-core)
**Authors:** Arkadiusz Przychocki
**Cross-references:** [ADR-005](ADR-005-jfr-first-telemetry-strategy.md) (JFR-First Telemetry Strategy), [ADR-006](ADR-006.link.md) (Spring-Free Kernel Boundary — "The Wall"), [ADR-008](ADR-008-open-core-strategy-and-commoditization-of-off-heap-tls.md) (Open-Core Strategy), [ADR-033](ADR-033-kernel-diagnostics-spi.md) (`KernelDiagnostics` SPI)

> **Relationship to the enterprise-private tooling split.** A separate, **enterprise-private** decision (ADR-018, *Observability Tooling Repo Split*, `enterprise-private` visibility) governs how the *enterprise* observability tooling is partitioned across repositories. That ADR is not reachable from the public/open-core tree, so this ADR does **not** link to it and does **not** depend on it. ADR-039 is the **open-core counterpart**: it states, on the public side, only what the open-core kernel itself commits to. Any enterprise-side mirror or amendment is a private follow-up owned by the enterprise track.

## Context

v0.9 introduces the `KernelDiagnostics` SPI (ADR-033) — the first open-core surface designed for out-of-process kernel introspection. Landing it forced a question the docs had answered inconsistently: **where does the open-core ↔ enterprise line sit for observability?**

Three observability surfaces are now in play, on two different axes:

1. **Runtime *state*** — `KernelDiagnostics` SPI (ADR-033): provider/capability inventory, bootstrap DAG, subsystem descriptors, read out-of-process over stdio JSON. Open-core.
2. **Runtime *events*** — the JFR / Glass-Box binary frame pipeline. Every `ExerisKernelException` encodes `rawArgs` primitives into a 64-byte frame (see `docs/subsystems/telemetry.md`). The on-wire layout is codified by the neutral, zero-dependency, publishable spec artifact **`exeris-telemetry-spec`**.
3. **Decoder tooling** — off-kernel consumers that read frame artifacts (crash-ring files, live streams) and render human-readable reports.

The state surface (1) and the event surface (2) are **orthogonal** and already cleanly separated by ADR-033 Obligation 10 (no event surface on `KernelDiagnostics`). The ambiguity was on the event/tooling side:

- **The wire format was mislabeled as "enterprise."** `exeris-telemetry-spec` was described as the *enterprise* wire format. But `exeris-kernel-core` itself is a producer: the L0 crash buffer (see `docs/subsystems/bootstrap.md`, currently TRL-3 / unimplemented in this repo) writes `kernel-<pid>.ring` files **in the same format**, with no enterprise tier required. The format therefore has **two** producers — one open-core, one enterprise.
- **The decoder story was duplicated.** Kernel docs described a kernel-core-bundled `exeris-decoder` that "reuses the schema registry to decode crash frames" — functionally the same tool as the already-existing off-kernel decoder. Two decoders for one wire format is exactly the offset-drift risk a single neutral spec exists to prevent.

## 🏁 The Decision

**`exeris-telemetry-spec` is a shared open wire contract with two producers. There is exactly one canonical decoder per wire format. The open/enterprise cut is: crash-FILE decode = open-core, LIVE-stream decode = enterprise. The kernel is producer-only and ships no decoder.**

### Concrete decisions

1. **Shared open wire contract.** `exeris-telemetry-spec` is an open, publishable, zero-dependency wire contract — **not** enterprise-only. It has two producers: the open-core `exeris-kernel-core` L0 crash buffer, and the enterprise live-stream / crash-ring emitter. The open-core producer emits `kernel-<pid>.ring` files in this format. The canonical crash-file extension is **`.ring`** (already used by the spec artifact and the decoder tooling; the prior `.bin` references in kernel docs were drift and have been corrected).

2. **Single canonical decoder per wire format.** There is exactly one decoder for the `.ring` crash-file format. The kernel does **not** ship a second, kernel-local decoder, and there is **no kernel-owned schema-registry fork** — the kernel shares the `rawArgs` binary layout with the decoder only via `exeris-telemetry-spec`.

3. **The decoder cut — file = open-core, live = enterprise.**
   - **Crash-FILE decode is open-core-eligible.** Decoding a `.ring` crash file is decoding an artifact an *open-core* kernel produces. The crash-file decode path — frame decode, frame validation, and crash-ring file reading / scanning / timeline reconstruction — operates only on the neutral `exeris-telemetry-spec` schema and is open by nature. This matches the spec's stated intent of enabling third-party decoders: a third party decoding an open-core crash file is the intended model.
   - **LIVE-stream decode is enterprise.** The live telemetry stream is emitted only by an enterprise producer; its client (and the corresponding tooling verbs) are intrinsically enterprise. No open-core producer emits this stream.

4. **State / event separation is structural (reinforces ADR-033).** Runtime *state* is read through `KernelDiagnostics` (ADR-033); *events* stay on the JFR / Glass-Box / `exeris-telemetry-spec` path. The `eu.exeris.kernel.spi.diagnostics.*` package MUST NOT import `eu.exeris.telemetry.spec.*`, JFR `@Event` / `jdk.jfr.Event` types, or any frame / `rawArgs` type. This is enforced by a boundary rule landing with the Sprint 1 SPI code (see ADR-033 Obligation 10 follow-up note).

## Obligations

1. **No open-core deep-links into enterprise-private docs.** Open-core kernel documentation describes the boundary self-containedly. It refers to the enterprise-private tooling-split decision descriptively, never via a path that would dead-link in the public tree.
2. **Naming discipline.** `.ring` is the canonical crash-file extension across kernel docs, `exeris-telemetry-spec`, and the decoder tooling. Any change to crash-file naming or the L0 frame layout must keep the open-core producer aligned with the shared `exeris-telemetry-spec` contract.
3. **No physical code move in v0.9.** This ADR records *intent*. The open subset of the existing decoder tooling is not physically re-homed in v0.9 — the open-core L0 crash-buffer producer is still TRL-3 / unimplemented, so there is no `.ring` file to decode yet. The physical re-split (publishing the file-decode subset as open, keeping live-stream tooling enterprise) follows once the open-core producer ships.
4. **Single schema source of truth.** The `rawArgs` binary layout is owned by the kernel Error Code Registry (`docs/subsystems/telemetry.md`) and expressed on the wire only through `exeris-telemetry-spec`. No second registry.

## Consequences

### ✅ Positive Outcomes

- **[+] The boundary is stated once, on the open side.** Open-core readers get a reachable, self-contained answer to "what is open vs enterprise for observability" without chasing a private ADR.
- **[+] One decoder, one wire format.** The duplicate kernel-local `exeris-decoder` narrative is retired; offset-drift risk across an uncoordinated third decoder is removed.
- **[+] The wire spec's open promise is honored.** Treating `exeris-telemetry-spec` as a shared open contract makes third-party crash-file decoders a first-class, intended use.
- **[+] State / event separation is now structurally enforceable.** The diagnostics SPI cannot accrete an event surface by accident.

### ⚠️ Trade-offs

- **[-] Intent precedes implementation.** The file=open / live=enterprise cut is recorded before the open-core L0 producer exists; the physical re-split is deferred and must be tracked.
- **[-] Two ADRs for one boundary.** The open-core (this ADR) and enterprise-private (ADR-018) sides are recorded separately. This is deliberate — it keeps the public tree free of dead links — but it means the full picture spans a public and a private document.

### 📋 What is NOT in scope

- **Enterprise tooling internals.** How the enterprise live-stream tooling is partitioned is an enterprise-private concern (ADR-018), not governed here.
- **Physically moving decoder code.** Deferred (Obligation 3).
- **The `KernelDiagnostics` SPI shape itself.** Owned by ADR-033; this ADR only reinforces its event-free boundary.

## Engineering Protocol

1. **Kernel docs aligned in this change set:** `docs/subsystems/telemetry.md` and `docs/subsystems/bootstrap.md` — `.ring` naming, single-canonical-decoder narrative, kernel-as-producer-only, and cross-references to this ADR.
2. **ADR-033 follow-up note** records the structural ArchUnit ban (state/event separation) that lands with the Sprint 1 SPI package, added as a new `@ArchTest` rule to the existing live `ExerisArchitectureTest` suite in `exeris-kernel-tck` (runs in CI on JDK 26 GA alongside the current SPI-purity rules).
3. **Registry:** reserved as ADR-039 in `exeris-docs/adr-index.md` (public) before this content landed.
4. **Deferred follow-up (not v0.9):** physical publication of the open crash-file decode subset, once the open-core L0 crash-buffer producer is implemented.
