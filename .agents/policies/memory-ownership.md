---
title: Policy — native memory ownership and lifecycle
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — native memory ownership and lifecycle

All native memory has an explicit owner and a deterministic lifecycle. In subsystem and runtime
code, prefer `MemoryAllocator`, `LoanedBuffer` or an approved native-context wrapper over ad-hoc
ownership; ad-hoc `Arena` management bypasses `WatermarkManager` and is banned on hot paths
([`scoped-bans.md`](scoped-bans.md)).

`LoanedBuffer` is unforgiving — the failure modes are a silent leak and a double-free SIGSEGV, not
an exception:

- Always try-with-resources.
- `retain()` **before** forking a subtask that uses the buffer; `close()` inside the subtask.
- The TCK runs `LeakDetectionMode.PARANOID`. A `LeakDetectedError` means the lifecycle is wrong.
  Fix the lifecycle, not the test.

Full rules, including the debugging recipes: [`CONTRIBUTING.md`](../../CONTRIBUTING.md) §"Off-Heap
Memory". The memory subsystem contract is [`docs/subsystems/memory.md`](../../docs/subsystems/memory.md)
and outranks this file.
