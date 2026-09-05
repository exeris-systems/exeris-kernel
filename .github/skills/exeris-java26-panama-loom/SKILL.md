---
# DO NOT EDIT — generated from .agents/skills/exeris-java26-panama-loom/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-java26-panama-loom
description: 'Runtime PR review for Exeris Kernel. Use for every PR that touches concurrency, memory, native interop, and data carriers to enforce ScopedValue, structured concurrency (track-dependent), Panama FFM, Valhalla readiness, immutable/early construction, and removal of legacy framework idioms.'
argument-hint: 'PR scope, changed files, and intended Java/runtime impact'
user-invocable: true
disable-model-invocation: false
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-java26-panama-loom/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Java 26 Panama Loom

## Purpose
Enforce Java 26+ idioms aligned with Exeris runtime direction.

This skill validates changes against:
- `docs/architecture.md`
- `docs/whitepaper.md`
- `docs/performance-contract.md`
- relevant subsystem contracts in `docs/subsystems/*.md`
- accepted ADR constraints in `docs/adr/*.md`

## When to Use
- Any PR touching concurrency, context propagation, memory APIs, native interop, transport, flow, or lifecycle orchestration
- Any PR introducing or changing runtime data carriers and state transition models
- Any refactor replacing legacy Java/framework patterns in core runtime paths

## Required Inputs
- PR diff or changed file list
- Affected subsystems and hot paths
- Expected runtime behavior change

## Java 26+ Runtime Procedure
1. **Load architectural and runtime canon**
   - Read related module/subsystem contracts and ADRs.
   - Determine whether changed code is on hot path or control path.

2. **Scoped context discipline**
   - Verify request/runtime context propagation uses `ScopedValue` where scope-bound context is needed.
   - Flag deep context parameter threading when `ScopedValue` is the cleaner runtime-aligned option.

3. **Structured concurrency discipline**
   - Verify fan-out/fan-in concurrency is structured. `StructuredTaskScope` is the preferred mechanism on the `1.0-preview` artifact line; on the default critical path (`main`, which must stay preview-clean for 1.0 GA per ROADMAP "Platform Baseline for 1.0 GA"), the kernel's own `fork`/`join`/`cancel` layer over virtual threads + `ScopedValue` at the existing seam is the correct form — a default-path move *away* from `StructuredTaskScope` is NOT a regression.
   - Flag unstructured async orchestration and thread-pool-centric patterns in runtime paths — structure is the requirement; `StructuredTaskScope` is one permitted mechanism, not the definition of compliance.

4. **Panama FFM correctness and suitability**
   - Check whether native interop/data movement uses `MemorySegment` where zero-copy off-heap behavior is required.
   - For native bindings, validate modern FFM stack usage (`Linker`, `SymbolLookup`, `FunctionDescriptor`) where applicable.
   - Flag accidental drift to legacy buffer/socket or unsafe interop idioms.

5. **Valhalla readiness for carriers**
   - Review data carriers for flattenable/value-oriented design readiness where logical (e.g., compact immutable result/state carriers).
   - Flag avoidable object-header-heavy wrappers and mutable DTO drift in high-frequency runtime paths.

6. **Immutable and early construction patterns**
   - Verify constructors and initialization flows prefer immutable state and early, deterministic construction.
   - Flag patterns that delay required initialization or allow mutable partially-initialized runtime state.

7. **Legacy framework idiom rejection**
   - Flag old framework-centric patterns that violate Exeris runtime style (magic DI, hidden lifecycle, opaque async abstractions).
   - Prefer explicit construction, clear ownership, and predictable lifecycle orchestration.

8. **Decision and report**
   - Produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.
   - Map each finding to violated Java 26+/Exeris idiom, runtime risk, and smallest corrective action.

## Decision Logic
- **APPROVE**: Java 26+ runtime idioms are followed; no material regression to legacy patterns.
- **CONDITIONAL**: Transitional code exists but has bounded remediation without architectural drift.
- **REJECT**: Core runtime path regresses from ScopedValue/structured-concurrency/FFM direction (regression = unstructured async; NOT a preview-clean move off `StructuredTaskScope` per ROADMAP), breaks immutable construction discipline, or reintroduces legacy framework idioms.

## Completion Criteria
A review is complete only if all are true:
- Context propagation model was assessed (`ScopedValue` suitability).
- Concurrency model was assessed (`StructuredTaskScope` suitability).
- Native interop and memory handling were assessed for FFM direction (`MemorySegment`, `Linker`, `SymbolLookup`, `FunctionDescriptor`) where relevant.
- Carrier design was assessed for value-oriented/flattenable readiness where logical.
- Construction and mutability patterns were assessed for early/immutable guarantees.
- Legacy framework idioms were explicitly checked and reported.
- Final verdict and remediation list were produced.

## Review Output Template
Use this structure in PR feedback:
1. **Scope analyzed** (modules/subsystems/hot paths)
2. **Context findings** (`ScopedValue`)
3. **Concurrency findings** (`StructuredTaskScope`)
4. **FFM findings** (`MemorySegment`, `Linker`, `SymbolLookup`, `FunctionDescriptor`)
5. **Carrier and construction findings** (Valhalla readiness, immutability, early construction)
6. **Legacy idiom findings**
7. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
8. **Required actions** (minimal root-cause fixes)

## Non-Negotiable Rules
- Prefer `ScopedValue` for scoped runtime context.
- Require structured orchestration: `StructuredTaskScope` on the preview line; the kernel's own fork/join/cancel seam on the preview-clean default path.
- Prefer FFM-first native interop where native boundaries exist.
- Keep carrier/state models immutable and value-oriented where logical.
- Do not reintroduce legacy framework idioms in Exeris runtime paths.
