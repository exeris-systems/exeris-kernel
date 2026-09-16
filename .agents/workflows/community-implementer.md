---
name: community-implementer
description: Implement an Exeris Community/Open-Core change with minimal targeted edits while preserving boundaries and runtime-safe idioms.
argument-hint: Implementation task or PR scope
steps:
  - {agent: exeris-implementer}
  - {skill: exeris-java26-panama-loom, when: "the change touches concurrency, FFM, ScopedValue or a runtime carrier"}
  - {skill: exeris-pr-preflight}
gates:
  - ci:maven / build-and-verify
  - hook:guardrails-gate-on-stop
---

Implement this as an Exeris Community/Open-Core change.

Constraints:
- Do not re-litigate architecture unless a direct violation is detected.
- Preserve existing module boundaries.
- Prefer explicit construction, ScopedValue, structured concurrency (`core.concurrent.StructuredScope` on the preview-clean default line; `StructuredTaskScope` only on the `preview` branch), immutable carriers, and zero-copy/off-heap-safe patterns where relevant.
- Avoid framework DI, ThreadLocal for runtime context, and unstructured orchestration in runtime paths.
- Keep changes minimal and targeted.
- If the change affects SPI-observable behavior, explicitly mark that TCK review is required.

Task:
$ARGUMENTS

Please provide:
1. implementation plan,
2. target files/modules,
3. smallest code change set,
4. risks or assumptions,
5. proportional verification needed.
