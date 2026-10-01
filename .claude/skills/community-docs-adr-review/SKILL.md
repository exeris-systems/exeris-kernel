---
name: community-docs-adr-review
description: Review Exeris Community/Open-Core changes for docs/ADR drift and produce a minimal required doc patch list.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/community-docs-adr-review.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Review this Exeris Community/Open-Core change for documentation and ADR consistency.

Rules:
- Keep docs realistic to current repository state.
- Do not document target architecture as implemented fact unless clearly marked as planned/placeholder/repository-state note.
- Use docs/modules/*.md and docs/subsystems/*.md as primary sources.
- Escalate to docs/adr/*.md only when architecture intent, module split, or lifecycle model changes.

Change:
$ARGUMENTS

Please output:
1. Affected docs
2. Drift classification: NONE / MINOR_DOC_UPDATE / ADR_IMPACT
3. What code reality changed?
4. What docs must change?
5. Minimal patch list (files + sections)
