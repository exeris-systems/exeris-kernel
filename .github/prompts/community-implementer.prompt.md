---
name: community-implementer
description: 'Implement an Exeris Community/Open-Core change with minimal targeted edits while preserving boundaries and runtime-safe idioms.'
argument-hint: 'Implementation task or PR scope'
---

Implement this as an Exeris Community/Open-Core change.

Constraints:
- Do not re-litigate architecture unless a direct violation is detected.
- Preserve existing module boundaries.
- Prefer explicit construction, ScopedValue, StructuredTaskScope, immutable carriers, and zero-copy/off-heap-safe patterns where relevant.
- Avoid framework DI, ThreadLocal for runtime context, and unstructured orchestration in runtime paths.
- Keep changes minimal and targeted.
- If the change affects SPI-observable behavior, explicitly mark that TCK review is required.

Please provide:
1. implementation plan,
2. target files/modules,
3. smallest code change set,
4. risks or assumptions,
5. proportional verification needed.
