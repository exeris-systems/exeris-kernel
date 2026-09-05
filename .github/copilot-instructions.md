# Copilot instructions — exeris-kernel

This repository's agent contract is [`AGENTS.md`](../AGENTS.md), and its detailed semantics live in
[`.agents/`](../.agents) — policies, references, skills, role profiles and workflows. Read
`AGENTS.md` first; it is the entry point every compatible agent can discover.

This file exists only because a Copilot client looks for it
([`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rule 7). It states no rule of its own: a rule written here would be a second place to author project
semantics, which is what the schema forbids. It previously carried its own copy of the guardrails,
one generation behind the Claude copy.

Copilot adapters generated from `.agents/` are in [`agents/`](agents), [`prompts/`](prompts) and
[`skills/`](skills), each carrying a do-not-edit marker naming its source.
