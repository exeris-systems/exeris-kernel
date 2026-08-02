---
name: exeris-performance-contract
description: Performance-lawyer review enforcing No Waste Compute for Exeris Kernel. Use when a change touches runtime hot paths (transport, flow, memory, persistence, bootstrap, dispatch) or can affect allocations, structured concurrency, off-heap copies, or banned APIs (ThreadLocal, ExecutorService, CompletableFuture, ByteBuffer, Unsafe). Skip for docs-only or pure test/tooling changes.
---

# Exeris Performance Contract

## Purpose
Enforce the Exeris **No Waste Compute** law on every PR review.

This skill validates that changes respect:
- `docs/performance-contract.md`
- `docs/whitepaper.md`
- `docs/architecture.md`
- subsystem hot-path contracts in `docs/subsystems/*.md`

## When to Use
- Any PR that can touch runtime code paths (skip for docs-only or pure test/tooling changes — classify scope first)
- Any change touching runtime, transport, flow, memory, persistence, telemetry, or bootstrap
- Any change that can affect allocations, concurrency model, or off-heap memory movement

## Required Inputs
- PR diff or changed file list
- Hot-path candidates (request path, dispatch path, state transitions, alloc points)
- Expected runtime behavior changes

## Performance-Lawyer Procedure
1. **Load performance canon first**
   - Read `docs/performance-contract.md` for non-negotiable targets.
   - Read impacted subsystem contracts (`docs/subsystems/*.md`).
   - Read architecture context (`docs/architecture.md`, `docs/whitepaper.md`) for boundary intent.

2. **Hard-ban API scan (automatic reject candidates)**
   Flag and escalate any use of:
   - `ThreadLocal`
   - `ExecutorService`, `Executors`, `CompletableFuture`
   - `java.io.*`, `java.net.Socket`, `ByteBuffer`
   - `sun.misc.Unsafe`
   - `Arena.ofConfined()` / `Arena.ofShared()` in business/runtime hot-path logic

3. **Hot-path exception discipline**
   - Detect checked exceptions thrown or propagated on hot paths.
   - Require preallocated sentinel errors or `int`/enum error code mapping for state-machine critical paths.

4. **Zero-copy/off-heap discipline**
   - Detect heap ↔ off-heap copying in request/dispatch path.
   - Require `MemorySegment` slicing/offset patterns and `LoanedBuffer` retention semantics instead of heap materialization.

5. **Complexity guard on hot paths**
   - Flag O(n) scans/lookups in hot execution paths.
   - Require O(1) state transition and lookup patterns where contract expects deterministic latency.

6. **Telemetry/JFR gate**
   - Verify critical lifecycle transitions emit typed JFR events.
   - Flag missing events for bootstrap, allocation failures, transport bind, and state transitions.

7. **Modern replacement guidance (mandatory recommendations)**
   For every violation, suggest nearest compliant replacement:
   - `ScopedValue` instead of `ThreadLocal`
   - `StructuredTaskScope` instead of unstructured async primitives
   - `MemorySegment` and zero-copy APIs instead of legacy buffers/IO
   - `LoanedBuffer` for managed off-heap ownership
   - `VarHandle` for lock-free state transitions
   - preallocated sentinel errors / enum error codes instead of checked exceptions on hot path

8. **Decision and report**
   - Produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.
   - Map each finding to violated rule, affected hot path, expected runtime risk, and minimal remediation.

## Decision Logic
- **APPROVE**: No hard-ban usage, no hot-path exception/complexity violations, zero-copy discipline preserved, JFR lifecycle coverage present.
- **CONDITIONAL**: Non-critical violations outside hot path with clear and bounded remediation.
- **REJECT**: Any hard-ban API use in runtime path, hot-path checked exceptions, heap↔off-heap copy regression, O(n) hot-path regression, or missing critical JFR lifecycle events.

## Completion Criteria
A review is complete only if all are true:
- Hard-ban scan completed for changed files.
- Hot-path exception and complexity checks completed.
- Zero-copy/off-heap movement evaluated for changed data paths.
- JFR lifecycle transitions verified for impacted critical flows.
- Replacement guidance provided for each finding.
- Final verdict and required actions included.

## Review Output Template
Use this structure in PR feedback:
1. **Scope analyzed** (files/hot paths/subsystems)
2. **Hard-ban findings**
3. **Hot-path findings** (exceptions, O(1) vs O(n), copy behavior)
4. **JFR findings** (present/missing lifecycle events)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (minimal root-cause fixes)
7. **Compliant alternatives** (ScopedValue, StructuredTaskScope, MemorySegment, LoanedBuffer, VarHandle, sentinel/enum errors)

## Non-Negotiable Rules
- No banned APIs in runtime hot paths.
- No checked exceptions on hot paths.
- No avoidable heap↔off-heap copies.
- No O(n) lookup in latency-critical paths where O(1) contract is expected.
- No critical lifecycle transition without typed JFR telemetry.
