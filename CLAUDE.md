---
title: "CLAUDE.md — exeris-kernel"
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
---

# CLAUDE.md — exeris-kernel

This repository's agent contract is [`AGENTS.md`](AGENTS.md), and its detailed semantics live in
[`.agents/`](.agents) — policies, references, skills, profiles, workflows, schemas, hooks and evals.
Read `AGENTS.md` first; it is the entry point every compatible agent can discover.

This file exists only because a Claude client looks for it (`agents-md-schema.md` rule 7). It states
no rule of its own: a rule written here would be a second place to author project semantics, which
is what the schema forbids.

Claude adapters generated from `.agents/` are in [`.claude/`](.claude), each carrying a do-not-edit
marker naming its source; the skills there are symlinks, and `settings.json`'s `hooks` key is a
generated region invoking the dispatcher that reads `.agents/hooks/hooks.yaml`. The renderer is not
in this repository — [`.claude/README.md`](.claude/README.md) says where it is and how to run it.
