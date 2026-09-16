---
title: "Copilot instructions — exeris-kernel"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
---

# Copilot instructions — exeris-kernel

This repository's agent contract is [`AGENTS.md`](../AGENTS.md), and its detailed semantics live in
[`.agents/`](../.agents). Read `AGENTS.md` first; it is the entry point every compatible agent can
discover. This file exists only because a Copilot client looks for it (`agents-md-schema.md` rule 7)
and states no rule of its own.

Copilot reads [`.agents/skills/`](../.agents/skills) natively, so the skills are here with no copy.
The role profiles and workflows have **no Copilot adapter yet**: the shared renderer ships one
mapping file per runtime and Copilot's is unwritten, so the hand-maintained adapters that sat under
`agents/`, `prompts/` and `skills/` were removed rather than left to rot with nothing regenerating
them. `.agents/manifest.yaml` records the gap under `adapters.copilot.status`.
