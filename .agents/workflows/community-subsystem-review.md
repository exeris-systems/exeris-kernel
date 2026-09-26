---
name: community-subsystem-review
description: Review Exeris change in a specific subsystem context with placement, contract, runtime risk, and TCK implications.
argument-hint: SUBSYSTEM_NAME + SUBSYSTEM_FILE + change scope
steps:
  - {skill: exeris-subsystem-specialist}
  - {agent: exeris-architect}
  - {agent: exeris-docs-adr, when: "the subsystem contract document no longer matches the code"}
gates:
  - ci:maven / build-and-verify
  - ci:guardrails / docs
---

Review this Exeris change in the context of a specific subsystem.

Inputs (subsystem name, subsystem doc filename, and change scope):
$ARGUMENTS

Instructions:
- Read the relevant `docs/subsystems/<SUBSYSTEM_FILE>.md` first.
- Use `docs/modules/*.md` to verify placement.
- Escalate to ADRs only if boundaries, lifecycle model, or shared architecture are affected.
- Evaluate both contract correctness and current repository reality.

Please output:
1. Subsystem responsibility match
2. Placement match
3. Contract/behavior risks
4. Runtime/performance risks if relevant
5. Test/TCK implications
6. Minimal safe next step

Examples:
- SUBSYSTEM_NAME = Memory, SUBSYSTEM_FILE = memory
- SUBSYSTEM_NAME = Transport, SUBSYSTEM_FILE = transport
