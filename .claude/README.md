# `.claude/` — generated adapters and provider configuration

This directory is **not** where project rules are authored. Per
[`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rules 2 and 7, the canonical semantic source is [`.agents/`](../.agents) and this directory adapts it
for Claude Code. The same is true of `.github/agents`, `.github/prompts` and `.github/skills`, which
adapt the same source for Copilot.

- `skills/`, `agents/`, `commands/` — **generated** from `.agents/skills`, `.agents/agents` and
  `.agents/workflows`. Edit the source.
- `settings.local.json` — provider-owned local configuration. Never semantic content.

A change made in this directory is lost the next time the renderer runs. That is the one thing to
remember.

## Rendering and checking them

Two committed scripts, no hand-editing:

```bash
tools/agent-adapter-check/agent-adapter-render.sh   # rewrite every adapter from .agents/
tools/agent-adapter-check/agent-adapter-check.sh    # assert each body still matches its source
```

The renderer keeps each destination's own frontmatter — Claude and Copilot name and configure the
same profile differently, and that difference is provider configuration, not semantics — and
replaces the body.

Every generated file carries the do-not-edit marker **twice**, and both placements matter:

- a YAML comment as the first line inside the frontmatter, because the organisation gate
  (`agents_file_check.py`) reads only the first 600 characters when it looks for one. A long
  `description` — or Copilot's `tools:` list, which alone runs past 600 — would push an
  after-frontmatter marker out of that window while the file still looked correct;
- an HTML comment below the frontmatter, which is what a human sees on opening the file.

The check covers `README.md` files under a provider directory as well, because the organisation
gate requires the marker on every `.md` there. Those two READMEs are hand-written and carry an
explicit banner rather than relying on a phrase in their prose happening to match the regex — which
is what they did until 2026-09-05, one rewording away from a red gate.

## Auto-memory

Persistent memory for sessions opened in this repository lives outside it, under
`~/.claude/projects/-home-arkstack-exeris-systems-exeris-kernel/memory/`. It is provider-owned and
holds process feedback and user preferences only. Project facts belong in `AGENTS.md`, in
`.agents/`, or in the documents and records that own them — versioned, and visible to humans and to
other tools.
