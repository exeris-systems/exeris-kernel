---
# DO NOT EDIT — generated from .agents/workflows/community-performance-memory.md (agents-md-schema.md rule 7). Edit the source.
name: community-performance-memory
description: 'Review Exeris Community runtime changes for hot-path allocation/copy churn, ownership lifecycle, runtime risk, and observability expectations.'
argument-hint: 'Hot-path or memory-sensitive change scope'
---
<!-- DO NOT EDIT. Generated from .agents/workflows/community-performance-memory.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review this Exeris Community change as a runtime hot-path and memory-lifecycle task.

Scope:
- Apply strict review to production runtime and hot paths.
- Distinguish runtime hot paths from test/tooling/fixtures.
- Preserve explicit ownership and deterministic lifecycle for native memory.
- Prefer MemoryAllocator, LoanedBuffer, MemorySegment, VarHandle, and zero-copy flow where relevant.
- Flag hidden heap churn, copy churn, accidental wrappers, ad-hoc ownership, risky blocking, or weak observability.

Change:
$ARGUMENTS

Please output:
1. Hot-path relevance
2. Allocation/copy risks
3. Memory ownership/lifecycle risks
4. Concurrency/runtime risks
5. JFR/observability expectations if relevant
6. Smallest safe fixes
7. Strong patterns worth keeping
