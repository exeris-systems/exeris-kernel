---
description: Review an Exeris change for Open-Core architecture placement, boundary integrity, ADR/contract alignment, and minimal safe direction.
argument-hint: PR diff or task scope to review
---

Review this change as an Exeris Community/Open-Core architecture task.

Context:
- Preserve The Wall: SPI must remain implementation-blind, Core must remain driver-agnostic, Community provides OSS runtime implementations behind SPI contracts.
- Use the smallest sufficient docs first: relevant docs/modules/*.md, docs/subsystems/*.md, then docs/adr/*.md if boundaries or placement are affected.
- Respect current repository realities: Community is a real provider module (transport, persistence/JDBC, flow, events, security); Enterprise is out-of-repo (separate closed-source distribution); HTTP codec/runtime currently lives in Core.

Scope:
$ARGUMENTS

Please answer in this structure:
1. Placement: should this live in SPI, Core, Community, or TCK?
2. Boundary check: does anything leak implementation detail across The Wall?
3. ADR/contract check: does this align with current architecture intent?
4. Risks: what is the main architectural risk?
5. Minimal safe direction: what is the smallest correct next step?

Do not optimize for elegance first. Optimize for boundary correctness and contract integrity.
